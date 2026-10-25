package com.hope.xueling.english.vocab.domain.vo;

import java.time.Instant;

/**
 * 熟悉度明细中的单个词条
 * @param wordId 单词 ID（字符串，避免前端精度丢失）
 * @param word 单词
 * @param meaning 释义
 * @param stage 掌握阶段：LEARNING 学习中、CONSOLIDATING 巩固中、STABLE 已稳定
 * @param inStudyPlan 是否仍属于至少一本已启用词书
 * @param due 当前是否已到期
 * @param dueAt 下次到期时间
 * @param lapseCount 遗忘次数（复习态下评为忘记的次数）
 * @param lastRating 最近一次评分，未评分为 null
 * @param stabilityDays 当前记忆稳定度（天），未排程为 null
 * @author 谢光湘
 * @since 2026/10/24
 */
public record StudyFamiliarityWordVO(String wordId, String word, String meaning,
                                     String stage, boolean inStudyPlan, boolean due, Instant dueAt,
                                     int lapseCount, Integer lastRating, Double stabilityDays) {}
