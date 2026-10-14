package com.bx.imsimulator.ws;

import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SimulatedSessionRegistry {

    private final ConcurrentHashMap<Long, SimulatedUserSession> sessions = new ConcurrentHashMap<>();

    public void register(Long userId, SimulatedUserSession session) {
        SimulatedUserSession old = sessions.put(userId, session);
        if (old != null) {
            old.close();
        }
    }

    public void remove(Long userId) {
        SimulatedUserSession s = sessions.remove(userId);
        if (s != null) {
            s.close();
        }
    }

    public void closeAll() {
        Collection<SimulatedUserSession> all = sessions.values();
        for (SimulatedUserSession s : all) {
            s.close();
        }
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }

    /** 当前所有在线会话的 userId，供验证接口遍历 */
    public java.util.Set<Long> userIds() {
        return sessions.keySet();
    }

    /** 第一步批量登录并注册在线后的 accessToken；无会话则 empty */
    public String findAccessToken(Long userId) {
        SimulatedUserSession s = sessions.get(userId);
        if (s == null) {
            return Strings.EMPTY;
        }
        return s.getAccessToken();
    }

    public SimulatedUserSession getIfPresent(Long userId) {
        return sessions.get(userId);
    }

    /** 所有已注册会话的 WS 私聊计数清零（私聊模拟每次启动前调用） */
    public void resetAllWsPrivateMessageCounters() {
        for (SimulatedUserSession s : sessions.values()) {
            s.resetWsPrivateMessageReceived();
        }
    }

    public long getAllPrivateMessageRecvived() {
        long count = 0;
        for (SimulatedUserSession s : sessions.values()) {
            count += s.getWsPrivateMessageReceivedCount();
        }
        return count;
    }

    /** 群聊模拟每次启动前清零所有会话的 WS 群聊计数 */
    public void resetAllWsGroupMessageCounters() {
        for (SimulatedUserSession s : sessions.values()) {
            s.resetWsGroupMessageReceived();
        }
    }

    public long getAllGroupMessageReceived() {
        long count = 0;
        for (SimulatedUserSession s : sessions.values()) {
            count += s.getWsGroupMessageReceivedCount();
        }
        return count;
    }

    /** 群聊模拟每次启动前清零所有会话的覆盖水位 */
    public void resetAllGroupSignalCoverage() {
        for (SimulatedUserSession s : sessions.values()) {
            s.resetGroupSignalCoverage();
        }
    }

    /**
     * 每个群的覆盖水位<b>下界</b>：所有在线会话中，该群最大信号 id 的<b>最小值</b>。
     *
     * <p>取下界而不是平均：只要有一个在线用户没追上，这次推送对他就是漏的。
     * 取最小值才能保证「所有人都覆盖到了」。
     *
     * <p>注意：只包含<b>至少被一个会话收到过信号</b>的群。某群所有人都没收到时不会出现在结果里，
     * 调用方必须用目标群列表去查，缺省的当作 0 处理。
     */
    /**
     * 逐人的覆盖水位：userId -> (groupId -> 最大信号 id)。
     *
     * <p>为什么不给一个「全局最小值」就完事：<b>发送者本人拿不到自己那条消息的信号</b>
     * （扇出时按设计剔除了 senderId）。所以每个会话对「自己发的最后一条」永远差一格，
     * 取全局最小值会被最后一条消息的发送者拉低 1，覆盖率恒为 0。
     * 正确的判据必须带上「谁发的」这个信息，由服务层按发送者排除后逐人比对。
     */
    public Map<Long, Map<Long, Long>> getCoverageByUser() {
        Map<Long, Map<Long, Long>> byUser = new HashMap<>();
        for (Map.Entry<Long, SimulatedUserSession> e : sessions.entrySet()) {
            Map<Long, Long> cov = e.getValue().getGroupSignalCoverage();
            if (!cov.isEmpty()) {
                byUser.put(e.getKey(), cov);
            }
        }
        return byUser;
    }

    public Map<Long, Long> getGroupCoverageFloor() {
        Map<Long, Long> floor = new HashMap<>();
        for (SimulatedUserSession s : sessions.values()) {
            for (Map.Entry<Long, Long> e : s.getGroupSignalCoverage().entrySet()) {
                floor.merge(e.getKey(), e.getValue(), Math::min);
            }
        }
        return floor;
    }

}
