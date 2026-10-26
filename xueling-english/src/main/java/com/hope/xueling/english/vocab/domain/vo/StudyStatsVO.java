package com.hope.xueling.english.vocab.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 学习统计概览
 * @author 谢光湘
 * @since 2026/10/24
 */
@Data
@AllArgsConstructor
public class StudyStatsVO {
    /**
     * 今日评分次数
     */
    private int todayReviewed;
    /**
     * 今日新学词数
     */
    private int todayNew;
    /**
     * 累计已学词数
     */
    private int learnedWords;
    /**
     * 当前待复习词数
     */
    private int dueCount;
    /**
     * 到期卡作答次数（统计样本量）
     */
    private int dueRecallAttempts;
    /**
     * 到期卡回忆率，无样本时为 null
     */
    private Double dueRecallRate;
    /**
     * 今天是否已自动打卡
     */
    private boolean checkedIn;
}
