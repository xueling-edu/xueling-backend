CREATE TABLE IF NOT EXISTS `word_study_content` (
    `word_id` bigint unsigned NOT NULL,
    `content_version` varchar(32) NOT NULL,
    `core_meaning` varchar(255) NOT NULL,
    `context_sentence` varchar(500) DEFAULT NULL,
    `context_translation` varchar(500) DEFAULT NULL,
    `context_question` varchar(500) DEFAULT NULL,
    `option_a` varchar(255) DEFAULT NULL,
    `option_b` varchar(255) DEFAULT NULL,
    `correct_option` char(1) DEFAULT NULL,
    `clue_explanation` varchar(500) DEFAULT NULL,
    `approved` tinyint(1) NOT NULL DEFAULT 0,
    `reviewed_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`word_id`),
    CONSTRAINT `fk_study_content_word` FOREIGN KEY (`word_id`) REFERENCES `word_dictionary` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_study_content_option` CHECK (`correct_option` IS NULL OR `correct_option` IN ('A','B'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工审核的一词一义教学内容';
