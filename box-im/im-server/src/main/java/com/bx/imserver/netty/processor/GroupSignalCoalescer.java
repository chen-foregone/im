package com.bx.imserver.netty.processor;

import com.bx.imcommon.enums.IMCmdType;
import com.bx.imcommon.model.IMSendInfo;
import com.bx.imcommon.perf.PerfCounters;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 群消息信号合流器：把同一通道、同一群在一个时间窗内的多条信号合并成一条。
 *
 * <p><b>为什么可以合并</b>：读扩散群推的不是消息内容，而是
 * {@code IMGroupSignal{groupId, maxMessageId, senderId}} —— 「该群有新消息，最大到 maxMessageId」。
 * 客户端收到后是按 maxMessageId 游标去拉时间线。因此同一窗口内多条信号里，
 * **只有 maxMessageId 最大的那条有意义**，前面的都会被同一次拉取覆盖。
 * 合并后语义等价，而且客户端少拉几次。
 *
 * <p><b>为什么这是投递侧最大的一笔收益</b>：原实现对「每个接收者」各做一次
 * {@code writeAndFlush}，即每条群消息在万人群、1000 在线时产生 999 个帧。
 * 实测单帧成本 16.0~16.8µs（处理循环内），1000 在线时投递线程占用已达 78~85%、
 * im-server CPU 峰值 6.22 核（§3.15）。合流后同一窗口内无论来多少条消息，
 * 每个（通道,群）只出 1 帧。
 *
 * <p><b>为什么不需要改客户端协议</b>：合并后的帧与原帧结构完全一致（仍是一条信号），
 * 只是数量变少。这比「把多条消息打成一个数组帧」安全得多——后者要求客户端升级。
 *
 * <p><b>代价</b>：最坏多出 {@code signal-merge-window-ms} 的推送延迟（默认 50ms）。
 * 读扩散群的消息本来就是「先拉后看」，这点延迟换取投递侧数量级的下降是划算的；
 * 但这是产品决策，可用 {@code im.delivery.signal-merge-enabled=false} 一键回滚。
 *
 * <p><b>并发正确性</b>：缓冲表用 {@code (channelId, groupId)} 做键。
 * 刷出时用 {@code remove(key, value)} 两参数版本——只有当值未被并发替换时才移除，
 * 否则留给下一轮。这样不会丢掉「刚写进来但还没刷出」的信号。
 */
@Slf4j
@Component
public class GroupSignalCoalescer {

    /** 合并开关。false 时 offer 一律返回 false，调用方退回「立即逐帧下发」 */
    @Value("${im.delivery.signal-merge-enabled:true}")
    private boolean enabled;

    /** 合并窗口。越大合并率越高、延迟越大 */
    @Value("${im.delivery.signal-merge-window-ms:50}")
    private long windowMs;

    private final ConcurrentHashMap<SignalKey, Pending> pending = new ConcurrentHashMap<>();

    private ScheduledExecutorService flusher;

    private final LongAdder framesWritten = new LongAdder();
    private final LongAdder signalsAbsorbed = new LongAdder();
    private final LongAdder flushRounds = new LongAdder();

    /** 缓冲键：通道 + 群。用 ChannelId 而不是 ChannelHandlerContext，前者有正确的 equals */
    private record SignalKey(ChannelId channelId, Long groupId) {
    }

    private static final class Pending {
        final ChannelHandlerContext ctx;
        final Object data;
        final long maxMessageId;

        Pending(ChannelHandlerContext ctx, Object data, long maxMessageId) {
            this.ctx = ctx;
            this.data = data;
            this.maxMessageId = maxMessageId;
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[群信号合流] 已关闭");
            return;
        }
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "im-group-signal-flusher");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::flush, windowMs, windowMs, TimeUnit.MILLISECONDS);
        log.info("[群信号合流] 已开启, 窗口={}ms", windowMs);
    }

    @PreDestroy
    public void stop() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
    }

    /**
     * 把一条信号放入合并缓冲。
     *
     * @return true 表示已吸收，调用方<b>不要</b>再立即下发；
     *         false 表示未启用或无法解析 groupId，调用方应走原路径
     */
    public boolean offer(ChannelHandlerContext ctx, Object data) {
        if (!enabled || ctx == null || data == null) {
            return false;
        }
        Long groupId = extractGroupId(data);
        if (groupId == null) {
            return false;
        }
        long maxId = extractMaxMessageId(data);
        SignalKey key = new SignalKey(ctx.channel().id(), groupId);
        // 保留 maxMessageId 更大的那条
        pending.merge(key, new Pending(ctx, data, maxId),
            (a, b) -> b.maxMessageId >= a.maxMessageId ? b : a);
        signalsAbsorbed.increment();
        return true;
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        flushRounds.increment();
        final boolean probe = PerfCounters.enabled;
        long t0 = probe ? System.nanoTime() : 0L;
        long frames = 0L;
        for (Map.Entry<SignalKey, Pending> e : pending.entrySet()) {
            // 两参数 remove：值被并发替换过就留给下一轮，绝不丢失
            if (!pending.remove(e.getKey(), e.getValue())) {
                continue;
            }
            Pending p = e.getValue();
            try {
                if (!p.ctx.channel().isActive()) {
                    continue;
                }
                IMSendInfo<Object> sendInfo = new IMSendInfo<>();
                sendInfo.setCmd(IMCmdType.GROUP_MESSAGE_SIGNAL.code());
                sendInfo.setData(p.data);
                p.ctx.channel().writeAndFlush(sendInfo);
                frames++;
            } catch (Exception ex) {
                log.warn("[群信号合流] 刷出失败,groupId:{}", e.getKey().groupId(), ex);
            }
        }
        framesWritten.add(frames);
        if (probe && frames > 0) {
            PerfCounters.procFrames.add(frames);
            PerfCounters.procLoopNanos.add(System.nanoTime() - t0);
        }
    }

    /** 兼容两种落地形态：真身 IMGroupSignal，或经序列化往返后的 Map */
    private static Long extractGroupId(Object data) {
        Object v = extract(data, "groupId");
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long extractMaxMessageId(Object data) {
        Object v = extract(data, "maxMessageId");
        if (v == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static Object extract(Object data, String field) {
        if (data instanceof Map<?, ?> m) {
            return m.get(field);
        }
        // 真身：反射取值，避免为了兼容引入编译期依赖
        try {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            return data.getClass().getMethod(getter).invoke(data);
        } catch (Exception e) {
            return null;
        }
    }

    public long getSignalsAbsorbed() {
        return signalsAbsorbed.sum();
    }

    public long getFramesWritten() {
        return framesWritten.sum();
    }
}
