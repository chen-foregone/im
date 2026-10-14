package com.bx.implatform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户在线状态
 * @author Blue
 * @version 1.0
 */
@Data
@Schema(description = "用户在线状态VO")
public class UserOnlineVO {
    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "终端类型")
    private Integer terminal;

    @Schema(description = "是否在线")
    private Boolean online;
}
