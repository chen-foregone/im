package com.bx.imsimulator.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchLoginResultVO {

    private int requested;
    /** HTTP 登录返回 200 且解析到 accessToken 的数量 */
    private int httpLoginSucceeded;
    /** HTTP + WebSocket 登录确认并已进入心跳的数量 */
    private int succeeded;
    private int failed;
    private long elapsedMs;
    private String message;
    private List<Map<String, String>> failureSamples;
}
