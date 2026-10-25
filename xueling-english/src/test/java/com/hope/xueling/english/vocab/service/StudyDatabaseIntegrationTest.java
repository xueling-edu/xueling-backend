package com.hope.xueling.english.vocab.service;

import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.english.vocab.domain.dto.SubmitStudyReviewDTO;
import com.hope.xueling.english.vocab.mapper.StudyMapper;
import com.hope.xueling.english.vocab.service.impl.FsrsReviewScheduler;
import com.hope.xueling.english.vocab.service.impl.StudyServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "XUELING_STUDY_DB_TEST", matches = "true")
class StudyDatabaseIntegrationTest {
    private static String jdbcUrl() {
        String port = System.getenv().getOrDefault("XUELING_DB_PORT", "3306");
        return "jdbc:mysql://127.0.0.1:" + port + "/xueling_db?serverTimezone=Asia/Shanghai";
    }

    @Test
    void newCardIsSavedOnceAndAllFixtureRowsAreRolledBack() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(),
                System.getenv("XUELING_DB_USERNAME"), System.getenv("XUELING_DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StudyServiceImpl service = new StudyServiceImpl(new StudyMapper(jdbc), new FsrsReviewScheduler());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AtomicLong fixtureUserId = new AtomicLong();

        transaction.execute(status -> {
            jdbc.update("INSERT INTO user (password) VALUES (?)", "study-fixture-only");
            long userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            fixtureUserId.set(userId);

            var today = service.today(userId);
            assertFalse(today.getCards().isEmpty());
            assertTrue(today.getCards().getFirst().isNewCard());
            var card = today.getCards().getFirst();
            SubmitStudyReviewDTO request = new SubmitStudyReviewDTO();
            request.setWordId(card.getWordId());
            request.setRating(3);
            request.setExpectedVersion(0);
            request.setRequestId(UUID.randomUUID().toString());
            request.setElapsedMs(1200);

            var first = service.submitReview(userId, request);
            var retry = service.submitReview(userId, request);
            assertEquals(first.getDueAt(), retry.getDueAt());
            assertEquals(1, first.getVersion());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM word_review_record WHERE user_id=?", Integer.class, userId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM word_learning_progress WHERE user_id=?", Integer.class, userId));
            var history = service.history(userId, 7).getLast();
            assertEquals(1, history.reviewCount());
            assertEquals(1, history.newWords());
            assertEquals(1, service.reviewCounts(userId).once());
            var familiarity = service.familiarity(userId, null, "ALL", 1);
            assertEquals(1, familiarity.learnedCount());
            assertEquals(1, familiarity.wordCount());
            assertEquals(card.getWordId(), familiarity.words().getFirst().wordId());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id=?",
                Integer.class, fixtureUserId.get()));
    }

    @Test
    void sharedWordsAreQueuedOnceAndRecallRateExcludesShortTermLearning() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(),
                System.getenv("XUELING_DB_USERNAME"), System.getenv("XUELING_DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StudyMapper mapper = new StudyMapper(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.execute(status -> {
            jdbc.update("INSERT INTO user (password) VALUES (?)", "study-fixture-only");
            long userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            mapper.ensureSettings(userId);
            long wordId = Long.parseLong(mapper.newCards(userId, 1).getFirst().getWordId());

            jdbc.update("INSERT INTO word_book (user_id,name) VALUES (?,?)", userId, "study-fixture-book");
            long bookId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO word_book_dictionary_relation (word_book_id,word_id) VALUES (?,?)", bookId, wordId);
            mapper.setBookEnabled(userId, bookId, true);
            assertEquals(1, mapper.newCards(userId, 20).stream()
                    .filter(card -> card.getWordId().equals(String.valueOf(wordId))).count());

            SubmitStudyReviewDTO request = new SubmitStudyReviewDTO();
            request.setWordId(String.valueOf(wordId));
            request.setRating(3);
            request.setExpectedVersion(0);
            request.setRequestId(UUID.randomUUID().toString());
            new StudyServiceImpl(mapper, new FsrsReviewScheduler()).submitReview(userId, request);
            long progressId = jdbc.queryForObject("SELECT id FROM word_learning_progress WHERE user_id=? AND word_id=?",
                    Long.class, userId, wordId);
            Instant at = Instant.now();
            insertFixtureReview(jdbc, progressId, userId, wordId, 3, "LEARNING", at.plusSeconds(1), false);
            insertFixtureReview(jdbc, progressId, userId, wordId, 3, "REVIEW", at.plusSeconds(2), true);
            insertFixtureReview(jdbc, progressId, userId, wordId, 3, "REVIEW", at.plusSeconds(3), true);
            insertFixtureReview(jdbc, progressId, userId, wordId, 1, "RELEARNING", at.plusSeconds(4), true);
            insertFixtureReview(jdbc, progressId, userId, wordId, 3, "REVIEW", at.plusSeconds(5), true);
            insertFixtureReview(jdbc, progressId, userId, wordId, 2, "REVIEW", at.plusSeconds(6), true);

            assertEquals(3, mapper.countDueAttempts(userId, at, at.plusSeconds(10)));
            assertEquals(2, mapper.countDueSuccesses(userId, at, at.plusSeconds(10)));
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void dueCardFollowsEnabledBooksAndSharedWordRemainsAvailable() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(), System.getenv("XUELING_DB_USERNAME"), System.getenv("XUELING_DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StudyMapper mapper = new StudyMapper(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.execute(status -> {
            jdbc.update("INSERT INTO user (password) VALUES (?)", "study-fixture-only");
            long userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            mapper.ensureSettings(userId);
            long systemBookId = jdbc.queryForObject(
                    "SELECT id FROM word_book WHERE system_key='CET4'", Long.class);
            String wordId = mapper.newCards(userId, 1).getFirst().getWordId();

            jdbc.update("INSERT INTO word_book (user_id,name) VALUES (?,?)", userId, "shared-study-fixture");
            long personalBookId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO word_book_dictionary_relation (word_book_id,word_id) VALUES (?,?)",
                    personalBookId, Long.parseLong(wordId));
            mapper.setBookEnabled(userId, personalBookId, true);

            SubmitStudyReviewDTO request = new SubmitStudyReviewDTO();
            request.setWordId(wordId);
            request.setRating(3);
            request.setExpectedVersion(0);
            request.setRequestId(UUID.randomUUID().toString());
            new StudyServiceImpl(mapper, new FsrsReviewScheduler()).submitReview(userId, request);
            jdbc.update("UPDATE word_learning_progress SET due_at=? WHERE user_id=? AND word_id=?",
                    LocalDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC),
                    userId, Long.parseLong(wordId));

            mapper.setBookEnabled(userId, systemBookId, false);
            assertEquals(1, mapper.countDue(userId, Instant.now()));
            assertEquals(wordId, mapper.dueCards(userId, Instant.now(), 20).getFirst().getWordId());

            mapper.setBookEnabled(userId, personalBookId, false);
            assertEquals(0, mapper.countDue(userId, Instant.now()));
            assertTrue(mapper.dueCards(userId, Instant.now(), 20).isEmpty());
            mapper.setBookEnabled(userId, personalBookId, true);
            assertEquals(1, mapper.countDue(userId, Instant.now()));
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void concurrentNewCardRatingsSaveOnlyOneReview() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(),
                System.getenv("XUELING_DB_USERNAME"), System.getenv("XUELING_DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StudyMapper mapper = new StudyMapper(jdbc);
        StudyServiceImpl service = new StudyServiceImpl(mapper, new FsrsReviewScheduler());
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO user (password) VALUES (?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "study-concurrency-fixture-only");
            return statement;
        }, keyHolder);
        long userId = keyHolder.getKey().longValue();
        var executor = Executors.newFixedThreadPool(2);
        try {
            mapper.ensureSettings(userId);
            String wordId = mapper.newCards(userId, 1).getFirst().getWordId();
            CountDownLatch start = new CountDownLatch(1);
            var rate = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                SubmitStudyReviewDTO request = new SubmitStudyReviewDTO();
                request.setWordId(wordId);
                request.setRating(3);
                request.setExpectedVersion(0);
                request.setRequestId(UUID.randomUUID().toString());
                try {
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                            .execute(status -> service.submitReview(userId, request));
                    return true;
                } catch (BusinessException staleCard) {
                    return false;
                }
            };
            var first = executor.submit(rate);
            var second = executor.submit(rate);
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM word_review_record WHERE user_id=?",
                    Integer.class, userId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM word_learning_progress WHERE user_id=?",
                    Integer.class, userId));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM user WHERE id=?", userId);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE id=?", Integer.class, userId));
    }

    private static void insertFixtureReview(JdbcTemplate jdbc, long progressId, long userId,
                                            long wordId, int rating, String stateAfter,
                                            Instant at, boolean due) {
        LocalDateTime reviewedAt = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("INSERT INTO word_review_record (progress_id,user_id,word_id,card_type,rating,reviewed_at,"
                        + "due_before,due_after,scheduler_version,desired_retention,state_after,version_after,request_id) "
                        + "VALUES (?,?,?,'EN_ZH',?,?,?,?,?,?,?,?,?)",
                progressId, userId, wordId, rating, reviewedAt,
                due ? reviewedAt.minusMinutes(1) : null, reviewedAt.plusDays(1),
                "fixture", 0.9, stateAfter, 1, UUID.randomUUID().toString());
    }
}
