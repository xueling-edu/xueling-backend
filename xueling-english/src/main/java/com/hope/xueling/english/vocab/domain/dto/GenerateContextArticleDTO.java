package com.hope.xueling.english.vocab.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateContextArticleDTO {
    private String requestId;
    private List<String> wordIds;
    private String topic;
}
