-- 可在导入或更新 ECDICT 后重复执行；只引用词典，不复制单词。
INSERT INTO `word_book` (`user_id`, `book_type`, `system_key`, `name`, `color`, `icon`, `word_count`, `publish_status`)
VALUES (NULL, 'SYSTEM', 'CET4', '四级核心词', 'linear-gradient(135deg, #84fab0 0%, #8fd3f4 100%)', '📘', 0, 'PUBLISHED')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);
INSERT INTO `word_book_dictionary_relation` (`word_book_id`, `word_id`, `sort_order`)
SELECT b.id, d.id, ROW_NUMBER() OVER (ORDER BY CASE WHEN d.frq IS NULL OR d.frq = 0 THEN 1 ELSE 0 END, d.frq, d.id)
FROM `word_book` b CROSS JOIN `word_dictionary` d
WHERE REGEXP_LIKE(COALESCE(d.tag, ''), '(^|[[:space:]])cet4([[:space:]]|$)')
  AND d.meaning IS NOT NULL AND d.meaning <> '' AND b.system_key = 'CET4'
ON DUPLICATE KEY UPDATE `sort_order` = VALUES(`sort_order`);
UPDATE `word_book` SET `word_count` =
    (SELECT COUNT(*) FROM `word_book_dictionary_relation` WHERE `word_book_id` = `word_book`.`id`)
WHERE `system_key` = 'CET4';
