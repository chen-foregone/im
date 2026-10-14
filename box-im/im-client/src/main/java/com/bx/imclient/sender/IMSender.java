package com.bx.imclient.sender;

import cn.hutool.core.collection.CollUtil;
import com.bx.imclient.listener.MessageListenerMulticaster;
import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.perf.PerfCounters;
import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.enums.IMSendCode;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.model.*;
import com.bx.imcommon.mq.RedisMQTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class IMSender {

    @Autowired
    private RedisMQTemplate redisMQTemplate;

    @Value("${spring.application.name}")
    private String appName;

    /**
     * 群接收方数量达到该值时，扇出改用「先用 presence 筛在线用户」的两段式查询。
     *
     * <p>两段式把 MGET 的 key 数从 3N 降到 N + 3x在线数，但代价是多一次网络往返。
     * 小群 key 本来就少，一次 MGET 更划算。实测（A 系列，固定 100 在线）：</p>
     * <ul>
     *   <li>500 人群：两段式 494 msg/s，单次 550 msg/s —— 退步 10%</li>
     *   <li>2000 人群：两段式 779 msg/s，单次 596 msg/s —— 提升 31%</li>
     * </ul>
     * <p>交叉点在 500~2000 之间，取 1000 作阈值。</p>
     */
    @Value("${im.group.presence-filter-threshold:1000}")
    private int presenceFilterThreshold;

    private final MessageListenerMulticaster listenerMulticaster;

    public <T> void sendSystemMessage(IMSystemMessage<T> message) {
        // 根据群聊每个成员所连的IM-server，进行分组
        Map<String, IMUserInfo> sendMap = new LinkedHashMap<>();
        for (Integer terminal : message.getRecvTerminals()) {
            message.getRecvIds().forEach(id -> {
                String key = IMRedisKey.userServerIdKey(id, terminal);
                sendMap.put(key, new IMUserInfo(id, terminal));
            });
        }
        // 批量拉取
        List<Object> serverIds = redisMQTemplate.opsForValue().multiGet(sendMap.keySet());
        // 格式:map<服务器id,list<接收方>>
        Map<Integer, List<IMUserInfo>> serverMap = new HashMap<>();
        List<IMUserInfo> offLineUsers = new LinkedList<>();
        int idx = 0;
        for (Map.Entry<String, IMUserInfo> entry : sendMap.entrySet()) {
            Integer serverId = (Integer)serverIds.get(idx++);
            if (!Objects.isNull(serverId)) {
                List<IMUserInfo> list = serverMap.computeIfAbsent(serverId, o -> new LinkedList<>());
                list.add(entry.getValue());
            } else {
                // 加入离线列表
                offLineUsers.add(entry.getValue());
            }
        }
        // 逐个server发送
        for (Map.Entry<Integer, List<IMUserInfo>> entry : serverMap.entrySet()) {
            IMRecvInfo recvInfo = new IMRecvInfo();
            recvInfo.setCmd(IMCmdType.SYSTEM_MESSAGE.code());
            recvInfo.setReceivers(new LinkedList<>(entry.getValue()));
            recvInfo.setServiceName(appName);
            recvInfo.setSendResult(message.getSendResult());
            recvInfo.setData(message.getData());
            // 推送至队列
            String key = String.join(":", IMRedisKey.IM_MESSAGE_SYSTEM_QUEUE, entry.getKey().toString());
            redisMQTemplate.opsForList().rightPush(key, recvInfo);
        }
        // 对离线用户回复消息状态
        if (message.getSendResult() && !offLineUsers.isEmpty()) {
            IMBatchSendResult<T> result = new IMBatchSendResult<>();
            result.setReceivers(offLineUsers);
            result.setCode(IMSendCode.NOT_ONLINE.code());
            result.setData(message.getData());
            listenerMulticaster.multicast(IMListenerType.SYSTEM_MESSAGE, List.of(result));
        }
    }

    public <T> void sendPrivateMessage(IMPrivateMessage<T> message) {
        IMBatchPrivateMessage<T> batch = new IMBatchPrivateMessage<>();
        batch.setSender(message.getSender());
        if (!Objects.isNull(message.getRecvId())) {
            batch.setRecvIds(List.of(message.getRecvId()));
        }
        batch.setRecvTerminals(message.getRecvTerminals());
        batch.setSendToSelf(message.getSendToSelf());
        batch.setSendResult(message.getSendResult());
        batch.setData(message.getData());
        sendBatchPrivateMessage(batch);
    }

    public <T> void sendBatchPrivateMessage(IMBatchPrivateMessage<T> message) {
        List<Long> recvIds = message.getRecvIds();
        List<Integer> recvTerminals = message.getRecvTerminals();
        List<IMUserInfo> offlineUsers = new ArrayList<>();
        IMUserInfo sender = message.getSender();
        // 接收方：redisKey → 接收用户信息（LinkedHashMap 保证遍历顺序与下方 multiGet 结果下标一致）
        Map<String, IMUserInfo> recvKeyToUser = new LinkedHashMap<>();
        if (!CollUtil.isEmpty(recvIds) && !CollUtil.isEmpty(recvTerminals)) {
            for (Integer terminal : recvTerminals) {
                for (Long id : recvIds) {
                    String key = IMRedisKey.userServerIdKey(id, terminal);
                    recvKeyToUser.put(key, new IMUserInfo(id, terminal));
                }
            }
        }
        // 发送方其它终端：与 selfKeys 按下标一一对应，用于根据 multiGet 结果推送同步消息
        List<String> selfKeys = new ArrayList<>();
        List<Integer> selfOtherTerminals = null;
        if (message.getSendToSelf()) {
            Long senderId = sender.getId();
            List<Integer> terminals = IMTerminalType.codes();
            selfOtherTerminals = new ArrayList<>(terminals.size());
            for (Integer terminal : terminals) {
                if (terminal.equals(sender.getTerminal())) {
                    continue;
                }
                selfKeys.add(IMRedisKey.userServerIdKey(senderId, terminal));
                selfOtherTerminals.add(terminal);
            }
        }
        int recvKeyCount = recvKeyToUser.size();
        int selfKeyCount = selfKeys.size();
        int totalKeys = recvKeyCount + selfKeyCount;
        // 一次 MGET：先全部接收方 key，再全部「自己其它终端」key；结果前半段对应接收方，后半段从 recvKeyCount 起对应 sendToSelf
        List<Object> serverIds = Collections.emptyList();
        if (totalKeys > 0) {
            List<String> keys = new ArrayList<>(totalKeys);
            keys.addAll(recvKeyToUser.keySet());
            keys.addAll(selfKeys);
            serverIds = redisMQTemplate.opsForValue().multiGet(keys);
        }
        // 按 IM-server 分组后写入私聊队列；无 serverId 的记入离线列表
        if (!recvKeyToUser.isEmpty()) {
            Map<Integer, List<IMUserInfo>> serverMap = new HashMap<>(16);
            int idx = 0;
            for (Map.Entry<String, IMUserInfo> entry : recvKeyToUser.entrySet()) {
                Integer serverId = (Integer)serverIds.get(idx++);
                if (serverId != null) {
                    serverMap.computeIfAbsent(serverId, k -> new ArrayList<>()).add(entry.getValue());
                } else {
                    offlineUsers.add(entry.getValue());
                }
            }
            for (Map.Entry<Integer, List<IMUserInfo>> entry : serverMap.entrySet()) {
                pushPrivateMessage(entry.getKey(), sender, entry.getValue(), message.getSendResult(),
                        message.getData());
            }
        }
        // 自己的其它终端在线则入队；sendResult 固定 false，与单条私聊「同步给自己」行为一致
        if (!selfKeys.isEmpty()) {
            Long senderId = sender.getId();
            for (int i = 0; i < selfKeys.size(); i++) {
                Integer serverId = (Integer)serverIds.get(recvKeyCount + i);
                if (serverId != null) {
                    List<IMUserInfo> receivers = List.of(new IMUserInfo(senderId, selfOtherTerminals.get(i)));
                    pushPrivateMessage(serverId, sender, receivers, false, message.getData());
                }
            }
        }
        // 需要回推发送结果时，将离线接收方一次性回调给业务侧
        if (message.getSendResult() && !offlineUsers.isEmpty()) {
            IMBatchSendResult<T> result = new IMBatchSendResult<>();
            result.setSender(message.getSender());
            result.setReceivers(offlineUsers);
            result.setCode(IMSendCode.NOT_ONLINE.code());
            result.setData(message.getData());
            listenerMulticaster.multicast(IMListenerType.PRIVATE_MESSAGE, List.of(result));
        }
    }

    public <T> void sendGroupMessage(IMGroupMessage<T> message) {
        // 根据群聊每个成员所连的IM-server，进行分组
        List<Long> recvIds = message.getRecvIds();
        List<Integer> recvTerminals = message.getRecvTerminals();
        List<IMUserInfo> offlineUsers = new ArrayList<>();
        IMUserInfo sender = message.getSender();
        // 接收方：redisKey → 用户信息（LinkedHashMap 保证遍历顺序与 multiGet 结果下标一致）
        Map<String, IMUserInfo> recvKeyToUser = resolveOnlineRecvKeys(recvIds, recvTerminals);
        // 发送方其它终端：与 selfKeys 按下标一一对应
        List<String> selfKeys = new ArrayList<>();
        List<Integer> selfOtherTerminals = null;
        if (message.getSendToSelf()) {
            Long senderId = sender.getId();
            List<Integer> terminals = IMTerminalType.codes();
            selfOtherTerminals = new ArrayList<>(terminals.size());
            for (Integer terminal : terminals) {
                if (terminal.equals(sender.getTerminal())) {
                    continue;
                }
                selfKeys.add(IMRedisKey.userServerIdKey(senderId, terminal));
                selfOtherTerminals.add(terminal);
            }
        }
        int recvKeyCount = recvKeyToUser.size();
        int totalKeys = recvKeyCount + selfKeys.size();
        List<Object> serverIds = Collections.emptyList();
        if (totalKeys > 0) {
            List<String> keys = new ArrayList<>(totalKeys);
            keys.addAll(recvKeyToUser.keySet());
            keys.addAll(selfKeys);
            // 批量拉取
            serverIds = redisMQTemplate.opsForValue().multiGet(keys);
        }
        if (!recvKeyToUser.isEmpty()) {
            // 格式:map<服务器id,list<接收方>>
            Map<Integer, List<IMUserInfo>> serverMap = new HashMap<>(16);
            int idx = 0;
            for (Map.Entry<String, IMUserInfo> entry : recvKeyToUser.entrySet()) {
                Integer serverId = (Integer)serverIds.get(idx++);
                if (serverId != null) {
                    serverMap.computeIfAbsent(serverId, k -> new ArrayList<>()).add(entry.getValue());
                } else {
                    // 加入离线列表
                    offlineUsers.add(entry.getValue());
                }
            }
            // 逐个server发送
            for (Map.Entry<Integer, List<IMUserInfo>> entry : serverMap.entrySet()) {
                pushGroupMessage(entry.getKey(), sender, entry.getValue(), message.getSendResult(), message.getData());
            }
        }
        // 推送给自己的其他终端
        if (!selfKeys.isEmpty()) {
            Long senderId = sender.getId();
            for (int i = 0; i < selfKeys.size(); i++) {
                // 获取终端连接的channelId
                Integer serverId = (Integer)serverIds.get(recvKeyCount + i);
                // 如果终端在线，将数据存储至redis，等待拉取推送
                if (serverId != null) {
                    // 自己的消息不需要回推消息结果
                    List<IMUserInfo> receivers = List.of(new IMUserInfo(senderId, selfOtherTerminals.get(i)));
                    pushGroupMessage(serverId, sender, receivers, false, message.getData());
                }
            }
        }
        // 对离线用户回复消息状态
        if (message.getSendResult() && !offlineUsers.isEmpty()) {
            IMBatchSendResult<T> result = new IMBatchSendResult<>();
            result.setReceivers(offlineUsers);
            result.setCode(IMSendCode.NOT_ONLINE.code());
            result.setData(message.getData());
            listenerMulticaster.multicast(IMListenerType.GROUP_MESSAGE, List.of(result));
        }
    }

    public Map<Long, List<IMTerminalType>> getOnlineTerminal(List<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        // 把所有用户的key都存起来
        Map<String, IMUserInfo> userMap = new LinkedHashMap<>();
        for (Long id : userIds) {
            for (Integer terminal : IMTerminalType.codes()) {
                String key = IMRedisKey.userServerIdKey(id, terminal);
                userMap.put(key, new IMUserInfo(id, terminal));
            }
        }
        // 批量拉取
        List<Object> serverIds = redisMQTemplate.opsForValue().multiGet(userMap.keySet());
        int idx = 0;
        Map<Long, List<IMTerminalType>> onlineMap = new HashMap<>();
        for (Map.Entry<String, IMUserInfo> entry : userMap.entrySet()) {
            // serverid有值表示用户在线
            if (serverIds.get(idx++) != null) {
                IMUserInfo userInfo = entry.getValue();
                List<IMTerminalType> terminals = onlineMap.computeIfAbsent(userInfo.getId(), o -> new LinkedList<>());
                terminals.add(IMTerminalType.fromCode(userInfo.getTerminal()));
            }
        }
        // 去重并返回
        return onlineMap;
    }

    public List<IMTerminalType> getOnlineTerminal(Long userId) {
        List<IMTerminalType> terminals = new LinkedList<>();
        for (Integer terminal : IMTerminalType.codes()) {
            IMTerminalType type = IMTerminalType.fromCode(terminal);
            if (isOnline(userId, type)) {
                terminals.add(type);
            }
        }
        return terminals;
    }

    public Boolean isOnline(Long userId, IMTerminalType terminal) {
        String key = IMRedisKey.userServerIdKey(userId, terminal.code());
        return redisMQTemplate.hasKey(key);
    }

    public Boolean isOnline(Long userId) {
        List<String> keys = new ArrayList<>(IMTerminalType.codes().size());
        for (Integer terminal : IMTerminalType.codes()) {
            keys.add(IMRedisKey.userServerIdKey(userId, terminal));
        }
        Long count = redisMQTemplate.countExistingKeys(keys);
        return count != null && count > 0;
    }

    public List<Long> getOnlineUser(List<Long> userIds) {
        return new LinkedList<>(getOnlineTerminal(userIds).keySet());
    }

    /**
     * 强制用户所有在线终端下线
     */
    public void forceLogout(Long userId, Integer type, String reason) {
        for (Integer terminal : IMTerminalType.codes()) {
            String key = IMRedisKey.userServerIdKey(userId, terminal);
            Object serverId = redisMQTemplate.opsForValue().get(key);
            if (Objects.isNull(serverId)) {
                continue;
            }
            IMForceLogoutInfo logoutInfo = new IMForceLogoutInfo();
            logoutInfo.setUserId(userId);
            logoutInfo.setTerminal(terminal);
            logoutInfo.setType(type);
            logoutInfo.setReason(reason);
            String queueKey = String.join(":", IMRedisKey.IM_USER_FORCE_LOGOUT_QUEUE, serverId.toString());
            redisMQTemplate.opsForList().rightPush(queueKey, logoutInfo);
        }
    }

    /** 封装私聊 {@link IMRecvInfo} 并入对应 IM-server 的 Redis 私聊队列 */
    private <T> void pushPrivateMessage(Integer serverId, IMUserInfo sender, List<IMUserInfo> receivers,
                                        Boolean sendResult, T data) {
        IMRecvInfo recvInfo = new IMRecvInfo();
        recvInfo.setCmd(IMCmdType.PRIVATE_MESSAGE.code());
        recvInfo.setSender(sender);
        recvInfo.setReceivers(receivers);
        recvInfo.setServiceName(appName);
        recvInfo.setSendResult(sendResult);
        recvInfo.setData(data);
        String queueKey = String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString());
        redisMQTemplate.opsForList().rightPush(queueKey, recvInfo);
    }

    /**
     * 解析「接收方 x 终端」里在线的那些，返回 redisKey → 用户信息。
     *
     * <p>LinkedHashMap 保证遍历顺序与后续 multiGet 的结果下标一致。</p>
     *
     * <p>两段式与单次的选择见 {@link #presenceFilterThreshold}。</p>
     */
    private Map<String, IMUserInfo> resolveOnlineRecvKeys(List<Long> recvIds, List<Integer> recvTerminals) {
        Map<String, IMUserInfo> recvKeyToUser = new LinkedHashMap<>();
        if (CollUtil.isEmpty(recvIds) || CollUtil.isEmpty(recvTerminals)) {
            return recvKeyToUser;
        }
        // 小群：key 数本来就少，多一次往返不划算，直接用原先的单次 MGET
        if (recvIds.size() < presenceFilterThreshold) {
            for (Integer terminal : recvTerminals) {
                for (Long id : recvIds) {
                    String key = IMRedisKey.userServerIdKey(id, terminal);
                    recvKeyToUser.put(key, new IMUserInfo(id, terminal));
                }
            }
            return recvKeyToUser;
        }
        // 大群：两段式查询，先用 N 个 presence key 筛出在线用户，再只对在线用户查终端 key。
        // 直查终端 key 需要 3N 个（每成员 x 3 终端），万人群一次 MGET 三万 key；
        // 在线 100 人的万人群改为 10,000 + 300 = 10,300 个，省 66%。
        //
        // 注意：presence 是后加的 key。若某连接的 presence 缺失（升级期间的存量连接），
        // 该用户会被判为离线而收不到群消息，需重连自愈。
        // presence 在连接存活期内由登录写入 + 心跳续期，正常路径不会缺。
        List<String> presenceKeys = new ArrayList<>(recvIds.size());
        for (Long id : recvIds) {
            presenceKeys.add(IMRedisKey.userPresenceKey(id));
        }
        final boolean probe = PerfCounters.enabled;
        long tPre = probe ? System.nanoTime() : 0L;
        List<Object> presenceValues = redisMQTemplate.opsForValue().multiGet(presenceKeys);
        if (probe) {
            PerfCounters.fanPresenceNanos.add(System.nanoTime() - tPre);
            PerfCounters.fanPresenceKeys.add(presenceKeys.size());
        }
        List<Long> onlineIds = new ArrayList<>();
        if (presenceValues != null) {
            for (int i = 0; i < presenceValues.size() && i < recvIds.size(); i++) {
                if (presenceValues.get(i) != null) {
                    onlineIds.add(recvIds.get(i));
                }
            }
        }
        for (Integer terminal : recvTerminals) {
            for (Long id : onlineIds) {
                String key = IMRedisKey.userServerIdKey(id, terminal);
                recvKeyToUser.put(key, new IMUserInfo(id, terminal));
            }
        }
        return recvKeyToUser;
    }

    /**
     * 读扩散群的下发：只推一条不含内容的信号给在线成员。
     *
     * <p>与 sendGroupMessage 的区别只在于下发体：这里不做离线展开、不回推发送结果、
     * 不带消息内容。在线成员的定位复用同一套 presence/终端查询逻辑。</p>
     *
     * <p>没有「发送方其它终端」的处理：发送方自己的其它终端也需要这条信号，
     * 但它们本来就在 recvIds 里（群成员列表含发送者，只是在扇出时被剔除），
     * 所以这里由调用方决定 recvIds 是否含发送者。</p>
     */
    public void sendGroupSignal(IMGroupSignal signal, List<Long> recvIds, List<Integer> recvTerminals,
                                IMUserInfo sender) {
        if (signal == null || CollUtil.isEmpty(recvIds) || CollUtil.isEmpty(recvTerminals)) {
            return;
        }
        Map<String, IMUserInfo> recvKeyToUser = resolveOnlineRecvKeys(recvIds, recvTerminals);
        // 发送方自己的其它终端：与 sendGroupMessage 一致，走单独的自推路径。
        // 少了这段，发送者在另一台设备上就不会收到这条消息的拉取信号。
        // 这里的 key 并入下面的主 MGET，而不是逐个 GET ——
        // 逐个 GET 会让每条消息多出最多 3 次串行往返，在万人群这种高消息速率下是实打实的开销。
        List<String> selfKeys = new ArrayList<>(3);
        List<Integer> selfTerminals = new ArrayList<>(3);
        if (sender != null && sender.getId() != null) {
            for (Integer terminal : IMTerminalType.recvCodes()) {
                if (terminal.equals(sender.getTerminal())) {
                    continue;
                }
                selfKeys.add(IMRedisKey.userServerIdKey(sender.getId(), terminal));
                selfTerminals.add(terminal);
            }
        }
        if (recvKeyToUser.isEmpty() && selfKeys.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(recvKeyToUser.size() + selfKeys.size());
        keys.addAll(recvKeyToUser.keySet());
        keys.addAll(selfKeys);
        final boolean probe2 = PerfCounters.enabled;
        long tSrv = probe2 ? System.nanoTime() : 0L;
        List<Object> serverIds = redisMQTemplate.opsForValue().multiGet(keys);
        if (probe2) {
            PerfCounters.fanServerNanos.add(System.nanoTime() - tSrv);
            int online = 0;
            if (serverIds != null) {
                for (Object o : serverIds) {
                    if (o != null) {
                        online++;
                    }
                }
            }
            PerfCounters.fanOnlineIds.add(online);
        }
        if (serverIds == null) {
            return;
        }
        Map<Integer, List<IMUserInfo>> serverMap = new HashMap<>(16);
        int idx = 0;
        for (Map.Entry<String, IMUserInfo> entry : recvKeyToUser.entrySet()) {
            Integer serverId = (Integer) serverIds.get(idx++);
            if (serverId != null) {
                serverMap.computeIfAbsent(serverId, k -> new ArrayList<>()).add(entry.getValue());
            }
        }
        for (Map.Entry<Integer, List<IMUserInfo>> entry : serverMap.entrySet()) {
            pushGroupMessage(entry.getKey(), sender, entry.getValue(), false, signal,
                    IMCmdType.GROUP_MESSAGE_SIGNAL);
        }
        for (int i = 0; i < selfKeys.size(); i++) {
            Integer serverId = (Integer) serverIds.get(recvKeyToUser.size() + i);
            if (serverId != null) {
                pushGroupMessage(serverId, sender,
                        List.of(new IMUserInfo(sender.getId(), selfTerminals.get(i))), false, signal,
                        IMCmdType.GROUP_MESSAGE_SIGNAL);
            }
        }
    }

    /** 封装群聊 {@link IMRecvInfo} 并入对应 IM-server 的 Redis 群聊队列 */
    private <T> void pushGroupMessage(Integer serverId, IMUserInfo sender, List<IMUserInfo> receivers,
                                      Boolean sendResult, T data) {
        pushGroupMessage(serverId, sender, receivers, sendResult, data, IMCmdType.GROUP_MESSAGE);
    }

    /** 同上，可指定下发的命令字（读扩散群下发的是群消息信号） */
    private <T> void pushGroupMessage(Integer serverId, IMUserInfo sender, List<IMUserInfo> receivers,
                                      Boolean sendResult, T data, IMCmdType cmd) {
        IMRecvInfo recvInfo = new IMRecvInfo();
        recvInfo.setCmd(cmd.code());
        recvInfo.setSender(sender);
        recvInfo.setReceivers(receivers);
        recvInfo.setServiceName(appName);
        recvInfo.setSendResult(sendResult);
        recvInfo.setData(data);
        String queueKey = String.join(":", IMRedisKey.IM_MESSAGE_GROUP_QUEUE, serverId.toString());
        // 推送至队列
        redisMQTemplate.opsForList().rightPush(queueKey, recvInfo);
    }
}
