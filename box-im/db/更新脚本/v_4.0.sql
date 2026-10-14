ALTER TABLE `im_private_message` ADD COLUMN `local_id` varchar(32) default '' comment '业务id,由前端生成';

update `im_private_message` set local_id = (FLOOR(UNIX_TIMESTAMP(send_time)*1000) - 1288834974657) << 22 | FLOOR(RAND() * 1024) << 12 | FLOOR(RAND() * 4096) where local_id = '';

ALTER TABLE `im_group_message` ADD COLUMN `local_id` varchar(32) default '' comment '业务id,由前端生成';

update `im_group_message` set local_id = (FLOOR(UNIX_TIMESTAMP(send_time)*1000) - 1288834974657) << 22 | FLOOR(RAND() * 1024) << 12 | FLOOR(RAND() * 4096) where local_id = '';

ALTER TABLE `im_private_message` ADD COLUMN `conv_key` varchar(64) not null default ''  comment '会话key，格式:userId1_userId2';

UPDATE `im_private_message` SET conv_key = CONCAT( LEAST(send_id, recv_id), '_', GREATEST(send_id, recv_id));

update  `im_private_message`  set content = JSON_OBJECT('id', content) where type = 10;

update  `im_group_message`  set content = JSON_OBJECT('id', content) where type = 10;

ALTER TABLE im_private_message MODIFY send_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

ALTER TABLE im_group_message MODIFY send_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

ALTER TABLE im_private_message ADD COLUMN seq_no int NOT NULL  comment '序列号,单个会话消息的序号连续递增';

ALTER TABLE im_group_message ADD COLUMN seq_no int NOT NULL  comment '序列号,单个会话消息的序号连续递增';

ALTER TABLE `im_private_message` ADD INDEX `idx_conv_key_seq_no`(`conv_key`,`seq_no`);

ALTER TABLE `im_private_message` ADD INDEX `idx_send_recv_id`(`send_id`, `recv_id`,`id`);

ALTER TABLE `im_group_message` ADD INDEX  `idx_group_id_seq_no` (`group_id`,`seq_no`);

ALTER TABLE im_friend ADD `version` BIGINT DEFAULT 0 comment '版本号';

ALTER TABLE `im_friend` ADD INDEX `idx_user_friend_id` (`user_id`, `friend_id`);

DELETE t1 FROM `im_friend` AS t1 INNER JOIN `im_friend` AS t2 ON t1.`user_id` = t2.`user_id` AND t1.`friend_id` = t2.`friend_id` AND t1.`id` > t2.`id`;

ALTER TABLE  `im_friend` DROP INDEX  `idx_user_friend_id`;

ALTER TABLE `im_friend` ADD UNIQUE KEY `idx_user_friend_id` (`user_id`, `friend_id`);

ALTER TABLE im_group_member ADD `version` BIGINT DEFAULT 0 comment '版本号';


create table `im_message_deletion`
(
    `id`          bigint  not null auto_increment primary key comment 'id',
    `user_id`     bigint  not null comment '用户id',
    `chat_type`   tinyint not null comment '会话类型 1:私聊 2:群聊',
    `chat_id`     bigint  not null comment '好友id、群聊id',
    `message_id`  bigint(20) comment '消息id',
    `delete_type` tinyint not null comment '删除类型 1:按消息删除 2:按会话删除',
    `delete_time` datetime default current_timestamp comment '消息删除时间',
    key           `idx_user_id` (`user_id`)
) engine = innodb charset = utf8mb4 comment '消息删除记录';


UPDATE im_private_message pm
JOIN (
    SELECT t.id,
           (@seq := IF(@conv = t.conv_key, @seq + 1, 1)) AS new_seq,
           (@conv := t.conv_key) AS _conv
    FROM (
        SELECT id, conv_key
        FROM im_private_message
        ORDER BY conv_key, id
    ) t
    JOIN (SELECT @conv := '', @seq := 0) vars
) s ON s.id = pm.id
SET pm.seq_no = s.new_seq;

UPDATE im_group_message gm
JOIN (
    SELECT t.id,
           (@seq := IF(@gid = t.group_id, @seq + 1, 1)) AS new_seq,
           (@gid := t.group_id) AS _gid
    FROM (
        SELECT id, group_id
        FROM im_group_message
        ORDER BY group_id, id
    ) t
    JOIN (SELECT @gid := 0, @seq := 0) vars
) s ON s.id = gm.id
SET gm.seq_no = s.new_seq;






