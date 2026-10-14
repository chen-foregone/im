package com.bx.imsimulator.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMsgSimulateJobViewVO {

    private String status;
    private String groupScale;
    private int senderCount;
    private int receiverGroupCount;
    private int sendRounds;
    /** 0 表示不限制 */
    private int maxSendQps;
    private int totalRequested;
    private int totalWs;
    private Long wsGroupMessagesReceived;
    /** 覆盖判据：最差用户在群里收到的最大信号 id 是否已追平服务端写到的位置。100 = 全部追平 */
    private int coveragePct;
    private int groupsCovered;
    private int groupsTotal;
    /** 诊断用：groupId -> "最差水位/服务端写到的位置" */
    private Map<Long, String> coverageDetail;
    private int succeeded;
    private int failed;
    private long sendMs;
    private long recvMs;
    private String message;
    private List<Map<String, Object>> failureSamples;

    public static GroupMsgSimulateJobViewVO idle() {
        return new GroupMsgSimulateJobViewVO("IDLE", "", 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0, Collections.emptyMap(),
            0, 0, 0L, 0L, "当前无任务", Collections.emptyList());
    }
}
