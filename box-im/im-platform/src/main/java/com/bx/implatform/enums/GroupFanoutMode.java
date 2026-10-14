package com.bx.implatform.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 群消息扇出模式。
 *
 * <p>小群用写扩散（每条消息逐成员推送），大群用读扩散（写群时间线 + 推一条信号，成员按游标拉取）。
 * 分界点由 {@code im.group.read-diffusion-threshold} 配置，默认 2000，
 * 取值依据见 {@code docs/群聊瓶颈解决方案.md} 的实测交叉点（N≈900~1200，留 2x 安全余量）。</p>
 *
 * <p><b>当前阶段（第 2 项）只维护这个标记，发送路径尚未按它分支</b> ——
 * 读扩散的落地见第 4 项「群时间线拉取」。因此现在把它置成 READ_DIFFUSION 不会改变任何行为。</p>
 *
 * @author box-im
 */
@Getter
@AllArgsConstructor
public enum GroupFanoutMode {

    /** 写扩散：逐成员推送，小群用 */
    WRITE_DIFFUSION(0),

    /** 读扩散：写群时间线，成员按游标拉取，大群用 */
    READ_DIFFUSION(1);

    private final int code;

    /**
     * 数据库 tinyint 是否代表读扩散。null 视为写扩散（老数据无该列时的兜底）。
     */
    public static boolean isReadDiffusion(Integer code) {
        return code != null && code == READ_DIFFUSION.code;
    }
}