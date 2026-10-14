package com.bx.implatform.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 会话类型枚举
 *
 * @author Blue
 * @date 2025-12-31
 */
@Getter
@AllArgsConstructor
public enum ChatType {

    /**
     * 私聊
     */
    PRIVATE(1, "私聊"),

    /**
     * 群聊
     */
    GROUP(2, "群聊");

    private final Integer code;

    private final String desc;

    /**
     * 根据code获取枚举
     *
     * @param code 类型码
     * @return 枚举值
     */
    public static ChatType fromCode(Integer code) {
        for (ChatType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}

