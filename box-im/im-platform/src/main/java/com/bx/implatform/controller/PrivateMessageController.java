package com.bx.implatform.controller;

import com.bx.implatform.dto.ChatDeleteDTO;
import com.bx.implatform.dto.MessageDeleteDTO;
import com.bx.implatform.dto.PrivateMessageDTO;
import com.bx.implatform.dto.PrivateMessageHistoryDTO;
import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.PrivateMessageService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.vo.PrivateMessageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "私聊消息")
@RestController
@RequestMapping("/message/private")
@RequiredArgsConstructor
public class PrivateMessageController {

    private final PrivateMessageService privateMessageService;

    @PostMapping("/send")
    @Operation(summary = "发送消息", description = "发送私聊消息")
    public Result<PrivateMessageVO> sendMessage(@Valid @RequestBody PrivateMessageDTO dto) {
        return ResultUtils.success(privateMessageService.sendMessage(dto));
    }

    @DeleteMapping("/recall/{id}")
    @Operation(summary = "撤回消息", description = "撤回私聊消息")
    public Result<PrivateMessageVO> recallMessage(@NotNull(message = "消息id不能为空") @PathVariable Long id) {
        return ResultUtils.success( privateMessageService.recallMessage(id));
    }

    @GetMapping(value = "/loadOfflineMessage")
    @Operation(summary = "拉取离线消息", description = "拉取离线消息")
    public Result<List<PrivateMessageVO>> loadOfflineMessage(@RequestParam Long minId) {
        return ResultUtils.success(privateMessageService.loadOfflineMessage(minId));
    }


    @PutMapping("/readed")
    @Operation(summary = "消息已读", description = "将会话中接收的消息状态置为已读")
    public Result readedMessage(@RequestParam Long friendId,@RequestParam(required = false) Long messageId) {
        privateMessageService.readedMessage(friendId,messageId);
        return ResultUtils.success();
    }


    @GetMapping("/maxReadedId")
    @Operation(summary = "获取最大已读消息的id", description = "获取某个会话中已读消息的最大id")
    public Result<Long> getMaxReadedId(@RequestParam Long friendId) {
        Long userId = SessionContext.getSession().getUserId();
        return ResultUtils.success(privateMessageService.getMaxReadedId(userId, friendId));
    }


    @DeleteMapping("/deleteMessage")
    @Operation(summary = "删除消息", description = "根据消息id列表删除消息")
    public Result deleteMessage(@Valid @RequestBody MessageDeleteDTO dto) {
        privateMessageService.deleteMessage(dto);
        return ResultUtils.success();
    }

    @DeleteMapping("/deleteChat")
    @Operation(summary = "删除会话", description = "删除会话以及会话中的所有消息")
    public Result deleteChat(@Valid @RequestBody ChatDeleteDTO dto) {
        privateMessageService.deleteChat(dto);
        return ResultUtils.success();
    }

    @PostMapping("/history")
    @Operation(summary = "查询历史消息", description = "查询历史消息")
    public Result loadHistoryMessage(@Valid @RequestBody PrivateMessageHistoryDTO dto) {
        return ResultUtils.success(privateMessageService.loadHistoryMessage(dto));
    }

}

