package com.bx.imserver.netty.ws.endecode;

import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.perf.PerfCounters;
import com.bx.imcommon.util.JsonUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;

public class MessageProtocolEncoder extends MessageToMessageEncoder<IMSendInfo> {

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, IMSendInfo sendInfo, List<Object> list) throws Exception {
        // 复用全局 mapper：原实现每帧 new ObjectMapper()，实测 31.65us/帧 vs 复用 1.86us/帧（17 倍）
        final boolean probe = PerfCounters.enabled;
        long t0 = probe ? System.nanoTime() : 0L;
        String text = JsonUtil.mapper().writeValueAsString(sendInfo);
        if (probe) {
            PerfCounters.encNanos.add(System.nanoTime() - t0);
            PerfCounters.encFrames.increment();
            PerfCounters.encBytes.add(text.length());
        }
        TextWebSocketFrame frame = new TextWebSocketFrame(text);
        list.add(frame);
    }
}
