package com.bx.imsimulator.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 与 im-platform 表 im_group_member 一致的最小字段集
 */
@Data
@TableName("im_group_member")
public class ImGroupMember {

    @TableId
    private Long id;

    private Long groupId;

    private Long userId;

    private Boolean quit;
}
