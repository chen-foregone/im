package com.bx.implatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.implatform.contant.Constant;
import com.bx.implatform.contant.RedisKey;
import com.bx.implatform.entity.MessageDeletion;
import com.bx.implatform.enums.DeleteType;
import com.bx.implatform.mapper.MessageDeletionMapper;
import com.bx.implatform.service.MessageDeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Blue
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = RedisKey.IM_CACHE_MESSAGE_DELETION)
public class MessageDeletionServiceImpl extends ServiceImpl<MessageDeletionMapper, MessageDeletion>
    implements MessageDeletionService {

    @CacheEvict(key = "#userId+':'+#chatType+':'+#chatId")
    @Override
    public void deleteByMessage(Long userId, Integer chatType, Long chatId, List<Long> messageIds) {
        // 过滤已经删除的消息
        LambdaQueryWrapper<MessageDeletion> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MessageDeletion::getUserId, userId);
        wrapper.eq(MessageDeletion::getChatId, chatId);
        wrapper.eq(MessageDeletion::getChatType, chatType);
        wrapper.in(MessageDeletion::getMessageId, messageIds);
        wrapper.select(MessageDeletion::getMessageId);
        List<MessageDeletion> deletions = this.list(wrapper);
        List<Long> existIds = deletions.stream().map(MessageDeletion::getMessageId).collect(Collectors.toList());
        // 存储删除记录
        List<MessageDeletion> newDeletions =
            messageIds.stream().filter(id -> existIds.stream().noneMatch(existId -> id.equals(existId))).map(id -> {
                MessageDeletion deletion = new MessageDeletion();
                deletion.setMessageId(id);
                deletion.setDeleteType(DeleteType.BY_MESSAGE.getCode());
                deletion.setChatType(chatType);
                deletion.setChatId(chatId);
                deletion.setUserId(userId);
                deletion.setDeleteTime(new Date());
                return deletion;
            }).collect(Collectors.toList());
        this.saveBatch(newDeletions);
    }

    @CacheEvict(key = "#userId+':'+#chatType+':'+#chatId")
    @Transactional
    @Override
    public void deleteByChat(Long userId, Integer chatType, Long chatId, Long maxMessageId) {
        // 清理该会话之前删除的消息记录(整个会话都删了，之前的删除记录没作用了)
        LambdaQueryWrapper<MessageDeletion> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MessageDeletion::getUserId, userId);
        wrapper.eq(MessageDeletion::getChatId, chatId);
        wrapper.eq(MessageDeletion::getChatType, chatType);
        wrapper.le(MessageDeletion::getMessageId, maxMessageId);
        this.remove(wrapper);
        // 存储新删除记录
        MessageDeletion deletion = new MessageDeletion();
        deletion.setMessageId(maxMessageId);
        deletion.setDeleteType(DeleteType.BY_CHAT.getCode());
        deletion.setChatType(chatType);
        deletion.setChatId(chatId);
        deletion.setUserId(userId);
        deletion.setDeleteTime(new Date());
        this.save(deletion);
    }

    @Override
    public List<MessageDeletion> findByChatType(Long userId, Integer chatType) {
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        LambdaQueryWrapper<MessageDeletion> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MessageDeletion::getUserId, userId);
        wrapper.eq(MessageDeletion::getChatType, chatType);
        wrapper.ge(MessageDeletion::getDeleteTime, minDate);
        return this.list(wrapper);
    }

    @Override
    @Cacheable(key = "#userId+':'+#chatType+':'+#chatId")
    public List<MessageDeletion> findByChatIdAndType(Long userId, Integer chatType, Long chatId) {
        Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS));
        LambdaQueryWrapper<MessageDeletion> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MessageDeletion::getUserId, userId);
        wrapper.eq(MessageDeletion::getChatType, chatType);
        wrapper.eq(MessageDeletion::getChatId, chatId);
        wrapper.ge(MessageDeletion::getDeleteTime, minDate);
        return this.list(wrapper);
    }
}
