package com.bx.imsimulator.config.props;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "simulator")
public class SimulatorProperties {

    /**
     * im-platform 根地址，例如 http://127.0.0.1:8888
     */
    private String apiUrl = "http://127.0.0.1:8888";

    /**
     * 与库中用户密码一致的明文
     */
    private String loginPassword = "123456";

    /**
     * im-server WebSocket 地址，例如 ws://127.0.0.1:8878/im
     */
    private String wsUrl = "ws://127.0.0.1:8878/im";

    /**
     * 收到心跳应答后，再次发送心跳的间隔（毫秒），与前端 wssocket 约 20s 一致
     */
    private long heartbeatIntervalMillis = 20_000L;

    /**
     * WebSocket 握手超时（秒）
     */
    private int websocketHandshakeTimeoutSeconds = 20;





}
