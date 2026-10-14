package com.bx.implatform.service.impl;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.imclient.IMClient;
import com.bx.imcommon.perf.PerfCounters;
import com.bx.imcommon.contant.IMConstant;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.IMGroupMessage;
import com.bx.imcommon.model.IMGroupSignal;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.imcommon.util.CommaTextUtils;
import com.bx.implatform.contant.Constant;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.GroupFanoutTask;
import com.bx.implatform.dto.GroupMessageDTO;
import com.bx.implatform.dto.GroupMessageHistoryDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.entity.Group;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.enums.GroupFanoutMode;
import com.bx.implatform.mapper.GroupMapper;
import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.entity.MessageDeletion;
import com.bx.implatform.enums.ChatType;
import com.bx.implatform.enums.DeleteType;
import com.bx.implatform.enums.MessageStatus;
import com.bx.implatform.enums.MessageType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.GroupMessageMapper;
import com.bx.implatform.service.GroupMemberService;
import com.bx.implatform.service.GroupMessageService;
import com.bx.implatform.service.GroupOnlineService;
import com.bx.implatform.service.MessageDeletionService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.util.SensitiveFilterUtil;
import com.bx.implatform.vo.GroupMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMessageServiceImpl extends ServiceImpl<GroupMessageMapper, GroupMessage> implements GroupMessageService {
    private final GroupMemberService groupMemberService;
    private final GroupOnlineService groupOnlineService;
    private final MessageDeletionService messageDeletionService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final IMClient imClient;
    private final SensitiveFilterUtil sensitiveFilterUtil;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** 读扩散分支要读群的 fanout_mode。用 Mapper 而非 GroupService，避免循环依赖 */
    private final GroupMapper groupMapper;

    @Value("${im.kafka.fanout-topic:im-group-fanout}")
    private String fanoutTopic;

    /** 总开关。false 时退回同步扇出，用于灰度与快速回滚 */
    @Value("${im.kafka.fanout-enabled:true}")
    private boolean fanoutEnabled;

    @Override
    public GroupMessageVO sendMessage(GroupMessageDTO dto) {
        validMessage(dto);
        UserSession session = SessionContext.getSession();
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        // 是否在群聊里面
        if (Objects.isNull(member) || member.getQuit()) {
            throw new GlobalException("您已不在群聊里面，无法发送消息");
        }
        // 群聊成员列表：启用 Kafka 扇出时不在请求线程构建，
        // 由消费者按 groupId 从缓存取(见下方 executeFanout)。
        // 万人群的一次 O(N) 过滤+拷贝也就一并移出了请求线程。
        // 保存消息
        GroupMessage message = BeanUtils.copyProperties(dto, GroupMessage.class);
        message.setSendId(session.getUserId());
        message.setSendTime(new Date());
        message.setSendNickName(member.getShowNickName());
        message.setAtUserIds(CommaTextUtils.asText(dto.getAtUserIds()));
        message.setStatus(MessageStatus.PENDING.code());
        // 过滤内容中的敏感词
        if (MessageType.TEXT.code().equals(dto.getType())) {
            message.setContent(sensitiveFilterUtil.filter(dto.getContent()));
        }
        // 保存消息(走代理触发分布式锁)
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl) AopContext.currentProxy();
        proxy.saveMessage(message);
        // 群发
        GroupMessageVO vo = convert(message);
        if (fanoutEnabled) {
            // 【P1 扇出异步化】只投递 4 个字段，真正的 O(群成员数) 工作交给 Kafka 消费者。
            // 原同步实现(万人群实测)：构 3N=30000 个 Redis key + 一条约 700KB 的 MGET
            // + 展开 N 个离线对象，占满请求线程，单请求 32ms、50 并发劣化到约 1058ms。
            //
            // 分区键用 messageId 而不是 groupId：客户端按 seqNo 排序渲染
            // (im-web/src/stores/chat.ts:505)，seqNo 即主键 id，
            // 因此推送乱序不影响显示，可以打散到 6 个分区换取消费者并发。
            GroupFanoutTask task = new GroupFanoutTask();
            task.setGroupId(dto.getGroupId());
            task.setMessageId(message.getId());
            task.setSenderId(session.getUserId());
            task.setSenderTerminal(session.getTerminal());
            kafkaTemplate.send(fanoutTopic, String.valueOf(message.getId()), task);
        } else {
            // 回滚路径：同步扇出，行为与改造前完全一致
            List<Long> userIds = groupMemberService.findUserIdsByGroupId(dto.getGroupId());
            // 不用发给自己
            userIds = userIds.stream().filter(id -> !session.getUserId().equals(id)).collect(Collectors.toList());
            IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
            sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
            sendMessage.setRecvIds(userIds);
            sendMessage.setData(vo);
            sendMessage.setSendResult(false);
            imClient.sendGroupMessage(sendMessage);
        }
        // 【性能+合规修复】原实现在 INFO 级别把消息明文内容写入日志：
        // 实测 56,002 条消息产生 70MB 日志（每行约 1.2KB，且含用户聊天内容）。
        // 现改为 INFO 只记元数据，内容降级到 DEBUG 按需开启。
        log.info("发送群聊消息,发送id:{},群聊id:{}", session.getUserId(), dto.getGroupId());
        if (log.isDebugEnabled()) {
            log.debug("发送群聊消息明细,发送id:{},群聊id:{},内容:{}", session.getUserId(), dto.getGroupId(), dto.getContent());
        }
        return vo;
    }

    /**
     * 执行群聊写扩散。运行在 Kafka 消费者的线程上，不占用 HTTP 请求线程。
     *
     * <p>与改造前的同步版本相比有两处差异，都是刻意的：
     * <ol>
     *   <li>消息本体按 messageId 从库里重读。读到的一定是<b>已提交</b>的数据，
     *       天然规避了「投递时事务尚未提交、消费者读到旧值」的时序问题。</li>
     *   <li>接收者列表按 groupId 重新获取。findUserIdsByGroupId 带 @Cacheable，
     *       正常是缓存命中；即使这段时间有人入群/退群，用最新成员列表也更合理。</li>
     * </ol>
     */
    @Override
    public void executeFanout(GroupFanoutTask task) {
        final boolean probe = PerfCounters.enabled;
        long tFan = probe ? System.nanoTime() : 0L;
        long tDb = probe ? System.nanoTime() : 0L;
        GroupMessage message = this.getById(task.getMessageId());
        if (Objects.isNull(message)) {
            log.warn("[群扇出] 消息不存在,messageId:{},groupId:{}", task.getMessageId(), task.getGroupId());
            return;
        }
        long tMember = probe ? System.nanoTime() : 0L;
        if (probe) {
            PerfCounters.fanDbNanos.add(tMember - tDb);
        }
        List<Long> userIds = resolveRecvIds(task);
        if (probe) {
            PerfCounters.fanMemberNanos.add(System.nanoTime() - tMember);
            PerfCounters.fanRecvIds.add(userIds.size());
        }
        if (userIds.isEmpty()) {
            return;
        }
        // 【读扩散】大群不推消息内容，只推一条「有新消息」的信号，客户端按游标拉取。
        // 判断依据是库里维护的 fanout_mode（由 GroupMemberServiceImpl 的成员数钩子翻转）。
        Group group = groupMapper.selectById(task.getGroupId());
        if (group != null && GroupFanoutMode.isReadDiffusion(group.getFanoutMode())) {
            IMGroupSignal signal = new IMGroupSignal();
            signal.setGroupId(task.getGroupId());
            signal.setMaxMessageId(message.getId());
            signal.setSenderId(task.getSenderId());
            // 这里传的是「已剔除发送者」的 userIds：发送者自身的其它终端
            // 由 sendGroupSignal 内部的自推路径覆盖，避免重复下发。
            imClient.sendGroupSignal(signal, userIds, IMTerminalType.recvCodes(),
                    new IMUserInfo(task.getSenderId(), task.getSenderTerminal()));
            if (probe) {
                PerfCounters.fanCalls.increment();
                PerfCounters.fanTotalNanos.add(System.nanoTime() - tFan);
            }
            log.debug("[读扩散] 只下发信号,groupId:{},messageId:{},接收方:{}",
                    task.getGroupId(), message.getId(), userIds.size());
            return;
        }
        GroupMessageVO vo = convert(message);
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(task.getSenderId(), task.getSenderTerminal()));
        sendMessage.setRecvIds(userIds);
        sendMessage.setData(vo);
        // 【性能修复】sendResult 默认是 true，会额外跑两遍 O(N)：
        //   im-server 为每个接收者挂 future 监听器并回推 IMBatchSendResult；
        //   本进程再经 MessageListenerMulticaster 展开成 N 个 IMSendResult。
        // 而唯一消费者 GroupMessageListener 在 INFO 级别直接 return，全程空转。
        // 本项目其它 8 处群发调用点都显式设了 false，只有群发主路径漏改。
        sendMessage.setSendResult(false);
        imClient.sendGroupMessage(sendMessage);
    }

    /**
     * 解析本次扇出的接收者（已剔除发送者）。
     *
     * <p>优先走「群在线集」：一次 SMEMBERS 直接拿到该群此刻在线的成员，
     * 复杂度 O(群在线人数)，取代原先「取全部成员(O(群成员数)) + 展开新列表 + 过滤」。
     *
     * <p>读失败或为空时回退旧路径。回退是安全的：旧路径的语义是「先发给所有人」，
     * 下游 {@code IMSender.resolveOnlineRecvKeys} 本来还会按 presence 与终端 key 再筛一次，
     * 只是多花 O(群成员数) 的时间。
     *
     * <p>注意必须区分「读失败(null)」与「确实没人在线(空集)」：
     * 前者回退，后者直接返回空列表，绝不能把 null 当空集——那会漏发全部在线用户。
     */
    private List<Long> resolveRecvIds(GroupFanoutTask task) {
        Long senderId = task.getSenderId();
        if (groupOnlineService.isEnabled()) {
            Set<Long> onlineIds = groupOnlineService.onlineUserIds(task.getGroupId());
            if (onlineIds != null) {
                List<Long> ids = new ArrayList<>(onlineIds.size());
                for (Long id : onlineIds) {
                    // 不用发给自己；发送者自己的其它终端由 sendGroupSignal 的自推路径覆盖
                    if (!senderId.equals(id)) {
                        ids.add(id);
                    }
                }
                return ids;
            }
        }
        return groupMemberService.findUserIdsByGroupId(task.getGroupId())
            .stream().filter(id -> !senderId.equals(id)).collect(Collectors.toList());
    }

    @Transactional
    @Override
    public GroupMessageVO recallMessage(Long id) {
        UserSession session = SessionContext.getSession();
        GroupMessage recallMessage = this.getById(id);
        if (Objects.isNull(recallMessage)) {
            throw new GlobalException("消息不存在");
        }
        if (!recallMessage.getSendId().equals(session.getUserId())) {
            throw new GlobalException("这条消息不是由您发送,无法撤回");
        }
        if (System.currentTimeMillis() - recallMessage.getSendTime()
                .getTime() > IMConstant.ALLOW_RECALL_SECOND * 1000) {
            throw new GlobalException("消息已发送超过5分钟，无法撤回");
        }
        // 判断是否在群里
        GroupMember member = groupMemberService.findByGroupAndUserId(recallMessage.getGroupId(), session.getUserId());
        if (Objects.isNull(member) || Boolean.TRUE.equals(member.getQuit())) {
            throw new GlobalException("您已不在群聊里面，无法撤回消息");
        }
        // 修改数据库
        recallMessage.setStatus(MessageStatus.RECALL.code());
        this.updateById(recallMessage);
        // 生成一条撤回消息
        String recallTip = String.format("%s 撤回了一条消息", member.getShowNickName());
        GroupMessage message = new GroupMessage();
        message.setLocalId(IdWorker.getIdStr());
        message.setStatus(MessageStatus.PENDING.code());
        message.setType(MessageType.RECALL.code());
        message.setGroupId(recallMessage.getGroupId());
        message.setSendId(session.getUserId());
        message.setSendNickName(member.getShowNickName());
        HashMap contentMap = new HashMap();
        contentMap.put("id", id);
        contentMap.put("tip", recallTip);
        message.setContent(JSON.toJSONString(contentMap));
        message.setSendTime(new Date());
        // 保存消息(走代理触发分布式锁)
        GroupMessageServiceImpl proxy = (GroupMessageServiceImpl) AopContext.currentProxy();
        proxy.saveMessage(message);
        // 群发
        List<Long> userIds = groupMemberService.findUserIdsByGroupId(message.getGroupId());
        GroupMessageVO msgInfo = BeanUtils.copyProperties(message, GroupMessageVO.class);
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setRecvIds(userIds);
        sendMessage.setData(msgInfo);
        imClient.sendGroupMessage(sendMessage);
        log.info("撤回群聊消息，发送id:{},群聊id:{},内容:{}", session.getUserId(), message.getGroupId(),
                message.getContent());
        return msgInfo;
    }

    @Override
    public List<GroupMessageVO> loadOffineMessage(Long minId) {
        long time = System.currentTimeMillis();
        UserSession session = SessionContext.getSession();
        List<GroupMessage> messages = new ArrayList<>();
        // 查询用户加入的群组
        List<GroupMember> members = groupMemberService.findByUserId(session.getUserId());
        Set<Long> groupIds = members.stream().map(GroupMember::getGroupId).collect(Collectors.toSet());
        // 只能拉取最近N天的消息：
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        // 先把时间窗口映射为 id 下界（窗口外最后一条 id），主查询走主键范围
        Long finalMinId = Math.max(minId, findOfflineTimeWindowBoundId(minDate));
        if (!groupIds.isEmpty()) {
            LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
            wrapper.gt(finalMinId > 0, GroupMessage::getId, finalMinId);
            wrapper.in(GroupMessage::getGroupId, groupIds);
            wrapper.orderByDesc(GroupMessage::getId);
            wrapper.last("limit " + Constant.MAX_OFFLINE_MESSAGE_SIZE);
            messages = this.list(wrapper);
        }
        // 保证每个会话至少会拉到一条消息
        if (messages.size() >= Constant.MAX_OFFLINE_MESSAGE_SIZE) {
            messages = appendLastMessageInConversation(groupIds, messages, finalMinId);
        }
        // 查询退群前的消息
        Date minQuitTime = minDate;
        if (minId > 0) {
            // 如果某个群的退群时间大于起始消息的发送时间，那消息是不用推送的，过滤掉
            GroupMessage message = this.getById(minId);
            if (!Objects.isNull(message) && message.getSendTime().compareTo(minDate) > 0) {
                minQuitTime = message.getSendTime();
            }
        }
        List<GroupMessage> quitMessages = Collections.synchronizedList(new ArrayList<>());
        List<GroupMember> quitMembers = groupMemberService.findQuitMembers(session.getUserId(), minQuitTime);
        quitMembers.parallelStream().forEach(quitMember -> {
            LambdaQueryWrapper<GroupMessage> quitWrapper = Wrappers.lambdaQuery();
            quitWrapper.gt(finalMinId > 0, GroupMessage::getId, finalMinId);
            quitWrapper.between(GroupMessage::getSendTime, minDate, quitMember.getQuitTime());
            quitWrapper.eq(GroupMessage::getGroupId, quitMember.getGroupId());
            quitWrapper.orderByDesc(GroupMessage::getId);
            quitWrapper.last("limit 100");
            List<GroupMessage> groupMessages = this.list(quitWrapper);
            quitMessages.addAll(groupMessages);
        });
        messages.addAll(quitMessages);
        members.addAll(quitMembers);
        // 已经删除的消息
        List<MessageDeletion> deletions =
                messageDeletionService.findByChatType(session.getUserId(), ChatType.GROUP.getCode());
        // 整个会话删掉的消息就不再推送了
        messages = messages.stream().filter(m -> !isDeleteChat(m, deletions)).collect(Collectors.toList());
        // 转成map方便提取
        Map<Long, GroupMember> groupMemberMap = CollStreamUtil.toIdentityMap(members, GroupMember::getGroupId);
        // 通过群聊对消息进行分组
        Map<Long, List<GroupMessage>> messageGroupMap =
                messages.stream().collect(Collectors.groupingBy(GroupMessage::getGroupId));
        List<GroupMessageVO> vos = new LinkedList<>();
        for (Map.Entry<Long, List<GroupMessage>> entry : messageGroupMap.entrySet()) {
            Long groupId = entry.getKey();
            List<GroupMessage> groupMessages = entry.getValue();
            // 填充消息状态
            String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
            Object o = redisTemplate.opsForHash().get(key, session.getUserId().toString());
            long readedMaxId = Objects.isNull(o) ? -1 : Long.parseLong(o.toString());
            Map<Object, Object> maxIdMap = null;
            for (GroupMessage m : groupMessages) {
                // 排除加群之前的消息
                GroupMember member = groupMemberMap.get(m.getGroupId());
                if (DateUtil.compare(member.getCreatedTime(), m.getSendTime()) > 0) {
                    continue;
                }
                // 组装vo
                GroupMessageVO vo = convert(m);
                // 标记已经删除的消息
                vo.setDeleted(isDeleteMessage(m, deletions));
                // 填充状态
                if (MessageStatus.PENDING.code().equals(m.getStatus()) && readedMaxId >= m.getId()) {
                    vo.setStatus(readedMaxId >= m.getId() ? MessageStatus.READED.code() : MessageStatus.PENDING.code());
                }
                // 针对回执消息填充已读人数
                if (m.getReceipt() && m.getSendId().equals(session.getUserId())) {
                    if (Objects.isNull(maxIdMap)) {
                        maxIdMap = redisTemplate.opsForHash().entries(key);
                    }
                    int count = getReadedUserIds(maxIdMap, m.getId(), m.getSendId()).size();
                    vo.setReadedCount(count);
                }
                vos.add(vo);
            }
        }
        log.info("拉取离线群聊消息,用户id:{},数量:{},耗时:{},minId:{}", session.getUserId(), vos.size(),
                System.currentTimeMillis() - time, minId);
        // 排序
        return vos.stream().sorted(Comparator.comparing(GroupMessageVO::getId)).collect(Collectors.toList());
    }

    @Override
    public void readedMessage(Long groupId, Long messageId) {
        UserSession session = SessionContext.getSession();
        // 如果前端没有传消息id,取出最后一条消息id
        if (Objects.isNull(messageId)) {
            messageId = findMaxMessageId(groupId);
            if (messageId < 0) {
                return;
            }
        }
        // 推送消息给自己的其他终端,同步清空会话列表中的未读数量
        GroupMessageVO msgInfo = new GroupMessageVO();
        msgInfo.setType(MessageType.READED.code());
        msgInfo.setSendTime(new Date());
        msgInfo.setSendId(session.getUserId());
        msgInfo.setGroupId(groupId);
        IMGroupMessage<GroupMessageVO> sendMessage = new IMGroupMessage<>();
        sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
        sendMessage.setSendToSelf(true);
        sendMessage.setData(msgInfo);
        sendMessage.setSendResult(false);
        imClient.sendGroupMessage(sendMessage);
        // 已读消息key
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
        // 原来的已读消息位置
        Object val = redisTemplate.opsForHash().get(key, session.getUserId().toString());
        Long maxReadedId = Objects.isNull(val) ? 0 : Long.parseLong(val.toString());
        if (messageId.compareTo(maxReadedId) <= 0) {
            return;
        }
        // 记录已读消息位置
        redisTemplate.opsForHash().put(key, session.getUserId().toString(), messageId);
        // 推送消息回执，刷新已读人数显示
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, groupId);
        wrapper.gt(GroupMessage::getId, maxReadedId);
        wrapper.le(GroupMessage::getId, messageId);
        wrapper.ne(GroupMessage::getStatus, MessageStatus.RECALL.code());
        wrapper.eq(GroupMessage::getReceipt, true);
        wrapper.last("limit 10");
        List<GroupMessage> receiptMessages = this.list(wrapper);
        if (CollectionUtil.isNotEmpty(receiptMessages)) {
            List<Long> userIds = groupMemberService.findUserIdsByGroupId(groupId);
            Map<Object, Object> maxIdMap = redisTemplate.opsForHash().entries(key);
            for (GroupMessage receiptMessage : receiptMessages) {
                int readedCount = getReadedUserIds(maxIdMap, receiptMessage.getId(), receiptMessage.getSendId()).size();
                // 如果所有人都已读，记录回执消息完成标记
                if (readedCount >= userIds.size() - 1) {
                    receiptMessage.setReceiptOk(true);
                    this.updateById(receiptMessage);
                }
                // 推送给回执消息发送方，更新已读人数
                msgInfo = new GroupMessageVO();
                msgInfo.setId(receiptMessage.getId());
                msgInfo.setLocalId(receiptMessage.getLocalId());
                msgInfo.setGroupId(groupId);
                msgInfo.setReadedCount(readedCount);
                msgInfo.setReceiptOk(receiptMessage.getReceiptOk());
                msgInfo.setType(MessageType.RECEIPT.code());
                sendMessage = new IMGroupMessage<>();
                sendMessage.setSender(new IMUserInfo(session.getUserId(), session.getTerminal()));
                sendMessage.setRecvIds(List.of(receiptMessage.getSendId()));
                sendMessage.setData(msgInfo);
                sendMessage.setSendToSelf(false);
                sendMessage.setSendResult(false);
                imClient.sendGroupMessage(sendMessage);
            }
        }
    }

    @Override
    public List<Long> findReadedUsers(Long groupId, Long messageId) {
        UserSession session = SessionContext.getSession();
        GroupMessage message = this.getById(messageId);
        if (Objects.isNull(message)) {
            throw new GlobalException("消息不存在");
        }
        // 是否在群聊里面
        GroupMember member = groupMemberService.findByGroupAndUserId(groupId, session.getUserId());
        if (Objects.isNull(member) || member.getQuit()) {
            throw new GlobalException("您已不在群聊里面");
        }
        // 已读位置key
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, groupId);
        // 一次获取所有用户的已读位置
        Map<Object, Object> maxIdMap = redisTemplate.opsForHash().entries(key);
        // 返回已读用户的id集合
        return getReadedUserIds(maxIdMap, message.getId(), message.getSendId());
    }

    @Override
    public List<GroupMessageVO> loadHistoryMessage(GroupMessageHistoryDTO dto) {
        UserSession session = SessionContext.getSession();
        GroupMember member = groupMemberService.findByGroupAndUserId(dto.getGroupId(), session.getUserId());
        if (Objects.isNull(member)) {
            throw new GlobalException("您已不在群聊里面");
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, dto.getGroupId());
        if (CollectionUtil.isNotEmpty(dto.getLocalIds())) {
            wrapper.in(GroupMessage::getLocalId, dto.getLocalIds());
        } else if (CollectionUtil.isNotEmpty(dto.getSeqNos())) {
            // P1：seqNo 与主键 id 同义，这里按 id 查
            wrapper.in(GroupMessage::getId, dto.getSeqNos());
        } else if (!Objects.isNull(dto.getMaxSeqNo()) && !Objects.isNull(dto.getMinSeqNo())) {
            wrapper.between(GroupMessage::getId, dto.getMinSeqNo(), dto.getMaxSeqNo());
        }
        // 退群后的消息不再拉取
        if (member.getQuit()) {
            wrapper.le(GroupMessage::getSendTime, member.getQuitTime());
        }
        // 进群前和60天前的消息都不再拉取
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        minDate = minDate.after(member.getCreatedTime()) ? minDate : member.getCreatedTime();
        wrapper.ge(GroupMessage::getSendTime, minDate);
        wrapper.orderByAsc(GroupMessage::getId);
        wrapper.last("limit 100");
        List<GroupMessage> messages = this.list(wrapper);
        if (CollectionUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        List<MessageDeletion> deletions =
                messageDeletionService.findByChatIdAndType(session.getUserId(), ChatType.GROUP.getCode(), dto.getGroupId());
        // 整个会话删掉的消息就不再推送了
        messages = messages.stream().filter(m -> !isDeleteChat(m, deletions)).collect(Collectors.toList());
        // 填充消息状态
        String key = StrUtil.join(":", RedisKey.IM_GROUP_READED_POSITION, dto.getGroupId());
        Object o = redisTemplate.opsForHash().get(key, session.getUserId().toString());
        long readedMaxId = Objects.isNull(o) ? -1 : Long.parseLong(o.toString());
        Map<Object, Object> maxIdMap = redisTemplate.opsForHash().entries(key);
        List<GroupMessageVO> vos = new ArrayList<>();
        // 转换vo
        for (GroupMessage m : messages) {
            GroupMessageVO vo = convert(m);
            // 标记已经删除的消息
            vo.setDeleted(isDeleteMessage(m, deletions));
            // 填充状态
            if (MessageStatus.PENDING.code().equals(m.getStatus()) && readedMaxId >= m.getId()) {
                vo.setStatus(MessageStatus.READED.code());
            }
            // 回执消息填充已读人数
            if (m.getReceipt() && m.getSendId().equals(session.getUserId())) {
                int count = getReadedUserIds(maxIdMap, m.getId(), m.getSendId()).size();
                vo.setReadedCount(count);
            }
            vos.add(vo);
        }
        return vos;
    }


    @Override
    public void deleteMessage(MessageDeleteDTO dto) {
        UserSession session = SessionContext.getSession();
        messageDeletionService.deleteByMessage(session.getUserId(), ChatType.GROUP.getCode(), dto.getChatId(),
                dto.getMessageIds());
    }

    @Override
    public void deleteChat(ChatDeleteDTO dto) {
        UserSession session = SessionContext.getSession();
        // 获取会话中最后一条消息
        Long maxMessageId = findMaxMessageId(dto.getChatId());
        if (maxMessageId < 0) {
            return;
        }
        // 保存删除记录
        messageDeletionService.deleteByChat(session.getUserId(), ChatType.GROUP.getCode(), dto.getChatId(),
                maxMessageId);
    }

    /**
     * 保存消息。
     *
     * <p>P1 改造：<b>不再需要分布式锁，也不再需要 Redis 序列号</b>。
     * 消息顺序统一以数据库自增主键 {@code id} 为唯一依据，
     * 对外暴露的 {@code seqNo} 在读时直接映射为 {@code id}
     * （见 {@link #convert(GroupMessage)}），两者天然同序。</p>
     *
     * <p>改造前：{@code @RedisLock} 串行化同一群的所有发送，先 INCR 出 seq_no 再 INSERT。
     * 实测该锁是单群吞吐的首要瓶颈（去掉后 A1 +62%、A2 +83%），
     * 但直接删锁会让 10.2% 的消息 id 与 seq_no 倒挂 —— 现在两者是同一个值，问题从根上消失。
     * 详见 docs/群聊性能优化对照报告.md</p>
     */
    @Override
    public void saveMessage(GroupMessage message) {
        if (StrUtil.isEmpty(message.getLocalId())) {
            message.setLocalId(IdWorker.getIdStr());
        }
        save(message);
        // 记录消息最大id
        String key = StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_ID, message.getGroupId());
        redisTemplate.opsForValue().set(key, message.getId(), Constant.MAX_OFFLINE_MESSAGE_DAYS, TimeUnit.DAYS);
    }

    private List<Long> getReadedUserIds(Map<Object, Object> maxIdMap, Long messageId, Long sendId) {
        List<Long> userIds = new LinkedList<>();
        maxIdMap.forEach((k, v) -> {
            Long userId = Long.valueOf(k.toString());
            long maxId = Long.parseLong(v.toString());
            // 发送者不计入已读人数
            if (!sendId.equals(userId) && maxId >= messageId) {
                userIds.add(userId);
            }
        });
        return userIds;
    }

    private void validMessage(GroupMessageDTO dto) {
        if (MessageType.TEXT.code().equals(dto.getType()) && dto.getContent().length() > Constant.MAX_MESSAGE_LENGTH) {
            throw new GlobalException(String.format("消息长度不能大于%s个字符", Constant.MAX_MESSAGE_LENGTH));
        }
        try {
            // 非文字消息-保证数据格式是json,防止前端报错
            if (!MessageType.TEXT.code().equals(dto.getType())) {
                JSON.parse(dto.getContent());
            }
        } catch (Exception e) {
            throw new GlobalException("消息格式异常");
        }
    }

    Long findMaxMessageId(Long groupId) {
        String key = StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_ID, groupId);
        Object id = redisTemplate.opsForValue().get(key);
        if (!Objects.isNull(id)) {
            return Long.parseLong(id.toString());
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupMessage::getGroupId, groupId);
        wrapper.orderByDesc(GroupMessage::getId);
        wrapper.last("limit 1");
        GroupMessage message = this.getOne(wrapper);
        if (!Objects.isNull(message)) {
            redisTemplate.opsForValue().set(key, message.getId());
            return message.getId();
        }
        return -1L;
    }

    private String buildMaxMessageIdKey(Long groupId) {
        return StrUtil.join(":", RedisKey.IM_GROUP_MESSAGE_MAX_ID, groupId);
    }

    /**
     * 查询离线时间窗口外的最后一条消息 id，作为 id > bound 的下界。
     */
    private Long findOfflineTimeWindowBoundId(Date minDate) {
        String key = RedisKey.IM_GROUP_OFFLINE_TIME_MIN_ID;
        Object cached = redisTemplate.opsForValue().get(key);
        if (!Objects.isNull(cached)) {
            return Long.parseLong(cached.toString());
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.select(GroupMessage::getId);
        wrapper.le(GroupMessage::getSendTime, minDate);
        wrapper.orderByDesc(GroupMessage::getSendTime);
        wrapper.orderByDesc(GroupMessage::getId);
        wrapper.last("limit 1");
        GroupMessage message = this.getOne(wrapper);
        if (Objects.isNull(message)) {
            return 0L;
        }
        redisTemplate.opsForValue().set(key, message.getId(), 30, TimeUnit.MINUTES);
        return message.getId();
    }

    List<GroupMessage> appendLastMessageInConversation(Set<Long> groupIds, List<GroupMessage> messages, Long minId) {
        groupIds = new HashSet<>(groupIds);
        Set<Long> existIds = messages.stream().map(GroupMessage::getGroupId).collect(Collectors.toSet());
        // 移除已经拉到消息的会话
        groupIds.removeAll(existIds);
        if (groupIds.isEmpty()) {
            return messages;
        }
        List<String> keys = groupIds.stream().map(this::buildMaxMessageIdKey).collect(Collectors.toList());
        List<Object> maxMessageIds = redisTemplate.opsForValue().multiGet(keys);
        maxMessageIds =
                maxMessageIds.stream().filter(id -> !Objects.isNull(id) && Long.parseLong(id.toString()) > minId)
                        .collect(Collectors.toList());
        if (maxMessageIds.isEmpty()) {
            return messages;
        }
        LambdaQueryWrapper<GroupMessage> wrapper = Wrappers.lambdaQuery();
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        wrapper.ge(GroupMessage::getSendTime, minDate);
        wrapper.in(GroupMessage::getId, maxMessageIds);
        List<GroupMessage> lastMessages = this.list(wrapper);
        messages.addAll(lastMessages);
        return messages;
    }

    private Boolean isDeleteMessage(GroupMessage message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> {
            if (!message.getGroupId().equals(deletion.getChatId())) {
                return false;
            }
            if (DeleteType.BY_MESSAGE.getCode().equals(deletion.getDeleteType())) {
                return message.getId().equals(deletion.getMessageId());
            }
            return false;
        });
    }

    private Boolean isDeleteChat(GroupMessage message, List<MessageDeletion> deletions) {
        return deletions.stream().anyMatch(deletion -> {
            if (!message.getGroupId().equals(deletion.getChatId())) {
                return false;
            }
            if (DeleteType.BY_CHAT.getCode().equals(deletion.getDeleteType())) {
                return deletion.getMessageId() >= message.getId();
            }
            return false;
        });
    }

    private GroupMessageVO convert(GroupMessage message) {
        // 不填充无意义的字段，尽可能压缩报文体积
        GroupMessageVO vo = new GroupMessageVO();
        vo.setId(message.getId());
        vo.setSeqNo(message.getId());
        vo.setLocalId(message.getLocalId());
        vo.setGroupId(message.getGroupId());
        vo.setContent(message.getContent());
        vo.setSendTime(message.getSendTime());
        vo.setSendId(message.getSendId());
        vo.setSendNickName(message.getSendNickName());
        vo.setType(message.getType());
        vo.setStatus(message.getStatus());
        if (StrUtil.isNotEmpty(message.getAtUserIds())) {
            List<String> ids = CommaTextUtils.asList(message.getAtUserIds());
            vo.setAtUserIds(ids.stream().map(id -> Long.parseLong(id)).collect(Collectors.toList()));
        }
        if (message.getReceipt()) {
            vo.setReceipt(message.getReceipt());
            vo.setReceiptOk(message.getReceiptOk());
            vo.setReadedCount(0);
        }
        return vo;
    }

}
