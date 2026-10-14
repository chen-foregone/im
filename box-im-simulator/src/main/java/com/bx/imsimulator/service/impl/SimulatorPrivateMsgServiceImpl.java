package com.bx.imsimulator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.bx.imsimulator.config.props.SimulatorProperties;
import com.bx.imsimulator.dto.PrivateMsgSimulateDTO;
import com.bx.imsimulator.entity.Friend;
import com.bx.imsimulator.entity.User;
import com.bx.imsimulator.job.PrivateMsgSimulateJob;
import com.bx.imsimulator.mapper.FriendMapper;
import com.bx.imsimulator.mapper.UserMapper;
import com.bx.imsimulator.service.SimulatorGroupMsgService;
import com.bx.imsimulator.service.SimulatorPrivateMsgService;
import com.bx.imsimulator.util.SendQpsLimiter;
import com.bx.imsimulator.vo.PrivateMsgSimulateJobViewVO;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorPrivateMsgServiceImpl implements SimulatorPrivateMsgService {

    private static final int SUCCESS_CODE = 200;
    private static final int MSG_TYPE_TEXT = 0;
    /**
     * HTTP 全部返回后，再等满该时间或 WS 增量已达计划发送总量即结束
     */
    private static final long AFTER_HTTP_DONE_MS = 30000L;
    private static final long SETTLE_POLL_MS = 100L;
    /**
     * 并行拉取每位发送方好友时的线程池上限
     */
    private static final int FRIEND_LOAD_POOL_MAX = 32;

    private final SimulatorProperties properties;
    private final UserMapper userMapper;
    private final FriendMapper friendMapper;
    private final SimulatedSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicReference<PrivateMsgSimulateJob> currentJob = new AtomicReference<>();
    private final Object privateMsgJobLock = new Object();
    private final ExecutorService privateMsgJobExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "private-msg-simulate");
        t.setDaemon(true);
        return t;
    });

    @Lazy
    @Autowired
    private SimulatorGroupMsgService simulatorGroupMsgService;

    @PreDestroy
    public void shutdownPrivateMsgJobExecutor() {
        privateMsgJobExecutor.shutdownNow();
    }

    @Override
    public boolean isSimulateRunning() {
        PrivateMsgSimulateJob job = currentJob.get();
        return job != null && !job.isFinished();
    }

    @Override
    public PrivateMsgSimulateStartVO startPrivateMsgSimulate(PrivateMsgSimulateDTO dto) {
        PrivateMsgSimulateJob job;
        synchronized (privateMsgJobLock) {
            if (simulatorGroupMsgService.isSimulateRunning()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "群聊模拟任务进行中，请待结束后再试");
            }
            PrivateMsgSimulateJob cur = currentJob.get();
            if (cur != null && !cur.isFinished()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "私聊模拟任务进行中，请待结束后再试");
            }
            int senderCount = dto.getSenderCount();
            int receiverCount = dto.getReceiverCount();
            int rounds = dto.getSendRounds();
            int maxSendQps = resolveMaxSendQps(dto.getMaxSendQps());
            long totalSends = senderCount * receiverCount * rounds;
            int total = (int) totalSends;
            sessionRegistry.resetAllWsPrivateMessageCounters();
            job = new PrivateMsgSimulateJob();
            job.setSenderCount(senderCount);
            job.setReceiverCount(receiverCount);
            job.setSendRounds(rounds);
            job.setMaxSendQps(maxSendQps);
            job.setTotalRequested(total);
            currentJob.set(job);
        }
        privateMsgJobExecutor.execute(() -> executeJob(job, dto));
        return new PrivateMsgSimulateStartVO("accepted");
    }

    @Override
    public void stopPrivateMsgSimulate() {
        PrivateMsgSimulateJob job = currentJob.get();
        if (job != null && !job.isFinished()) {
            job.requestStop();
        }
    }

    @Override
    public PrivateMsgSimulateJobViewVO getPrivateMsgSimulateStatus() {
        PrivateMsgSimulateJob job = currentJob.get();
        if (job == null) {
            return PrivateMsgSimulateJobViewVO.idle();
        }
        return toView(job);
    }

    private PrivateMsgSimulateJobViewVO toView(PrivateMsgSimulateJob job) {
        long now = System.currentTimeMillis();
        PrivateMsgSimulateJobViewVO vo = new PrivateMsgSimulateJobViewVO();
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
        vo.setSenderCount(job.getSenderCount());
        vo.setReceiverCount(job.getReceiverCount());
        vo.setSendRounds(job.getSendRounds());
        vo.setMaxSendQps(job.getMaxSendQps());
        vo.setTotalRequested(job.getTotalRequested());
        vo.setWsPrivateMessagesReceived(sessionRegistry.getAllPrivateMessageRecvived());
        vo.setSucceeded(job.getSucceeded().get());
        vo.setFailed(job.getFailed().get());
        vo.setMessage(job.getMessage());
        vo.setFailureSamples(job.getFailureSamples());
        return vo;
    }


    private void executeJob(PrivateMsgSimulateJob job, PrivateMsgSimulateDTO dto) {
        if (currentJob.get() != job) {
            return;
        }
        int senderCount = dto.getSenderCount();
        int receiverCount = dto.getReceiverCount();
        int rounds = dto.getSendRounds();
        int maxSendQps = job.getMaxSendQps();
        SendQpsLimiter rateLimiter = maxSendQps > 0 ? new SendQpsLimiter(maxSendQps) : null;
        try {
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().select(User::getId, User::getUserName).orderByAsc(User::getId).last("LIMIT " + senderCount);
            List<User> senders = userMapper.selectList(wrapper);
            if (senders.size() < senderCount) {
                job.markFailed("库中用户数不足：需要发送方 " + senderCount + " 名，实际 " + senders.size());
                return;
            }

            Map<Long, List<Friend>> friendsByUserId = loadFriendsPerSender(senders, receiverCount);
            List<Runnable> tasks = new ArrayList<>();
            for (User sender : senders) {
                List<Friend> friends = friendsByUserId.getOrDefault(sender.getId(), List.of());
                if (friends.size() < receiverCount) {
                    job.markFailed("用户 " + sender.getUserName() + "的好友不足 " + receiverCount + " 个，实际 " + friends.size());
                    return;
                }
                String token = sessionRegistry.findAccessToken(sender.getId());
                if (token.isEmpty()) {
                    job.markFailed("用户 " + sender.getUserName() + "未登陆，请先对该用户执行批量登录");
                    return;
                }
                List<Long> friendIds = friends.stream().map(Friend::getFriendId).collect(Collectors.toList());
                Runnable task = () -> runSendMessageTask(job, token, sender.getId(), friendIds, rounds, rateLimiter);
                tasks.add(task);
            }
            job.setCreatedAtMs(System.currentTimeMillis());
            int poolSize = senderCount;
            ExecutorService executors = Executors.newFixedThreadPool(senderCount);
            List<Future<?>> futures = new ArrayList<>(poolSize);
            for (Runnable task : tasks) {
                futures.add(executors.submit(task));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            executors.shutdown();
            long httpDoneAt = System.currentTimeMillis();
            job.setHttpAllFinishedAtMs(httpDoneAt);
            if (job.isStopRequested()) {
                long ws = sessionRegistry.getAllPrivateMessageRecvived();
                job.markCancelled("已手动停止", ws);
                return;
            }
            job.setStatus(PrivateMsgSimulateJob.Status.SETTLING);
            waitSettleComplete(job);
        } catch (Throwable t) {
            log.error("private msg job", t);
            job.markFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 每位发送方独立查询自己的好友列表，多线程并行拉取以缩短准备阶段耗时。
     */
    private Map<Long, List<Friend>> loadFriendsPerSender(List<User> senders, int receiverCount) throws Exception {
        Map<Long, List<Friend>> friendsByUserId = new ConcurrentHashMap<>();
        int poolSize = Math.min(senders.size(), FRIEND_LOAD_POOL_MAX);
        ExecutorService loadExecutor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<?>> loadFutures = new ArrayList<>(senders.size());
            for (User sender : senders) {
                loadFutures.add(loadExecutor.submit(() -> {
                    List<Friend> friends = listTopFriendsByUserId(sender.getId(), receiverCount);
                    friendsByUserId.put(sender.getId(), friends);
                }));
            }
            for (Future<?> future : loadFutures) {
                future.get();
            }
        } finally {
            loadExecutor.shutdown();
        }
        return friendsByUserId;
    }

    private List<Friend> listTopFriendsByUserId(Long userId, int limit) {
        LambdaQueryWrapper<Friend> wrapper = new LambdaQueryWrapper<Friend>().select(Friend::getId, Friend::getUserId, Friend::getFriendId, Friend::getDeleted).eq(Friend::getUserId, userId).eq(Friend::getDeleted, false).orderByAsc(Friend::getId).last("LIMIT " + limit);
        return friendMapper.selectList(wrapper);
    }

    private void waitSettleComplete(PrivateMsgSimulateJob job) {
        while (currentJob.get() == job && !job.isFinished()) {
            if (job.isStopRequested()) {
                long wsDelta = sessionRegistry.getAllPrivateMessageRecvived();
                job.markCancelled("已手动停止", wsDelta);
                return;
            }
            long wsDelta = sessionRegistry.getAllPrivateMessageRecvived();
            job.setWsPrivateMessagesReceived(wsDelta);
            log.info("消息数量:{}", wsDelta);
            long now = System.currentTimeMillis();
            if (wsDelta >= job.getTotalRequested() || now - job.getHttpAllFinishedAtMs() >= AFTER_HTTP_DONE_MS) {
                job.markCompleted("done", wsDelta);
                return;
            }

            try {
                Thread.sleep(SETTLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.setWsPrivateMessagesReceived(wsDelta);
                job.markFailed("interrupted");
                return;
            }
        }
        log.info("未接受到所有消息");
    }

    private static int resolveMaxSendQps(Integer maxSendQps) {
        if (maxSendQps == null || maxSendQps <= 0) {
            return 0;
        }
        return maxSendQps;
    }

    private void runSendMessageTask(PrivateMsgSimulateJob job, String token, Long userId, List<Long> friendIds,
                                    int rounds, SendQpsLimiter rateLimiter) {
        URI sendUri = URI.create(properties.getApiUrl() + "/message/private/send");
        for (int round = 0; round < rounds; round++) {
            if (job.isStopRequested()) {
                return;
            }
            for (Long fid : friendIds) {
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
                Optional<String> err = sendPrivateMessage(sendUri, token, userId, fid, round);
                if (err.isEmpty()) {
                    job.incrementSucceeded();
                } else {
                    job.incrementFailed();
                    job.addFailureSample(sampleRow(round, userId, fid, err.get()));
                }
            }
        }
    }

    private static long safeTotal(int a, int b, int rounds) {
        long p = (long) a * (long) b;
        if (p > Long.MAX_VALUE / Math.max(1, rounds)) {
            return -1;
        }
        return p * rounds;
    }

    private static Map<String, Object> sampleRow(int round, Long sendId, Long recvId, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("round", round);
        map.put("sendId", sendId);
        map.put("recvId", recvId);
        map.put("reason", reason);
        return map;
    }

    private Optional<String> sendPrivateMessage(URI sendUri, String accessToken, long sendId, long recvId, int round) {
        try {
            String content = String.format("用户%d向%d发送消息，轮次:%d", sendId, recvId, round);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recvId", recvId);
            body.put("content", content);
            body.put("type", MSG_TYPE_TEXT);
            body.put("localId", IdWorker.getIdStr());
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder().uri(sendUri).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("accessToken", accessToken).POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.of("HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code = root.path("code").asInt();
            if (code == SUCCESS_CODE) {
                return Optional.empty();
            }
            String msg = root.path("message").asText("");
            return Optional.of("code=" + code + (msg.isEmpty() ? "" : ", " + msg));
        } catch (Exception e) {
            log.debug("private send error idx={}", round, e);
            return Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
