package com.bx.imcommon.model;

import lombok.Data;

/**
 * 群消息信号：读扩散群推给在线成员的通知体，不含消息内容。
 *
 * <p>客户端收到后应以 {@code maxMessageId} 为游标，调用已有的群历史接口
 * ({@code GET /message/group/history}) 拉取自己的增量，再按 seqNo 合并渲染。</p>
 *
 * <p>为什么可以不带内容：客户端本来就按 seqNo 排序渲染
 * (im-web/src/stores/chat.ts:505)，且已有 reloadMissMessage 能按 seqNo 区间补拉，
 * 「永远能拉」的能力早已具备，信号只是把「该拉了」这个时机告诉客户端。</p>
 */
@Data
public class IMGroupSignal {

    /** 群id */
    private Long groupId;

    /**
     * 该群当前最大的消息id（即本次写入的那条）。
     * 客户端可据此判断「我是不是已经有这条消息了」，避免无谓拉取。
     */
    private Long maxMessageId;

    /** 发送者id，供客户端做未读数/提示音判断 */
    private Long senderId;
}