package com.bx.imsimulator.controller;

import com.bx.imsimulator.config.props.SimulatorProperties;
import com.bx.imsimulator.ws.SimulatedSessionRegistry;
import com.bx.imsimulator.ws.SimulatedUserSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 正确性验证用的接口（A2–A4）。
 *
 * <p>压测脚本只报数不断言，所以这些接口的目标是<b>把断言需要的数据暴露出来</b>：
 * 每个会话在某个群的覆盖水位、会话的 accessToken（让外部脚本能以真实用户身份调业务接口）、
 * 以及「以某个用户身份发一条群消息」这种受控动作。
 *
 * <p>为什么要暴露 token：A4 要验证「退群后不再收到信号」，
 * 退群必须走真实的 {@code DELETE /group/quit/{groupId}}，那就得带真实用户的 token。
 * 与其把退群/入群逻辑复制到这里，不如把 token 交出去，由测试脚本直接调业务接口 ——
 * 这样测试覆盖的是<b>真实业务路径</b>，而不是我们重新实现的一条捷径。
 */
@Slf4j
@RestController
@RequestMapping("/simulator/verify")
@RequiredArgsConstructor
public class SimulatorVerifyController {

    private final SimulatedSessionRegistry sessionRegistry;
    private final SimulatorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 取某个在线会话的 accessToken，供测试脚本以该用户身份调用业务接口 */
    @GetMapping("/token")
    public Map<String, Object> token(@RequestParam Long userId) {
        SimulatedUserSession s = sessionRegistry.getIfPresent(userId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("userId", userId);
        r.put("online", s != null);
        r.put("accessToken", s == null ? "" : s.getAccessToken());
        return r;
    }

    /**
     * 某个群里所有在线会话的覆盖水位（userId -> 最大信号 id）。
     * 只返回 &gt; 0 的，便于测试脚本判断「这个人到底有没有在收这个群的信号」。
     */
    @GetMapping("/watermarks")
    public Map<Long, Long> watermarks(@RequestParam Long groupId) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Long userId : sessionRegistry.userIds()) {
            SimulatedUserSession s = sessionRegistry.getIfPresent(userId);
            if (s == null) {
                continue;
            }
            long wm = s.getGroupSignalMaxId(groupId);
            if (wm > 0L) {
                out.put(userId, wm);
            }
        }
        return out;
    }

    /** 清零所有会话的覆盖水位。测试分步测量时用来划一条新的基准线 */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        sessionRegistry.resetAllGroupSignalCoverage();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("sessions", sessionRegistry.size());
        return r;
    }

    /**
     * 以某个在线用户的身份发一条群消息，返回服务端分配的消息 id。
     *
     * <p>压测接口是「一批发送者 × 若干轮」的批量模式，A4 需要的是
     * 「发一条，看水位，改群成员，再发一条」这种受控节奏，所以单独给一个。
     */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) throws Exception {
        Long senderUserId = Long.valueOf(String.valueOf(body.get("senderUserId")));
        Long groupId = Long.valueOf(String.valueOf(body.get("groupId")));
        String content = String.valueOf(body.getOrDefault("content", "verify"));

        String token = sessionRegistry.findAccessToken(senderUserId);
        Map<String, Object> r = new LinkedHashMap<>();
        if (token == null || token.isEmpty()) {
            r.put("ok", false);
            r.put("error", "发送者不在线: " + senderUserId);
            return r;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("groupId", groupId);
        payload.put("content", content);
        payload.put("type", 0);
        payload.put("localId", String.valueOf(System.nanoTime()));

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(properties.getApiUrl() + "/message/group/send"))
            .header("Content-Type", "application/json")
            .header("accessToken", token)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());
        r.put("ok", root.path("code").asInt() == 200);
        r.put("messageId", root.path("data").path("id").asLong(0L));
        r.put("code", root.path("code").asInt());
        r.put("message", root.path("message").asText(""));
        return r;
    }
}
