package com.hope.xueling.common.service;

import com.hope.xueling.common.domain.dto.CreateFeedbackDTO;
import com.hope.xueling.common.domain.entity.Feedback;
import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ForbiddenException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.common.mapper.FeedbackMapper;
import com.hope.xueling.common.security.AdminAccessPolicy;
import com.hope.xueling.common.service.impl.FeedbackServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackServiceImplTest {
    private FakeMapper fake;
    private FeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        fake = new FakeMapper();
        FeedbackMapper mapper = (FeedbackMapper) Proxy.newProxyInstance(
                FeedbackMapper.class.getClassLoader(), new Class<?>[]{FeedbackMapper.class}, fake);
        service = new FeedbackServiceImpl(mapper, new AdminAccessPolicy("42, 84"));
    }

    @Test
    void onlyConfiguredAdminsMaySeeManagementList() {
        assertTrue(service.isAdmin(42));
        assertFalse(service.isAdmin(4));
        assertThrows(ForbiddenException.class, () -> service.listForAdmin(4, null, 1));
        assertEquals(0, fake.calls);
    }

    @Test
    void userCannotReadAnotherUsersFeedback() {
        Feedback feedback = new Feedback();
        feedback.setId(100L);
        feedback.setUserId(9L);
        fake.byId = feedback;
        assertThrows(BusinessException.class, () -> service.findMine(8L, 100L));
    }

    @Test
    void retryWithSameRequestIdReturnsExistingFeedbackWithoutAnotherInsert() {
        Feedback existing = new Feedback();
        existing.setId(100L);
        existing.setUserId(8L);
        fake.existing = existing;
        assertEquals("100", service.create(8L, request()).getId());
        assertEquals(0, fake.inserts);
    }

    @Test
    void validatesContentAndRateLimitBeforeInsert() {
        CreateFeedbackDTO request = request();
        request.setContent("短");
        assertThrows(ValidationException.class, () -> service.create(8L, request));
        request.setContent("希望这里可以提供更清楚的提示");
        fake.recentCount = 5;
        assertThrows(BusinessException.class, () -> service.create(8L, request));
        assertEquals(0, fake.inserts);
    }

    @Test
    void translationFeedbackUsesVerifiedDictionaryIdentity() {
        CreateFeedbackDTO request = request();
        request.setCategory("TRANSLATION");
        request.setSubcategory("meaning");
        request.setWordId("101");
        request.setContextWord("untrusted spelling");
        request.setObservedContent("错误释义");
        fake.wordName = "apple";
        service.create(8L, request);
        assertEquals(101L, fake.saved.getWordId());
        assertEquals("apple", fake.saved.getContextWord());
        assertEquals("错误释义", fake.saved.getObservedContent());

        fake.wordName = null;
        request.setRequestId(UUID.randomUUID().toString());
        assertThrows(ValidationException.class, () -> service.create(8L, request));
    }

    private static CreateFeedbackDTO request() {
        CreateFeedbackDTO request = new CreateFeedbackDTO();
        request.setRequestId(UUID.randomUUID().toString());
        request.setCategory("FEATURE_IDEA");
        request.setContent("希望这里可以提供更清楚的提示");
        return request;
    }

    private static class FakeMapper implements java.lang.reflect.InvocationHandler {
        Feedback existing;
        Feedback byId;
        int recentCount;
        int inserts;
        int calls;
        String wordName;
        Feedback saved;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            calls++;
            return switch (method.getName()) {
                case "selectOne" -> existing;
                case "selectById" -> byId;
                case "countRecent" -> recentCount;
                case "findWord" -> wordName;
                case "insert" -> { inserts++; saved = (Feedback) args[0]; yield 1; }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
