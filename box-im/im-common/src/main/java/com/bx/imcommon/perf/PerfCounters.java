package com.bx.imcommon.perf;

import java.util.concurrent.atomic.LongAdder;

/**
 * 投递链路细粒度计数器（Step 0 诊断用）。
 *
 * <p>关闭时所有调用点只做一次 volatile 布尔判断，开销可忽略。
 * 由配置项 <code>im.perf.probe-enabled</code> 控制（默认 false）。</p>
 *
 * <p>目的：把「一次群消息扇出到 100 个在线的 99 帧」拆成
 * <b>出队 / 反序列化 / 处理循环 / 编码</b> 四段，定位真实耗时。</p>
 */
public final class PerfCounters {

    /** 总开关，由配置在启动时设置 */
    public static volatile boolean enabled = false;

    // ---- 出队段（RedisMQPullTask）----
    public static final LongAdder pullCalls = new LongAdder();
    public static final LongAdder pullBatches = new LongAdder();
    public static final LongAdder pullEmptyBatches = new LongAdder();
    public static final LongAdder pullMessages = new LongAdder();
    public static final LongAdder pullBatchNanos = new LongAdder();
    public static final LongAdder deserNanos = new LongAdder();

    // ---- 只统计群队列（im:message:group），避免与私聊/系统/强制下线消费者混算 ----
    public static final LongAdder grpBatches = new LongAdder();
    public static final LongAdder grpEmptyBatches = new LongAdder();
    public static final LongAdder grpMsgs = new LongAdder();
    public static final LongAdder grpBatchNanos = new LongAdder();
    public static final LongAdder grpDeserNanos = new LongAdder();

    // ---- 处理段（GroupMessageProcessor）----
    public static final LongAdder procMessages = new LongAdder();
    public static final LongAdder procFrames = new LongAdder();
    public static final LongAdder procLoopNanos = new LongAdder();
    public static final LongAdder procNoChannel = new LongAdder();

    /**
     * 被合流器吸收的信号条数（即「本来要出的帧数」里被省下的部分）。
     *
     * <p>注意：这里统计的是<b>吸收次数</b>，不是净省下的帧数。
     * 净省 = procMerged - 实际刷出的帧数，后者已计入 procFrames。
     * 追加在 FIELDS 末尾，避免挪动既有列下标。
     */
    public static final LongAdder procMergedSignals = new LongAdder();

    // ---- 扇出段（im-platform: GroupMessageServiceImpl / IMSender）----
    public static final LongAdder fanCalls = new LongAdder();
    public static final LongAdder fanTotalNanos = new LongAdder();
    public static final LongAdder fanDbNanos = new LongAdder();      // getById + selectById
    public static final LongAdder fanMemberNanos = new LongAdder();  // findUserIdsByGroupId + 过滤
    public static final LongAdder fanPresenceNanos = new LongAdder();// N 个 presence key 的 MGET
    public static final LongAdder fanServerNanos = new LongAdder();  // 终端 key MGET + 分组入队
    public static final LongAdder fanRecvIds = new LongAdder();      // 累计接收方个数
    public static final LongAdder fanPresenceKeys = new LongAdder(); // 累计 presence key 数
    public static final LongAdder fanOnlineIds = new LongAdder();    // 累计在线人数

    // ---- 编码段（MessageProtocolEncoder）----
    public static final LongAdder encFrames = new LongAdder();
    public static final LongAdder encNanos = new LongAdder();
    public static final LongAdder encBytes = new LongAdder();

    private PerfCounters() {
    }

    public static final String[] FIELDS = {
        "pullCalls", "pullBatches", "pullEmpty", "pullMsgs", "pullBatchMs", "deserMs",
        "grpBatches", "grpEmpty", "grpMsgs", "grpBatchMs", "grpDeserMs",
        "procMsgs", "procFrames", "procNoCh", "procLoopMs",
        "encFrames", "encMs", "encKB",
        "fanCalls", "fanMs", "fanDbMs", "fanMemberMs", "fanPresenceMs", "fanServerMs",
        "fanRecvIds", "fanPresKeys", "fanOnlineIds",
        "procMerged"
    };

    /** 返回与 {@link #FIELDS} 一一对应的快照（耗时单位为毫秒） */
    public static double[] snapshot() {
        return new double[] {
            pullCalls.sum(), pullBatches.sum(), pullEmptyBatches.sum(), pullMessages.sum(),
            pullBatchNanos.sum() / 1e6, deserNanos.sum() / 1e6,
            grpBatches.sum(), grpEmptyBatches.sum(), grpMsgs.sum(),
            grpBatchNanos.sum() / 1e6, grpDeserNanos.sum() / 1e6,
            procMessages.sum(), procFrames.sum(), procNoChannel.sum(),
            procLoopNanos.sum() / 1e6,
            encFrames.sum(), encNanos.sum() / 1e6, encBytes.sum() / 1024.0,
            fanCalls.sum(), fanTotalNanos.sum() / 1e6, fanDbNanos.sum() / 1e6,
            fanMemberNanos.sum() / 1e6, fanPresenceNanos.sum() / 1e6, fanServerNanos.sum() / 1e6,
            fanRecvIds.sum(), fanPresenceKeys.sum(), fanOnlineIds.sum(),
            procMergedSignals.sum()
        };
    }

    public static String header() {
        return "ts,windowMs," + String.join(",", FIELDS);
    }

    /** 计算 delta 并格式化为一行 CSV */
    public static String diffLine(double[] prev, double[] now, long windowMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()).append(',').append(windowMs);
        for (int i = 0; i < FIELDS.length; i++) {
            sb.append(',').append(Math.round((now[i] - prev[i]) * 1000.0) / 1000.0);
        }
        return sb.toString();
    }
}
