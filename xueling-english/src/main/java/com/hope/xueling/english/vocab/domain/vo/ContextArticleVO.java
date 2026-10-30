package com.hope.xueling.english.vocab.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

public record ContextArticleVO(String id, String title, String content, String topic,
                               List<TargetWordVO> targets, LocalDateTime createTime) {
    public record TargetWordVO(String wordId, String word, String meaning, List<Integer> offsets) {}
}
