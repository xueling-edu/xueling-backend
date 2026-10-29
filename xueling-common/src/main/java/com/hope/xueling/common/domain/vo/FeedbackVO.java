package com.hope.xueling.common.domain.vo;

import com.hope.xueling.common.domain.entity.Feedback;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈视图对象，ID 统一以字符串返回避免前端精度丢失
 * @author 谢光湘
 * @since 2026/10/24
 */
@Data
public class FeedbackVO {
    /**
     * 反馈 ID（字符串）
     */
    private String id;
    /**
     * 提交人用户 ID（字符串）
     */
    private String userId;
    /**
     * 反馈大类
     */
    private String category;
    /**
     * 反馈子类
     */
    private String subcategory;
    /**
     * 反馈正文
     */
    private String content;
    /**
     * 提交反馈时所在页面
     */
    private String sourcePage;
    /**
     * 反馈关联的单词或上下文内容
     */
    private String contextWord;
    private String wordId;
    private String observedContent;
    private String suggestedContent;
    /**
     * 用户留下的联系方式
     */
    private String contact;
    /**
     * 处理状态
     */
    private String status;
    /**
     * 管理员回复内容
     */
    private String adminReply;
    private String resolution;
    private String revisionId;
    /**
     * 提交时间
     */
    private LocalDateTime createTime;
    /**
     * 最后更新时间
     */
    private LocalDateTime updateTime;
    /**
     * 管理员回复时间
     */
    private LocalDateTime repliedAt;

    /**
     * 由实体转换为视图对象，Long 型 ID 转为字符串
     * @param feedback 反馈实体
     * @return 反馈视图
     */
    public static FeedbackVO from(Feedback feedback) {
        FeedbackVO vo = new FeedbackVO();
        vo.setId(String.valueOf(feedback.getId()));
        vo.setUserId(String.valueOf(feedback.getUserId()));
        vo.setCategory(feedback.getCategory());
        vo.setSubcategory(feedback.getSubcategory());
        vo.setContent(feedback.getContent());
        vo.setSourcePage(feedback.getSourcePage());
        vo.setContextWord(feedback.getContextWord());
        vo.setWordId(feedback.getWordId() == null ? null : String.valueOf(feedback.getWordId()));
        vo.setObservedContent(feedback.getObservedContent());
        vo.setSuggestedContent(feedback.getSuggestedContent());
        vo.setContact(feedback.getContact());
        vo.setStatus(feedback.getStatus());
        vo.setAdminReply(feedback.getAdminReply());
        vo.setResolution(feedback.getResolution());
        vo.setRevisionId(feedback.getRevisionId() == null ? null : String.valueOf(feedback.getRevisionId()));
        vo.setCreateTime(feedback.getCreateTime());
        vo.setUpdateTime(feedback.getUpdateTime());
        vo.setRepliedAt(feedback.getRepliedAt());
        return vo;
    }
}
