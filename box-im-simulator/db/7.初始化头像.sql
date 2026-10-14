-- 将用户表、好友表、群成员表头像统一为默认图（按需为 UPDATE 增加 WHERE，避免误更新生产数据）
SET NAMES utf8mb4;

UPDATE im_user
SET head_image = 'https://www.boxim.online/file/box-im/image/20260523/2057889495449083904.jpeg',
    head_image_thumb = 'https://www.boxim.online/file/box-im/image/20260523/2057889495449083904.jpeg';

UPDATE im_friend
SET friend_head_image = 'https://www.boxim.online/file/box-im/image/20260523/2057889495449083904.jpeg';

UPDATE im_group_member
SET head_image = 'https://www.boxim.online/file/box-im/image/20260523/2057889495449083904.jpeg';
