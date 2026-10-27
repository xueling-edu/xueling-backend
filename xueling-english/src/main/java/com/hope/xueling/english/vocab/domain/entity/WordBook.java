package com.hope.xueling.english.vocab.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单词本实体类
 * @author 谢光湘
 * @since 2026/1/25
 */
@Data
@TableName("word_book")
public class WordBook {
    /**
     * 单词本ID（由 MyBatis-Plus 雪花算法统一生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
     /**
     * 单词本创建人ID
     */
    private Long userId;

    /**
     * 词书类型：SYSTEM 系统词书、USER 用户自建词书
     */
    private String bookType;
    /** 系统词书发布状态；用户词书为空。 */
    private String publishStatus;

    /**
     * 系统词书标识，用户自建词书为空
     */
    private String systemKey;

    /**
     * 单词本名称
     */
    private String name;
     /**
     * 单词本颜色
     */
    private String color;
    /**
     * 单词本图标
     */
    private String icon;

    /**
     * 单词数量
     */
    private int wordCount;
    /**
    * 单词本创建时间
    */
    private LocalDateTime createTime;
    /**
    * 是否删除
    */
    private int isDeleted;
    /**
    * 单词本删除时间
    */
    private LocalDateTime deleteTime;
}
