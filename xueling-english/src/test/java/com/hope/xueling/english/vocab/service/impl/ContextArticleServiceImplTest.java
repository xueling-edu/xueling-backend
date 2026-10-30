package com.hope.xueling.english.vocab.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.english.vocab.domain.dto.GenerateContextArticleDTO;
import com.hope.xueling.english.vocab.domain.dto.SubmitContextAttemptDTO;
import com.hope.xueling.english.vocab.domain.entity.ContextArticle;
import com.hope.xueling.english.vocab.domain.entity.ContextAttempt;
import com.hope.xueling.english.vocab.domain.entity.WordDictionary;
import com.hope.xueling.english.vocab.mapper.ContextArticleMapper;
import com.hope.xueling.english.vocab.mapper.ContextAttemptMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextArticleServiceImplTest {
    private final ChatClient client = mock(ChatClient.class);
    private final ContextArticleMapper mapper = mock(ContextArticleMapper.class);
    private final ContextAttemptMapper attemptMapper = mock(ContextAttemptMapper.class);
    private final ContextArticleServiceImpl service = new ContextArticleServiceImpl(client, mapper, attemptMapper, new ObjectMapper());

    private WordDictionary word(long id, String spelling, String meaning) {
        WordDictionary word = new WordDictionary();
        word.setId(id);
        word.setWord(spelling);
        word.setMeaning(meaning);
        return word;
    }

    private ContextArticle article() {
        ContextArticle article = new ContextArticle();
        article.setId(9L);
        article.setUserId(7L);
        article.setStatus("READY");
        article.setTitle("A day");
        article.setContent("Art by the river was calm.");
        article.setTopic("日常生活");
        article.setCreateTime(LocalDateTime.now());
        article.setTargetsJson("[{\"wordId\":\"1\",\"word\":\"art\",\"meaning\":\"艺术\",\"offsets\":[0]}," +
                "{\"wordId\":\"2\",\"word\":\"river\",\"meaning\":\"河流\",\"offsets\":[11]}," +
                "{\"wordId\":\"3\",\"word\":\"calm\",\"meaning\":\"平静\",\"offsets\":[21]}]");
        return article;
    }

    @Test
    void rejectsWordsOutsideFiveToEightBeforeCallingModel() {
        var request = new GenerateContextArticleDTO(UUID.randomUUID().toString(), List.of("1", "2"), "日常生活");
        assertThrows(ValidationException.class, () -> service.generate(7, request));
        verify(mapper).selectOne(any(LambdaQueryWrapper.class));
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(client, attemptMapper);
    }

    @Test
    void checksExactWordBoundariesAndOffsets() {
        List<WordDictionary> words = List.of(word(1, "art", "n. 艺术"));
        String filler = " The artist found a painting in the gallery.".repeat(15);
        assertNull(service.validate(new ContextArticleServiceImpl.GeneratedText("A day", "The artist smiled." + filler), words));

        String content = "Art can change a city." + filler;
        var targets = service.validate(new ContextArticleServiceImpl.GeneratedText("A day", content), words);
        assertNotNull(targets);
        assertEquals(List.of(0), targets.getFirst().offsets());
    }

    @Test
    void unsetTopicAddsNoTopicConstraint() {
        assertNull(service.normalizeTopic(null));
        assertNull(service.normalizeTopic("  "));
        String prompt = service.buildUserPrompt(null, "art：艺术");
        assertEquals("目标词及预期词义：\nart：艺术", prompt);
        assertFalse(prompt.contains("日常生活"));
        assertFalse(prompt.contains("null"));
        assertEquals("旅行见闻", service.normalizeTopic(" 旅行见闻 "));
        assertThrows(ValidationException.class, () -> service.normalizeTopic("其他主题"));
    }

    @Test
    void attemptIsSavedOutsideFormalReview() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(article());
        var result = service.submitAttempt(7, 9, new SubmitContextAttemptDTO(List.of("Art", "wrong", "calm")));
        assertEquals(2, result.correctCount());
        assertEquals(3, result.totalCount());
        verify(attemptMapper).insert((ContextAttempt) argThat((ContextAttempt attempt) -> attempt.getArticleId() == 9L &&
                attempt.getUserId() == 7L && attempt.getCorrectCount() == 2 && attempt.getTotalCount() == 3));
        verifyNoInteractions(client);
    }

    @Test
    void repeatedRequestReturnsSavedArticleWithoutAnotherModelCall() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(article());
        var result = service.generate(7, new GenerateContextArticleDTO(UUID.randomUUID().toString(), List.of(), "ignored"));
        assertEquals("9", result.id());
        verifyNoInteractions(client, attemptMapper);
        verify(mapper, never()).insert(any(ContextArticle.class));
    }
}
