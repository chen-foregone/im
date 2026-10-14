package com.bx.imsimulator.service;

import com.bx.imsimulator.vo.BatchLoginResultVO;

public interface SimulatorLoginService {

    /**
     * 从库中按 id 升序取前 {@code limit} 名用户，向 im-platform 并发发起登录。
     *
     * @param limit    用户数上限，null 时使用配置 simulator.fetch-user-limit，最大 10000
     * @param terminal 登录终端：0 web，1 app，2 pc；null 时使用配置 simulator.terminal
     */
    BatchLoginResultVO batchLogin(Integer limit, Integer terminal);

    /** 关闭当前由本服务保持的所有模拟 WebSocket（下线） */
    void stopSessions();
}
