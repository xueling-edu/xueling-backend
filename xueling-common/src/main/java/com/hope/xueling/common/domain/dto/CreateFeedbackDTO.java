package com.hope.xueling.common.domain.dto;

import lombok.Data;

/**
 * 提交用户反馈入参
 * @author 谢光湘
 * @since 2026/10/24
 */
@Data
public class CreateFeedbackDTO {
    /**
     * 客户端生成的请求编号（UUID），用于幂等去重，同一用户重复提交只落一条
     */
    private String requestId;
    /**
     * 反馈大类：FUNCTION_ISSUE 功能异常、FEATURE_IDEA 功能建议、EXPERIENCE 体验问题、TRANSLATION 翻译问题、OTHER 其他
     */
    private String category;
    /**
     * 反馈子类，翻译问题需取 meaning、sound、example、other 之一
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
}
