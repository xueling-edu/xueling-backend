CREATE TABLE IF NOT EXISTS `word_study_followup` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `word_id` bigint unsigned NOT NULL,
    `request_id` varchar(64) NOT NULL,
    `rating` tinyint unsigned NOT NULL,
    `content_version` varchar(32) NOT NULL DEFAULT 'dictionary-1',
    `created_at` datetime(3) NOT NULL,
    `teaching_result` varchar(32) DEFAULT NULL,
    `teaching_at` datetime(3) DEFAULT NULL,
    `revisit_result` varchar(32) DEFAULT NULL,
    `revisited_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_followup_request` (`user_id`, `request_id`),
    KEY `idx_followup_pending` (`user_id`, `revisit_result`, `created_at`),
    CONSTRAINT `fk_followup_review` FOREIGN KEY (`user_id`, `request_id`)
        REFERENCES `word_review_record` (`user_id`, `request_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评分后教学与会话回访，独立于FSRS';
