package com.bx.implatform.service;

import com.bx.imcommon.contant.IMRedisKey;
import com.bx.implatform.entity.GroupMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 群在线成员集：群维度扇出的「预筛表」。
 *
 * <p>维护每个群的在线成员集合 {@code im:group:online:{groupId}}：
 * 用户上线时加入其所在全部群，下线时移除。扇出时一次 SMEMBERS 就拿到「该群此刻在线的人」，
 * 不再需要对全部群成员做 N 个 key 的 presence MGET，也不再需要展开整份成员列表。
 *
 * <p><b>为什么必须按批 + 流水线</b>：
 * 一个用户可以属于几十个群（实测测试数据里是 70 个）。若逐个用户、逐个群地发 SADD，
 * 1000 人上线就是 70,000 次独立 Redis 往返 —— 实测耗时近 1 分钟、事件队列积压 276 条，
 * 等于把一个 O(消息数) 的问题换成了一个 O(用户数 × 群数) 的登录风暴。
 * 所以这里把整批上线事件合成「一次 DB 查询 + 一次 Redis 流水线」，往返数与用户数无关。
 *
 * <p><b>为什么用 StringRedisTemplate 而不是项目里的 RedisTemplate</b>：
 * 后者的 value 序列化开了 {@code enableDefaultTyping}，一个 Long 会被写成
 * {@code ["java.lang.Long",123]}。万人群的在线集若用它存，光是类型元数据就要多占数倍带宽，
 * 省下的 key 数会被序列化开销吃掉。这里存纯十进制字符串，既省又便于 redis-cli 直接核对。
 *
 * <p><b>一致性取舍</b>：在线集是「预筛」，不是真值来源。
 * <ul>
 *   <li>多出条目无害：后续按 userId 查终端 key 时会自然落空，只是浪费一次查询。</li>
 *   <li>少条目会让这次推送漏掉某人的在线终端；但读扩散群按设计「永远能拉」，
 *       客户端下一次拉时间线即可补齐。</li>
 * </ul>
 * 因此这里只做尽力而为的维护，任何异常都只告警、不影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupOnlineService {

    private final StringRedisTemplate stringRedisTemplate;
    private final GroupMemberService groupMemberService;

    /**
     * 总开关。false 时 {@link #onlineUserIds} 直接返回 null，扇出回退到
     * 「查全部成员 + 下游 presence 筛选」的旧路径，用于 A/B 对比与快速回滚。
     */
    @Value("${im.fanout.online-set-enabled:true}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    /** 一批用户上线：一次 DB 查询 + 一次 Redis 流水线 */
    public void usersOnline(Collection<Long> userIds) {
        if (!enabled || userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            Map<Long, List<Long>> userToGroups = loadUserGroups(userIds);
            if (userToGroups.isEmpty()) {
                return;
            }
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisSerializer ser = new StringRedisSerializer();
                for (Map.Entry<Long, List<Long>> e : userToGroups.entrySet()) {
                    byte[] value = ser.serialize(e.getKey().toString());
                    for (Long groupId : e.getValue()) {
                        connection.sAdd(ser.serialize(IMRedisKey.groupOnlineKey(groupId)), value);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[群在线集] 批量上线维护失败,用户数:{}", userIds.size(), e);
        }
    }

    /**
     * 一批用户下线：先流水线查 presence 过滤出「所有终端都下线」的人，再流水线 SREM。
     *
     * <p>为什么不能直接 SREM：im-server 对「每个终端断开」都发一次 OFFLINE 事件，
     * 多终端用户只断一个终端时不能把他从在线集里摘掉。
     * presence key 的语义正是「任一终端在线即存在，最后一个终端下线才删除」。
     */
    public void usersOffline(Collection<Long> userIds) {
        if (!enabled || userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            List<Long> reallyOffline = filterLastTerminalOffline(userIds);
            if (reallyOffline.isEmpty()) {
                return;
            }
            Map<Long, List<Long>> userToGroups = loadUserGroups(reallyOffline);
            if (userToGroups.isEmpty()) {
                return;
            }
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisSerializer ser = new StringRedisSerializer();
                for (Map.Entry<Long, List<Long>> e : userToGroups.entrySet()) {
                    byte[] value = ser.serialize(e.getKey().toString());
                    for (Long groupId : e.getValue()) {
                        connection.sRem(ser.serialize(IMRedisKey.groupOnlineKey(groupId)), value);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[群在线集] 批量下线维护失败,用户数:{}", userIds.size(), e);
        }
    }

    /**
     * 单个用户退出单个群：立即从该群在线集摘掉。
     *
     * <p>为什么必须显式做：扇出的接收者就是这张集合。不摘掉的话，
     * 一个已经退群的人会继续收到那个群的推送，直到他下线为止。
     * 注意与 usersOffline 的区别：那个是「用户整体下线」，presence key 会被删除；
     * 这个是「用户还在线，但不再属于这个群」，presence key 依然存在，
     * 所以不能复用下线那条路径。</p>
     */
    public void userQuitGroup(Long userId, Long groupId) {
        if (!enabled || userId == null || groupId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForSet().remove(IMRedisKey.groupOnlineKey(groupId), userId.toString());
        } catch (Exception e) {
            log.warn("[群在线集] 退群摘除失败,userId:{},groupId:{}", userId, groupId, e);
        }
    }

    /**
     * 单个用户加入单个群：仅当他此刻在线时才进入在线集。
     *
     * <p>离线用户刻意不加：在线集是「当下能收到推送的人」的快照，
     * 离线的人等他下次登录时由 usersOnline 统一补齐。
     * 这里若不加判断，会给一个根本收不到推送的人占位，把集合撑大。</p>
     */
    public void userJoinGroup(Long userId, Long groupId) {
        if (!enabled || userId == null || groupId == null) {
            return;
        }
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(IMRedisKey.userPresenceKey(userId)))) {
                return;
            }
            stringRedisTemplate.opsForSet().add(IMRedisKey.groupOnlineKey(groupId), userId.toString());
        } catch (Exception e) {
            log.warn("[群在线集] 入群加入失败,userId:{},groupId:{}", userId, groupId, e);
        }
    }

    /** 一次查库拿到这批用户各自所在的群 */
    private Map<Long, List<Long>> loadUserGroups(Collection<Long> userIds) {
        List<GroupMember> members = groupMemberService.findByUserIds(userIds);
        Map<Long, List<Long>> userToGroups = new HashMap<>();
        if (members == null) {
            return userToGroups;
        }
        for (GroupMember member : members) {
            userToGroups.computeIfAbsent(member.getUserId(), k -> new ArrayList<>()).add(member.getGroupId());
        }
        return userToGroups;
    }

    /** 流水线 EXISTS，保留 presence key 已消失（即最后一个终端也下线了）的用户 */
    private List<Long> filterLastTerminalOffline(Collection<Long> userIds) {
        List<Long> candidates = new ArrayList<>(userIds);
        List<Object> exists = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisSerializer ser = new StringRedisSerializer();
            for (Long userId : candidates) {
                connection.exists(ser.serialize(IMRedisKey.userPresenceKey(userId)));
            }
            return null;
        });
        List<Long> result = new ArrayList<>();
        if (exists == null || exists.size() != candidates.size()) {
            // 流水线结果异常时保守处理：不删任何在线集条目。
            // 多留条目无害（下游查终端 key 会落空），误删则会漏发。
            log.warn("[群在线集] presence 流水线结果异常,期望:{},实际:{}", candidates.size(),
                exists == null ? "null" : exists.size());
            return result;
        }
        for (int i = 0; i < candidates.size(); i++) {
            Object v = exists.get(i);
            boolean online;
            if (v instanceof Boolean) {
                online = (Boolean) v;
            } else if (v instanceof Number) {
                online = ((Number) v).longValue() > 0;
            } else {
                online = v != null;
            }
            if (!online) {
                result.add(candidates.get(i));
            }
        }
        return result;
    }

    /**
     * 群内当前在线的成员 id。
     *
     * @return 空集表示「确实没人在线」；返回 {@code null} 表示开关关闭或读取失败，
     *         调用方必须回退旧路径，绝不能把 null 当成空集（那会漏发全部在线用户）。
     */
    public Set<Long> onlineUserIds(Long groupId) {
        if (!enabled) {
            return null;
        }
        try {
            Set<String> raw = stringRedisTemplate.opsForSet().members(IMRedisKey.groupOnlineKey(groupId));
            if (raw == null || raw.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Long> ids = new HashSet<>(raw.size());
            for (String s : raw) {
                if (s != null && !s.isEmpty()) {
                    ids.add(Long.valueOf(s));
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[群在线集] 读取失败,groupId:{}", groupId, e);
            return null;
        }
    }
}
