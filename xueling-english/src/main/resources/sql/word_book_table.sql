CREATE TABLE `word_book` (
                             `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                             `user_id` bigint unsigned DEFAULT NULL COMMENT '系统词书为空，个人词书为所属用户ID',
                             `book_type` varchar(12) NOT NULL DEFAULT 'USER' COMMENT 'SYSTEM或USER',
                             `publish_status` varchar(16) DEFAULT NULL COMMENT '系统词书发布状态',
                             `system_key` varchar(40) DEFAULT NULL COMMENT '系统词书唯一标识',
                             `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '单词本名称',
                             `color` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '封面背景色',
                             `icon` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '📘' COMMENT '封面图标',
                             `word_count` int unsigned NOT NULL DEFAULT '0' COMMENT '单词总数',
                             `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除(0=否,1=是)',
                             `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             `delete_time` datetime DEFAULT NULL COMMENT '删除时间',
                             PRIMARY KEY (`id`),
                             UNIQUE KEY `uk_system_key` (`system_key`),
                             KEY `idx_type_deleted` (`book_type`, `is_deleted`),
                             KEY `idx_user_deleted` (`user_id`, `is_deleted`),
                             KEY `idx_create_time` (`create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单词本表';
