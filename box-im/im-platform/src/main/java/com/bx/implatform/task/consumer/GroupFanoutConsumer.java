package com.bx.implatform.task.consumer;

import com.bx.implatform.dto.GroupFanoutTask;
import com.bx.implatform.service.GroupMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 群聊写扩散(fan-out)消费者。
 *
 * <p>职责刻意做得极薄：只负责把 Kafka 记录转交给 Service。
 * 真正的扇出逻辑(重读消息、取群成员、构建 IMGroupMessage、调用 IMSender)
 * 放在 GroupMessageServiceImpl.executeFanout，因为那里能复用私有的 convert()。
 *
 * <p>并发度由 spring.kafka.listener.concurrency 控制(默认与分区数一致)。
 * 位点提交策略是 ack-mode=record：每条处理成功才提交，
 * 扇出抛异常会触发重试，不会静默丢消息。
 *
 * <p>开关 im.kafka.fanout-enabled=false 时本 Bean 不注册，退回同步扇出。
 *
 * @author performance-tuning
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.kafka.fanout-enabled", havingValue = "true", matchIfMissing = true)
public class GroupFanoutConsumer {

    private final GroupMessageService groupMessageService;

    @KafkaListener(topics = "${im.kafka.fanout-topic:im-group-fanout}")
    public void onFanout(GroupFanoutTask task) {
        if (task == null) {
            return;
        }
        groupMessageService.executeFanout(task);
    }

}
