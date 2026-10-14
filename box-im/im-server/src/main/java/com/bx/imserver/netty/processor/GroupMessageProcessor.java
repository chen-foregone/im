package com.bx.imserver.netty.processor;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.bx.imcommon.contant.IMRedisKey;
import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.enums.IMSendCode;
import com.bx.imcommon.model.IMBatchSendResult;
import com.bx.imcommon.model.IMRecvInfo;
import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.imcommon.mq.RedisMQTemplate;
import com.bx.imcommon.perf.PerfCounters;
import com.bx.imcommon.util.ThreadPoolExecutorFactory;
import com.bx.imserver.netty.UserChannelCtxMap;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroupMessageProcessor extends AbstractMessageProcessor<IMRecvInfo> {

    private final RedisMQTemplate redisMQTemplate;
    private final GroupSignalCoalescer groupSignalCoalescer;
    private final ScheduledThreadPoolExecutor EXECUTOR = ThreadPoolExecutorFactory.getThreadPoolExecutor();

    @Override
    public void process(IMRecvInfo recvInfo) {
        IMUserInfo sender = recvInfo.getSender();
        List<IMUserInfo> receivers = recvInfo.getReceivers();
        log.info("接收到群消息，发送者:{},接收用户数量:{}，内容:{}", sender.getId(), receivers.size(), recvInfo.getData());
        List<IMUserInfo> noChannelReceivers = new ArrayList<>();
        List<IMUserInfo> errorReceivers = new ArrayList<>();
        List<PushTask> pushTasks = new ArrayList<>(receivers.size());
        // 按上游投递时指定的 cmd 原样下发：写扩散群是 GROUP_MESSAGE(4)，
        // 读扩散群是 GROUP_MESSAGE_SIGNAL(6)。写死 4 会让客户端把
        // 「无内容的信号」当成群消息处理。
        final Integer cmd = recvInfo.getCmd() != null ? recvInfo.getCmd() : IMCmdType.GROUP_MESSAGE.code();
        // 读扩散群的信号是「该群有新消息到 maxMessageId」的通知，同一窗口内只有最大的那条有意义，
        // 因此交给合流器缓冲，攒够一个窗口再每个（通道,群）下发一帧。
        // 这是投递侧最大的一笔收益：万人群 1000 在线时，原实现每条消息要出 999 帧。
        final boolean isSignal = IMCmdType.GROUP_MESSAGE_SIGNAL.code().equals(cmd);
        int merged = 0;
        final boolean probe = PerfCounters.enabled;
        long t0 = probe ? System.nanoTime() : 0L;
        for (IMUserInfo receiver : receivers) {
            try {
                ChannelHandlerContext channelCtx =
                    UserChannelCtxMap.getChannelCtx(receiver.getId(), receiver.getTerminal());
                if (!Objects.isNull(channelCtx)) {
                    if (isSignal && groupSignalCoalescer.offer(channelCtx, recvInfo.getData())) {
                        merged++;
                        continue;
                    }
                    IMSendInfo<Object> sendInfo = new IMSendInfo<>();
                    sendInfo.setCmd(cmd);
                    sendInfo.setData(recvInfo.getData());
                    pushTasks.add(new PushTask(receiver, channelCtx.channel().writeAndFlush(sendInfo)));
                } else {
                    noChannelReceivers.add(receiver);
                    log.error("未找到channel,发送者:{},接收id:{}，内容:{}", sender.getId(), receiver.getId(),
                        recvInfo.getData());
                }
            } catch (Exception e) {
                errorReceivers.add(receiver);
                log.error("发送消息异常,发送者:{},接收id:{}，内容:{}", sender.getId(), receiver.getId(),
                    recvInfo.getData(), e);
            }
        }
        if (probe) {
            PerfCounters.procLoopNanos.add(System.nanoTime() - t0);
            PerfCounters.procMessages.increment();
            PerfCounters.procFrames.add(pushTasks.size());
            PerfCounters.procMergedSignals.add(merged);
            PerfCounters.procNoChannel.add(noChannelReceivers.size());
        }
        sendResult(recvInfo, noChannelReceivers, IMSendCode.NOT_FIND_CHANNEL);
        sendResult(recvInfo, errorReceivers, IMSendCode.UNKONW_ERROR);
        listenPushResults(recvInfo, sender, pushTasks);
    }

    private void listenPushResults(IMRecvInfo recvInfo, IMUserInfo sender, List<PushTask> pushTasks) {
        if (!Boolean.TRUE.equals(recvInfo.getSendResult()) || CollectionUtil.isEmpty(pushTasks)) {
            return;
        }
        List<IMUserInfo> successReceivers = Collections.synchronizedList(new ArrayList<>(pushTasks.size()));
        List<IMUserInfo> asyncErrorReceivers = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger pendingPush = new AtomicInteger(pushTasks.size());
        AtomicBoolean loopFinished = new AtomicBoolean(false);
        Runnable tryComplete = () -> {
            if (loopFinished.get() && pendingPush.get() == 0) {
                sendResult(recvInfo, successReceivers, IMSendCode.SUCCESS);
                sendResult(recvInfo, asyncErrorReceivers, IMSendCode.UNKONW_ERROR);
            }
        };
        for (PushTask task : pushTasks) {
            IMUserInfo receiver = task.getReceiver();
            task.getFuture().addListener(f -> {
                if (f.isSuccess()) {
                    successReceivers.add(receiver);
                } else {
                    asyncErrorReceivers.add(receiver);
                    log.error("消息推送失败,发送者:{},接收id:{}，内容:{}", sender.getId(), receiver.getId(),
                        recvInfo.getData(), f.cause());
                }
                pendingPush.decrementAndGet();
                tryComplete.run();
            });
        }
        loopFinished.set(true);
        tryComplete.run();
    }

    private void sendResult(IMRecvInfo recvInfo, List<IMUserInfo> receivers, IMSendCode sendCode) {
        if (Boolean.TRUE.equals(recvInfo.getSendResult()) && CollectionUtil.isNotEmpty(receivers)) {
            EXECUTOR.execute(() -> {
                IMBatchSendResult<Object> result = new IMBatchSendResult<>();
                result.setSender(recvInfo.getSender());
                result.setReceivers(receivers);
                result.setCode(sendCode.code());
                result.setData(recvInfo.getData());
                String key = StrUtil.join(":", IMRedisKey.IM_RESULT_GROUP_QUEUE, recvInfo.getServiceName());
                redisMQTemplate.opsForList().rightPush(key, result);
            });
        }
    }
}
