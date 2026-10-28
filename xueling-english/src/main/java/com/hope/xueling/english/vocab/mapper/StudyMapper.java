package com.hope.xueling.english.vocab.mapper;

import com.hope.xueling.english.vocab.domain.vo.StudyQueueCardVO;
import com.hope.xueling.english.vocab.domain.vo.StudySettingsVO;
import com.hope.xueling.english.vocab.domain.vo.StudyReviewResultVO;
import com.hope.xueling.english.vocab.domain.vo.StudyReviewCountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 学习计划数据访问：直接使用 JdbcTemplate 手写 SQL，覆盖学习设置、今日队列、卡片进度、评分记录、打卡与统计。
 * <p>时间统一按 UTC 存取：写入前把 Instant 转为 LocalDateTime，读取后按 UTC 还原，避免容器时区影响排程比较。</p>
 * @author 谢光湘
 * @since 2026/10/24
 */
@Repository
@RequiredArgsConstructor
public class StudyMapper {
    /**
     * JDBC 模板
     */
    private final JdbcTemplate jdbc;

    public record FollowupRow(String requestId, int rating, boolean newCard, int laterCards,
                              Instant dueAt, String teachingResult, String wordId, String word,
                              String meaning, String phonetic, String example, String exampleTranslation,
                              String coreMeaning, String contextSentence, String contextTranslation,
                              String contextQuestion, String optionA, String optionB,
                              String correctOption, String clueExplanation, String contentVersion) {}
    public record FollowupSummary(int passed, int failed, int skipped) {}

    public FollowupSummary followupSummary(long userId, Instant start, Instant end) {
        return jdbc.queryForObject("SELECT COUNT(CASE WHEN revisit_result='revisit_pass' THEN 1 END) passed,"
                + "COUNT(CASE WHEN revisit_result='revisit_fail' THEN 1 END) failed,"
                + "COUNT(CASE WHEN revisit_result='skipped' THEN 1 END) skipped "
                + "FROM word_study_followup WHERE user_id=? AND revisited_at>=? AND revisited_at<?",
                (rs, row) -> new FollowupSummary(rs.getInt("passed"), rs.getInt("failed"), rs.getInt("skipped")),
                userId, dbTime(start), dbTime(end));
    }

    /** Create a recoverable teaching task only after the first review has been committed. */
    public void insertFollowup(long userId, long wordId, String requestId, int rating, boolean newCard, Instant at) {
        jdbc.update("UPDATE word_study_followup SET revisit_result='superseded', revisited_at=? "
                + "WHERE user_id=? AND word_id=? AND revisit_result IS NULL", dbTime(at), userId, wordId);
        if (rating <= 2 || (rating == 3 && newCard)) {
            jdbc.update("INSERT INTO word_study_followup (user_id,word_id,request_id,rating,content_version,created_at) "
                    + "SELECT ?,d.id,?,?,COALESCE(c.content_version,'dictionary-1'),? "
                    + "FROM word_dictionary d LEFT JOIN word_study_content c ON c.word_id=d.id AND c.approved=1 "
                    + "WHERE d.id=? AND (?<=2 OR c.context_sentence IS NOT NULL)",
                    userId, requestId, rating, dbTime(at), wordId, rating);
        }
    }

