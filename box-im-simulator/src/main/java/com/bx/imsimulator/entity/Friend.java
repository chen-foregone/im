package com.bx.imsimulator.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 与 im-platform 的 im_friend 一致的最小字段，用于按 user_id 取前若干好友。
 */
@Data
@TableName("im_friend")
public class Friend {

    @TableId
    private Long id;

    private Long userId;

    private Long friendId;

    private Boolean deleted;
}
