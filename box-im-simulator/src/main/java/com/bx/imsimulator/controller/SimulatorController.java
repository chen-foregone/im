package com.bx.imsimulator.controller;

import com.bx.imsimulator.dto.GroupMsgSimulateDTO;
import com.bx.imsimulator.dto.PrivateMsgSimulateDTO;
import com.bx.imsimulator.service.SimulatorGroupMsgService;
import com.bx.imsimulator.service.SimulatorLoginService;
import com.bx.imsimulator.service.SimulatorPrivateMsgService;
import com.bx.imsimulator.vo.BatchLoginResultVO;
import com.bx.imsimulator.vo.GroupMsgSimulateJobViewVO;
import com.bx.imsimulator.vo.PrivateMsgSimulateJobViewVO;
import com.bx.imsimulator.vo.PrivateMsgSimulateStartVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final SimulatorLoginService simulatorLoginService;
    private final SimulatorPrivateMsgService simulatorPrivateMsgService;
    private final SimulatorGroupMsgService simulatorGroupMsgService;

    /**
     * 从库中按 id 升序取前 N 名用户：HTTP 登录 im-platform，再连 im-server WebSocket 并保持心跳。N 默认见 simulator.fetch-user-limit。
     */
    @PostMapping("/batch-login")
    public BatchLoginResultVO batchLogin(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Integer terminal) {
        return simulatorLoginService.batchLogin(limit, terminal);
    }

    /** 关闭所有模拟连接，不再维持在线 */
    @PostMapping("/stop-sessions")
    public void stopSessions() {
        simulatorLoginService.stopSessions();
    }

    /**
     * 启动私聊模拟：立即返回；清零 WS 私聊计数后后台发 HTTP。
     * 进行中再调会 409。请轮询 {@code GET /simulator/private-msg-simulate/status}（建议 100ms）。
     */
    @PostMapping("/private-msg-simulate")
    public PrivateMsgSimulateStartVO startPrivateMsgSimulate(@RequestBody PrivateMsgSimulateDTO dto) {
        return simulatorPrivateMsgService.startPrivateMsgSimulate(dto);
    }

    /** 当前私聊模拟任务状态（无 jobId；从未启动为 IDLE） */
    @GetMapping("/private-msg-simulate/status")
    public PrivateMsgSimulateJobViewVO getPrivateMsgSimulateStatus() {
        return simulatorPrivateMsgService.getPrivateMsgSimulateStatus();
    }

    /** 手动停止私聊模拟（协作式中断） */
    @PostMapping("/private-msg-simulate/stop")
    public void stopPrivateMsgSimulate() {
        simulatorPrivateMsgService.stopPrivateMsgSimulate();
    }

    @PostMapping("/group-msg-simulate")
    public PrivateMsgSimulateStartVO startGroupMsgSimulate(@RequestBody GroupMsgSimulateDTO dto) {
        return simulatorGroupMsgService.startGroupMsgSimulate(dto);
    }

    @GetMapping("/group-msg-simulate/status")
    public GroupMsgSimulateJobViewVO getGroupMsgSimulateStatus() {
        return simulatorGroupMsgService.getGroupMsgSimulateStatus();
    }

    @PostMapping("/group-msg-simulate/stop")
    public void stopGroupMsgSimulate() {
        simulatorGroupMsgService.stopGroupMsgSimulate();
    }
}
