package com.hope.xueling.english.vocab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hope.xueling.english.vocab.domain.entity.ContextArticle;
import com.hope.xueling.english.vocab.domain.entity.WordDictionary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ContextArticleMapper extends BaseMapper<ContextArticle> {
    @Select("<script>SELECT DISTINCT d.id, d.word, d.meaning FROM word_learning_progress p " +
            "JOIN word_dictionary d ON d.id=p.word_id WHERE p.user_id=#{userId} " +
            "AND p.word_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<WordDictionary> selectLearnedWords(@Param("userId") long userId, @Param("ids") List<Long> ids);

    @Select("SELECT COUNT(*) FROM vocab_context_article WHERE user_id=#{userId} AND create_time >= CURRENT_DATE()")
    int countToday(@Param("userId") long userId);
}
