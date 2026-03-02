-- 朋友圈动态表
CREATE TABLE `user_moments` (
  `moments_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '动态ID',
  `user_id` varchar(12) NOT NULL COMMENT '发布用户ID',
  `nick_name` varchar(20) DEFAULT NULL COMMENT '发布用户昵称',
  `content` varchar(1000) DEFAULT NULL COMMENT '文字内容',
  `images` varchar(2000) DEFAULT NULL COMMENT '图片列表(逗号分隔)',
  `type` tinyint(4) NOT NULL DEFAULT '0' COMMENT '动态类型 0:纯文字 1:图文 2:视频',
  `like_count` int(11) NOT NULL DEFAULT '0' COMMENT '点赞数',
  `comment_count` int(11) NOT NULL DEFAULT '0' COMMENT '评论数',
  `create_time` bigint(20) NOT NULL COMMENT '发布时间(毫秒时间戳)',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态 0:已删除 1:正常',
  PRIMARY KEY (`moments_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈动态表';

-- 朋友圈评论表
CREATE TABLE `user_moments_comment` (
  `comment_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `moments_id` bigint(20) NOT NULL COMMENT '动态ID',
  `user_id` varchar(12) NOT NULL COMMENT '评论用户ID',
  `nick_name` varchar(20) DEFAULT NULL COMMENT '评论用户昵称',
  `reply_user_id` varchar(12) DEFAULT NULL COMMENT '回复用户ID(null表示直接评论)',
  `reply_nick_name` varchar(20) DEFAULT NULL COMMENT '回复用户昵称',
  `content` varchar(500) NOT NULL COMMENT '评论内容',
  `create_time` bigint(20) NOT NULL COMMENT '评论时间(毫秒时间戳)',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态 0:已删除 1:正常',
  PRIMARY KEY (`comment_id`),
  KEY `idx_moments_id` (`moments_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈评论表';

-- 朋友圈点赞表
CREATE TABLE `user_moments_like` (
  `moments_id` bigint(20) NOT NULL COMMENT '动态ID',
  `user_id` varchar(12) NOT NULL COMMENT '点赞用户ID',
  `nick_name` varchar(20) DEFAULT NULL COMMENT '点赞用户昵称',
  `create_time` bigint(20) NOT NULL COMMENT '点赞时间(毫秒时间戳)',
  PRIMARY KEY (`moments_id`,`user_id`),
  KEY `idx_moments_id` (`moments_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈点赞表';
