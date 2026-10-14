package com.bx.imcommon.contant;

public final class IMRedisKey {


    /**
     * im-server最大id,从0开始递增
     */
    public static final String  IM_MAX_SERVER_ID = "im:max_server_id";
    /**
     * 用户ID所连接的IM-server的ID
     */
    public static final String  IM_USER_SERVER_ID = "im:user:server_id";

    /**
     * 同用户多终端共 slot，保证单用户查询在线不会分发到多个节点，提升性能
     */
    public static String userServerIdKey(Long userId, Integer terminal) {
        return String.join(":", IM_USER_SERVER_ID, "{" + userId + "}", terminal.toString());
    }

    /**
     * 用户在线标记（不区分终端）。任一终端在线即存在，最后一个终端下线时删除，带 TTL。
     *
     * <p>存在的意义：群消息扇出时若直接查 3x N 个终端 key，万人群要一次 MGET 30,000 个 key。
     * 改为先用 N 个 presence key 筛出在线用户、再只对在线用户查终端 key，
     * key 数降到 N + 3x(在线数)。在线 100 人的万人群：30,000 -> 10,300，省 66%。</p>
     *
     * <p>与 userServerIdKey 同样用 {} 包住 userId，保证同用户共 slot。</p>
     */
    public static String userPresenceKey(Long userId) {
        return String.join(":", "im:user:presence", "{" + userId + "}");
    }

    /**
     * 系统消息队列
     */
    public static final String IM_MESSAGE_SYSTEM_QUEUE = "im:message:system";
    /**
     * 私聊消息队列
     */
    public static final String IM_MESSAGE_PRIVATE_QUEUE = "im:message:private";
    /**
     * 群聊消息队列
     */
    public static final String IM_MESSAGE_GROUP_QUEUE = "im:message:group";

    /**
     * 系统消息发送结果队列
     */
    public static final String IM_RESULT_SYSTEM_QUEUE = "im:result:system";
    /**
     * 私聊消息发送结果队列
     */
    public static final String IM_RESULT_PRIVATE_QUEUE = "im:result:private";
    /**
     * 群聊消息发送结果队列
     */
    public static final String IM_RESULT_GROUP_QUEUE = "im:result:group";

    /**
     * 用户事件队列
     */
    public static final String IM_USER_EVENT_QUEUE = "im:user:event";

    /**
     * 强制用户退出队列
     */
    public static final String IM_USER_FORCE_LOGOUT_QUEUE = "im:user:force_logout";

    /**
     * 用户拒绝访问标记（封禁/注销等，鉴权与 WS 登录拦截用）
     * value 为 {@link com.bx.imcommon.enums.IMForceLogoutType} 的 code
     */
    public static final String IM_USER_DENIED = "im:user:denied";

    /**
     * 群在线成员集合（SET，元素是 userId 的十进制字符串）。
     *
     * <p>存在的意义：群消息扇出原本要对「全部群成员」做一次 N 个 key 的 presence MGET。
     * 万人群每条消息都要扫 10,000 个 key，实测 1000 人在线时占单条消息 125ms 里的 44.7ms；
     * 紧随其后的「展开成员列表再过滤发送者」又要 27.7ms，两者合计 58%。
     *
     * <p>改为按群维护在线集后，扇出只需一次 SMEMBERS，复杂度从 O(群成员数) 降到 O(群在线人数)。
     *
     * <p>用 {} 包住 groupId，与 userServerIdKey / userPresenceKey 同样的用意：
     * 同一个群的整个集合落在同一个槽，集群模式下 SMEMBERS/SADD 才不会跨槽。
     */
    public static final String IM_GROUP_ONLINE = "im:group:online";

    public static String groupOnlineKey(Long groupId) {
        return String.join(":", IM_GROUP_ONLINE, "{" + groupId + "}");
    }

}
