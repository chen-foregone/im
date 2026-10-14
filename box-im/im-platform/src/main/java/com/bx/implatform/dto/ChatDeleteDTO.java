package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @author Blue
 * @version 1.0
 */
@Data
@Schema(description = "删除会话DTO")
public class ChatDeleteDTO {

    @NotNull(message = "会话id不可为空")
    @Schema(description = "会话id,即好友id/群id")
    private Long chatId;

}
