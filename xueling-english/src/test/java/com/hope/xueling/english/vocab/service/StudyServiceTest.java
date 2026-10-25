package com.hope.xueling.english.vocab.service;

import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.english.vocab.domain.dto.SubmitStudyReviewDTO;
import com.hope.xueling.english.vocab.domain.vo.StudyQueueCardVO;
import com.hope.xueling.english.vocab.domain.vo.StudyReviewResultVO;
import com.hope.xueling.english.vocab.domain.vo.StudySettingsVO;
import com.hope.xueling.english.vocab.mapper.StudyMapper;
import com.hope.xueling.english.vocab.service.impl.FsrsReviewScheduler;
import com.hope.xueling.english.vocab.service.impl.StudyServiceImpl;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StudyServiceTest {
    private static final long USER_ID = 7L;
    private final FakeStudyMapper mapper = new FakeStudyMapper();
    private final StudyServiceImpl service = new StudyServiceImpl(mapper, new FsrsReviewScheduler());

    private static class FakeStudyMapper extends StudyMapper {
        StudySettingsVO settings = new StudySettingsVO("Asia/Shanghai", 15, 0.9);
        PreviousReview previous;
        boolean eligible = true;
        int dueCount;
        int introduced;
        LocalDate introducedDate;
        int dueQueries;
        int newQueries;
        Instant dayStart;
        Instant dayEnd;
        boolean progressQueried;
        ProgressRow savedProgress;
        int insertedCheckIns;
        Instant nextDue;
        boolean moreNewCards;
        List<LocalDate> checkedDates = List.of();
        List<HistoryReviewRow> historyRows = List.of();
        List<FamiliarityRow> familiarityRows = List.of();
        Long selectedBookId;

        FakeStudyMapper() { super(null); }
        @Override public void ensureSettings(long userId) {}
        @Override public void ensureSettingsForUpdate(long userId) {}
        @Override public boolean hasCheckIn(long userId, LocalDate date) { return checkedDates.contains(date); }
        @Override public List<LocalDate> checkInDates(long userId) { return checkedDates; }
        @Override public StudySettingsVO settings(long userId, boolean lock) { return settings; }
        @Override public PreviousReview reviewByRequestId(long userId, String requestId) { return previous; }
        @Override public ProgressRow progressForUpdate(long userId, long wordId) {
            progressQueried = true;
            return savedProgress;
        }
        @Override public void insertProgress(long userId, long wordId, String cardJson, String state,
                                             Instant dueAt, Instant reviewedAt, LocalDate date,
                                             String schedulerVersion, int lapseCount) {
            savedProgress = new ProgressRow(1L, cardJson, state, dueAt, 1, 1, lapseCount);
            introduced++;
        }
        @Override public void insertReview(long progressId, long userId, long wordId, int rating,
                                           Instant reviewedAt, Instant dueBefore, Instant dueAfter,
                                           Integer elapsedMs, String schedulerVersion, double retention,
                                           String stateAfter, int versionAfter, String requestId) {}
        @Override public void insertCheckIn(long userId, LocalDate date, Instant checkedInAt) {
            insertedCheckIns++;
            checkedDates = List.of(date);
        }
        @Override public boolean eligibleNewWord(long userId, long wordId) { return eligible; }
        @Override public int countDue(long userId, Instant now) { return dueCount; }
        @Override public int countIntroduced(long userId, LocalDate date) {
            introducedDate = date;
            return introduced;
        }
        @Override public int countTodayReviews(long userId, Instant start, Instant end) {
            dayStart = start;
            dayEnd = end;
            return 0;
        }
        @Override public int countDueAttempts(long userId, Instant start, Instant end) { return 0; }
        @Override public int countDueSuccesses(long userId, Instant start, Instant end) { return 0; }
        @Override public int countLearned(long userId) { return 0; }
        @Override public Instant nextDue(long userId, Instant now) { return nextDue; }
        @Override public List<HistoryReviewRow> historyReviews(long userId, Instant start, Instant end) {
            return historyRows;
        }
        @Override public int dueBacklogAt(long userId, Instant cutoff) { return 0; }
        @Override public boolean accessibleBook(long userId, long bookId) { return bookId == 12L; }
        @Override public List<FamiliarityRow> familiarityRows(long userId, Long bookId) {
            selectedBookId = bookId;
            return familiarityRows;
        }
        @Override public List<StudyQueueCardVO> dueCards(long userId, Instant now, int limit) {
            dueQueries++;
            return List.of();
        }
        @Override public List<StudyQueueCardVO> newCards(long userId, int limit) {
            newQueries++;
            return moreNewCards ? List.of(new StudyQueueCardVO()) : List.of();
        }
    }

    private SubmitStudyReviewDTO review() {
        SubmitStudyReviewDTO request = new SubmitStudyReviewDTO();
        request.setWordId("123");
        request.setRating(3);
        request.setExpectedVersion(0);
        request.setRequestId(UUID.randomUUID().toString());
        return request;
    }

    @Test
    void retryWithSameRequestIdReturnsOriginalResultWithoutSchedulingAgain() {
        StudyReviewResultVO result = new StudyReviewResultVO("123", "LEARNING",
                Instant.parse("2026-10-20T02:10:00Z"), 1);
        mapper.previous = new StudyMapper.PreviousReview(123L, 3, result);

        assertSame(result, service.submitReview(USER_ID, review()));
        assertFalse(mapper.progressQueried);
    }

    @Test
    void dailyNewLimitRejectsAnotherNewCard() {
        mapper.introduced = 15;

        assertThrows(BusinessException.class, () -> service.submitReview(USER_ID, review()));
    }

    @Test
    void pausedBookRejectsAStaleReviewWithoutChangingProgress() {
        mapper.eligible = false;
        mapper.savedProgress = new StudyMapper.ProgressRow(1L, "{}", "REVIEW",
                Instant.EPOCH, 1, 1, 0);
        SubmitStudyReviewDTO request = review();
        request.setExpectedVersion(1);

        assertThrows(BusinessException.class, () -> service.submitReview(USER_ID, request));
        assertEquals(1, mapper.savedProgress.version());
    }

    @Test
    void recallRateHasNoValueWithoutFormalDueReviews() {
        var stats = service.stats(USER_ID);

        assertEquals(0, stats.getDueRecallAttempts());
        assertNull(stats.getDueRecallRate());
    }

    @Test
    void overdueCardsComeBeforeNewWordsAndBacklogPausesNewWords() {
        Instant instant = Instant.parse("2026-10-20T08:00:00Z");
        mapper.dueCount = 30;
        StudyServiceImpl fixedService = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(instant, ZoneOffset.UTC));

        var today = fixedService.today(USER_ID);

        assertEquals(30, today.getDueCount());
        assertEquals(0, today.getNewRemaining());
        assertEquals(1, mapper.dueQueries);
        assertEquals(0, mapper.newQueries);
    }

    @Test
    void userTimeZoneSetsDailyBoundaryAndNewWordAllowance() {
        Instant beforeMidnight = Instant.parse("2026-10-20T15:59:59Z");
        Instant afterMidnight = Instant.parse("2026-10-20T16:00:00Z");
        mapper.introduced = 15;
        var before = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(beforeMidnight, ZoneOffset.UTC)).today(USER_ID);
        assertEquals(LocalDate.of(2026, 10, 20), mapper.introducedDate);
        assertEquals(Instant.parse("2026-10-19T16:00:00Z"), mapper.dayStart);
        assertEquals(afterMidnight, mapper.dayEnd);

        mapper.introduced = 0;
        var after = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(afterMidnight, ZoneOffset.UTC)).today(USER_ID);
        assertEquals(LocalDate.of(2026, 10, 21), mapper.introducedDate);
        assertEquals(afterMidnight, mapper.dayStart);
        assertEquals(Instant.parse("2026-10-21T16:00:00Z"), mapper.dayEnd);
        assertEquals(0, before.getNewRemaining());
        assertEquals(15, after.getNewRemaining());
        assertEquals(1, mapper.newQueries);
    }

    @Test
    void lastSavedRatingAutomaticallyChecksInUsingUsersStudyDate() {
        Instant instant = Instant.parse("2026-10-20T16:30:00Z");
        mapper.settings = new StudySettingsVO("Asia/Shanghai", 1, 0.9);
        var fixedService = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(instant, ZoneOffset.UTC));

        fixedService.submitReview(USER_ID, review());

        assertEquals(1, mapper.insertedCheckIns);
        assertEquals(List.of(LocalDate.of(2026, 10, 21)), mapper.checkedDates);
        assertTrue(fixedService.today(USER_ID).isCheckedIn());
    }

    @Test
    void aRatingDoesNotCheckInWhileAnotherNewCardIsAvailable() {
        mapper.settings = new StudySettingsVO("Asia/Shanghai", 2, 0.9);
        mapper.moreNewCards = true;

        service.submitReview(USER_ID, review());

        assertEquals(0, mapper.insertedCheckIns);
    }

    @Test
    void aRatingWaitsForAnotherCardDueLaterTodayBeforeCheckingIn() {
        Instant instant = Instant.parse("2026-10-20T12:00:00Z");
        mapper.settings = new StudySettingsVO("Asia/Shanghai", 1, 0.9);
        mapper.nextDue = instant.plusSeconds(600);
        var fixedService = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(instant, ZoneOffset.UTC));

        fixedService.submitReview(USER_ID, review());

        assertEquals(0, mapper.insertedCheckIns);
    }

    @Test
    void calendarReportsStreakAcrossMonthBoundaryAndKeepsLearningSeparate() {
        Instant instant = Instant.parse("2026-10-20T12:00:00Z");
        mapper.checkedDates = List.of(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19));
        mapper.historyRows = List.of(new StudyMapper.HistoryReviewRow(instant.minusSeconds(600),
                null, null, 3, 1200));
        var fixedService = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(instant, ZoneOffset.UTC));

        var calendar = fixedService.calendar(USER_ID, "2026-10");

        assertEquals(2, calendar.currentStreak());
        assertEquals(2, calendar.longestStreak());
        assertEquals(4, calendar.totalCheckIns());
        assertFalse(calendar.checkedInToday());
        assertEquals(1, calendar.days().get(19).reviewCount());
        assertFalse(calendar.days().get(19).checkedIn());
        assertTrue(calendar.days().get(18).checkedIn());
    }

    @Test
    void historyCountsFormalDueRecallSeparatelyFromShortTermLearning() {
        Instant instant = Instant.parse("2026-10-20T12:00:00Z");
        mapper.dueCount = 5;
        mapper.historyRows = List.of(
                new StudyMapper.HistoryReviewRow(instant.minusSeconds(3600),
                        instant.minusSeconds(7200), "REVIEW", 1, 2000),
                new StudyMapper.HistoryReviewRow(instant.minusSeconds(1800),
                        instant.minusSeconds(1900), "LEARNING", 3, 3000),
                new StudyMapper.HistoryReviewRow(instant.minusSeconds(900),
                        null, null, 3, 1000),
                new StudyMapper.HistoryReviewRow(instant.minusSeconds(600),
                        instant.minusSeconds(700), "REVIEW", 2, 1000),
                new StudyMapper.HistoryReviewRow(instant.minusSeconds(300),
                        instant.minusSeconds(400), "REVIEW", 3, 1000));
        StudyServiceImpl fixedService = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(instant, ZoneOffset.UTC));

        var history = fixedService.history(USER_ID, 7);
        var today = history.getLast();

        assertEquals(7, history.size());
        assertEquals(LocalDate.of(2026, 10, 20), today.date());
        assertEquals(5, today.reviewCount());
        assertEquals(1, today.newWords());
        assertEquals(8000, today.studyTimeMs());
        assertEquals(3, today.dueRecallAttempts());
        assertEquals(2.0 / 3.0, today.dueRecallRate());
        assertEquals(1.0 / 3.0, today.goodOrEasyRate());
        assertEquals(5, today.dueBacklog());
    }

    @Test
    void familiaritySeparatesMemoryStageFromDueStatusAndFiltersBook() {
        Instant instant = Instant.parse("2026-10-20T12:00:00Z");
        String learning = Card.builder().state(State.LEARNING).due(instant.plusSeconds(600)).build().toJson();
        String consolidating = Card.builder().state(State.REVIEW).stability(7.0)
                .due(instant.minusSeconds(60)).build().toJson();
        String stable = Card.builder().state(State.REVIEW).stability(45.0)
                .due(instant.minusSeconds(60)).build().toJson();
        mapper.familiarityRows = List.of(
                new StudyMapper.FamiliarityRow(1, "one", "一", "LEARNING", learning, instant.plusSeconds(600), 0, 3, true),
                new StudyMapper.FamiliarityRow(2, "two", "二", "REVIEW", consolidating, instant.minusSeconds(60), 2, 1, true),
                new StudyMapper.FamiliarityRow(3, "three", "三", "REVIEW", stable, instant.minusSeconds(60), 0, 4, false));
        var fixedService = new StudyServiceImpl(mapper, new FsrsReviewScheduler(),
                Clock.fixed(instant, ZoneOffset.UTC));

        var result = fixedService.familiarity(USER_ID, 12L, "STABLE", 1);
        assertEquals(12L, mapper.selectedBookId);
        assertEquals(3, result.learnedCount());
        assertEquals(1, result.learningCount());
        assertEquals(1, result.consolidatingCount());
        assertEquals(1, result.stableCount());
        assertEquals(1, result.dueCount());
        assertEquals("three", result.words().getFirst().word());
        assertFalse(result.words().getFirst().inStudyPlan());
        assertFalse(result.words().getFirst().due());
        assertEquals("two", result.attentionWords().getFirst().word());
        assertThrows(com.hope.xueling.common.exception.ValidationException.class,
                () -> fixedService.familiarity(USER_ID, 99L, "ALL", 1));
    }
}
