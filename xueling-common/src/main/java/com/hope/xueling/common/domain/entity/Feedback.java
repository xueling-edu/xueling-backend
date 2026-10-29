package com.hope.xueling.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈实体，对应 user_feedback 表
 * @author 谢光湘
 * @since 2026/10/24
 */
@Data
@TableName("user_feedback")
public class Feedback {
    /**
     * 主键，由 MyBatis-Plus 雪花算法生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 提交人用户 ID
     */
    private Long userId;
    /**
     * 客户端请求编号（UUID），与用户 ID 联合唯一，用于幂等去重
     */
    private String requestId;
    /**
     * 反馈大类：FUNCTION_ISSUE、FEATURE_IDEA、EXPERIENCE、TRANSLATION、OTHER
     */
    private String category;
    /**
     * 反馈子类，翻译问题取 meaning、sound、example、other
     */
    private String subcategory;
    /**
     * 反馈正文
     */
    private String content;
    /**
     * 提交反馈时所在页面路径
     */
    private String sourcePage;
    /**
     * 反馈关联的单词或上下文内容
     */
    private String contextWord;
    private Long wordId;
    private String observedContent;
    private String suggestedContent;
    /**
     * 用户留下的联系方式
     */
    private String contact;
    /**
     * 处理状态：PENDING、PROCESSING、REPLIED、CLOSED
     */
    private String status;
    /**
     * 管理员回复内容
     */
    private String adminReply;
    private String resolution;
    private Long revisionId;
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
}
