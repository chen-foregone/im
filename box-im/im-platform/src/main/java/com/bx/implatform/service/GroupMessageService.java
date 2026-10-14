package com.bx.implatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.GroupMessageDTO;
import com.bx.implatform.dto.GroupFanoutTask;
import com.bx.implatform.dto.GroupMessageHistoryDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.entity.GroupMessage;
import com.bx.implatform.vo.GroupMessageVO;

import java.util.List;

public interface GroupMessageService extends IService<GroupMessage> {

    /**
     * 发送群聊消息(高并发接口，查询mysql接口都要进行缓存)
     *
     * @param dto 群聊消息
     * @return 群聊id
     */
    GroupMessageVO sendMessage(GroupMessageDTO dto);

    /**
     * 撤回消息
     *
     * @param id 消息id
     */
    GroupMessageVO recallMessage(Long id);

    /**
     * 拉取离线消息，只能拉取最近1个月的消息
     *
     * @param minId 消息起始id
     */
    List<GroupMessageVO> loadOffineMessage(Long minId);

    /**
     * 消息已读,同步其他终端，清空未读数量
     *
     * @param groupId   群聊
     * @param messageId 消息id
     */
    void readedMessage(Long groupId, Long messageId);

    /**
     * 查询群里消息已读用户id列表
     *
     * @param groupId   群里id
     * @param messageId 消息id
     * @return 已读用户id集合
     */
    List<Long> findReadedUsers(Long groupId, Long messageId);

    /**
     * 拉取历史消息
     *
     * @param dto dto
     */
    List<GroupMessageVO> loadHistoryMessage(GroupMessageHistoryDTO dto);

    /**
     * 删除消息
     *
     * @param dto
     */
    void deleteMessage(MessageDeleteDTO dto);

    /**
     * 删除会话
     *
     * @param dto dto
     */
    void deleteChat(ChatDeleteDTO dto);

    /**
     * 保存消息
     * @param message
     */
    void saveMessage(GroupMessage message);

    /**
     * 执行群聊写扩散(fan-out)。
     *
     * <p>由 Kafka 消费者 {@code GroupFanoutConsumer} 调用，运行在消费者的线程上，
     * 因此万人大群的 O(群成员数) 工作不再占用 HTTP 请求线程。
     *
     * @param task 扇出任务，只含 groupId/messageId/senderId/senderTerminal
     */
    void executeFanout(GroupFanoutTask task);
}
