package com.bx.implatform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * @author Blue
 * @version 1.0
 */
@Data
@Schema(description = "查询私聊历史消息DTO")
public class PrivateMessageHistoryDTO {

    @NotNull(message = "好友id不可为空")
    @Schema(description = "好友id")
    Long friendId;

    @Size(max = 100, message = "一次最多拉取100条消息")
    @Schema(description = "条件1:本地消息列表")
    List<String> localIds;

    @Size(max = 100, message = "一次最多拉取100条消息")
    @Schema(description = "条件2:消息序号列表")
    List<Long> seqNos;

    @Schema(description = "条件3:最小消息序号")
    Long minSeqNo;

    @Schema(description = "条件3:最大消息序号,0或负值表示不限制")
    Long maxSeqNo;

}
