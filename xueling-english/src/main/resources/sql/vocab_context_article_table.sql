CREATE TABLE `vocab_context_article` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `request_id` varchar(64) NOT NULL,
    `status` varchar(16) NOT NULL,
    `title` varchar(200) DEFAULT NULL,
    `content` text,
    `targets_json` json NOT NULL,
    `topic` varchar(80) DEFAULT NULL,
    `model_name` varchar(80) DEFAULT NULL,
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_user_request` (`user_id`, `request_id`),
    KEY `idx_context_user_created` (`user_id`, `create_time`),
    CONSTRAINT `fk_context_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自选词汇语境文章';

CREATE TABLE `vocab_context_attempt` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `article_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `answers_json` json NOT NULL,
    `correct_count` tinyint unsigned NOT NULL,
    `total_count` tinyint unsigned NOT NULL,
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_context_attempt_user` (`user_id`, `create_time`),
    CONSTRAINT `fk_context_attempt_article` FOREIGN KEY (`article_id`) REFERENCES `vocab_context_article` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_context_attempt_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自选语境练习记录，不修改正式复习排程';
