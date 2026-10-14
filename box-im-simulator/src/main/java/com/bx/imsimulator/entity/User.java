package com.bx.imsimulator.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 仅用于模拟器读取 im_user 登录名（与 im-platform 表结构一致的最小字段集）
 */
@Data
@TableName("im_user")
public class User {

    @TableId
    private Long id;

    private String userName;
}
