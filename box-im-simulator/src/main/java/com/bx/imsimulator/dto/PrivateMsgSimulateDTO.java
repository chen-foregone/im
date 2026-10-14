package com.bx.imsimulator.dto;

import lombok.Data;

/**
 * 模拟私聊：a 个发送方，每人取前 b 个好友；共进行 c 轮发送，总条数 = a×b×c。
 */
@Data
public class PrivateMsgSimulateDTO {

    /** 发送方人数 a（按 im_user.id 升序取前 a 个） */
    private Integer senderCount;

    /** 每位发送方从 im_friend 中取前 b 个好友作为接收方 */
    private Integer receiverCount;

    /** 发送轮次 c：每轮对每个 (发送方, 其第 j 个好友) 各发 1 条，共 a×b 条/轮 */
    private Integer sendRounds;

    /**
     * 全局发送速率上限（条/秒），所有发送方合计；null 或 ≤0 表示不限制。
     */
    private Integer maxSendQps;

}
