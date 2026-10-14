package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 群
 *
 * @author blue
 * @since 2022-10-31
 */
@Data
@TableName("im_group")
public class Group {

    /**
     * id
     */
    @TableId
    private Long id;

    /**
     * 群名字
     */
    private String name;

    /**
     * 群主id
     */
    private Long ownerId;

    /**
     * 群头像
     */
    private String headImage;

    /**
     * 群头像缩略图
     */
    private String headImageThumb;

    /**
     * 群公告
     */
    private String notice;

    /**
     * 是否被封禁
     */
    private Boolean isBanned;

    /**
     * 被封禁原因
     */
    private String reason;

    /**
     * 创建时间
     */
    private Date createdTime;

    /**
     * 是否已删除
     */
    private Boolean dissolve;

    /**
     * 扇出模式 0=写扩散(小群) 1=读扩散(大群)。
     * <p>由群成员数跨过阈值时自动翻转，语义见 {@link com.bx.implatform.enums.GroupFanoutMode}。</p>
     * <p>注意：该列由 {@code db/更新脚本/v_5.0.sql} 引入，未执行迁移时整个群模块会因 Unknown column 报错。</p>
     */
    private Integer fanoutMode;

    /**
     * 群成员数（不含已退群）。
     * <p>冗余字段，用于免去每次判断模式时对 im_group_member 做 COUNT(*)。
     * 由 {@code GroupMemberServiceImpl.refreshGroupMemberCount} 在任何成员变动后重算。</p>
     */
    private Integer memberCount;
}
