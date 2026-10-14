package com.bx.imsimulator.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 私聊模拟状态轮询视图（单任务，无 jobId）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrivateMsgSimulateJobViewVO {

    /** IDLE / RUNNING / SETTLING / COMPLETED / FAILED / CANCELLED */
    private String status;

    private int senderCount;

    private int receiverCount;

    private int sendRounds;

    /** 0 表示不限制 */
    private int maxSendQps;

    private int totalRequested;

    /** 发送阶段为 null；settling 起为当前 WS cmd=3 增量 */
    private Long wsPrivateMessagesReceived;

    private int succeeded;

    private int failed;

    private long sendMs;

    private long recvMs;

    private String message;

    private List<Map<String, Object>> failureSamples;

    public static PrivateMsgSimulateJobViewVO idle() {
        return new PrivateMsgSimulateJobViewVO("IDLE", 0, 0, 0, 0, 0, 0L, 0, 0, 0, 0, "当前无任务",
            Collections.emptyList());
    }
}
