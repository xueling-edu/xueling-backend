package com.hope.xueling.english.vocab.service;

import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.english.vocab.mapper.StudyMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StudyFollowupServiceTest {
    private static final String REQUEST = UUID.randomUUID().toString();

    private static class FakeMapper extends StudyMapper {
        FollowupRow row;
        String teaching;
        String revisit;
        FakeMapper() { super(null); }
        @Override public List<FollowupRow> pendingFollowups(long userId) {
            return row == null || revisit != null ? List.of() : List.of(row);
        }
        @Override public String followupActionResult(long userId, String requestId, String action) {
            return "teaching".equals(action) ? teaching : revisit;
        }
        @Override public int saveFollowupAction(long userId, String requestId, String action,
                                                String result, Instant at) {
            if ("teaching".equals(action)) teaching = result;
            else revisit = result;
            return 1;
        }
    }

    private static StudyMapper.FollowupRow row(int laterCards, Instant dueAt, String teaching) {
        return new StudyMapper.FollowupRow(REQUEST, 1, true, laterCards, dueAt, teaching,
                "17", "example", "例子", null, null, null,
                null, null, null, null, null, null, null, null, "dictionary-1");
    }

    @Test
    void revisitRequiresTeachingSpacingAndAFutureOfficialDueDate() {
        FakeMapper mapper = new FakeMapper();
        StudyFollowupService service = new StudyFollowupService(mapper);
        mapper.row = row(1, Instant.now().plusSeconds(3600), "done");
        assertThrows(BusinessException.class, () -> service.record(7, REQUEST, "revisit", "revisit_pass"));
        mapper.row = row(2, Instant.now().minusSeconds(1), "done");
        assertThrows(BusinessException.class, () -> service.record(7, REQUEST, "revisit", "revisit_pass"));
        mapper.row = row(2, Instant.now().plusSeconds(3600), null);
        assertThrows(BusinessException.class, () -> service.record(7, REQUEST, "revisit", "revisit_pass"));
        mapper.row = row(2, Instant.now().plusSeconds(3600), "done");
        service.record(7, REQUEST, "revisit", "revisit_pass");
        assertEquals("revisit_pass", mapper.revisit);
        service.record(7, REQUEST, "revisit", "revisit_pass"); // idempotent retry
    }

    @Test
    void teachingIsRecordedWithoutChangingTheFirstRating() {
        FakeMapper mapper = new FakeMapper();
        mapper.row = row(0, Instant.now().plusSeconds(3600), null);
        StudyFollowupService service = new StudyFollowupService(mapper);
        service.record(7, REQUEST, "teaching", "context_fail");
        assertEquals("context_fail", mapper.teaching);
        assertNull(mapper.revisit);
        assertThrows(BusinessException.class, () -> service.record(7, REQUEST, "teaching", "context_pass"));
    }
}
