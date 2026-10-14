package com.bx.imsimulator.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 与 im-platform 表 im_group 一致的最小字段集（模拟器按名称前缀拉群）
 */
@Data
@TableName("im_group")
public class ImGroup {

    @TableId
    private Long id;

    private String name;

    private Boolean dissolve;

    private Boolean isBanned;
}
