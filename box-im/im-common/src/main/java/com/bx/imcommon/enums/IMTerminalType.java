package com.bx.imcommon.enums;

import lombok.AllArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@AllArgsConstructor
public enum IMTerminalType {

    /**
     * web
     */
    WEB(0, "web"),
    /**
     * app
     */
    APP(1, "app"),
    /**
     * pc
     */
    PC(2, "pc"),
    /**
     * 未知
     */
    UNKNOW(-1, "未知");

    private final Integer code;

    private final String desc;


    public static IMTerminalType fromCode(Integer code) {
        for (IMTerminalType typeEnum : values()) {
            if (typeEnum.code.equals(code)) {
                return typeEnum;
            }
        }
        return null;
    }

    public static List<Integer> codes() {
        return Arrays.stream(values()).map(IMTerminalType::code).collect(Collectors.toList());
    }

    /**
     * 消息接收终端：在 codes() 基础上排除 UNKNOW。
     *
     * <p>UNKNOW(-1) 不会被任何真实客户端使用：terminal 由客户端登录报文给出
     * （LoginProcessor 写入），服务端对 terminal 为 null 的连接直接拒绝，
     * 线上 Redis 中也查不到 {@code im:user:server_id:*:-1} 的 key。</p>
     *
     * <p>历史实现把它放进了各消息模型的 recvTerminals 默认值，导致每条消息的
     * MGET 多出 25% 的无效 key（见 docs/群聊性能测试报告-基线.md）。</p>
     */
    public static List<Integer> recvCodes() {
        return Arrays.stream(values())
                .filter(type -> type != UNKNOW)
                .map(IMTerminalType::code)
                .collect(Collectors.toList());
    }

    public Integer code() {
        return this.code;
    }

}
