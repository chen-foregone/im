package com.bx.imsimulator.enums;

import java.util.Optional;

/**
 * 群规模：与库中 im_group.name 前缀对应
 */
public enum GroupMsgSimulateScale {
    TEN("十人群"),
    HUNDRED("百人群"),
    THOUSAND("千人群"),
    TEN_THOUSAND("万人群"),
    // 压测目标规模：500 / 2000 / 5000
    // 对应数据由 db/8.、9.、10. 三个脚本生成
    FIVE_HUNDRED("五百人群"),
    TWO_THOUSAND("两千人群"),
    FIVE_THOUSAND("五千人群");

    private final String namePrefix;

    GroupMsgSimulateScale(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    public String getNamePrefix() {
        return namePrefix;
    }

    public static Optional<GroupMsgSimulateScale> fromApiValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GroupMsgSimulateScale.valueOf(raw.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