    public List<FollowupRow> pendingFollowups(long userId) {
        return jdbc.query("SELECT f.request_id,f.rating,(r.due_before IS NULL) AS new_card,"
                + "(SELECT COUNT(DISTINCT later.word_id) FROM word_review_record later "
                + "WHERE later.user_id=f.user_id AND later.id>r.id AND later.word_id<>f.word_id) AS later_cards,"
                + "p.due_at,f.teaching_result,d.id AS word_id,d.word,d.meaning,d.phonetic,d.example,d.example_translation,"
                + "c.core_meaning,c.context_sentence,c.context_translation,c.context_question,c.option_a,c.option_b,"
                + "c.correct_option,c.clue_explanation,f.content_version "
                + "FROM word_study_followup f JOIN word_review_record r "
                + "ON r.user_id=f.user_id AND r.request_id=f.request_id "
                + "JOIN word_learning_progress p ON p.user_id=f.user_id AND p.word_id=f.word_id AND p.card_type='EN_ZH' "
                + "JOIN word_dictionary d ON d.id=f.word_id "
                + "LEFT JOIN word_study_content c ON c.word_id=d.id AND c.approved=1 "
                + "AND c.content_version=f.content_version "
                + "WHERE f.user_id=? AND f.revisit_result IS NULL ORDER BY f.id LIMIT 30",
                (rs, row) -> new FollowupRow(rs.getString("request_id"), rs.getInt("rating"),
                        rs.getBoolean("new_card"), rs.getInt("later_cards"), utc(rs, "due_at"),
                        rs.getString("teaching_result"), String.valueOf(rs.getLong("word_id")),
                        rs.getString("word"), rs.getString("meaning"), rs.getString("phonetic"),
                        rs.getString("example"), rs.getString("example_translation"),
                        rs.getString("core_meaning"), rs.getString("context_sentence"),
                        rs.getString("context_translation"), rs.getString("context_question"),
                        rs.getString("option_a"), rs.getString("option_b"),
                        rs.getString("correct_option"), rs.getString("clue_explanation"),
                        rs.getString("content_version")), userId);
    }

    public int saveFollowupAction(long userId, String requestId, String action, String result, Instant at) {
        if ("teaching".equals(action)) {
            return jdbc.update("UPDATE word_study_followup SET teaching_result=?,teaching_at=?,"
                    + "revisit_result=CASE WHEN rating=3 THEN 'not_required' ELSE revisit_result END "
                    + "WHERE user_id=? AND request_id=? AND teaching_result IS NULL",
                    result, dbTime(at), userId, requestId);
        }
        return jdbc.update("UPDATE word_study_followup SET revisit_result=?,revisited_at=? "
                + "WHERE user_id=? AND request_id=? AND revisit_result IS NULL",
                result, dbTime(at), userId, requestId);
    }

