package com.bx.implatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.dto.PrivateMessageDTO;
import com.bx.implatform.dto.PrivateMessageHistoryDTO;
import com.bx.implatform.entity.PrivateMessage;
import com.bx.implatform.vo.PrivateMessageVO;

import java.util.List;

public interface PrivateMessageService extends IService<PrivateMessage> {

    /**
     * 发送私聊消息(高并发接口，查询mysql接口都要进行缓存)
     *
     * @param dto 私聊消息
     * @return 消息id
     */
    PrivateMessageVO sendMessage(PrivateMessageDTO dto);


    /**
     * 撤回消息
     *
     * @param id 消息id
     */
    PrivateMessageVO recallMessage(Long id);


    /**
     * 拉取离线消息，只能拉取最近1个月的消息
     *
     * @param minId 消息起始id
     */
    List<PrivateMessageVO> loadOfflineMessage(Long minId);


    /**
     * 消息已读,将整个会话的消息都置为已读状态
     *
     * @param friendId 好友id
     * @param messageId 消息id
     */
    void readedMessage(Long friendId,Long messageId);

    /**
     *  获取某个会话中已读消息的最大id
     *
     * @param sendId 发送方id
     * @param recvId 接受方id
     */
    Long getMaxReadedId(Long sendId,Long recvId);


    /**
     * 保存消息
     * @param message
     */
    void saveMessage(PrivateMessage message);

    /**
     * 删除消息
     * @param dto
     */
    void deleteMessage(MessageDeleteDTO dto);

    /**
     * 删除会话
     * @param dto dto
     */
    void deleteChat(ChatDeleteDTO dto);

    /**
     * 加载历史消息
     * @param dto
     */
    List<PrivateMessageVO>  loadHistoryMessage(PrivateMessageHistoryDTO dto);
}
