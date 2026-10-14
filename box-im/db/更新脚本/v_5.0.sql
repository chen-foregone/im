-- ============================================================
-- v5.0 群扇出模式 + 群成员数
-- 对应改造：docs/群聊瓶颈解决方案.md 第 3.7 节「群扩容 2000 -> 10000」
-- ============================================================

-- 1) 加列
ALTER TABLE `im_group`
    ADD COLUMN `fanout_mode` tinyint NOT NULL DEFAULT 0 COMMENT '扇出模式 0=写扩散(小群) 1=读扩散(大群)';

ALTER TABLE `im_group`
    ADD COLUMN `member_count` int NOT NULL DEFAULT 0 COMMENT '群成员数(不含已退群)';

-- 2) 回填 member_count（一次性，之后由应用维护）
UPDATE `im_group` g
SET g.`member_count` = (
    SELECT COUNT(*) FROM `im_group_member` m
    WHERE m.`group_id` = g.`id` AND m.`quit` = 0
);

-- 3) 已有的超阈值大群：**默认不翻转**。
--    原因：读扩散的消费端（群时间线拉取）尚未实现，此刻翻成 1 不会改变行为，
--    却会让 `fanout_mode` 与真实行为不一致，成为误导。等第 4 项落地后再执行下面这段。
--
-- UPDATE `im_group` SET `fanout_mode` = 1 WHERE `member_count` > 2000;

-- 4) 校验
-- SELECT fanout_mode, COUNT(*) AS groups, MIN(member_count), MAX(member_count)
-- FROM `im_group` GROUP BY fanout_mode;