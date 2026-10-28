package com.hope.xueling.english.vocab.service;

import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.english.vocab.mapper.StudyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** Teaching and short-session recall evidence. These operations never change FSRS state. */
@Service
@RequiredArgsConstructor
public class StudyFollowupService {
    private final StudyMapper mapper;
    private final Clock clock = Clock.systemUTC();

    public List<StudyMapper.FollowupRow> pending(long userId) {
        return mapper.pendingFollowups(userId);
    }

    public StudyMapper.FollowupSummary summary(long userId) {
        mapper.ensureSettings(userId);
        ZoneId zone = ZoneId.of(mapper.settings(userId, false).getTimeZone());
        LocalDate day = clock.instant().atZone(zone).toLocalDate();
        return mapper.followupSummary(userId, day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Transactional
    public void record(long userId, String requestId, String action, String result) {
        if (requestId == null || requestId.length() > 64 || action == null || result == null) {
            throw new ValidationException("巩固记录参数无效");
        }
        try { java.util.UUID.fromString(requestId); }
        catch (IllegalArgumentException e) { throw new ValidationException("请求ID必须是UUID"); }
        if (!Set.of("teaching", "revisit").contains(action)) throw new ValidationException("未知学习动作");
        if ("teaching".equals(action) && !Set.of("done", "skipped", "context_pass", "context_fail",
                "meaning_slow", "context_unsure").contains(result)) throw new ValidationException("未知教学结果");
        if ("revisit".equals(action) && !Set.of("revisit_pass", "revisit_fail", "skipped").contains(result))
            throw new ValidationException("未知回访结果");
        String previous = mapper.followupActionResult(userId, requestId, action);
        if (result.equals(previous)) return;
        if (previous != null) throw new BusinessException("这项学习动作已记录其他结果");
        StudyMapper.FollowupRow row = mapper.pendingFollowups(userId).stream()
                .filter(item -> item.requestId().equals(requestId)).findFirst()
                .orElseThrow(() -> new BusinessException("巩固任务已完成或不存在，请刷新学习计划"));
        Instant now = clock.instant();
        if ("revisit".equals(action) && (row.rating() > 2 || row.teachingResult() == null
                || row.laterCards() < 2 || !row.dueAt().isAfter(now))) {
            throw new BusinessException("请先完成教学并间隔其他单词；正式复习已到期时请使用正式卡");
        }
        if (mapper.saveFollowupAction(userId, requestId, action, result, now) == 0) {
            if (result.equals(mapper.followupActionResult(userId, requestId, action))) return;
            throw new BusinessException("这项学习动作已在其他设备完成，请刷新学习计划");
        }
    }
}
