package com.hope.xueling.english.vocab.domain.vo;

import lombok.Data;

import java.time.Instant;

/**
 * 学习队列中的一张卡片，对应一个用户的一个单词（英文到中文）
 * @author 谢光湘
 * @since 2026/10/24
 */
@Data
public class StudyQueueCardVO {
    /**
     * 单词 ID（字符串，避免前端精度丢失）
     */
    private String wordId;
    /**
     * 单词
     */
    private String word;
    /**
     * 音标
     */
    private String phonetic;
    /**
     * 释义（显示答案后展示）
     */
    private String meaning;
    /**
     * 例句
     */
    private String example;
    /**
     * 例句翻译
     */
    private String exampleTranslation;
    /** Reviewed content for this one meaning; absent fields trigger a plain explanation. */
    private String coreMeaning;
    private String contextSentence;
    private String contextTranslation;
    private String contextQuestion;
    private String optionA;
    private String optionB;
    private String correctOption;
    private String clueExplanation;
    private String contentVersion;
    /**
     * 配图地址
     */
    private String imageUrl;
    /**
     * 卡片状态：NEW 新卡、LEARNING 学习中、REVIEW 复习中
     */
    private String state;
    /**
     * 下次到期时间
     */
    private Instant dueAt;
    /**
     * 卡片版本号，提交评分时需回传用于并发校验
     */
    private int version;
    /**
     * 本次是否为首次学习的新卡
     */
    private boolean newCard;
}
