package com.hope.xueling.english.vocab.service.impl;

import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.english.vocab.domain.dto.SubmitStudyReviewDTO;
import com.hope.xueling.english.vocab.domain.dto.UpdateStudySettingsDTO;
import com.hope.xueling.english.vocab.domain.vo.*;
import com.hope.xueling.english.vocab.mapper.StudyMapper;
import com.hope.xueling.english.vocab.service.IStudyService;
import com.hope.xueling.english.vocab.service.ReviewScheduler;
import io.github.openspacedrepetition.Card;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;

/**
 * 学习计划服务实现：以“用户 × 单词 × 英文到中文”的卡片为单位，负责今日队列、评分排程、学习统计与设置。
 * <p>关键规则：到期卡优先于新词；到期数达到 30 时暂停引入新词；同一用户的写入通过锁定设置行串行化；
 * 评分按 requestId 幂等、按卡片版本号做并发校验；当日到期清零且新词额度用尽时自动打卡。</p>
 * @author 谢光湘
 * @since 2026/10/24
 */
@Service
public class StudyServiceImpl implements IStudyService {
    /**
     * 单次返回的队列卡片数与分页大小
     */
    private static final int PAGE_SIZE = 20;
    /**
     * 学习相关的数据访问
     */
    private final StudyMapper studyMapper;
    /**
     * 复习排程算法
     */
    private final ReviewScheduler reviewScheduler;
    /**
     * 时钟，测试中可替换为固定时钟以验证跨天逻辑
     */
    private final Clock clock;

    /**
     * 生产环境构造器，使用系统 UTC 时钟
     * @param studyMapper 学习相关的数据访问
     * @param reviewScheduler 复习排程算法
     */
    @Autowired
    public StudyServiceImpl(StudyMapper studyMapper, ReviewScheduler reviewScheduler) {
        this(studyMapper, reviewScheduler, Clock.systemUTC());
    }

    public StudyServiceImpl(StudyMapper studyMapper, ReviewScheduler reviewScheduler, Clock clock) {
        this.studyMapper = studyMapper;
        this.reviewScheduler = reviewScheduler;
        this.clock = clock;
    }

    /**
     * 用户时区下的自然日区间
     * @param date 日期
     * @param start 当日零点对应的时刻
     * @param end 次日零点对应的时刻
     */
    private record DayWindow(LocalDate date, Instant start, Instant end) {}

