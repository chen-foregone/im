package com.bx.imsimulator.ws;

import com.bx.imsimulator.config.props.SimulatorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟单个用户：WebSocket 连接 im-server，发送登录帧并维持与前端一致的心跳（cmd 1）。
 */
public class SimulatedUserSession {

    private static final Logger log = LoggerFactory.getLogger(SimulatedUserSession.class);
    /** 单条 WS 文本消息拼接上限，防止异常堆积占满内存 */
    private static final int MAX_WS_TEXT_MESSAGE_CHARS = 2 * 1024 * 1024;

    private final SimulatorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Long userId;
    private final String userName;
    private final String accessToken;
    private final String devId;

    private final CompletableFuture<Optional<String>> wsLoginFuture = new CompletableFuture<>();
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final AtomicLong wsPrivateMessageReceived = new AtomicLong(0);
    private final AtomicLong wsGroupMessageReceived = new AtomicLong(0);

    /**
     * 每个群收到过的信号里最大的 maxMessageId（即「覆盖水位」）。
     *
     * <p>为什么不能只数帧：读扩散群的信号会<b>合流</b>（同一窗口内多条只发最大的那条），
     * 帧数天然远小于「消息数 x 在线人数」，按帧核对必然报出巨幅偏差。
     * 真正的正确性判据是<b>覆盖</b>：客户端最终拿到的游标是否已经追上服务端写到的位置。
     * 因为客户端就是按 maxMessageId 去拉时间线的，只要水位追平，中间合流掉多少条都不缺消息。
     */
    private final ConcurrentHashMap<Long, Long> groupSignalMaxId = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatTask;

