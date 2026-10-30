package com.hope.xueling.english.vocab.service;

import com.hope.xueling.english.vocab.domain.dto.GenerateContextArticleDTO;
import com.hope.xueling.english.vocab.domain.dto.SubmitContextAttemptDTO;
import com.hope.xueling.english.vocab.domain.vo.ContextArticleSummaryVO;
import com.hope.xueling.english.vocab.domain.vo.ContextArticleVO;
import com.hope.xueling.english.vocab.domain.vo.ContextAttemptVO;

import java.util.List;

public interface IContextArticleService {
    ContextArticleVO generate(long userId, GenerateContextArticleDTO request);
    ContextArticleVO get(long userId, long articleId);
    List<ContextArticleSummaryVO> list(long userId);
    ContextAttemptVO submitAttempt(long userId, long articleId, SubmitContextAttemptDTO request);
}