    /**
     * 按用户设置的时区计算当前自然日及其起止时刻
     * @param settings 学习设置，提供时区
     * @param now 当前时刻
     * @return 自然日区间
     */
    private DayWindow dayWindow(StudySettingsVO settings, Instant now) {
        ZoneId zone = ZoneId.of(settings.getTimeZone());
        LocalDate date = now.atZone(zone).toLocalDate();
        return new DayWindow(date, date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * 取当前时刻并截断到毫秒，避免数据库精度差异导致的比较问题
     * @return 当前时刻
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 组装今日学习数据：计算到期数与剩余新词额度，到期卡优先，额度用尽或到期过多时不再给新词
     * @param userId 用户 ID
     * @return 今日学习数据
     */
    @Override
    @Transactional
    public TodayStudyVO today(long userId) {
        studyMapper.ensureSettings(userId);
        StudySettingsVO settings = studyMapper.settings(userId, false);
        Instant now = now();
        DayWindow day = dayWindow(settings, now);
        int dueCount = studyMapper.countDue(userId, now);
        int introduced = studyMapper.countIntroduced(userId, day.date());
        int remaining = Math.max(0, settings.getDailyNewLimit() - introduced);
        if (dueCount >= 30) remaining = 0;
        List<StudyQueueCardVO> cards = dueCount > 0
                ? studyMapper.dueCards(userId, now, PAGE_SIZE)
                : remaining > 0 ? studyMapper.newCards(userId, Math.min(PAGE_SIZE, remaining)) : List.of();
        TodayStudyVO today = new TodayStudyVO();
        today.setDueCount(dueCount);
        today.setNewRemaining(remaining);
        today.setTodayNew(introduced);
        today.setTodayReviewed(studyMapper.countTodayReviews(userId, day.start(), day.end()));
        today.setCheckedIn(studyMapper.hasCheckIn(userId, day.date()));
        today.setNextDueAt(studyMapper.nextDue(userId, now));
        today.setCards(cards);
        return today;
    }

    /**
     * 提交一次评分：校验参数与请求编号，锁设置行后按 requestId 幂等、按版本号校验并发，再校验新词额度与到期时间，最后排程并落库；满足条件时自动打卡
     * @param userId 用户 ID
     * @param request 评分请求，包含单词 ID、评分、版本、请求编号与用时
     * @return 评分后的卡片状态、到期时间与新版本号
     */
    @Override
    @Transactional
    public StudyReviewResultVO submitReview(long userId, SubmitStudyReviewDTO request) {
        if (request == null || request.getWordId() == null || request.getRating() == null
                || request.getRating() < 1 || request.getRating() > 4
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
                || request.getRequestId() == null || request.getRequestId().length() > 64) {
            throw new ValidationException("复习参数无效");
        }
        try {
            UUID.fromString(request.getRequestId());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("请求ID必须是UUID");
        }
        if (request.getElapsedMs() != null && (request.getElapsedMs() < 0 || request.getElapsedMs() > 600_000)) {
            throw new ValidationException("用时超出范围");
        }
        long wordId;
        try {
            wordId = Long.parseLong(request.getWordId());
            if (wordId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new ValidationException("单词ID无效");
        }

        studyMapper.ensureSettingsForUpdate(userId);
        // 锁设置行，串行化同一用户的新词额度与评分提交。
        StudySettingsVO settings = studyMapper.settings(userId, true);
        StudyMapper.PreviousReview previous = studyMapper.reviewByRequestId(userId, request.getRequestId());
        if (previous != null) {
            if (previous.wordId() != wordId || previous.rating() != request.getRating()) {
                throw new ValidationException("请求ID已用于另一条评分");
            }
            return previous.result();
        }

        Instant reviewedAt = now();
        StudyMapper.ProgressRow progress = studyMapper.progressForUpdate(userId, wordId);
        int version = progress == null ? 0 : progress.version();
        if (version != request.getExpectedVersion()) {
            throw new BusinessException("卡片已在其他设备更新，请刷新今日学习");
        }
        if (!studyMapper.eligibleNewWord(userId, wordId)) {
            throw new BusinessException("该单词不在已启用词书中，请同步最新学习计划");
        }
        DayWindow day = dayWindow(settings, reviewedAt);
        if (progress == null) {
            if (studyMapper.countIntroduced(userId, day.date()) >= settings.getDailyNewLimit()) {
                throw new BusinessException("今日新词额度已用完");
            }
            if (studyMapper.countDue(userId, reviewedAt) >= 30) {
                throw new BusinessException("待复习单词较多，请先完成到期复习");
            }
        } else if (progress.dueAt().isAfter(reviewedAt)) {
            throw new BusinessException("该单词尚未到复习时间");
        }

        ReviewScheduler.ScheduledReview scheduled = reviewScheduler.review(
                progress == null ? null : progress.cardJson(), request.getRating(),
                settings.getDesiredRetention(), reviewedAt);
        int lapseCount = (progress == null ? 0 : progress.lapseCount())
                + (progress != null && "REVIEW".equals(progress.state()) && request.getRating() == 1 ? 1 : 0);
        if (progress == null) {
            studyMapper.insertProgress(userId, wordId, scheduled.cardJson(), scheduled.state(),
                    scheduled.dueAt(), reviewedAt, day.date(), reviewScheduler.version(), lapseCount);
            progress = studyMapper.progressForUpdate(userId, wordId);
        } else {
            studyMapper.updateProgress(progress.id(), scheduled.cardJson(), scheduled.state(),
                    scheduled.dueAt(), reviewedAt, progress.reviewCount() + 1, lapseCount,
                    reviewScheduler.version());
        }
        int versionAfter = version + 1;
        studyMapper.insertReview(progress.id(), userId, wordId, request.getRating(), reviewedAt,
                version == 0 ? null : progress.dueAt(), scheduled.dueAt(), request.getElapsedMs(),
                reviewScheduler.version(), settings.getDesiredRetention(), scheduled.state(),
                versionAfter, request.getRequestId());
        // 今天稍后还会到期的短时重学卡完成后，才算完成当天任务。
        if (studyMapper.countDue(userId, reviewedAt) == 0) {
            int introducedToday = studyMapper.countIntroduced(userId, day.date());
            int remaining = Math.max(0, settings.getDailyNewLimit() - introducedToday);
            if (remaining == 0 || studyMapper.newCards(userId, 1).isEmpty()) {
                Instant nextDue = studyMapper.nextDue(userId, reviewedAt);
                if (nextDue == null || !nextDue.isBefore(day.end())) {
                    studyMapper.insertCheckIn(userId, day.date(), reviewedAt);
                }
            }
        }
        return new StudyReviewResultVO(String.valueOf(wordId), scheduled.state(), scheduled.dueAt(), versionAfter);
    }

    /**
     * 按用户时区汇总指定月份的学习情况，并计算当前连续、最长连续与累计打卡天数
     * @param userId 用户 ID
     * @param month 月份，格式 yyyy-MM，为空取当前月；仅允许最近 10 年且不晚于当前月
     * @return 日历数据
     */
    @Override
    @Transactional
    public StudyCalendarVO calendar(long userId, String month) {
        studyMapper.ensureSettings(userId);
        ZoneId zone = ZoneId.of(studyMapper.settings(userId, false).getTimeZone());
        LocalDate today = now().atZone(zone).toLocalDate();
        YearMonth selected;
        try {
            selected = month == null ? YearMonth.from(today) : YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new ValidationException("月份格式应为 YYYY-MM");
        }
        if (selected.isAfter(YearMonth.from(today)) || selected.isBefore(YearMonth.from(today).minusYears(10))) {
            throw new ValidationException("月份超出可查看范围");
        }
        LocalDate first = selected.atDay(1);
        LocalDate after = selected.plusMonths(1).atDay(1);
        Map<LocalDate, DayAggregate> aggregates = new HashMap<>();
        for (StudyMapper.HistoryReviewRow row : studyMapper.historyReviews(userId,
                first.atStartOfDay(zone).toInstant(), after.atStartOfDay(zone).toInstant())) {
            LocalDate date = row.reviewedAt().atZone(zone).toLocalDate();
            DayAggregate aggregate = aggregates.computeIfAbsent(date, ignored -> new DayAggregate());
            aggregate.reviews++;
            if (row.dueBefore() == null) aggregate.newWords++;
            aggregate.elapsedMs += row.elapsedMs() == null ? 0 : row.elapsedMs();
        }
        List<LocalDate> checkedDates = studyMapper.checkInDates(userId);
        var checked = new java.util.HashSet<>(checkedDates);
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate date : checkedDates) {
            run = previous != null && previous.plusDays(1).equals(date) ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = date;
        }
        LocalDate streakEnd = checked.contains(today) ? today : today.minusDays(1);
        int current = 0;
        while (checked.contains(streakEnd.minusDays(current))) current++;
        List<StudyCalendarDayVO> days = new ArrayList<>();
        for (LocalDate date = first; date.isBefore(after); date = date.plusDays(1)) {
            DayAggregate aggregate = aggregates.getOrDefault(date, new DayAggregate());
            days.add(new StudyCalendarDayVO(date, checked.contains(date), aggregate.reviews,
                    aggregate.newWords, aggregate.elapsedMs));
        }
        return new StudyCalendarVO(selected.toString(), today, checked.contains(today), current,
                longest, checkedDates.size(), days);
    }

    /**
     * 统计今日评分次数、新词数、累计已学词数、待复习数与到期卡回忆率
     * @param userId 用户 ID
     * @return 学习统计
     */
    @Override
    @Transactional
    public StudyStatsVO stats(long userId) {
        studyMapper.ensureSettings(userId);
        StudySettingsVO settings = studyMapper.settings(userId, false);
        Instant now = now();
        DayWindow day = dayWindow(settings, now);
        int attempts = studyMapper.countDueAttempts(userId, day.start(), day.end());
        int successes = studyMapper.countDueSuccesses(userId, day.start(), day.end());
        return new StudyStatsVO(studyMapper.countTodayReviews(userId, day.start(), day.end()),
                studyMapper.countIntroduced(userId, day.date()), studyMapper.countLearned(userId),
                studyMapper.countDue(userId, now), attempts,
                attempts == 0 ? null : (double) successes / attempts);
    }

    private static class DayAggregate {
        int reviews;
        int newWords;
        long elapsedMs;
        int dueAttempts;
        int dueSuccesses;
        int goodOrEasyRecalls;
    }

    /**
     * 统计近 7 天或 30 天的每日学习数据，含到期卡回忆率、良好/容易占比与历史到期积压
     * @param userId 用户 ID
     * @param days 统计天数，仅支持 7 或 30
     * @return 按天排列的学习记录
     */
    @Override
    @Transactional
    public List<StudyDayStatsVO> history(long userId, int days) {
        if (days != 7 && days != 30) throw new ValidationException("仅支持近7天或30天");
        studyMapper.ensureSettings(userId);
        ZoneId zone = ZoneId.of(studyMapper.settings(userId, false).getTimeZone());
        Instant current = now();
        LocalDate today = current.atZone(zone).toLocalDate();
        LocalDate first = today.minusDays(days - 1L);
        Instant start = first.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        Map<LocalDate, DayAggregate> aggregates = new HashMap<>();
        for (StudyMapper.HistoryReviewRow row : studyMapper.historyReviews(userId, start, end)) {
            LocalDate date = row.reviewedAt().atZone(zone).toLocalDate();
            DayAggregate aggregate = aggregates.computeIfAbsent(date, ignored -> new DayAggregate());
            aggregate.reviews++;
            if (row.dueBefore() == null) aggregate.newWords++;
            aggregate.elapsedMs += row.elapsedMs() == null ? 0 : row.elapsedMs();
            if ("REVIEW".equals(row.stateBefore()) && row.dueBefore() != null
                    && !row.dueBefore().isAfter(row.reviewedAt())) {
                aggregate.dueAttempts++;
                if (row.rating() > 1) aggregate.dueSuccesses++;
                if (row.rating() >= 3) aggregate.goodOrEasyRecalls++;
            }
        }
        List<StudyDayStatsVO> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = first.plusDays(i);
            DayAggregate aggregate = aggregates.getOrDefault(date, new DayAggregate());
            Instant cutoff = date.equals(today) ? current : date.plusDays(1).atStartOfDay(zone).toInstant();
            int backlog = date.equals(today) ? studyMapper.countDue(userId, current)
                    : studyMapper.dueBacklogAt(userId, cutoff);
            result.add(new StudyDayStatsVO(date, aggregate.reviews, aggregate.newWords,
                    aggregate.elapsedMs, aggregate.dueAttempts,
                    aggregate.dueAttempts == 0 ? null : (double) aggregate.dueSuccesses / aggregate.dueAttempts,
                    aggregate.dueAttempts == 0 ? null : (double) aggregate.goodOrEasyRecalls / aggregate.dueAttempts,
                    backlog));
        }
        return result;
    }

    /**
     * 按复习次数分档统计词数
     * @param userId 用户 ID
     * @return 复习次数分布
     */
    @Override
    @Transactional
    public StudyReviewCountVO reviewCounts(long userId) {
        studyMapper.ensureSettings(userId);
        return studyMapper.reviewCounts(userId);
    }

    /**
     * 计算熟悉度分布：复习态且稳定度不低于 30 天记为已稳定，否则为巩固中，非复习态为学习中；同时给出需要关注的词
     * @param userId 用户 ID
     * @param bookId 词书 ID，为空表示全部词书
     * @param stage 掌握阶段过滤：ALL、LEARNING、CONSOLIDATING、STABLE
     * @param page 页码，从 1 开始
     * @return 熟悉度分布与分页明细
     */
    @Override
    @Transactional
    public StudyFamiliarityVO familiarity(long userId, Long bookId, String stage, int page) {
        if (page < 1 || page > 100_000 || !List.of("ALL", "LEARNING", "CONSOLIDATING", "STABLE").contains(stage)) {
            throw new ValidationException("熟悉程度筛选参数无效");
        }
        if (bookId != null && (bookId <= 0 || !studyMapper.accessibleBook(userId, bookId))) {
            throw new ValidationException("词书不存在或无权访问");
        }
        studyMapper.ensureSettings(userId);
        Instant current = now();
        List<StudyFamiliarityWordVO> allWords = new ArrayList<>();
        int learning = 0;
        int consolidating = 0;
        int stable = 0;
        int due = 0;
        for (StudyMapper.FamiliarityRow row : studyMapper.familiarityRows(userId, bookId)) {
            Double stabilityDays = null;
            String wordStage = "LEARNING";
            if ("REVIEW".equals(row.state())) {
                stabilityDays = Card.fromJson(row.cardJson()).getStability();
                wordStage = stabilityDays != null && stabilityDays >= 30 ? "STABLE" : "CONSOLIDATING";
            }
            boolean isDue = row.inStudyPlan() && !row.dueAt().isAfter(current);
            if (isDue) due++;
            switch (wordStage) {
                case "LEARNING" -> learning++;
                case "CONSOLIDATING" -> consolidating++;
                default -> stable++;
            }
            allWords.add(new StudyFamiliarityWordVO(String.valueOf(row.wordId()), row.word(),
                    row.meaning(), wordStage, row.inStudyPlan(), isDue, row.dueAt(), row.lapseCount(),
                    row.lastRating(), stabilityDays));
        }
        List<StudyFamiliarityWordVO> filtered = allWords.stream()
                .filter(word -> "ALL".equals(stage) || stage.equals(word.stage()))
                .toList();
        int start = Math.min(filtered.size(), (page - 1) * PAGE_SIZE);
        int end = Math.min(filtered.size(), start + PAGE_SIZE);
        List<StudyFamiliarityWordVO> attention = allWords.stream()
                .filter(word -> word.lapseCount() >= 2 || Integer.valueOf(1).equals(word.lastRating()))
                .sorted(Comparator.comparing((StudyFamiliarityWordVO word) ->
                                !Integer.valueOf(1).equals(word.lastRating()))
                        .thenComparing(Comparator.comparingInt(StudyFamiliarityWordVO::lapseCount).reversed())
                        .thenComparing(StudyFamiliarityWordVO::dueAt))
                .limit(5).toList();
        return new StudyFamiliarityVO(allWords.size(), learning, consolidating, stable, due,
                filtered.size(), page, PAGE_SIZE, filtered.subList(start, end), attention);
    }

    /**
     * 获取学习设置，不存在时按默认值初始化
     * @param userId 用户 ID
     * @return 学习设置
     */
    @Override
    @Transactional
    public StudySettingsVO settings(long userId) {
        studyMapper.ensureSettings(userId);
        return studyMapper.settings(userId, false);
    }

    /**
     * 更新学习设置：每日新词上限 0 至 100，目标记忆率 0.8 至 0.97，时区需为合法的 IANA 标识
     * @param userId 用户 ID
     * @param request 时区、每日新词上限与目标记忆率
     * @return 更新后的学习设置
     */
    @Override
    @Transactional
    public StudySettingsVO updateSettings(long userId, UpdateStudySettingsDTO request) {
        if (request == null || request.getTimeZone() == null || request.getDailyNewLimit() == null
                || request.getDesiredRetention() == null || request.getDailyNewLimit() < 0
                || request.getDailyNewLimit() > 100 || !Double.isFinite(request.getDesiredRetention())
                || request.getDesiredRetention() < 0.8 || request.getDesiredRetention() > 0.97) {
            throw new ValidationException("学习设置无效");
        }
        try {
            ZoneId.of(request.getTimeZone());
        } catch (DateTimeException e) {
            throw new ValidationException("时区无效");
        }
        studyMapper.ensureSettingsForUpdate(userId);
        StudySettingsVO settings = new StudySettingsVO(request.getTimeZone(), request.getDailyNewLimit(),
                request.getDesiredRetention());
        studyMapper.updateSettings(userId, settings);
        return settings;
    }

    /**
     * 启用或停用某个词书参与学习计划，停用后该词书的词不再进入队列
     * @param userId 用户 ID
     * @param bookId 词书 ID
     * @param enabled 是否纳入学习计划
     */
    @Override
    @Transactional
    public void setBookEnabled(long userId, long bookId, boolean enabled) {
        if (!studyMapper.accessibleBook(userId, bookId)) {
            throw new BusinessException("词书不存在或无权访问");
        }
        studyMapper.ensureSettingsForUpdate(userId);
        studyMapper.setBookEnabled(userId, bookId, enabled);
    }
}
