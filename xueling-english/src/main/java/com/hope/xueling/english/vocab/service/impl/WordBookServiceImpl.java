package com.hope.xueling.english.vocab.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.english.vocab.domain.dto.AddWordsToWordBookDTO;
import com.hope.xueling.english.vocab.domain.dto.CreateWordBookDTO;
import com.hope.xueling.english.vocab.domain.dto.RemoveWordsFromWordBookDTO;
import com.hope.xueling.english.vocab.domain.entity.WordBook;
import com.hope.xueling.english.vocab.domain.entity.WordBookDictionaryRelation;
import com.hope.xueling.english.vocab.domain.entity.WordDictionary;
import com.hope.xueling.english.vocab.domain.vo.WordBookDetailVO;
import com.hope.xueling.english.vocab.domain.vo.WordBookVO;
import com.hope.xueling.english.vocab.domain.vo.WordDictionaryVO;
import com.hope.xueling.english.vocab.domain.vo.WordVO;
import com.hope.xueling.english.vocab.mapper.WordBookDictionaryRelationMapper;
import com.hope.xueling.english.vocab.mapper.WordBookMapper;
import com.hope.xueling.english.vocab.mapper.WordDictionaryMapper;
import com.hope.xueling.english.vocab.mapper.StudyMapper;
import com.hope.xueling.english.vocab.service.IWordBookService;
import com.hope.xueling.english.vocab.util.WordNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 单词本服务实现类
 * @author 谢光湘
 * @since 2026/1/25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordBookServiceImpl implements IWordBookService {
    /**
     * 词书数据访问
     */
    private final WordBookMapper wordBookMapper;
    /**
     * 词条数据访问
     */
    private final WordDictionaryMapper wordDictionaryMapper;
    /**
     * 词书词条关联访问
     */
    private final WordBookDictionaryRelationMapper wordBookDictionaryRelationMapper;
    /**
     * 学习进度访问，用于统计已学词数与词书启停状态
     */
    private final StudyMapper studyMapper;

    /**
     * 定义允许的颜色列表（应与前端一致，或者放在配置中心/数据库）
     */
    private static final Set<String> ALLOWED_COLORS = Set.of(
            "linear-gradient(120deg, #e0c3fc 0%, #8ec5fc 100%)",
            "linear-gradient(135deg, #a18cd1 0%, #fbc2eb 100%)",
            "linear-gradient(135deg, #84fab0 0%, #8fd3f4 100%)",
            "linear-gradient(120deg, #f6d365 0%, #fda085 100%)",
            "linear-gradient(135deg, #667eea 0%, #764ba2 100%)",
            "linear-gradient(120deg, #ff9a9e 0%, #fecfef 99%, #fecfef 100%)"
    );

    /**
     * 定义允许的图标列表
     */
    private static final Set<String> ALLOWED_ICONS = Set.of(
            "📘", "📖", "📚", "📕", "📗", "📙", "🎓", "🗣️", "📝", "🧠", "🌟", "🔥"
    );


    /**
     * 创建用户自建词书：封面颜色与图标需在白名单内，词数初始为 0
     * @param createWordBookDTO 词书名称、封面颜色与图标
     * @param userId 创建人用户 ID
     */
    @Override
    public void createWordBook(CreateWordBookDTO createWordBookDTO, Long userId) {
        // 1. 白名单校验
        if (!ALLOWED_COLORS.contains(createWordBookDTO.getColor())) {
            throw new ValidationException("不支持的封面颜色风格");
        }

        if (!ALLOWED_ICONS.contains(createWordBookDTO.getIcon())) {
            throw new ValidationException("不支持的图标类型");
        }
        WordBook wordBook = new WordBook();
        wordBook.setUserId(userId);
        wordBook.setBookType("USER");
        wordBook.setName(createWordBookDTO.getName());
        wordBook.setColor(createWordBookDTO.getColor());
        wordBook.setIcon(createWordBookDTO.getIcon());
        wordBook.setWordCount(0);
        wordBookMapper.insert(wordBook);
    }

    /**
     * 查询用户可见的词书：自己创建的词书加全部系统词书，同时计算学习覆盖率与学习计划启停状态
     * @param userId 用户 ID
     * @return 词书列表
     */
    @Override
    public List<WordBookVO> getWordBooks(Long userId) {
        // 查询用户所有单词本
        QueryWrapper<WordBook> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("is_deleted", 0)
                .and(q -> q.eq("user_id", userId)
                        .or(system -> system.eq("book_type", "SYSTEM").eq("publish_status", "PUBLISHED")))
                .orderByAsc("book_type").orderByDesc("create_time");
        List<WordBook> wordBooks = wordBookMapper.selectList(queryWrapper);
        if (wordBooks.isEmpty()) {
            return Collections.emptyList();
        }
        // 转换为VO
        return wordBooks.stream().map(wordBook -> {
            WordBookVO wordBookVo = new WordBookVO();
            wordBookVo.setId(String.valueOf(wordBook.getId()));
            wordBookVo.setName(wordBook.getName());
            wordBookVo.setWordCount(wordBook.getWordCount());
            int learned = studyMapper.bookLearnedCount(userId, wordBook.getId());
            int coverage = wordBook.getWordCount() == 0 ? 0
                    : Math.min(100, learned * 100 / wordBook.getWordCount());
            wordBookVo.setLearningCoverage(coverage);
            wordBookVo.setMastery(coverage);
            wordBookVo.setLearnedCount(learned);
            wordBookVo.setStudyEnabled(studyMapper.bookEnabled(userId, wordBook.getId()));
            wordBookVo.setBookType(wordBook.getBookType());
            wordBookVo.setColor(wordBook.getColor());
            wordBookVo.setIcon(wordBook.getIcon());
            return wordBookVo;
        }).toList();
    }

    /**
     * 查询词书详情：系统词书所有用户可读，自建词书仅创建者可读，并返回词条列表
     * @param wordBookId 词书 ID
     * @param userId 用户 ID
     * @return 词书详情
     */
    @Override
    public WordBookDetailVO getWordBookDetail(Long wordBookId, Long userId) {
        // 检查单词本是否存在，用户是否对单词本有读写权限
        WordBook wordBook = getReadableWordBook(wordBookId, userId);
        // 构建VO
        WordBookDetailVO wordBookDetailVO = new WordBookDetailVO();
        wordBookDetailVO.setId(String.valueOf(wordBook.getId()));
        wordBookDetailVO.setName(wordBook.getName());
        wordBookDetailVO.setWordCount(wordBook.getWordCount());
        int learned = studyMapper.bookLearnedCount(userId, wordBookId);
        int coverage = wordBook.getWordCount() == 0 ? 0
                : Math.min(100, learned * 100 / wordBook.getWordCount());
        wordBookDetailVO.setLearningCoverage(coverage);
        wordBookDetailVO.setMastery(coverage);
        wordBookDetailVO.setLearnedCount(learned);
        wordBookDetailVO.setStudyEnabled(studyMapper.bookEnabled(userId, wordBookId));
        wordBookDetailVO.setBookType(wordBook.getBookType());
        // 查询单词列表
        QueryWrapper<WordBookDictionaryRelation> relationQueryWrapper = new QueryWrapper<>();
        relationQueryWrapper.eq("word_book_id", wordBookId)
                .orderByAsc("sort_order").orderByAsc("word_id");
        List<WordBookDictionaryRelation> relations = wordBookDictionaryRelationMapper.selectList(relationQueryWrapper);
        if (relations.isEmpty()) {
            wordBookDetailVO.setWordList(Collections.emptyList());
        } else {
            // 批量查询单词详情
            QueryWrapper<WordDictionary> wordQueryWrapper = new QueryWrapper<>();
            wordQueryWrapper.in("id", relations.stream().map(WordBookDictionaryRelation::getWordId).toList());
            List<WordDictionary> wordDictionaries = wordDictionaryMapper.selectList(wordQueryWrapper);
            Map<Long, WordDictionary> dictionaryById = wordDictionaries.stream()
                    .collect(java.util.stream.Collectors.toMap(WordDictionary::getId, word -> word));
            //转换为VO
            wordBookDetailVO.setWordList(relations.stream()
                    .map(relation -> dictionaryById.get(relation.getWordId()))
                    .filter(Objects::nonNull)
                    .map(wordDictionary -> {
                WordVO wordVO = new WordVO();
                wordVO.setId(String.valueOf(wordDictionary.getId()));
                wordVO.setWord(wordDictionary.getWord());
                wordVO.setMeaning(wordDictionary.getMeaning());
                wordVO.setPhonetic(wordDictionary.getPhonetic());
                wordVO.setMeaning(wordDictionary.getMeaning());
                wordVO.setCreateTime(wordDictionary.getCreateTime());
                return wordVO;
            }).toList());
        }
        return wordBookDetailVO;
    }


    /**
     * 把逗号分隔的单词列表匹配到系统词典中已有释义的词条，用于批量导入前的校验
     * @param words 逗号分隔的单词串
     * @return 匹配到的词条列表
     */
    @Override
    public List<WordDictionaryVO> matchWords(String words) {
        if (words == null || words.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> wordList = Arrays.stream(words.split(","))
                .map(String::trim)  // 去除首尾空格
                .filter(w -> !w.isEmpty())
                .distinct() // 去重，避免无效查询
                .toList();

        if (wordList.isEmpty()) return Collections.emptyList();

        QueryWrapper<WordDictionary> wrapper = new QueryWrapper<>();
        wrapper.in("word_key", wordList.stream().map(WordNormalizer::normalize).toList())
                .isNotNull("meaning").ne("meaning", "");

        List<WordDictionary> wordDictionaries = wordDictionaryMapper.selectList(wrapper);
        return wordDictionaries.stream().map(wordDictionary -> {
            WordDictionaryVO wordDictionaryVO = new WordDictionaryVO();
            wordDictionaryVO.setId(String.valueOf(wordDictionary.getId()));
            wordDictionaryVO.setWord(wordDictionary.getWord());
            wordDictionaryVO.setMeaning(wordDictionary.getMeaning());
            wordDictionaryVO.setPhonetic(wordDictionary.getPhonetic());
            wordDictionaryVO.setCreateTime(wordDictionary.getCreateTime());
            return wordDictionaryVO;
        }).toList();
    }

    /**
     * 向词书批量添加单词：单次最多 20 个，已存在的关联自动跳过，并同步更新词书词数
     * @param addWordsToWordBookDTO 词书 ID 与单词 ID 列表
     * @param userId 用户 ID
     */
    @Transactional
    @Override
    public void addWordsToWordBook(AddWordsToWordBookDTO addWordsToWordBookDTO, Long userId) {
        Long wordBookId = Long.parseLong(addWordsToWordBookDTO.getWordBookId());
        List<Long> wordIds = addWordsToWordBookDTO.getWordIds().stream().map(Long::parseLong).toList();
        //单词数数量不超过20个
        if (wordIds.size() > 20) {
            log.warn("一次添加单词数量超过限制，用户ID：{}，单词本ID：{}，单词数量：{}", userId, wordBookId, wordIds.size());
            throw new ValidationException("一次最多添加20个单词");
        }
        // 检查单词本是否存在，用户是否对单词本有读写权限
        WordBook wordBook = checkWordBookExistsAndHasPermission(wordBookId, userId);
        // 找出还没有加入单词本的单词
        List<Long> needAddWordIds = new ArrayList<>();
        wordIds.forEach(wordId -> {
            QueryWrapper<WordBookDictionaryRelation> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("word_book_id", wordBookId).eq("word_id", wordId);
            WordBookDictionaryRelation wordBookDictionaryRelation = wordBookDictionaryRelationMapper.selectOne(queryWrapper);
            if (wordBookDictionaryRelation == null) {
                needAddWordIds.add(wordId);
            }
        });
        List<WordBookDictionaryRelation> needAddRelations = needAddWordIds.stream().map(wordId -> {
            WordBookDictionaryRelation relation = new WordBookDictionaryRelation();
            relation.setWordBookId(wordBookId);
            relation.setWordId(wordId);
            return relation;
        }).toList();
        // 批量添加单词到单词本
        wordBookDictionaryRelationMapper.insert(needAddRelations);
        // 更新单词本单词数量
        wordBook.setWordCount(wordBook.getWordCount() + needAddRelations.size());
        wordBookMapper.updateById(wordBook);
    }

    /**
     * 从词书批量移除单词，并同步更新词书词数
     * @param removeWordsFromWordBookDTO 词书 ID 与单词 ID 列表
     * @param userId 用户 ID
     */
    @Override
    public void deleteWordsFromWordBook(RemoveWordsFromWordBookDTO removeWordsFromWordBookDTO, Long userId) {
        Long wordBookId = Long.parseLong(removeWordsFromWordBookDTO.getWordBookId());
        List<Long> wordIds = removeWordsFromWordBookDTO.getWordIds().stream().map(Long::parseLong).toList();
        // 检查单词本是否存在，用户是否对单词本有读写权限
        WordBook wordBook = checkWordBookExistsAndHasPermission(wordBookId, userId);
        // 批量删除单词本中的单词
        QueryWrapper<WordBookDictionaryRelation> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("word_book_id", wordBookId).in("word_id", wordIds);
        wordBookDictionaryRelationMapper.delete(queryWrapper);
        // 更新单词本单词数量
        wordBook.setWordCount(wordBook.getWordCount() - wordIds.size());
        wordBookMapper.updateById(wordBook);
    }


    /**
     * 检查单词本是否存在，用户是否对单词本有读写权限
     */
    private WordBook checkWordBookExistsAndHasPermission(Long wordBookId, Long userId) {
        QueryWrapper<WordBook> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", wordBookId).eq("is_deleted", 0);
        WordBook wordBook = wordBookMapper.selectOne(queryWrapper);
        if (wordBook == null) {
            throw new BusinessException("单词本不存在");
        }
        // 检查用户是否对单词本有读写权限
        if (!userId.equals(wordBook.getUserId())) {
            throw new BusinessException("用户对单词本没有读写权限");
        }
        return wordBook;
    }

    /**
     * 按读权限取词书：系统词书所有人可读，自建词书仅创建者可读
     * @param wordBookId 词书 ID
     * @param userId 用户 ID
     * @return 词书实体
     */
    private WordBook getReadableWordBook(Long wordBookId, Long userId) {
        WordBook book = wordBookMapper.selectById(wordBookId);
        if (book == null || book.getIsDeleted() != 0
                || ("SYSTEM".equals(book.getBookType()) && !"PUBLISHED".equals(book.getPublishStatus()))
                || (!"SYSTEM".equals(book.getBookType()) && !userId.equals(book.getUserId()))) {
            throw new BusinessException("单词本不存在或无权访问");
        }
        return book;
    }
}
