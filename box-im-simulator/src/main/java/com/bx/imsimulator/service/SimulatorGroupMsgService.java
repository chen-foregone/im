package com.bx.imsimulator.service;

import com.bx.imsimulator.dto.GroupMsgSimulateDTO;
import com.bx.imsimulator.vo.GroupMsgSimulateJobViewVO;
import com.bx.imsimulator.vo.PrivateMsgSimulateStartVO;

public interface SimulatorGroupMsgService {

    boolean isSimulateRunning();

    PrivateMsgSimulateStartVO startGroupMsgSimulate(GroupMsgSimulateDTO dto);

    GroupMsgSimulateJobViewVO getGroupMsgSimulateStatus();

    void stopGroupMsgSimulate();
}
