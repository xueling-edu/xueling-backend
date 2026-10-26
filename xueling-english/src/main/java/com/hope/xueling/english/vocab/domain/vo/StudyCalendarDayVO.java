package com.hope.xueling.english.vocab.domain.vo;

import java.time.LocalDate;

/**
 * 学习日历中的单日记录
 * @param date 日期（用户时区）
 * @param checkedIn 当日是否已完成当前可学任务并写入打卡记录
 * @param reviewCount 当日评分次数
 * @param newWords 当日首次学习的词数
 * @param studyTimeMs 当日累计学习时长（毫秒），由各次作答耗时累加得到
 * @author 谢光湘
 * @since 2026/10/24
 */
public record StudyCalendarDayVO(LocalDate date, boolean checkedIn, int reviewCount,
                                 int newWords, long studyTimeMs) {}
