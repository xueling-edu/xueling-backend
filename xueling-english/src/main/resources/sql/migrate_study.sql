-- 仅用于旧学习表没有任何记录的开发数据库。DDL 不支持整体回滚；不要使用 mysql --force。
-- 先检查数据和结构，再执行任何 DROP/ALTER，避免误删真实学习记录或重复执行。
DROP PROCEDURE IF EXISTS assert_empty_legacy_study_tables;
DELIMITER //
CREATE PROCEDURE assert_empty_legacy_study_tables()
BEGIN
    DECLARE row_count BIGINT DEFAULT 0;
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'word_book' AND COLUMN_NAME = 'book_type')
       OR EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'word_book_dictionary_relation'
                    AND COLUMN_NAME = 'sort_order') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Study schema already migrated; stop before dropping tables';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'word_review_record') THEN
        SELECT COUNT(*) INTO row_count FROM word_review_record;
        IF row_count > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy review records exist; a data-preserving migration is required';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'word_learning_progress') THEN
        SELECT COUNT(*) INTO row_count FROM word_learning_progress;
        IF row_count > 0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy learning progress exists; a data-preserving migration is required';
        END IF;
    END IF;
END //
DELIMITER ;
CALL assert_empty_legacy_study_tables();
DROP PROCEDURE assert_empty_legacy_study_tables;

DROP TABLE IF EXISTS `word_review_record`;
DROP TABLE IF EXISTS `word_learning_progress`;
ALTER TABLE `word_book`
    MODIFY `user_id` bigint unsigned NULL,
    ADD COLUMN `book_type` varchar(12) NOT NULL DEFAULT 'USER' AFTER `user_id`,
    ADD COLUMN `system_key` varchar(40) NULL AFTER `book_type`,
    ADD UNIQUE KEY `uk_system_key` (`system_key`),
    ADD KEY `idx_type_deleted` (`book_type`, `is_deleted`);
ALTER TABLE `word_book_dictionary_relation`
    ADD COLUMN `sort_order` int unsigned NOT NULL DEFAULT 0 AFTER `word_id`,
    ADD KEY `idx_book_order` (`word_book_id`, `sort_order`, `word_id`);
-- 接着执行四张新表的结构文件，最后执行 seed_cet4.sql。
