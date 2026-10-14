package com.bx.imcommon.enums;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum IMEventType {

    /**
     * 用户上线
     */
    ONLINE(1,"用户上线"),

    /**
     * 用户下线
     */
    OFFLINE(2,"用户下线");

    private final Integer code;

    private final String desc;


    public Integer code() {
        return this.code;
    }


}

