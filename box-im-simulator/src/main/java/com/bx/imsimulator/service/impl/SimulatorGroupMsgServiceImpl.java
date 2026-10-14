package com.bx.imsimulator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.bx.imsimulator.config.props.SimulatorProperties;
import com.bx.imsimulator.dto.GroupMsgSimulateDTO;
import com.bx.imsimulator.entity.ImGroup;
import com.bx.imsimulator.entity.ImGroupMember;
import com.bx.imsimulator.entity.User;
import com.bx.imsimulator.enums.GroupMsgSimulateScale;
import com.bx.imsimulator.job.GroupMsgSimulateJob;
import com.bx.imsimulator.mapper.ImGroupMapper;
import com.bx.imsimulator.mapper.ImGroupMemberMapper;
import com.bx.imsimulator.mapper.UserMapper;
import com.bx.imsimulator.service.SimulatorGroupMsgService;
import com.bx.imsimulator.service.SimulatorPrivateMsgService;
import com.bx.imsimulator.util.SendQpsLimiter;
import com.bx.imsimulator.vo.GroupMsgSimulateJobViewVO;
import com.bx.imsimulator.vo.PrivateMsgSimulateStartVO;
import com.bx.imsimulator.ws.SimulatedSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorGroupMsgServiceImpl implements SimulatorGroupMsgService {

    private static final int SUCCESS_CODE = 200;
    private static final int MSG_TYPE_TEXT = 0;
    private static final long AFTER_HTTP_DONE_MS = 30000L;
    private static final long SETTLE_POLL_MS = 100L;
    private static final int MAX_SENDERS = 1000;
    private static final int MAX_GROUPS = 10;
    private static final int MAX_TOTAL_HTTP_SENDS = 100000000;

    private final SimulatorProperties properties;
    private final UserMapper userMapper;
    private final ImGroupMapper imGroupMapper;
    private final ImGroupMemberMapper imGroupMemberMapper;
    private final SimulatedSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicReference<GroupMsgSimulateJob> currentJob = new AtomicReference<>();

    /**
     * 本轮每个群实际写进去的最大消息 id（来自发送接口返回的 GroupMessageVO.id）。
     *
     * <p>这是覆盖判据的<b>分子基准</b>：某个在线用户在该群收到的最大信号 id 只要 ≥ 这个值，
     * 就说明他的游标已经追平服务端，中间被合流掉多少条信号都不缺消息。
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>> maxSentByGroupSender = new ConcurrentHashMap<>();
    private final Object groupMsgJobLock = new Object();
    private final ExecutorService groupMsgJobExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "group-msg-simulate");
        t.setDaemon(true);
        return t;
    });

    @Lazy
    @Autowired
    private SimulatorPrivateMsgService simulatorPrivateMsgService;

    @PreDestroy
    public void shutdownGroupMsgJobExecutor() {
        groupMsgJobExecutor.shutdownNow();
    }

    @Override
    public boolean isSimulateRunning() {
        GroupMsgSimulateJob job = currentJob.get();
        return job != null && !job.isFinished();
    }

    @Override
    public PrivateMsgSimulateStartVO startGroupMsgSimulate(GroupMsgSimulateDTO dto) {
        if (simulatorPrivateMsgService.isSimulateRunning()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "私聊模拟任务进行中，请待结束后再试");
        }
        GroupMsgSimulateScale scale = GroupMsgSimulateScale.fromApiValue(dto.getGroupScale()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "groupScale 非法，可选：" + java.util.Arrays.toString(GroupMsgSimulateScale.values())));
        int senderCount = dto.getSenderCount();
        int groupCount = dto.getReceiverGroupCount();
        int rounds = dto.getSendRounds();
        if (senderCount < 1 || senderCount > MAX_SENDERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送人数须为 1～" + MAX_SENDERS);
        }
        if (groupCount < 1 || groupCount > MAX_GROUPS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接收群数量须为 1～" + MAX_GROUPS);
        }
        if (rounds < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送轮次须 ≥ 1");
        }
        long totalSends = (long) senderCount * groupCount * rounds;
        if (totalSends > MAX_TOTAL_HTTP_SENDS || totalSends > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "总 HTTP 发送量 sender×群数×轮次 不得超过 " + MAX_TOTAL_HTTP_SENDS);
        }
        int total = (int) totalSends;
        int totalWs = total * (dto.getOnlineMemberCount() - 1);
        int maxSendQps = resolveMaxSendQps(dto.getMaxSendQps());

        GroupMsgSimulateJob job;
        synchronized (groupMsgJobLock) {
            GroupMsgSimulateJob cur = currentJob.get();
            if (cur != null && !cur.isFinished()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "群聊模拟任务进行中，请待结束后再试");
            }
            sessionRegistry.resetAllWsGroupMessageCounters();
            sessionRegistry.resetAllGroupSignalCoverage();
            maxSentByGroupSender.clear();
            job = new GroupMsgSimulateJob();
            job.setGroupScale(scale.name());
            job.setSenderCount(senderCount);
            job.setReceiverGroupCount(groupCount);
            job.setSendRounds(rounds);
            job.setMaxSendQps(maxSendQps);
            job.setTotalRequested(total);
            job.setTotalWs(totalWs);
            currentJob.set(job);
        }
        groupMsgJobExecutor.execute(() -> executeJob(job, dto, scale));
        return new PrivateMsgSimulateStartVO("accepted");
    }

    @Override
    public void stopGroupMsgSimulate() {
        GroupMsgSimulateJob job = currentJob.get();
        if (job != null && !job.isFinished()) {
            job.requestStop();
        }
    }

    @Override
    public GroupMsgSimulateJobViewVO getGroupMsgSimulateStatus() {
        GroupMsgSimulateJob job = currentJob.get();
        if (job == null) {
            return GroupMsgSimulateJobViewVO.idle();
        }
        return toView(job);
    }

    private GroupMsgSimulateJobViewVO toView(GroupMsgSimulateJob job) {
        long now = System.currentTimeMillis();
        GroupMsgSimulateJobViewVO vo = new GroupMsgSimulateJobViewVO();
        if (job.isFinished() && job.getFinishedAtMs() > 0) {
            vo.setRecvMs(job.getFinishedAtMs() - job.getCreatedAtMs());
        } else {
            vo.setRecvMs(now - job.getCreatedAtMs());
        }
        if (job.getHttpAllFinishedAtMs() > 0) {
            vo.setSendMs(job.getHttpAllFinishedAtMs() - job.getCreatedAtMs());
        } else {
            vo.setSendMs(now - job.getCreatedAtMs());
        }
        vo.setStatus(job.getStatus().name());
        vo.setGroupScale(job.getGroupScale());
        vo.setSenderCount(job.getSenderCount());
        vo.setReceiverGroupCount(job.getReceiverGroupCount());
        vo.setSendRounds(job.getSendRounds());
        vo.setMaxSendQps(job.getMaxSendQps());
        vo.setTotalRequested(job.getTotalRequested());
        vo.setTotalWs(job.getTotalWs());
        vo.setWsGroupMessagesReceived(sessionRegistry.getAllGroupMessageReceived());
        Coverage cov = evaluateCoverage();
        vo.setGroupsCovered(cov.covered());
        vo.setGroupsTotal(cov.total());
        vo.setCoveragePct(cov.total() == 0 ? 0 : (int) Math.round(cov.covered() * 100.0 / cov.total()));
        // 诊断：把每个群的「最差水位 vs 服务端写到的位置」暴露出来，便于定位覆盖不达标的原因
        Map<Long, Map<Long, Long>> byUser = sessionRegistry.getCoverageByUser();
        Map<Long, String> detail = new LinkedHashMap<>();
        for (Map.Entry<Long, ConcurrentHashMap<Long, Long>> g : maxSentByGroupSender.entrySet()) {
            Long groupId = g.getKey();
            Map<Long, Long> perSender = g.getValue();
            long worstGap = 0L;
            long minWm = Long.MAX_VALUE;
            long maxTarget = 0L;
            int considered = 0;
            for (Map.Entry<Long, Map<Long, Long>> u : byUser.entrySet()) {
                Long wm = u.getValue().get(groupId);
                if (wm == null) {
                    continue;
                }
                considered++;
                long target = targetForUser(perSender, u.getKey());
                minWm = Math.min(minWm, wm);
                maxTarget = Math.max(maxTarget, target);
                worstGap = Math.max(worstGap, target - wm);
            }
            String verdict = (considered == 0 || worstGap <= 0L) ? " OK" : " MISS";
            detail.put(groupId, "会话数=" + considered + " 最差水位=" + (considered == 0 ? 0 : minWm)
                + " 最大目标=" + maxTarget + " 最大缺口=" + worstGap + verdict);
        }
        vo.setCoverageDetail(detail);
        vo.setSucceeded(job.getSucceeded().get());
        vo.setFailed(job.getFailed().get());
        vo.setMessage(job.getMessage());
        vo.setFailureSamples(job.getFailureSamples());
        return vo;
    }

    private void executeJob(GroupMsgSimulateJob job, GroupMsgSimulateDTO dto, GroupMsgSimulateScale scale) {
        if (currentJob.get() != job) {
            return;
        }
        int senderCount = dto.getSenderCount();
        int groupCount = dto.getReceiverGroupCount();
        int rounds = dto.getSendRounds();
        int maxSendQps = job.getMaxSendQps();
        SendQpsLimiter rateLimiter = maxSendQps > 0 ? new SendQpsLimiter(maxSendQps) : null;
        try {
            String prefix = scale.getNamePrefix();
            LambdaQueryWrapper<User> uw =
                    new LambdaQueryWrapper<User>().select(User::getId, User::getUserName).orderByAsc(User::getId)
                            .last("LIMIT " + senderCount);
            List<User> senders = userMapper.selectList(uw);
            if (senders.size() < senderCount) {
                job.markFailed("库中用户数不足：需要发送方 " + senderCount + " 名，实际 " + senders.size());
                return;
            }
            List<Long> senderIds = senders.stream().map(User::getId).toList();
            LambdaQueryWrapper<ImGroupMember> allMw =
                    new LambdaQueryWrapper<ImGroupMember>().select(ImGroupMember::getGroupId, ImGroupMember::getUserId)
                            .in(ImGroupMember::getUserId, senderIds)
                            .and(w -> w.eq(ImGroupMember::getQuit, false).or().isNull(ImGroupMember::getQuit));
            List<ImGroupMember> memberRows = imGroupMemberMapper.selectList(allMw);
            List<Long> candidateGroupIds =
                    memberRows.stream().map(ImGroupMember::getGroupId).distinct().collect(Collectors.toList());
            if (candidateGroupIds.isEmpty()) {
                job.markFailed("所选发送用户无任何有效群成员关系，无法发群消息");
                return;
            }
            LambdaQueryWrapper<ImGroup> gw = new LambdaQueryWrapper<ImGroup>().select(ImGroup::getId, ImGroup::getName)
                    .in(ImGroup::getId, candidateGroupIds).eq(ImGroup::getDissolve, false)
                    .and(w -> w.eq(ImGroup::getIsBanned, false).or().isNull(ImGroup::getIsBanned))
                    .likeRight(ImGroup::getName, prefix);
            List<ImGroup> scaleGroups = imGroupMapper.selectList(gw);
            Map<Long, ImGroup> scaleGroupById =
                    scaleGroups.stream().collect(Collectors.toMap(ImGroup::getId, g -> g, (a, b) -> a));
            Map<Long, List<Long>> userToScaleGroupIds = new HashMap<>();
            for (ImGroupMember m : memberRows) {
                if (!scaleGroupById.containsKey(m.getGroupId())) {
                    continue;
                }
                userToScaleGroupIds.computeIfAbsent(m.getUserId(), k -> new ArrayList<>()).add(m.getGroupId());
            }
            Map<Long, List<ImGroup>> groupsPerSender = new LinkedHashMap<>();
            for (User sender : senders) {
                List<Long> rawIds = userToScaleGroupIds.getOrDefault(sender.getId(), Collections.emptyList());
                List<Long> orderedUnique =
                        rawIds.stream().distinct().sorted().collect(Collectors.toCollection(ArrayList::new));
                if (orderedUnique.size() < groupCount) {
                    job.markFailed("用户 " + sender.getUserName() + "（id=" + sender.getId() + "）在名称以「" + prefix
                            + "」开头的未解散群中不足 " + groupCount + " 个，实际 " + orderedUnique.size());
                    return;
                }
                List<ImGroup> pick = new ArrayList<>(groupCount);
                for (int i = 0; i < groupCount; i++) {
                    pick.add(scaleGroupById.get(orderedUnique.get(i)));
                }
                groupsPerSender.put(sender.getId(), pick);
            }

            List<Runnable> tasks = new ArrayList<>();
            for (User sender : senders) {
                String token = sessionRegistry.findAccessToken(sender.getId());
                if (token.isEmpty()) {
                    job.markFailed("用户 " + sender.getUserName() + "未登陆，请先对该用户执行批量登录");
                    return;
                }
                List<ImGroup> groupsForSender = groupsPerSender.get(sender.getId());
                Runnable task = () -> runSendGroupMessageTask(job, token, sender.getId(), groupsForSender, rounds, rateLimiter);
                tasks.add(task);
            }
            job.setCreatedAtMs(System.currentTimeMillis());
            ExecutorService executors = Executors.newFixedThreadPool(senderCount);
            List<Future<?>> futures = new ArrayList<>(senderCount);
            for (Runnable task : tasks) {
                futures.add(executors.submit(task));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            executors.shutdown();
            job.setHttpAllFinishedAtMs(System.currentTimeMillis());
            if (job.isStopRequested()) {
                long ws = sessionRegistry.getAllGroupMessageReceived();
                job.markCancelled("已手动停止", ws);
                return;
            }
            job.setStatus(GroupMsgSimulateJob.Status.SETTLING);
            waitSettleComplete(job);
        } catch (Throwable t) {
            log.error("group msg job", t);
            job.markFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 收尾判据：<b>覆盖</b>，不是帧数。
     *
     * <p>为什么不能按帧数收尾：读扩散群的信号会合流（§3.18），同一窗口内多条只发最大的那条，
     * 帧数天然远小于 {@code 消息数 x 在线人数}。用 {@code totalWs} 当期望值会永远等不到，
     * 每次都耗满 30s 超时，{@code recvMs} 因此被凭空撑大 30s，AB 对比完全失真。
     *
     * <p>正确判据：<b>所有目标群里，最差的那个在线用户的覆盖水位都已追平服务端写到的位置。</b>
     * 客户端就是按 maxMessageId 拉时间线的，追平即不丢消息。
     *
     * <p>兜底：若发送接口没返回消息 id（{@code maxSentIdByGroup} 为空），退回原来的帧数判据。
     */
    private void waitSettleComplete(GroupMsgSimulateJob job) {
        while (currentJob.get() == job && !job.isFinished()) {
            if (job.isStopRequested()) {
                long wsDelta = sessionRegistry.getAllGroupMessageReceived();
                job.markCancelled("已手动停止", wsDelta);
                return;
            }
            long wsDelta = sessionRegistry.getAllGroupMessageReceived();
            job.setWsGroupMessagesReceived(wsDelta);
            log.info("群聊 WS 累计条数:{}", wsDelta);
            long now = System.currentTimeMillis();
            long elapsed = now - job.getHttpAllFinishedAtMs();
            boolean done;
            String reason;
            // 兜底有两种：拿不到消息 id，或者一个会话都没记到水位（说明 cmd=6 的解析没落地）。
            // 没有兜底的话，覆盖判据永远不成立 -> 每轮都耗满 30s 超时 -> recvMs 被凭空撑大，
            // 而这个 30s 会被误读成「投递变慢了」。宁可临时退回帧数判据，也不能污染时间指标。
            if (maxSentByGroupSender.isEmpty() || !hasAnyCoverageWatermark()) {
                done = wsDelta >= job.getTotalWs();
                reason = "frames-done";
            } else {
                Coverage cov = evaluateCoverage();
                done = cov.complete;
                reason = cov.complete ? "coverage-complete" : "coverage-partial";
                log.info("群聊覆盖: {}/{} 群追平, 最差水位明细见 status", cov.covered, cov.total);
            }
            if (done || elapsed >= AFTER_HTTP_DONE_MS) {
                job.markCompleted(reason, wsDelta);
                return;
            }
            try {
                Thread.sleep(SETTLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.setWsGroupMessagesReceived(wsDelta);
                job.markFailed("interrupted");
                return;
            }
        }
    }

    /** 覆盖评估结果 */
    private record Coverage(int covered, int total, boolean complete) {
    }

    /**
     * 评估覆盖：对每个目标群，取所有在线会话中的<b>最小</b>水位，与服务端写到的最大 id 比较。
     *
     * <p>取最小而不是平均：只要有一个在线用户没追上，这次推送对他就是漏的。
     */
    /** 是否至少有一个会话记到了水位。用于判断覆盖判据当前是否可用 */
    private boolean hasAnyCoverageWatermark() {
        return !sessionRegistry.getCoverageByUser().isEmpty();
    }

    /**
     * 每个用户对某个群的目标水位：该群里<b>不是他发的</b>消息的最大 id。
     *
     * <p>发送者本人收不到自己那条消息的信号（扇出时按设计剔除 senderId），
     * 所以拿全局最大 id 当目标会让最后一条消息的发送者永远差一格。
     * 排除掉他自己发的消息，目标才落在「他本该收到的东西」上。
     */
    private static long targetForUser(Map<Long, Long> perSender, Long userId) {
        long target = 0L;
        for (Map.Entry<Long, Long> s : perSender.entrySet()) {
            if (!s.getKey().equals(userId) && s.getValue() > target) {
                target = s.getValue();
            }
        }
        return target;
    }

    /**
     * 评估覆盖：对每个群，要求<b>所有收到过该群信号的会话</b>都已追平「非自己发送」的最大 id。
     *
     * <p>没有任何该群水位的会话跳过 —— 那些用户不是该群成员，本来就不该收到。
     */
    private Coverage evaluateCoverage() {
        int total = maxSentByGroupSender.size();
        if (total == 0) {
            return new Coverage(0, 0, false);
        }
        Map<Long, Map<Long, Long>> byUser = sessionRegistry.getCoverageByUser();
        int covered = 0;
        for (Map.Entry<Long, ConcurrentHashMap<Long, Long>> g : maxSentByGroupSender.entrySet()) {
            Long groupId = g.getKey();
            Map<Long, Long> perSender = g.getValue();
            boolean ok = true;
            for (Map.Entry<Long, Map<Long, Long>> u : byUser.entrySet()) {
                Long wm = u.getValue().get(groupId);
                if (wm == null) {
                    continue;
                }
                if (wm < targetForUser(perSender, u.getKey())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                covered++;
            }
        }
        return new Coverage(covered, total, covered == total);
    }

    private static int resolveMaxSendQps(Integer maxSendQps) {
        if (maxSendQps == null || maxSendQps <= 0) {
            return 0;
        }
        return maxSendQps;
    }

    private void runSendGroupMessageTask(GroupMsgSimulateJob job, String token, Long userId, List<ImGroup> groups,
                                         int rounds, SendQpsLimiter rateLimiter) {
        URI sendUri = URI.create(properties.getApiUrl() + "/message/group/send");
        for (int round = 0; round < rounds; round++) {
            if (job.isStopRequested()) {
                return;
            }
            for (ImGroup g : groups) {
                if (job.isStopRequested()) {
                    return;
                }
                if (rateLimiter != null) {
                    try {
                        rateLimiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (job.isStopRequested()) {
                        return;
                    }
                }
                Optional<String> err = sendGroupMessage(sendUri, token, userId, g, round);
                if (err.isEmpty()) {
                    job.incrementSucceeded();
                } else {
                    job.incrementFailed();
                    job.addFailureSample(sampleRow(round, userId, g.getId(), err.get()));
                }
            }
        }
    }

    private static Map<String, Object> sampleRow(int round, Long sendId, Long groupId, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("round", round);
        map.put("sendId", sendId);
        map.put("groupId", groupId);
        map.put("reason", reason);
        return map;
    }

    private Optional<String> sendGroupMessage(URI sendUri, String accessToken, long sendId, ImGroup group, int round) {
        try {
            String content = String.format("用户%d向群%s发消息，轮次:%d", sendId, group.getName(), round);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("groupId", group.getId());
            body.put("content", content);
            body.put("type", MSG_TYPE_TEXT);
            body.put("localId", IdWorker.getIdStr());
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder().uri(sendUri).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("accessToken", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.of("HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code = root.path("code").asInt();
            if (code == SUCCESS_CODE) {
                // 记录该群写到的最大消息 id，作为覆盖判据的基准（见 3.18）
                long msgId = root.path("data").path("id").asLong(0L);
                if (msgId > 0L && group != null) {
                    // 按「群 -> 发送者」记录，判据才能把发送者从自己的目标里排除（见 evaluateCoverage）
                    maxSentByGroupSender.computeIfAbsent(group.getId(), k -> new ConcurrentHashMap<>())
                        .merge(sendId, msgId, Math::max);
                }
                return Optional.empty();
            }
            String msg = root.path("message").asText("");
            return Optional.of("code=" + code + (msg.isEmpty() ? "" : ", " + msg));
        } catch (Exception e) {
            log.debug("group send error groupId={} round={}", group.getId(), round, e);
            return Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
