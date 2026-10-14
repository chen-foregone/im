package com.bx.imclient.listener;

import com.bx.imcommon.model.IMUserEvent;

import java.util.List;

public interface EventListener {

     void process(List<IMUserEvent> event);

}
