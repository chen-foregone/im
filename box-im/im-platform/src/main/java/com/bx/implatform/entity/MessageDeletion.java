package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 消息删除记录
 *
 * @author Blue
 * @date 2025-12-31
 */
@Data
@TableName("im_message_deletion")
public class MessageDeletion {

    /**
     * id
     */
    @TableId
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 会话类型 1:私聊 2:群聊
     */
    private Integer chatType;

    /**
     * 好友id、群聊id
     */
    private Long chatId;

    /**
     * 消息id
     */
    private Long messageId;

    /**
     * 删除类型 1:按消息删除 2:按会话删除
     */
    private Integer deleteType;

    /**
     * 消息删除时间
     */
    private Date deleteTime;

}

