package com.hope.xueling.common.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hope.xueling.common.domain.dto.CreateFeedbackDTO;
import com.hope.xueling.common.domain.dto.UpdateFeedbackDTO;
import com.hope.xueling.common.domain.entity.Feedback;
import com.hope.xueling.common.domain.vo.FeedbackVO;
import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ForbiddenException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.common.mapper.FeedbackMapper;
import com.hope.xueling.common.security.AdminAccessPolicy;
import com.hope.xueling.common.service.IFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 用户反馈服务实现：用户侧提交与查询、管理侧列表与处理；提交按 requestId 幂等，并限制短时间内的提交次数
 * @author 谢光湘
 * @since 2026/10/24
 */
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements IFeedbackService {
    /**
     * 分页大小
     */
    private static final int PAGE_SIZE = 20;
    /**
     * 允许的反馈大类
     */
    private static final Set<String> CATEGORIES = Set.of("FUNCTION_ISSUE", "FEATURE_IDEA", "EXPERIENCE", "OTHER", "TRANSLATION");
    /**
     * 允许的处理状态
     */
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "REPLIED", "CLOSED");
    /**
     * 翻译问题允许的子类
     */
    private static final Set<String> TRANSLATION_TYPES = Set.of("meaning", "sound", "example", "other");

    /**
     * 反馈数据访问
     */
    private final FeedbackMapper feedbackMapper;
    /**
     * 管理员白名单，用于判定反馈处理权限
     */
    private final AdminAccessPolicy adminAccessPolicy;

    /**
     * 创建反馈：校验请求编号格式、反馈类型、内容长度与来源页面，翻译类必须指定子类；同用户同 requestId 重复提交返回首次结果
     * @param userId 提交人用户 ID
     * @param request 反馈内容与来源信息
     * @return 创建或已存在的反馈
     */
    @Override
    public FeedbackVO create(long userId, CreateFeedbackDTO request) {
        if (request == null) throw new ValidationException("请填写反馈内容");
        String requestId = required(request.getRequestId(), 64, "请求编号");
        if (!requestId.matches("[0-9a-fA-F-]{36}")) throw new ValidationException("请求编号格式有误");
        Feedback existing = findByRequestId(userId, requestId);
        if (existing != null) return FeedbackVO.from(existing);

        String category = required(request.getCategory(), 32, "反馈类型");
        if (!CATEGORIES.contains(category)) throw new ValidationException("反馈类型无效");
        String content = required(request.getContent(), 2000, "反馈内容");
        if (content.length() < 5) throw new ValidationException("请至少填写 5 个字的反馈内容");
        String subcategory = optional(request.getSubcategory(), 32, "问题类型");
        if ("TRANSLATION".equals(category)) {
            if (subcategory == null || !TRANSLATION_TYPES.contains(subcategory)) throw new ValidationException("请选择翻译问题类型");
        } else if (subcategory != null) {
            throw new ValidationException("此反馈类型无需问题子类");
        }
        String sourcePage = optional(request.getSourcePage(), 255, "来源页面");
        if (sourcePage != null && (!sourcePage.startsWith("/") || sourcePage.startsWith("//"))) {
            throw new ValidationException("来源页面格式有误");
        }
        String contextWord = optional(request.getContextWord(), 255, "关联单词");
        String observedContent = optional(request.getObservedContent(), 2000, "所见内容");
        String suggestedContent = optional(request.getSuggestedContent(), 2000, "建议内容");
        Long wordId = null;
        if (request.getWordId() != null && !request.getWordId().isBlank()) {
            if (!"TRANSLATION".equals(category)) throw new ValidationException("只有词条纠错可以关联单词");
            try { wordId = Long.valueOf(request.getWordId()); }
            catch (NumberFormatException error) { throw new ValidationException("词条编号无效"); }
            if (wordId <= 0) throw new ValidationException("词条编号无效");
        }
        if (!"TRANSLATION".equals(category) && (observedContent != null || suggestedContent != null))
            throw new ValidationException("此反馈类型无需词条内容");
        if (wordId != null) {
            String canonicalWord = feedbackMapper.findWord(wordId);
            if (canonicalWord == null) throw new ValidationException("关联词条不存在，请重新打开单词后提交");
            contextWord = canonicalWord;
        }
        String contact = optional(request.getContact(), 100, "联系方式");
        if (feedbackMapper.countRecent(userId) >= 5) throw new BusinessException("提交太频繁，请稍后再试");

        Feedback feedback = new Feedback();
        feedback.setUserId(userId);
        feedback.setRequestId(requestId);
        feedback.setCategory(category);
        feedback.setSubcategory(subcategory);
        feedback.setContent(content);
        feedback.setSourcePage(sourcePage);
        feedback.setContextWord(contextWord);
        feedback.setWordId(wordId);
        feedback.setObservedContent(observedContent);
        feedback.setSuggestedContent(suggestedContent);
        feedback.setContact(contact);
        feedback.setStatus("PENDING");
        feedback.setCreateTime(LocalDateTime.now());
        feedback.setUpdateTime(feedback.getCreateTime());
        try {
            feedbackMapper.insert(feedback);
        } catch (DuplicateKeyException e) {
            Feedback duplicate = findByRequestId(userId, requestId);
            if (duplicate == null) throw e;
            return FeedbackVO.from(duplicate);
        }
        return FeedbackVO.from(feedback);
    }

    /**
     * 分页查询用户自己的反馈，按提交时间倒序
     * @param userId 用户 ID
     * @param page 页码，从 1 开始
     * @return 当前页反馈列表
     */
    @Override
    public List<FeedbackVO> mine(long userId, int page) {
        return feedbackMapper.listByUser(userId, PAGE_SIZE, offset(page)).stream().map(FeedbackVO::from).toList();
    }

    /**
     * 查询用户自己的单条反馈，非本人反馈按不存在处理
     * @param userId 用户 ID
     * @param feedbackId 反馈 ID
     * @return 反馈详情
     */
    @Override
    public FeedbackVO findMine(long userId, long feedbackId) {
        Feedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null || !feedback.getUserId().equals(userId)) throw new BusinessException("反馈不存在");
        return FeedbackVO.from(feedback);
    }

    /**
     * 判断用户是否为管理员
     * @param userId 用户 ID
     * @return 是否为管理员
     */
    @Override
    public boolean isAdmin(long userId) {
        return adminAccessPolicy.isAdmin(userId);
    }

    /**
     * 管理端分页查询反馈，可按处理状态过滤
     * @param userId 操作人用户 ID，需为管理员
     * @param status 处理状态过滤值，可为空
     * @param page 页码，从 1 开始
     * @return 当前页反馈列表
     */
    @Override
    public List<FeedbackVO> listForAdmin(long userId, String status, int page) {
        /**
         * 校验操作人具备管理员权限，否则抛出 403
         * @param userId 操作人用户 ID
         */
        requireAdmin(userId);
        if (status != null && !STATUSES.contains(status)) throw new ValidationException("处理状态无效");
        return feedbackMapper.listForAdmin(status, PAGE_SIZE, offset(page)).stream().map(FeedbackVO::from).toList();
    }

    /**
     * 管理端处理反馈：更新状态与回复内容，置为已回复时必须已有或本次填写回复，并记录回复时间
     * @param userId 操作人用户 ID，需为管理员
     * @param feedbackId 反馈 ID
     * @param request 处理状态与回复内容
     * @return 更新后的反馈
     */
    @Override
    public FeedbackVO update(long userId, long feedbackId, UpdateFeedbackDTO request) {
        requireAdmin(userId);
        if (request == null) throw new ValidationException("请填写处理结果");
        String status = required(request.getStatus(), 20, "处理状态");
        if (!STATUSES.contains(status)) throw new ValidationException("处理状态无效");
        String reply = optional(request.getAdminReply(), 2000, "处理回复");
        Feedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) throw new BusinessException("反馈不存在");
        if ("REPLIED".equals(status) && reply == null && feedback.getAdminReply() == null) {
            throw new ValidationException("回复用户时请填写处理说明");
        }
        feedback.setStatus(status);
        if (reply != null) {
            feedback.setAdminReply(reply);
            feedback.setRepliedAt(LocalDateTime.now());
        }
        feedback.setUpdateTime(LocalDateTime.now());
        feedbackMapper.updateById(feedback);
        return FeedbackVO.from(feedback);
    }

    /**
     * 按用户与请求编号查询已存在的反馈，用于幂等判重
     * @param userId 用户 ID
     * @param requestId 客户端请求编号
     * @return 已存在的反馈，不存在时返回 null
     */
    private Feedback findByRequestId(long userId, String requestId) {
        return feedbackMapper.selectOne(new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getUserId, userId).eq(Feedback::getRequestId, requestId));
    }

    private void requireAdmin(long userId) {
        if (!isAdmin(userId)) throw new ForbiddenException("无权处理用户反馈");
    }

    /**
     * 将页码换算为 SQL 偏移量，页码需在 1 至 1000 之间
     * @param page 页码，从 1 开始
     * @return 偏移量
     */
    private static int offset(int page) {
        if (page < 1 || page > 1000) throw new ValidationException("页码超出范围");
        return (page - 1) * PAGE_SIZE;
    }

    /**
     * 校验必填文本并裁剪长度
     * @param value 原始值
     * @param max 最大长度
     * @param label 字段中文名，用于错误提示
     * @return 裁剪后的文本
     */
    private static String required(String value, int max, String label) {
        String trimmed = optional(value, max, label);
        if (trimmed == null) throw new ValidationException("请填写" + label);
        return trimmed;
    }

    /**
     * 校验可空文本：空白返回 null，超长报错
     * @param value 原始值
     * @param max 最大长度
     * @param label 字段中文名，用于错误提示
     * @return 裁剪后的文本，可能为 null
     */
    private static String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new ValidationException(label + "不能超过 " + max + " 字");
        return trimmed;
    }
}
