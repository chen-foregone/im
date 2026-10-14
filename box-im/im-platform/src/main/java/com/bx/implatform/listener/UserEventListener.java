package com.bx.implatform.listener;

import com.bx.imclient.annotation.IMListener;
import com.bx.imclient.listener.EventListener;
import com.bx.imcommon.enums.IMEventType;
import com.bx.imcommon.enums.IMListenerType;
import com.bx.imcommon.model.IMUserEvent;
import com.bx.imcommon.model.IMUserInfo;
import com.bx.implatform.service.FriendService;
import com.bx.implatform.service.GroupOnlineService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户事件监听
 *
 * @author Blue
 * @version 1.0
 */
@Slf4j
@IMListener(type = IMListenerType.USER_EVENT)
@AllArgsConstructor
public class UserEventListener implements EventListener {

    private final FriendService friendService;

    private final GroupOnlineService groupOnlineService;

    @Override
    public void process(List<IMUserEvent> events) {
        // 先把整批事件分类收集，再一次性交给 GroupOnlineService。
        // 逐个用户调用会造成「每用户一次 DB 查询 + 每群一次 Redis 往返」，
        // 登录风暴下 1000 人 × 70 群 = 70,000 次往返，实测事件队列积压近 1 分钟。
        List<Long> onlineUserIds = new ArrayList<>();
        List<Long> offlineUserIds = new ArrayList<>();
        for (IMUserEvent event : events) {
            IMUserInfo userInfo = event.getUserInfo();
            if (userInfo == null) {
                continue;
            }
            // 向好友推送在线状态
            friendService.sendOnlineStatus(userInfo.getId(), userInfo.getTerminal());
            if (event.getEventType().equals(IMEventType.ONLINE.code())) {
                onlineUserIds.add(userInfo.getId());
                log.info("用户上线,id:{},终端：{}", userInfo.getId(), userInfo.getTerminal());
            } else if (event.getEventType().equals(IMEventType.OFFLINE.code())) {
                offlineUserIds.add(userInfo.getId());
                log.info("用户下线,id:{},终端：{}", userInfo.getId(), userInfo.getTerminal());
            }
        }
        // 维护群在线集：上线即加入其所在全部群；下线时由 service 内部按 presence 过滤出
        // 「最后一个终端也下线了」的用户再移除（多终端用户只断一个终端时不能移除）
        groupOnlineService.usersOnline(onlineUserIds);
        groupOnlineService.usersOffline(offlineUserIds);
    }
}