    public String followupActionResult(long userId, String requestId, String action) {
        String column = "teaching".equals(action) ? "teaching_result" : "revisit_result";
        List<String> rows = jdbc.query("SELECT " + column + " FROM word_study_followup "
                        + "WHERE user_id=? AND request_id=?", (rs, row) -> rs.getString(1), userId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 卡片进度行
     * @param id 进度记录 ID
     * @param cardJson 排程器序列化的卡片状态
     * @param state 卡片状态：LEARNING、REVIEW
     * @param dueAt 下次到期时间
     * @param version 版本号，每次评分自增，用于并发校验
     * @param reviewCount 累计复习次数
     * @param lapseCount 累计遗忘次数
     */
    public record ProgressRow(long id, String cardJson, String state, Instant dueAt,
                              int version, int reviewCount, int lapseCount) {}

    /**
     * 按请求编号命中的历史评分，用于幂等重放
     * @param wordId 单词 ID
     * @param rating 当时提交的评分
     * @param result 当时返回的排程结果
     */
    public record PreviousReview(long wordId, int rating, StudyReviewResultVO result) {}
    /**
     * 评分记录行，用于统计与日历聚合
     * @param reviewedAt 评分时间
     * @param dueBefore 评分前的到期时间，null 表示首次学习
     * @param stateBefore 评分前的卡片状态
     * @param rating 评分
     * @param elapsedMs 作答耗时（毫秒）
     */
    public record HistoryReviewRow(Instant reviewedAt, Instant dueBefore,
                                   String stateBefore, int rating, Integer elapsedMs) {}
    /**
     * 熟悉度统计行
     * @param wordId 单词 ID
     * @param word 单词
     * @param meaning 释义
     * @param state 卡片状态
     * @param cardJson 排程器序列化的卡片状态，用于取稳定度
     * @param dueAt 下次到期时间
     * @param lapseCount 累计遗忘次数
     * @param lastRating 最近一次评分
     */
    public record FamiliarityRow(long wordId, String word, String meaning, String state,
                                 String cardJson, Instant dueAt, int lapseCount, Integer lastRating,
                                 boolean inStudyPlan) {}

    /** A learned word stays in the review queue while any enabled book contains it. */
    private static final String ACTIVE_BOOK_WORD = "EXISTS (SELECT 1 FROM user_study_book s "
            + "JOIN word_book b ON b.id=s.book_id "
            + "JOIN word_book_dictionary_relation rel ON rel.word_book_id=b.id "
            + "WHERE s.user_id=p.user_id AND s.enabled=1 AND b.is_deleted=0 "
            + "AND ((b.book_type='SYSTEM' AND b.publish_status='PUBLISHED') OR b.user_id=s.user_id) AND rel.word_id=p.word_id)";

    /**
     * 把结果集中的 UTC 时间列还原为 Instant
     * @param rs 结果集
     * @param column 列名
     * @return 时刻，列为 null 时返回 null
     */
    private static Instant utc(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * 把 Instant 转为数据库使用的 UTC LocalDateTime
     * @param instant 时刻
     * @return UTC 本地时间
     */
    private static LocalDateTime dbTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 把结果集行映射为队列卡片，新卡固定版本 0、状态 NEW、无到期时间
     * @param rs 结果集
     * @param isNew 是否为新卡
     * @return 队列卡片
     */
    private static StudyQueueCardVO queueCard(ResultSet rs, boolean isNew) throws SQLException {
        StudyQueueCardVO card = new StudyQueueCardVO();
        card.setWordId(String.valueOf(rs.getLong("word_id")));
        card.setWord(rs.getString("word"));
        card.setMeaning(rs.getString("meaning"));
        card.setPhonetic(rs.getString("phonetic"));
        card.setExample(rs.getString("example"));
        card.setExampleTranslation(rs.getString("example_translation"));
        card.setCoreMeaning(rs.getString("core_meaning"));
        card.setContextSentence(rs.getString("context_sentence"));
        card.setContextTranslation(rs.getString("context_translation"));
        card.setContextQuestion(rs.getString("context_question"));
        card.setOptionA(rs.getString("option_a"));
        card.setOptionB(rs.getString("option_b"));
        card.setCorrectOption(rs.getString("correct_option"));
        card.setClueExplanation(rs.getString("clue_explanation"));
        card.setContentVersion(rs.getString("content_version"));
        card.setImageUrl(rs.getString("image_url"));
        card.setNewCard(isNew);
        card.setVersion(isNew ? 0 : rs.getInt("version"));
        card.setState(isNew ? "NEW" : rs.getString("state"));
        card.setDueAt(isNew ? null : utc(rs, "due_at"));
        return card;
    }

    /**
     * 初始化学习设置与默认 CET4 词书（已存在时忽略），用于读路径
     * @param userId 用户 ID
     */
    public void ensureSettings(long userId) {
        jdbc.update("INSERT IGNORE INTO user_study_settings (user_id) VALUES (?)", userId);
        jdbc.update("INSERT IGNORE INTO user_study_book (user_id, book_id) "
                + "SELECT ?, id FROM word_book WHERE system_key = 'CET4' AND is_deleted = 0 AND publish_status='PUBLISHED'", userId);
    }

    /**
     * 初始化学习设置与默认词书，写路径使用：先以 ON DUPLICATE KEY UPDATE 取得独占锁，避免后续锁升级死锁
     * @param userId 用户 ID
     */
    public void ensureSettingsForUpdate(long userId) {
        // 写入路径先取得独占锁，避免两个 INSERT IGNORE 的共享锁同时升级为 FOR UPDATE 时死锁。
        jdbc.update("INSERT INTO user_study_settings (user_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE user_id=user_id", userId);
        jdbc.update("INSERT IGNORE INTO user_study_book (user_id, book_id) "
                + "SELECT ?, id FROM word_book WHERE system_key = 'CET4' AND is_deleted = 0 AND publish_status='PUBLISHED'", userId);
    }

    /**
     * 读取学习设置
     * @param userId 用户 ID
     * @param lock 是否加 FOR UPDATE 行锁
     * @return 学习设置
     */
    public StudySettingsVO settings(long userId, boolean lock) {
        String sql = "SELECT time_zone, daily_new_limit, desired_retention FROM user_study_settings WHERE user_id = ?"
                + (lock ? " FOR UPDATE" : "");
        return jdbc.queryForObject(sql, (rs, rowNum) -> new StudySettingsVO(
                rs.getString("time_zone"), rs.getInt("daily_new_limit"),
                rs.getDouble("desired_retention")), userId);
    }

    /**
     * 更新学习设置
     * @param userId 用户 ID
     * @param settings 新的学习设置
     */
    public void updateSettings(long userId, StudySettingsVO settings) {
        jdbc.update("UPDATE user_study_settings SET time_zone=?, daily_new_limit=?, desired_retention=? WHERE user_id=?",
                settings.getTimeZone(), settings.getDailyNewLimit(), settings.getDesiredRetention(), userId);
    }

    /**
     * 统计当前已到期的卡片数
     * @param userId 用户 ID
     * @param now 当前时刻
     * @return 到期卡片数
     */
    public int countDue(long userId, Instant now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_learning_progress p "
                + "WHERE p.user_id=? AND p.card_type='EN_ZH' AND p.due_at<=? AND " + ACTIVE_BOOK_WORD,
                Integer.class, userId, dbTime(now));
    }

    /**
     * 查询未来最近一次到期时间
     * @param userId 用户 ID
     * @param now 当前时刻
     * @return 下次到期时间，没有未到期卡片时返回 null
     */
    public Instant nextDue(long userId, Instant now) {
        LocalDateTime value = jdbc.query("SELECT MIN(p.due_at) FROM word_learning_progress p "
                        + "WHERE p.user_id=? AND p.card_type='EN_ZH' AND p.due_at>? AND " + ACTIVE_BOOK_WORD,
                rs -> rs.next() ? rs.getObject(1, LocalDateTime.class) : null,
                userId, dbTime(now));
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * 取到期卡片，按到期时间升序
     * @param userId 用户 ID
     * @param now 当前时刻
     * @param limit 最多返回条数
     * @return 队列卡片列表
     */
    public List<StudyQueueCardVO> dueCards(long userId, Instant now, int limit) {
        return jdbc.query("SELECT d.id AS word_id,d.word,d.meaning,d.phonetic,d.example,"
                + "d.example_translation,d.image_url,c.core_meaning,c.context_sentence,c.context_translation,"
                + "c.context_question,c.option_a,c.option_b,c.correct_option,c.clue_explanation,c.content_version,"
                + "p.state,p.due_at,p.version "
                + "FROM word_learning_progress p JOIN word_dictionary d ON d.id=p.word_id "
                + "LEFT JOIN word_study_content c ON c.word_id=d.id AND c.approved=1 "
                + "WHERE p.user_id=? AND p.card_type='EN_ZH' AND p.due_at<=? AND " + ACTIVE_BOOK_WORD + " "
                + "ORDER BY p.due_at,p.id LIMIT ?",
                (rs, rowNum) -> queueCard(rs, false), userId, dbTime(now), limit);
    }

    /**
     * 取尚未学过的词作为新卡：仅取已启用词书、且词条释义非空，按词书优先级与词书内排序
     * @param userId 用户 ID
     * @param limit 最多返回条数
     * @return 队列卡片列表
     */
    public List<StudyQueueCardVO> newCards(long userId, int limit) {
        return jdbc.query("SELECT d.id AS word_id,d.word,d.meaning,d.phonetic,d.example,"
                + "d.example_translation,d.image_url,c.core_meaning,c.context_sentence,c.context_translation,"
                + "c.context_question,c.option_a,c.option_b,c.correct_option,c.clue_explanation,c.content_version "
                + "FROM user_study_book s JOIN word_book b ON b.id=s.book_id "
                + "JOIN word_book_dictionary_relation r ON r.word_book_id=b.id "
                + "JOIN word_dictionary d ON d.id=r.word_id "
                + "LEFT JOIN word_study_content c ON c.word_id=d.id AND c.approved=1 "
                + "LEFT JOIN word_learning_progress p ON p.user_id=s.user_id AND p.word_id=d.id AND p.card_type='EN_ZH' "
                + "WHERE s.user_id=? AND s.enabled=1 AND b.is_deleted=0 "
                + "AND ((b.book_type='SYSTEM' AND b.publish_status='PUBLISHED') OR b.user_id=s.user_id) AND p.id IS NULL "
                + "AND d.meaning IS NOT NULL AND d.meaning<>'' "
                + "GROUP BY d.id ORDER BY MIN(s.priority),MIN(r.sort_order),d.id LIMIT ?",
                (rs, rowNum) -> queueCard(rs, true), userId, limit);
    }

    /**
     * 统计指定自然日已引入的新词数
     * @param userId 用户 ID
     * @param date 自然日
     * @return 新词数
     */
    public int countIntroduced(long userId, LocalDate date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_learning_progress "
                + "WHERE user_id=? AND introduced_date=? AND card_type='EN_ZH'", Integer.class, userId, date);
    }

    /**
     * 统计时间区间内的评分次数
     * @param userId 用户 ID
     * @param start 区间起点
     * @param end 区间终点
     * @return 评分次数
     */
    public int countTodayReviews(long userId, Instant start, Instant end) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_review_record "
                + "WHERE user_id=? AND reviewed_at>=? AND reviewed_at<?", Integer.class,
                userId, dbTime(start), dbTime(end));
    }

