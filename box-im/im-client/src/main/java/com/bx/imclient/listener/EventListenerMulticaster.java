package com.bx.imclient.listener;

import cn.hutool.core.collection.CollUtil;
import com.bx.imclient.annotation.IMListener;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.model.IMUserEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
public class EventListenerMulticaster {

    @Autowired(required = false)
    private List<EventListener> eventListeners = Collections.emptyList();

    public void multicast(List<IMUserEvent> events) {
        if (CollUtil.isEmpty(events)) {
            return;
        }
        for (EventListener listener : eventListeners) {
            IMListener annotation = listener.getClass().getAnnotation(IMListener.class);
            if (!Objects.isNull(annotation) && annotation.type().equals(IMListenerType.USER_EVENT)) {
                // 回调到调用方处理
                listener.process(events);
            }
        }
    }
}
