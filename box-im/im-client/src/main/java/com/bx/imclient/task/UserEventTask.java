package com.bx.imclient.task;

import com.bx.imclient.listener.EventListenerMulticaster;
import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.model.IMUserEvent;
import com.bx.imcommon.mq.RedisMQConsumer;
import com.bx.imcommon.mq.RedisMQListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@RedisMQListener(queue = IMRedisKey.IM_USER_EVENT_QUEUE, batchSize = 100)
public class UserEventTask extends RedisMQConsumer<IMUserEvent> {

    private final EventListenerMulticaster listenerMulticaster;
    @Override
    public void onMessage(List<IMUserEvent> events) {
        listenerMulticaster.multicast(events);
    }
}
