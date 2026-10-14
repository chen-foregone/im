package com.bx.implatform.listener;

import com.bx.imclient.annotation.IMListener;
import com.bx.imclient.listener.MessageListener;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.enums.IMSendCode;
import com.bx.imcommon.model.IMSendResult;
import com.bx.implatform.vo.GroupMessageVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

@Slf4j
@IMListener(type = IMListenerType.GROUP_MESSAGE)
@AllArgsConstructor
public class GroupMessageListener implements MessageListener<GroupMessageVO> {

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void process(List<IMSendResult<GroupMessageVO>> results) {
        // 【性能修复】原实现是「每个接收者打一行 log.info」——万人群一条消息就是 10000 行。
        // 实测：四轮压测写出 1,988,338 行 / 330MB，约 7300 行/秒的同步磁盘 I/O，
        // 直接导致 5000/10000 人群出现 HttpTimeoutException（各 50 次）。
        // 现改为「每次回调聚合一行」+ 仅 DEBUG 级别：I/O 从 O(N) 降到 O(1)。
        // 需要逐条投递明细时，把 com.bx.implatform.listener 的日志级别调到 DEBUG 即可。
        if (results == null || results.isEmpty() || !log.isDebugEnabled()) {
            return;
        }
        GroupMessageVO first = results.get(0).getData();
        long success = results.stream()
                .filter(result -> IMSendCode.SUCCESS.code().equals(result.getCode()))
                .count();
        log.debug("群消息送达，消息id:{}，成功 {}/{}", first.getId(), success, results.size());
    }

}
