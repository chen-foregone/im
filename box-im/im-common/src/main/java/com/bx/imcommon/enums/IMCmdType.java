package com.bx.imcommon.enums;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum IMCmdType {

    /**
     * 登录
     */
    LOGIN(0, "登录"),
    /**
     * 心跳
     */
    HEART_BEAT(1, "心跳"),
    /**
     * 强制下线
     */
    FORCE_LOGOUT(2, "强制下线"),
    /**
     * 私聊消息
     */
    PRIVATE_MESSAGE(3, "私聊消息"),
    /**
     * 群发消息
     */
    GROUP_MESSAGE(4, "群发消息"),
    /**
     * 系统消息
     */
    SYSTEM_MESSAGE(5,"系统消息"),
    /**
     * 群消息信号：读扩散群里不推消息内容，只推「有新消息」通知，客户端按游标拉取。
     * 见 docs/群聊瓶颈解决方案.md 3.6。
     */
    GROUP_MESSAGE_SIGNAL(6, "群消息信号");


    private final Integer code;

    private final String desc;


    public static IMCmdType fromCode(Integer code) {
        for (IMCmdType typeEnum : values()) {
            if (typeEnum.code.equals(code)) {
                return typeEnum;
            }
        }
        return null;
    }


    public Integer code() {
        return this.code;
    }


}