    /**
     * 写入打卡记录，同一用户同一天唯一，重复写入忽略
     * @param userId 用户 ID
     * @param date 自然日
     * @param checkedInAt 打卡时刻
     */
    public void insertCheckIn(long userId, LocalDate date, Instant checkedInAt) {
        jdbc.update("INSERT IGNORE INTO study_check_in (user_id,study_date,checked_in_at) VALUES (?,?,?)",
                userId, date, dbTime(checkedInAt));
    }

    /**
     * 判断指定自然日是否已打卡
     * @param userId 用户 ID
     * @param date 自然日
     * @return 是否已打卡
     */
    public boolean hasCheckIn(long userId, LocalDate date) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM study_check_in WHERE user_id=? AND study_date=?",
                Integer.class, userId, date);
        return count != null && count > 0;
    }

    /**
     * 查询用户全部打卡日期，按日期升序
     * @param userId 用户 ID
     * @return 打卡日期列表
     */
    public List<LocalDate> checkInDates(long userId) {
        return jdbc.query("SELECT study_date FROM study_check_in WHERE user_id=? ORDER BY study_date",
                (rs, rowNum) -> rs.getObject("study_date", LocalDate.class), userId);
    }

    /**
     * 查询区间内的评分记录，并带出该次评分前的卡片状态
     * @param userId 用户 ID
     * @param start 区间起点
     * @param end 区间终点
     * @return 评分记录列表
     */
    public List<HistoryReviewRow> historyReviews(long userId, Instant start, Instant end) {
        return jdbc.query("SELECT r.reviewed_at,r.due_before,r.rating,r.elapsed_ms,"
                        + "(SELECT previous_review.state_after FROM word_review_record previous_review "
                        + "WHERE previous_review.progress_id=r.progress_id AND previous_review.id<r.id "
                        + "ORDER BY previous_review.id DESC LIMIT 1) AS state_before "
                        + "FROM word_review_record r WHERE r.user_id=? AND r.reviewed_at>=? AND r.reviewed_at<? "
                        + "ORDER BY r.reviewed_at,r.id",
                (rs, rowNum) -> new HistoryReviewRow(utc(rs, "reviewed_at"), utc(rs, "due_before"),
                        rs.getString("state_before"), rs.getInt("rating"),
                        rs.getObject("elapsed_ms", Integer.class)), userId, dbTime(start), dbTime(end));
    }

    /**
     * 统计指定时刻的历史到期积压：按每张卡片最后一条评分记录判断其到期时间是否早于该时刻
     * @param userId 用户 ID
     * @param cutoff 截止时刻
     * @return 积压卡片数
     */
    public int dueBacklogAt(long userId, Instant cutoff) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_review_record r JOIN "
                        + "(SELECT progress_id,MAX(id) AS last_id FROM word_review_record "
                        + "WHERE user_id=? AND reviewed_at<? GROUP BY progress_id) latest ON r.id=latest.last_id "
                        + "WHERE r.due_after<?", Integer.class,
                userId, dbTime(cutoff), dbTime(cutoff));
    }

    /**
     * 以 FOR UPDATE 读取卡片进度，串行化同一张卡片的并发评分
     * @param userId 用户 ID
     * @param wordId 单词 ID
     * @return 进度行，尚未学习时返回 null
     */
    public ProgressRow progressForUpdate(long userId, long wordId) {
        List<ProgressRow> rows = jdbc.query("SELECT id,card_json,state,due_at,version,review_count,lapse_count "
                + "FROM word_learning_progress WHERE user_id=? AND word_id=? AND card_type='EN_ZH' FOR UPDATE",
                (rs, rowNum) -> new ProgressRow(rs.getLong("id"), rs.getString("card_json"),
                        rs.getString("state"), utc(rs, "due_at"), rs.getInt("version"),
                        rs.getInt("review_count"), rs.getInt("lapse_count")), userId, wordId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 判断单词是否属于用户已启用的词书且释义非空，即是否允许作为新词学习
     * @param userId 用户 ID
     * @param wordId 单词 ID
     * @return 是否可学
     */
    public boolean eligibleNewWord(long userId, long wordId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_study_book s "
                + "JOIN word_book b ON b.id=s.book_id "
                + "JOIN word_book_dictionary_relation r ON r.word_book_id=b.id "
                + "JOIN word_dictionary d ON d.id=r.word_id "
                + "WHERE s.user_id=? AND s.enabled=1 AND b.is_deleted=0 "
                + "AND ((b.book_type='SYSTEM' AND b.publish_status='PUBLISHED') OR b.user_id=s.user_id) AND r.word_id=? "
                + "AND d.meaning IS NOT NULL AND d.meaning<>''",
                Integer.class, userId, wordId);
        return count != null && count > 0;
    }

    /**
     * 新建卡片进度，初始复习次数 1、版本 1，并记录引入日期
     * @param userId 用户 ID
     * @param wordId 单词 ID
     * @param cardJson 卡片状态 JSON
     * @param state 卡片状态
     * @param dueAt 下次到期时间
     * @param reviewedAt 本次复习时刻
     * @param date 引入日期（用户时区）
     * @param schedulerVersion 排程算法版本
     * @param lapseCount 累计遗忘次数
     */
    public void insertProgress(long userId, long wordId, String cardJson, String state,
                               Instant dueAt, Instant reviewedAt, LocalDate date, String schedulerVersion,
                               int lapseCount) {
        jdbc.update("INSERT INTO word_learning_progress (user_id,word_id,card_type,state,card_json,due_at,"
                        + "last_review_at,introduced_date,review_count,lapse_count,scheduler_version,version) "
                        + "VALUES (?,?,'EN_ZH',?,?,?,?,?,1,?,?,1)",
                userId, wordId, state, cardJson, dbTime(dueAt), dbTime(reviewedAt), date,
                lapseCount, schedulerVersion);
    }

    /**
     * 更新卡片进度，版本号自增 1
     * @param progressId 进度记录 ID
     * @param cardJson 卡片状态 JSON
     * @param state 卡片状态
     * @param dueAt 下次到期时间
     * @param reviewedAt 本次复习时刻
     * @param reviewCount 累计复习次数
     * @param lapseCount 累计遗忘次数
     * @param schedulerVersion 排程算法版本
     */
    public void updateProgress(long progressId, String cardJson, String state, Instant dueAt,
                               Instant reviewedAt, int reviewCount, int lapseCount, String schedulerVersion) {
        jdbc.update("UPDATE word_learning_progress SET card_json=?,state=?,due_at=?,last_review_at=?,"
                        + "review_count=?,lapse_count=?,scheduler_version=?,version=version+1 WHERE id=?",
                cardJson, state, dbTime(dueAt), dbTime(reviewedAt), reviewCount, lapseCount,
                schedulerVersion, progressId);
    }

    /**
     * 按请求编号查询已写入的评分记录，用于幂等重放
     * @param userId 用户 ID
     * @param requestId 客户端请求编号
     * @return 历史评分，未找到时返回 null
     */
    public PreviousReview reviewByRequestId(long userId, String requestId) {
        List<PreviousReview> rows = jdbc.query("SELECT word_id,rating,state_after,due_after,version_after "
                + "FROM word_review_record WHERE user_id=? AND request_id=?",
                (rs, rowNum) -> new PreviousReview(rs.getLong("word_id"), rs.getInt("rating"),
                        new StudyReviewResultVO(String.valueOf(rs.getLong("word_id")),
                                rs.getString("state_after"), utc(rs, "due_after"),
                                rs.getInt("version_after"))), userId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 写入评分记录，保留评分前后的到期时间与版本，供统计与积压回算使用
     * @param progressId 进度记录 ID
     * @param userId 用户 ID
     * @param wordId 单词 ID
     * @param rating 评分
     * @param reviewedAt 评分时刻
     * @param dueBefore 评分前的到期时间，首次学习为 null
     * @param dueAfter 评分后的到期时间
     * @param elapsedMs 作答耗时（毫秒）
     * @param schedulerVersion 排程算法版本
     * @param retention 本次使用的目标记忆率
     * @param stateAfter 评分后的卡片状态
     * @param versionAfter 评分后的版本号
     * @param requestId 客户端请求编号
     */
    public void insertReview(long progressId, long userId, long wordId, int rating,
                             Instant reviewedAt, Instant dueBefore, Instant dueAfter, Integer elapsedMs,
                             String schedulerVersion, double retention, String stateAfter, int versionAfter,
                             String requestId) {
        jdbc.update("INSERT INTO word_review_record (progress_id,user_id,word_id,card_type,rating,reviewed_at,"
                        + "due_before,due_after,elapsed_ms,scheduler_version,desired_retention,state_after,"
                        + "version_after,request_id) VALUES (?,?,?,'EN_ZH',?,?,?,?,?,?,?,?,?,?)",
                progressId, userId, wordId, rating, dbTime(reviewedAt),
                dueBefore == null ? null : dbTime(dueBefore), dbTime(dueAfter), elapsedMs,
                schedulerVersion, retention, stateAfter, versionAfter, requestId);
    }

    /**
     * 统计用户已学词数
     * @param userId 用户 ID
     * @return 已学词数
     */
    public int countLearned(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_learning_progress WHERE user_id=? AND card_type='EN_ZH'",
                Integer.class, userId);
    }

    /**
     * 按复习次数分档统计词数
     * @param userId 用户 ID
     * @return 复习次数分布
     */
    public StudyReviewCountVO reviewCounts(long userId) {
        return jdbc.queryForObject("SELECT "
                        + "COALESCE(SUM(CASE WHEN review_count=1 THEN 1 ELSE 0 END),0) AS once_count,"
                        + "COALESCE(SUM(CASE WHEN review_count=2 THEN 1 ELSE 0 END),0) AS twice_count,"
                        + "COALESCE(SUM(CASE WHEN review_count BETWEEN 3 AND 5 THEN 1 ELSE 0 END),0) AS middle_count,"
                        + "COALESCE(SUM(CASE WHEN review_count>=6 THEN 1 ELSE 0 END),0) AS high_count "
                        + "FROM word_learning_progress WHERE user_id=? AND card_type='EN_ZH'",
                (rs, rowNum) -> new StudyReviewCountVO(rs.getInt("once_count"), rs.getInt("twice_count"),
                        rs.getInt("middle_count"), rs.getInt("high_count")), userId);
    }

    /**
     * 查询熟悉度统计所需的卡片行，可按词书过滤，并带出最近一次评分
     * @param userId 用户 ID
     * @param bookId 词书 ID，null 表示全部词书
     * @return 熟悉度统计行
     */
    public List<FamiliarityRow> familiarityRows(long userId, Long bookId) {
        String sql = "SELECT p.word_id,d.word,d.meaning,p.state,p.card_json,p.due_at,p.lapse_count,"
                + ACTIVE_BOOK_WORD + " AS in_study_plan,"
                + "(SELECT r.rating FROM word_review_record r WHERE r.progress_id=p.id "
                + "ORDER BY r.id DESC LIMIT 1) AS last_rating "
                + "FROM word_learning_progress p JOIN word_dictionary d ON d.id=p.word_id "
                + "WHERE p.user_id=? AND p.card_type='EN_ZH'";
        if (bookId != null) {
            sql += " AND EXISTS (SELECT 1 FROM word_book_dictionary_relation rel "
                    + "WHERE rel.word_id=p.word_id AND rel.word_book_id=?)";
        }
        sql += " ORDER BY p.due_at,p.id";
        return jdbc.query(sql, (rs, rowNum) -> new FamiliarityRow(
                rs.getLong("word_id"), rs.getString("word"), rs.getString("meaning"),
                rs.getString("state"), rs.getString("card_json"), utc(rs, "due_at"),
                rs.getInt("lapse_count"), rs.getObject("last_rating", Integer.class),
                rs.getBoolean("in_study_plan")),
                bookId == null ? new Object[]{userId} : new Object[]{userId, bookId});
    }

    /**
     * 统计区间内到期卡的作答次数：评分前状态为复习态且已到期
     * @param userId 用户 ID
     * @param start 区间起点
     * @param end 区间终点
     * @return 作答次数
     */
    public int countDueAttempts(long userId, Instant start, Instant end) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_review_record "
                + "WHERE user_id=? AND reviewed_at>=? AND reviewed_at<? AND due_before IS NOT NULL "
                + "AND due_before<=reviewed_at AND (SELECT previous_review.state_after "
                + "FROM word_review_record previous_review "
                + "WHERE previous_review.progress_id=word_review_record.progress_id "
                + "AND previous_review.id<word_review_record.id "
                + "ORDER BY previous_review.id DESC LIMIT 1)='REVIEW'",
                Integer.class, userId, dbTime(start), dbTime(end));
    }

    /**
     * 统计区间内到期卡回忆成功次数（评分大于忘记）
     * @param userId 用户 ID
     * @param start 区间起点
     * @param end 区间终点
     * @return 成功次数
     */
    public int countDueSuccesses(long userId, Instant start, Instant end) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_review_record "
                + "WHERE user_id=? AND reviewed_at>=? AND reviewed_at<? AND due_before IS NOT NULL "
                + "AND due_before<=reviewed_at AND rating>1 AND (SELECT previous_review.state_after "
                + "FROM word_review_record previous_review "
                + "WHERE previous_review.progress_id=word_review_record.progress_id "
                + "AND previous_review.id<word_review_record.id "
                + "ORDER BY previous_review.id DESC LIMIT 1)='REVIEW'",
                Integer.class,
                userId, dbTime(start), dbTime(end));
    }

    /**
     * 判断词书是否可访问：系统词书所有人可访问，自建词书仅创建者可访问
     * @param userId 用户 ID
     * @param bookId 词书 ID
     * @return 是否可访问
     */
    public boolean accessibleBook(long userId, long bookId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM word_book WHERE id=? AND is_deleted=0 "
                + "AND ((book_type='SYSTEM' AND publish_status='PUBLISHED') OR user_id=?)", Integer.class, bookId, userId);
        return count != null && count > 0;
    }

    /**
     * 设置词书是否纳入学习计划，无记录时插入
     * @param userId 用户 ID
     * @param bookId 词书 ID
     * @param enabled 是否启用
     */
    public void setBookEnabled(long userId, long bookId, boolean enabled) {
        jdbc.update("INSERT INTO user_study_book (user_id,book_id,enabled) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)", userId, bookId, enabled);
    }

    /**
     * 查询词书是否已纳入学习计划
     * @param userId 用户 ID
     * @param bookId 词书 ID
     * @return 是否启用
     */
    public boolean bookEnabled(long userId, long bookId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_study_book "
                + "WHERE user_id=? AND book_id=? AND enabled=1", Integer.class, userId, bookId);
        return count != null && count > 0;
    }

    /**
     * 统计用户在指定词书中已学的词数，用于计算学习覆盖率
     * @param userId 用户 ID
     * @param bookId 词书 ID
     * @return 已学词数
     */
    public int bookLearnedCount(long userId, long bookId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM word_book_dictionary_relation r "
                + "JOIN word_learning_progress p ON p.word_id=r.word_id AND p.user_id=? AND p.card_type='EN_ZH' "
                + "WHERE r.word_book_id=?", Integer.class, userId, bookId);
    }
}