    public SimulatedUserSession(SimulatorProperties properties, ObjectMapper objectMapper, HttpClient httpClient,
        Long userId, String userName, String accessToken, String devId) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.userId = userId;
        this.userName = userName;
        this.accessToken = accessToken;
        this.devId = devId;
    }

    public Long getUserId() {
        return userId;
    }

    /** 第一步 HTTP 登录得到的 accessToken，供后续模拟私聊等复用 */
    public String getAccessToken() {
        return accessToken;
    }

    /** WS 上收到的私聊推送条数（与 im-server 私聊帧一致，{@code cmd==3}） */
    public long getWsPrivateMessageReceivedCount() {
        return wsPrivateMessageReceived.get();
    }

    /** 私聊模拟新一轮开始前清零，便于按任务统计增量 */
    public void resetWsPrivateMessageReceived() {
        wsPrivateMessageReceived.set(0);
    }

    /** WS 上收到的群聊推送条数（与 im-server 群聊帧一致，{@code cmd==4}） */
    public long getWsGroupMessageReceivedCount() {
        return wsGroupMessageReceived.get();
    }

    /** 群聊模拟新一轮开始前清零 */
    public void resetWsGroupMessageReceived() {
        wsGroupMessageReceived.set(0);
    }

    /** 群聊模拟新一轮开始前清零覆盖水位 */
    public void resetGroupSignalCoverage() {
        groupSignalMaxId.clear();
    }

    /** 本会话在群里收到过的最大信号 id；0 表示一条信号都没收到 */
    public long getGroupSignalMaxId(Long groupId) {
        Long v = groupSignalMaxId.get(groupId);
        return v == null ? 0L : v;
    }

    /** 本会话的覆盖水位快照（groupId -> 最大信号 id），供注册表汇总 */
    public Map<Long, Long> getGroupSignalCoverage() {
        return new HashMap<>(groupSignalMaxId);
    }

    /**
     * 建立 WebSocket、发送登录、等待服务端 cmd=0 确认后启动心跳。
     *
     * @return empty 表示已进入在线（心跳已调度）；否则为错误说明
     */
    public Optional<String> connectBlocking() {
        URI wsUri = URI.create(properties.getWsUrl());
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sim-ws-" + userId);
            t.setDaemon(true);
            return t;
        };
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(tf);
        Listener listener = new Listener();
        try {
            CompletableFuture<WebSocket> cf = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getWebsocketHandshakeTimeoutSeconds()))
                .buildAsync(wsUri, listener);
            cf.whenComplete((w, ex) -> {
                if (ex != null) {
                    if (!wsLoginFuture.isDone()) {
                        wsLoginFuture.complete(Optional.of("connect: " + ex.getMessage()));
                    }
                    releaseResources();
                }
            });
            return wsLoginFuture.get(properties.getWebsocketHandshakeTimeoutSeconds() + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (!wsLoginFuture.isDone()) {
                wsLoginFuture.complete(Optional.of("websocket: " + e.getMessage()));
            }
            releaseResources();
            return wsLoginFuture.getNow(Optional.of("websocket: " + e.getMessage()));
        }
    }

    private void failPending(String msg) {
        if (!wsLoginFuture.isDone()) {
            wsLoginFuture.complete(Optional.of(msg));
        }
        releaseResources();
    }

    private void completeOk() {
        wsLoginFuture.complete(Optional.empty());
    }

    /**
     * 关闭连接与心跳；已进入在线状态的会话也可调用以模拟下线。
     */
    public void close() {
        releaseResources();
    }

    private void releaseResources() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> ht = heartbeatTask;
        if (ht != null) {
            ht.cancel(false);
        }
        ScheduledExecutorService ex = heartbeatExecutor;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            ws.abort();
        }
    }

    private String buildWsLoginJson() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accessToken", accessToken);
            data.put("devId", devId);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("cmd", 0);
            root.put("data", data);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendHeartbeat() {
        WebSocket ws = webSocket;
        if (ws == null || released.get()) {
            return;
        }
        try {
            ws.sendText("{\"cmd\":1,\"data\":{}}", true);
        } catch (Exception e) {
            log.debug("heartbeat send failed user={}", userName, e);
        }
    }

    private void scheduleNextHeartbeat(long delayMs) {
        ScheduledExecutorService ex = heartbeatExecutor;
        if (ex == null || ex.isShutdown() || released.get()) {
            return;
        }
        ScheduledFuture<?> prev = heartbeatTask;
        if (prev != null) {
            prev.cancel(false);
        }
        heartbeatTask = ex.schedule(this::sendHeartbeat, delayMs, TimeUnit.MILLISECONDS);
    }

    private void onServerLoginAck() {
        sendHeartbeat();
        scheduleNextHeartbeat(properties.getHeartbeatIntervalMillis());
    }

    private void onServerHeartbeatAck() {
        scheduleNextHeartbeat(properties.getHeartbeatIntervalMillis());
    }

    private class Listener implements WebSocket.Listener {

        /** 同一条逻辑消息可能分多次 onText（last=false），须拼齐后再 JSON 解析 */
        private final StringBuilder textMessageBuffer = new StringBuilder(512);

        @Override
        public void onOpen(WebSocket ws) {
            if (released.get()) {
                return;
            }
            webSocket = ws;
            try {
                ws.sendText(buildWsLoginJson(), true).join();
            } catch (Exception e) {
                failPending("send login: " + e.getMessage());
                return;
            }
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            final String payload;
            synchronized (textMessageBuffer) {
                int addLen = data == null ? 0 : data.length();
                if (textMessageBuffer.length() + addLen > MAX_WS_TEXT_MESSAGE_CHARS) {
                    log.warn("ws text buffer overflow, reset userId={}", userId);
                    textMessageBuffer.setLength(0);
                }
                if (addLen > 0) {
                    textMessageBuffer.append(data);
                }
                if (!last) {
                    ws.request(1);
                    return WebSocket.Listener.super.onText(ws, data, last);
                }
                if (textMessageBuffer.isEmpty()) {
                    ws.request(1);
                    return WebSocket.Listener.super.onText(ws, data, last);
                }
                if (textMessageBuffer.length() > MAX_WS_TEXT_MESSAGE_CHARS) {
                    log.warn("ws text single message too large, drop userId={}", userId);
                    textMessageBuffer.setLength(0);
                    ws.request(1);
                    return WebSocket.Listener.super.onText(ws, data, last);
                }
                payload = textMessageBuffer.toString();
                textMessageBuffer.setLength(0);
            }
            try {
                JsonNode n = objectMapper.readTree(payload);
                int cmd = n.path("cmd").asInt(-1);
                if (cmd == 0) {
                    if (!wsLoginFuture.isDone()) {
                        onServerLoginAck();
                        completeOk();
                    }
                } else if (cmd == 1) {
                    onServerHeartbeatAck();
                } else if (cmd == 3) {
                    JsonNode message = n.path("data");
                    int type = message.path("type").asInt(-1);
                    if (type == 0) {
                        log.info("接受到新消息:{}", message.path("content").asText());
                        wsPrivateMessageReceived.incrementAndGet();
                    }
                } else if (cmd == 4) {
                    JsonNode message = n.path("data");
                    int type = message.path("type").asInt(-1);
                    if (type == 0) {
                        log.debug("接收到群消息 userId={}", userId);
                        wsGroupMessageReceived.incrementAndGet();
                    }
                } else if (cmd == 6) {
                    // 读扩散群的「有新消息」信号：不带消息内容，
                    // 与 cmd=4 一样算一次群消息投递（见 docs/群聊瓶颈解决方案.md 3.8）。
                    wsGroupMessageReceived.incrementAndGet();
                    // 同时记录覆盖水位：该群推到过的最大消息 id。
                    // 合流后帧数不再等于投递数，覆盖水位才是正确性判据（见 3.18）。
                    JsonNode d = n.path("data");
                    long gid = d.path("groupId").asLong(0L);
                    long mid = d.path("maxMessageId").asLong(0L);
                    if (gid > 0L) {
                        groupSignalMaxId.merge(gid, mid, Math::max);
                    }
                } else if (cmd == 2) {
                    if (!wsLoginFuture.isDone()) {
                        wsLoginFuture.complete(Optional.of("force_logout"));
                    }
                    releaseResources();
                }
            } catch (Exception e) {
                synchronized (textMessageBuffer) {
                    textMessageBuffer.setLength(0);
                }
                if (!wsLoginFuture.isDone()) {
                    failPending("ws message: " + e.getMessage());
                } else {
                    log.debug("ws json parse error userId={}", userId, e);
                }
            }
            ws.request(1);
            return WebSocket.Listener.super.onText(ws, data, last);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!wsLoginFuture.isDone()) {
                failPending("ws error: " + error.getMessage());
            }
            WebSocket.Listener.super.onError(ws, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!wsLoginFuture.isDone()) {
                wsLoginFuture.complete(Optional.of("ws closed: " + statusCode + " " + reason));
            }
            releaseResources();
            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
        }
    }
}
