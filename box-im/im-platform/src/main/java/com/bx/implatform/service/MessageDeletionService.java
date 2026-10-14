package com.bx.implatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bx.implatform.entity.MessageDeletion;

import java.util.List;

/**
 * 消息删除记录Service
 *
 * @author Blue
 * @date 2025-12-31
 */
public interface MessageDeletionService extends IService<MessageDeletion> {

    /**
     * 删除指定id的消息
     *
     * @param userId     用户id
     * @param chatType   会话类型
     * @param chatId     会话对象id
     * @param messageIds 消息id列表
     */
    void deleteByMessage(Long userId, Integer chatType, Long chatId, List<Long> messageIds);

    /**
     * 按会话删除消息
     *
     * @param userId       用户id
     * @param chatType     会话类型
     * @param chatId       会话对象id
     * @param maxMessageId 会话最大消息id
     */
    void deleteByChat(Long userId, Integer chatType, Long chatId, Long maxMessageId);

    /**
     * 查询删除记录
     *
     * @param userId   用户id
     * @param chatType 会话类型
     * @return
     */
    List<MessageDeletion> findByChatType(Long userId, Integer chatType);

    /**
     * 查询删除记录
     *
     * @param userId   用户id
     * @param chatType 会话类型
     * @param chatId   会话对象id
     * @return
     */
    List<MessageDeletion> findByChatIdAndType(Long userId, Integer chatType, Long chatId);
}
