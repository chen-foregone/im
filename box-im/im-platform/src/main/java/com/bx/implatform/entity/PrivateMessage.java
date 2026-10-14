package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * <p>
 *
 * </p>
 *
 * @author blue
 * @since 2022-10-01
 */
@Data
@TableName("im_private_message")
public class PrivateMessage {

    /**
     * id
     */
    private Long id;

    /**
     * 前端本地消息id,由前端生成
     */
    private String localId;

    /**
     * 消息序列号，单会话连续递增
     */
    private Long seqNo;

    /**
     * 发送用户id
     */
    private Long sendId;

    /**
     * 接收用户id
     */
    private Long recvId;

    /**
     * 会话key, 格式:userId1_userId2,注意跟前端的conv_key格式不一致
     */
    private String convKey;


    /**
     * 发送内容
     */
    private String content;

    /**
     * 消息类型 MessageType
     */
    private Integer type;

    /**
     * 状态
     */
    private Integer status;


    /**
     * 发送时间
     */
    private Date sendTime;


}
