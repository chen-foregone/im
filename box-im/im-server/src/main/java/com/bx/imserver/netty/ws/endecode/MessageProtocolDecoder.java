package com.bx.imserver.netty.ws.endecode;

import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.util.JsonUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;

public class MessageProtocolDecoder extends MessageToMessageDecoder<TextWebSocketFrame> {

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, TextWebSocketFrame textWebSocketFrame, List<Object> list) throws Exception {
        IMSendInfo sendInfo = JsonUtil.mapper().readValue(textWebSocketFrame.text(), IMSendInfo.class);
        list.add(sendInfo);
    }
}
