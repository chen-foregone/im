package com.bx.imsimulator.service;

import com.bx.imsimulator.dto.PrivateMsgSimulateDTO;
import com.bx.imsimulator.vo.PrivateMsgSimulateJobViewVO;
import com.bx.imsimulator.vo.PrivateMsgSimulateStartVO;

public interface SimulatorPrivateMsgService {

    /**
     * 启动私聊模拟（仅允许单任务：进行中则 409）。立即返回；发送与 WS 收尾在后台执行。
     * 启动前会清零所有已注册会话的 WS 私聊计数。
     */
    PrivateMsgSimulateStartVO startPrivateMsgSimulate(PrivateMsgSimulateDTO dto);

    /** 轮询当前任务执行数据；从未启动过任务时为 IDLE */
    PrivateMsgSimulateJobViewVO getPrivateMsgSimulateStatus();

    /** 请求停止当前私聊模拟（协作式：正在进行的 HTTP 发完后不再发新请求；无进行中任务则无操作） */
    void stopPrivateMsgSimulate();

    /** 是否存在未结束的私聊模拟任务（用于与群聊模拟互斥） */
    boolean isSimulateRunning();
}
