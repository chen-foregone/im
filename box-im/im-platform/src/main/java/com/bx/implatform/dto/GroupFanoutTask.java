package com.bx.implatform.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 群聊写扩散(fan-out)任务。
 *
 * <p>设计取舍：<b>只投递 4 个字段，不投递接收者列表</b>。
 * 万人群一条消息有 10,000 个接收者 x 3 个终端，若把 recvIds 一起投递，
 * 单条 Kafka 消息约 100KB；而 recvIds 可由 groupId 从群成员缓存直接得到，
 * 消息体也从库里按 messageId 重读。实测群成员列表已有缓存(findUserIdsByGroupId)，
 * 重读代价为 1 次缓存命中 + 1 次主键查询。
 *
 * <p>这样做还有一个额外好处：消费者读到的是<b>已提交</b>的消息，
 * 天然规避了「投递时数据尚未提交」的时序问题。
 *
 * @author performance-tuning
 */
@Data
public class GroupFanoutTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 群聊 id，同时作为 Kafka 分区键，保证同群任务进入同一分区 */
    private Long groupId;

    /** 消息 id（im_group_message 主键）。消费者据此重读消息本体 */
    private Long messageId;

    /** 发送者 id */
    private Long senderId;

    /** 发送者终端类型，用于 sendToSelf 时跳过自己的当前终端 */
    private Integer senderTerminal;

}
