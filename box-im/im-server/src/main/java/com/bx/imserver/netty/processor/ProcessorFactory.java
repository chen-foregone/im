package com.bx.imserver.netty.processor;

import com.bx.imcommon.enums.IMCmdType;
import com.bx.imserver.util.SpringContextHolder;

public class ProcessorFactory {

    public static AbstractMessageProcessor createProcessor(IMCmdType cmd) {
        return switch (cmd) {
            case FORCE_LOGOUT -> SpringContextHolder.getApplicationContext().getBean(ForceLogoutProcessor.class);
            case LOGIN->SpringContextHolder.getApplicationContext().getBean(LoginProcessor.class);
            case HEART_BEAT -> SpringContextHolder.getApplicationContext().getBean(HeartbeatProcessor.class);
            case PRIVATE_MESSAGE->SpringContextHolder.getApplicationContext().getBean(PrivateMessageProcessor.class);
            // 读扩散群的信号与群消息走同一个处理器，只是下发的 cmd 不同，
            // 处理器按 IMRecvInfo.cmd 原样下发（见 GroupMessageProcessor）。
            case GROUP_MESSAGE, GROUP_MESSAGE_SIGNAL->SpringContextHolder.getApplicationContext().getBean(GroupMessageProcessor.class);
            case SYSTEM_MESSAGE->SpringContextHolder.getApplicationContext().getBean(SystemMessageProcessor.class);
            default -> null;
        };
    }

}
