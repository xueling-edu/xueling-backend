-- 在现有 user_feedback 表上执行一次；新库使用更新后的 user_feedback_table.sql。
ALTER TABLE user_feedback
  MODIFY COLUMN context_word varchar(255) DEFAULT NULL,
  ADD COLUMN word_id bigint unsigned DEFAULT NULL COMMENT '关联词典词条ID' AFTER context_word,
  ADD COLUMN observed_content text DEFAULT NULL COMMENT '用户报错时看到的内容' AFTER word_id,
  ADD COLUMN suggested_content text DEFAULT NULL COMMENT '用户建议的正确内容' AFTER observed_content,
  ADD COLUMN resolution varchar(20) DEFAULT NULL COMMENT 'FIXED、NO_CHANGE或UNRESOLVED' AFTER admin_reply,
  ADD COLUMN revision_id bigint unsigned DEFAULT NULL COMMENT '关联词条修订ID' AFTER resolution,
  ADD KEY idx_word_id (word_id);
