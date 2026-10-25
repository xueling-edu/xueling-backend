# 单词学习功能数据库准备

数据库使用 MySQL 8.0。学习时间字段按 UTC 写入 `datetime(3)`；用户设置中的时区仅用于划分“今天”和展示时间。

## 已有开发数据库

旧的 `word_learning_progress` 和 `word_review_record` **都为空**时，备份数据库并确认目标数据库名称后，依次执行：

1. `migrate_study.sql`：先检查旧表无记录且词书结构未升级，再删除旧的空学习表并扩展词书结构；检查不通过时立即停止。
2. `user_study_settings_table.sql`
3. `user_study_book_table.sql`
4. `word_learning_progress_table.sql`
5. `word_review_record_table.sql`
6. `study_check_in_table.sql`：创建每日自动打卡表（已有学习数据库也需单独执行）。
7. `seed_cet4.sql`：从已导入的 ECDICT 建立系统四级词书。

如果任一旧学习表有记录，**不要执行** `migrate_study.sql`；旧表只有二档复习结果，无法直接等价转换为新 FSRS 卡片，需要先制定保留历史记录和初始化新卡片的迁移方案。脚本报错后不得使用 `mysql --force` 继续执行。

如果 ECDICT 尚未导入，先完成词典导入，再执行 `seed_cet4.sql`。`migrate_study.sql` 只执行一次。MySQL 命令行执行 SQL 文件时显式指定 `--default-character-set=utf8mb4`。

## 新数据库

先按原项目顺序创建 `user`、`word_dictionary`、`word_book` 和 `word_book_dictionary_relation`，接着执行上面的第 2～6 个表结构文件。导入词典后执行 `seed_cet4.sql`。

系统词书通过 `system_key='CET4'` 查询，不依赖固定主键。用户首次访问 `/study/today` 时自动创建默认学习设置，并将系统四级词书加入自己的学习计划。
