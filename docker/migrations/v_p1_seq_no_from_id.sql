-- ============================================================
-- P1：seq_no 改由主键 id 派生
--
-- 背景：saveMessage 原先用 @RedisLock + Redis INCR 生成 seq_no，
--       以保证 seq_no 与数据库自增 id 同序。实测该锁是单群吞吐的
--       首要瓶颈（去掉后 A1 +62%、A2 +83%），但直接删锁会导致
--       10.2% 的消息 id/seq_no 倒挂（loadOffineMessage 按 id、
--       消息历史按 seq_no，两者不一致即数据错乱）。
-- 方案：seq_no 不再单独生成；对外暴露的 seqNo 直接映射主键 id。
-- 详见 docs/群聊性能优化对照报告.md
-- ============================================================

-- 1) 旧的 seq_no 列不再读写：改为可空，数据保留以便回滚
ALTER TABLE im_group_message
    MODIFY COLUMN seq_no BIGINT NULL DEFAULT NULL
    COMMENT '已废弃：seqNo 由主键 id 派生，本列不再读写';

-- 2) 新增 (group_id, id) 索引，支撑「按群过滤 + 按 id 排序/范围查询」
ALTER TABLE im_group_message ADD INDEX idx_group_id (group_id, id);

-- 3) 旧的 (group_id, seq_no) 索引已无查询使用，留着只会拖慢写入
ALTER TABLE im_group_message DROP INDEX idx_group_id_seq_no;
