package com.hope.xueling.english.vocab.controller;

import com.hope.xueling.common.domain.vo.Result;
import com.hope.xueling.common.util.ThreadLocalUtils;
import com.hope.xueling.english.vocab.domain.dto.GenerateContextArticleDTO;
import com.hope.xueling.english.vocab.domain.dto.SubmitContextAttemptDTO;
import com.hope.xueling.english.vocab.domain.vo.ContextArticleSummaryVO;
import com.hope.xueling.english.vocab.domain.vo.ContextArticleVO;
import com.hope.xueling.english.vocab.domain.vo.ContextAttemptVO;
import com.hope.xueling.english.vocab.service.IContextArticleService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/vocab-context/articles")
public class ContextArticleController {
    private final IContextArticleService service;

    private long userId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }

    @PostMapping
    public Result<ContextArticleVO> generate(@RequestBody GenerateContextArticleDTO request) {
        return Result.success(service.generate(userId(), request));
    }

    @GetMapping
    public Result<List<ContextArticleSummaryVO>> list() {
        return Result.success(service.list(userId()));
    }

    @GetMapping("/{id}")
    public Result<ContextArticleVO> get(@PathVariable long id) {
        return Result.success(service.get(userId(), id));
    }

    @PostMapping("/{id}/attempts")
    public Result<ContextAttemptVO> submitAttempt(@PathVariable long id, @RequestBody SubmitContextAttemptDTO request) {
        return Result.success(service.submitAttempt(userId(), id, request));
    }
}
