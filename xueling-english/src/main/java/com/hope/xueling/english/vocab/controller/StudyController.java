package com.hope.xueling.english.vocab.controller;

import com.hope.xueling.common.domain.vo.Result;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.common.util.ThreadLocalUtils;
import com.hope.xueling.english.vocab.domain.dto.SubmitStudyReviewDTO;
import com.hope.xueling.english.vocab.domain.dto.UpdateStudyBookDTO;
import com.hope.xueling.english.vocab.domain.dto.UpdateStudySettingsDTO;
import com.hope.xueling.english.vocab.domain.vo.*;
import com.hope.xueling.english.vocab.service.IStudyService;
import com.hope.xueling.english.vocab.service.StudyFollowupService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import com.hope.xueling.english.vocab.mapper.StudyMapper;

/**
 * 学习计划控制器：今日学习队列、评分提交、学习统计、日历与学习设置，用户身份一律取自登录令牌
 * @author 谢光湘
 * @since 2026/10/24
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/study")
public class StudyController {
    private final IStudyService studyService;
    private final StudyFollowupService followupService;

    @GetMapping("/followups")
    public Result<List<StudyMapper.FollowupRow>> followups() {
        return Result.success(followupService.pending(userId()));
    }

    @GetMapping("/followups/summary")
    public Result<StudyMapper.FollowupSummary> followupSummary() {
        return Result.success(followupService.summary(userId()));
    }

    @PostMapping("/followups/{requestId}/actions")
    public Result<String> followupAction(@PathVariable String requestId, @RequestBody Map<String, String> action) {
        followupService.record(userId(), requestId, action.get("action"), action.get("result"));
        return Result.success("已记录");
    }

    /**
     * 从当前请求的令牌上下文中取用户 ID
     * @return 当前登录用户 ID
     */
    private long userId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }

    /**
     * 获取今日学习队列：优先返回到期复习卡，再按每日新词上限补充新词
     * @return 今日学习数据，包含队列卡片与进度统计
     */
    @GetMapping("/today")
    public Result<TodayStudyVO> today() {
        return Result.success(studyService.today(userId()));
    }

    /**
     * 提交一张卡片的四档评分，按 FSRS 计算下次到期时间并记录复习日志
     * @param request 卡片 ID、评分档位与客户端请求 ID
     * @return 本次评分后的排程结果与最新进度
     */
    @PostMapping("/reviews")
    public Result<StudyReviewResultVO> submitReview(@RequestBody SubmitStudyReviewDTO request) {
        return Result.success(studyService.submitReview(userId(), request));
    }

    /**
     * 获取学习统计概览
     * @return 学习统计
     */
    @GetMapping("/stats")
    public Result<StudyStatsVO> stats() {
        return Result.success(studyService.stats(userId()));
    }

    /**
     * 获取最近若干天的学习记录
     * @param days 统计天数，默认 7 天
     * @return 按天排列的学习记录
     */
    @GetMapping("/history")
    public Result<List<StudyDayStatsVO>> history(@RequestParam(defaultValue = "7") int days) {
        return Result.success(studyService.history(userId(), days));
    }

    /**
     * 获取学习日历，按天汇总学习情况
     * @param month 月份，格式 yyyy-MM，为空时取当前月
     * @return 日历数据
     */
    @GetMapping("/calendar")
    public Result<StudyCalendarVO> calendar(@RequestParam(required = false) String month) {
        return Result.success(studyService.calendar(userId(), month));
    }

    /**
     * 获取待复习与待学习数量，用于入口角标
     * @return 复习计数
     */
    @GetMapping("/review-counts")
    public Result<StudyReviewCountVO> reviewCounts() {
        return Result.success(studyService.reviewCounts(userId()));
    }

    /**
     * 分页查询词条熟悉度分布明细，可按词书与掌握阶段过滤
     * @param bookId 词书 ID，为空表示全部词书
     * @param stage 掌握阶段，默认 ALL
     * @param page 页码，从 1 开始，默认 1
     * @return 熟悉度分布与分页词条
     */
    @GetMapping("/familiarity")
    public Result<StudyFamiliarityVO> familiarity(@RequestParam(required = false) Long bookId,
                                                  @RequestParam(defaultValue = "ALL") String stage,
                                                  @RequestParam(defaultValue = "1") int page) {
        return Result.success(studyService.familiarity(userId(), bookId, stage, page));
    }

    /**
     * 获取当前用户的学习设置
     * @return 学习设置，包含时区、每日新词上限与目标记忆率
     */
    @GetMapping("/settings")
    public Result<StudySettingsVO> settings() {
        return Result.success(studyService.settings(userId()));
    }

    /**
     * 更新学习设置
     * @param request 时区、每日新词上限与目标记忆率
     * @return 更新后的学习设置
     */
    @PutMapping("/settings")
    public Result<StudySettingsVO> updateSettings(@RequestBody UpdateStudySettingsDTO request) {
        return Result.success(studyService.updateSettings(userId(), request));
    }

    /**
     * 启用或停用某个词书参与学习计划
     * @param bookId 词书 ID
     * @param request 启用状态
     * @return 操作结果提示
     */
    @PutMapping("/books/{bookId}")
    public Result<String> setBookEnabled(@PathVariable long bookId, @RequestBody UpdateStudyBookDTO request) {
        if (request == null || request.getEnabled() == null) throw new ValidationException("请选择词书状态");
        studyService.setBookEnabled(userId(), bookId, request.getEnabled());
        return Result.success("学习词书已更新");
    }
}
