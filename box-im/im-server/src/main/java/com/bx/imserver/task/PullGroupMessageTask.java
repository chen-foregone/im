package com.bx.imserver.task;

import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.model.IMRecvInfo;
import com.bx.imcommon.mq.RedisMQListener;
import com.bx.imserver.netty.processor.AbstractMessageProcessor;
import com.bx.imserver.netty.processor.ProcessorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RedisMQListener(queue = IMRedisKey.IM_MESSAGE_GROUP_QUEUE, batchSize = 100, period = 10)
public class PullGroupMessageTask extends AbstractPullMessageTask<IMRecvInfo> {

    @Override
    public void onMessage(IMRecvInfo recvInfo) {
        // 按投递方写入的 cmd 路由：写扩散是 GROUP_MESSAGE，读扩散是 GROUP_MESSAGE_SIGNAL。
        // 写死 GROUP_MESSAGE 会让读扩散的信号被当作群消息内容下发。
        IMCmdType cmd = IMCmdType.fromCode(recvInfo.getCmd());
        if (cmd == null) {
            cmd = IMCmdType.GROUP_MESSAGE;
        }
        AbstractMessageProcessor processor = ProcessorFactory.createProcessor(cmd);
        processor.process(recvInfo);
    }

}
