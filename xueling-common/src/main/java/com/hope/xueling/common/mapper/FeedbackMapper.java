package com.hope.xueling.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hope.xueling.common.domain.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户反馈 Mapper：提供用户维度的分页查询、管理端状态过滤分页与提交频率统计
 * @author 谢光湘
 * @since 2026/10/24
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
    @Select("SELECT word FROM word_dictionary WHERE id = #{wordId}")
    String findWord(@Param("wordId") long wordId);
    /**
     * 分页查询指定用户提交的反馈，按提交时间倒序
     * @param userId 用户 ID
     * @param limit 每页条数
     * @param offset SQL 偏移量，由页码换算
     * @return 反馈列表
     */
    @Select("SELECT * FROM user_feedback WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Feedback> listByUser(@Param("userId") long userId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 管理端分页查询反馈，status 为空时不过滤
     * @param status 处理状态，可为 null
     * @param limit 每页条数
     * @param offset SQL 偏移量
     * @return 反馈列表
     */
    @Select("<script>SELECT * FROM user_feedback <where><if test='status != null'>status = #{status}</if></where> ORDER BY create_time DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Feedback> listForAdmin(@Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 统计该用户最近一小时的提交数，用于限制提交频率
     * @param userId 用户 ID
     * @return 最近一小时提交条数
     */
    @Select("SELECT COUNT(*) FROM user_feedback WHERE user_id = #{userId} AND create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)")
    int countRecent(@Param("userId") long userId);
}
