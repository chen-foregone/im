package com.bx.imsimulator.dto;

import lombok.Data;

/**
 * 群聊模拟：每个发送用户在其已加入、名称符合规模前缀的群中按群 id 升序取前 g 个，各发 c 轮，总 HTTP 条数 = s×g×c。
 */
@Data
public class GroupMsgSimulateDTO {

    /** {@link com.bx.imsimulator.enums.GroupMsgSimulateScale#name()}，如 TEN */
    private String groupScale;

    /** 发送人数 s（按 im_user.id 升序取前 s 个） */
    private Integer senderCount;

    /** 接收群数量 g（每名发送者：已加入且名称前缀匹配的群中按 id 升序取前 g 个） */
    private Integer receiverGroupCount;

    /** 发送轮次 c */
    private Integer sendRounds;

    /** 在线群成员数量(用于计算ws推送量)  */
    private Integer onlineMemberCount;

    /**
     * 全局发送速率上限（条/秒），所有发送方合计；null 或 ≤0 表示不限制。
     */
    private Integer maxSendQps;
}
