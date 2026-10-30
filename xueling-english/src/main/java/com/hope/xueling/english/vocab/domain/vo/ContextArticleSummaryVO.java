package com.hope.xueling.english.vocab.domain.vo;

import java.time.LocalDateTime;

public record ContextArticleSummaryVO(String id, String title, String topic, int wordCount, LocalDateTime createTime) {}
