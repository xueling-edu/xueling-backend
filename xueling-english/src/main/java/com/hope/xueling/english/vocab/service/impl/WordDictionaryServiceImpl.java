package com.hope.xueling.english.vocab.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.util.ThreadLocalUtils;
import com.hope.xueling.english.vocab.domain.dto.WordDictionaryDTO;
import com.hope.xueling.english.vocab.domain.entity.WordBook;
import com.hope.xueling.english.vocab.domain.entity.WordBookDictionaryRelation;
import com.hope.xueling.english.vocab.domain.entity.WordDictionary;
import com.hope.xueling.english.vocab.domain.vo.WordDictionaryVO;
import com.hope.xueling.english.vocab.domain.vo.WordVO;
import com.hope.xueling.english.vocab.mapper.WordBookDictionaryRelationMapper;
import com.hope.xueling.english.vocab.mapper.WordBookMapper;
import com.hope.xueling.english.vocab.mapper.WordDictionaryMapper;
import com.hope.xueling.english.vocab.service.IWordDefinitionService;
import com.hope.xueling.english.vocab.service.IWordDictionaryService;
import com.hope.xueling.english.vocab.util.WordMeaningParser;
import com.hope.xueling.english.vocab.util.WordNormalizer;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 词典服务实现：优先读库中已有释义，缺失时调用大模型生成并回写，词条按 word_key 跨用户复用
 * @author 谢光湘
 * @since 2026/10/24
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class WordDictionaryServiceImpl implements IWordDictionaryService {
    /**
     * 大模型词典释义服务，仅在库中无有效释义时调用
     */
    private final IWordDefinitionService wordDefinitionService;
    /**
     * 词条数据访问
     */
    private final WordDictionaryMapper wordDictionaryMapper;
    /**
     * 词书词条关联访问，用于判断单词是否已在用户词书中
     */
    private final WordBookDictionaryRelationMapper wordBookDictionaryRelationMapper;
    /**
     * 词书数据访问，用于取当前用户的词书范围
     */
    private final WordBookMapper wordBookMapper;

    /**
     * 查询单词释义：库中已有释义直接返回；否则调用大模型生成并落库，并发插入冲突时回退为查询补齐
     * @param word 待查单词
     * @return 词典视图，含按词性分组的义项与是否已在词书中的标记
     */
    @Override
    public WordDictionaryVO queryWordDictionary(String word) {
        String key = WordNormalizer.normalize(word);
        WordDictionary entity = findByKey(key);
        if (entity != null && entity.getMeaning() != null && !entity.getMeaning().isBlank()) {
            return toVO(entity);
        }

        WordDictionaryDTO translation;
        try {
            translation = wordDefinitionService.translateWord(word.trim());
        } catch (RuntimeException ex) {
            log.error("AI 翻译失败: {}", key, ex);
            throw new BusinessException("翻译服务暂不可用，请稍后重试");
        }
        if (translation == null || translation.getMeaning() == null || translation.getMeaning().isBlank()) {
            throw new BusinessException("没有找到可靠的释义，请检查拼写");
        }

        if (entity == null) {
            entity = new WordDictionary();
            entity.setWord(translation.getWord().trim());
            entity.setWordKey(key);
            entity.setSource("AI");
            entity.setMeaning(translation.getMeaning());
            entity.setPhonetic(translation.getPhonetic());
            entity.setExample(translation.getExample());
            entity.setExampleTranslation(translation.getExampleTranslation());
            try {
                wordDictionaryMapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                entity = findByKey(key);
                if (entity == null) throw ex;
                if (entity.getMeaning() == null || entity.getMeaning().isBlank()) {
                    /**
                     * 用大模型结果补齐已有词条：覆盖释义与例句，仅在原音标为空时补充音标，来源标记为 AI_ENRICHED
                     * @param entity 待补齐的词条
                     * @param translation 大模型返回的词典条目
                     */
                    entity = enrich(entity, translation);
                }
            }
        } else {
            entity = enrich(entity, translation);
        }
        return toVO(entity);
    }

    /**
     * 按 ID 批量查询词条详情
     * @param wordIds 词条 ID 列表（字符串）
     * @return 词条详情列表
     */
    @Override
    public List<WordVO> getWordDetailByIds(List<String> wordIds) {
        return wordDictionaryMapper.selectByIds(wordIds).stream().map(entity -> {
            WordVO vo = new WordVO();
            BeanUtils.copyProperties(entity, vo);
            vo.setId(String.valueOf(entity.getId()));
            return vo;
        }).toList();
    }

    /**
     * 按规范化检索键查询词条
     * @param key 规范化后的 word_key
     * @return 词条实体，不存在时返回 null
     */
    private WordDictionary findByKey(String key) {
        return wordDictionaryMapper.selectOne(new QueryWrapper<WordDictionary>().eq("word_key", key));
    }

    private WordDictionary enrich(WordDictionary entity, WordDictionaryDTO translation) {
        LambdaUpdateWrapper<WordDictionary> update = new LambdaUpdateWrapper<WordDictionary>()
                .eq(WordDictionary::getId, entity.getId())
                .and(w -> w.isNull(WordDictionary::getMeaning).or().eq(WordDictionary::getMeaning, ""))
                .set(WordDictionary::getMeaning, translation.getMeaning())
                .set(WordDictionary::getSource, "AI_ENRICHED")
                .set(WordDictionary::getExample, translation.getExample())
                .set(WordDictionary::getExampleTranslation, translation.getExampleTranslation())
                .setSql("content_version = content_version + 1");
        if (entity.getPhonetic() == null || entity.getPhonetic().isBlank()) {
            update.set(WordDictionary::getPhonetic, translation.getPhonetic());
        }
        wordDictionaryMapper.update(null, update);
        return wordDictionaryMapper.selectById(entity.getId());
    }

    /**
     * 转换为视图对象：解析义项，并在存在登录用户时标记该词是否已加入其词书
     * @param entity 词条实体
     * @return 词典视图
     */
    private WordDictionaryVO toVO(WordDictionary entity) {
        WordDictionaryVO vo = new WordDictionaryVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setId(String.valueOf(entity.getId()));
        vo.setTranslations(WordMeaningParser.parse(entity.getMeaning()));
        vo.setInWordBook(false);
        Claims claims = ThreadLocalUtils.get();
        if (claims != null) {
            Long userId = claims.get("userId", Long.class);
            List<Long> bookIds = wordBookMapper.selectList(new QueryWrapper<WordBook>()
                            .select("id").eq("user_id", userId).eq("is_deleted", 0))
                    .stream().map(WordBook::getId).toList();
            if (!bookIds.isEmpty()) {
                List<WordBookDictionaryRelation> relations = wordBookDictionaryRelationMapper.selectList(
                        new QueryWrapper<WordBookDictionaryRelation>().eq("word_id", entity.getId())
                                .in("word_book_id", bookIds).last("LIMIT 1"));
                if (!relations.isEmpty()) {
                    vo.setInWordBook(true);
                    vo.setWordBookId(String.valueOf(relations.get(0).getWordBookId()));
                }
            }
        }
        return vo;
    }
}
