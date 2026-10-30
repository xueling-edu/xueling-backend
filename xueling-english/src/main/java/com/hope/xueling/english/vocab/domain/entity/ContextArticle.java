package com.hope.xueling.english.vocab.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("vocab_context_article")
public class ContextArticle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String requestId;
    private String status;
    private String title;
    private String content;
    private String targetsJson;
    private String topic;
    private String modelName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
