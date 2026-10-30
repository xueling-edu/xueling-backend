package com.hope.xueling.english.vocab.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hope.xueling.common.exception.BusinessException;
import com.hope.xueling.common.exception.ValidationException;
import com.hope.xueling.english.vocab.domain.dto.GenerateContextArticleDTO;
import com.hope.xueling.english.vocab.domain.dto.SubmitContextAttemptDTO;
import com.hope.xueling.english.vocab.domain.entity.ContextArticle;
import com.hope.xueling.english.vocab.domain.entity.ContextAttempt;
import com.hope.xueling.english.vocab.domain.entity.WordDictionary;
import com.hope.xueling.english.vocab.domain.vo.ContextArticleSummaryVO;
import com.hope.xueling.english.vocab.domain.vo.ContextArticleVO;
import com.hope.xueling.english.vocab.domain.vo.ContextAttemptVO;
import com.hope.xueling.english.vocab.mapper.ContextArticleMapper;
import com.hope.xueling.english.vocab.mapper.ContextAttemptMapper;
import com.hope.xueling.english.vocab.service.IContextArticleService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ContextArticleServiceImpl implements IContextArticleService {
    private static final String MODEL_NAME = "deepseekChatClient";
    private static final int DAILY_LIMIT = 10;
    private static final Set<String> TOPICS = Set.of("日常生活", "校园与学习", "旅行见闻", "科技与未来", "自然与环境", "职场与成长");
    private final ChatClient chatClient;
    private final ContextArticleMapper mapper;
    private final ContextAttemptMapper attemptMapper;
    private final ObjectMapper objectMapper;

    public ContextArticleServiceImpl(@Qualifier("deepseekChatClient") ChatClient chatClient,
                                     ContextArticleMapper mapper, ContextAttemptMapper attemptMapper,
                                     ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.mapper = mapper;
        this.attemptMapper = attemptMapper;
        this.objectMapper = objectMapper;
    }

    public record GeneratedText(String title, String content) {}
    public record MeaningReview(boolean approved) {}

    @Override
    public ContextArticleVO generate(long userId, GenerateContextArticleDTO request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().length() > 64) {
            throw new ValidationException("生成请求编号无效");
        }
        try {
            UUID.fromString(request.getRequestId());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("生成请求编号无效");
        }
        ContextArticle previous = findRequest(userId, request.getRequestId());
        if (previous != null) return existing(userId, previous);
        if (request.getWordIds() == null || request.getWordIds().size() < 5 || request.getWordIds().size() > 8) {
            throw new ValidationException("请选择 5 至 8 个已学单词");
        }
        List<Long> ids = new ArrayList<>();
        for (String raw : request.getWordIds()) {
            try {
                long id = Long.parseLong(raw);
                if (id <= 0) throw new NumberFormatException();
                ids.add(id);
            } catch (NumberFormatException exception) {
                throw new ValidationException("单词编号无效");
            }
        }
        if (new HashSet<>(ids).size() != ids.size()) throw new ValidationException("不能重复选择单词");
        String topic = normalizeTopic(request.getTopic());
        List<WordDictionary> words = mapper.selectLearnedWords(userId, ids);
        if (words.size() != ids.size() || words.stream().anyMatch(word -> word.getMeaning() == null || word.getMeaning().isBlank())) {
            throw new ValidationException("所选单词必须已学习且有释义");
        }
        Map<Long, WordDictionary> byId = words.stream().collect(Collectors.toMap(WordDictionary::getId, word -> word));
        words = ids.stream().map(byId::get).toList();
        if (mapper.countToday(userId) >= DAILY_LIMIT) throw new BusinessException("今天的生成次数已达上限，请明天再试");
        String targetsJson = json(words.stream().map(word ->
                new ContextArticleVO.TargetWordVO(Long.toString(word.getId()), word.getWord(), word.getMeaning(), List.<Integer>of())).toList());
        long articleId;
        try {
            ContextArticle reservation = new ContextArticle();
            reservation.setUserId(userId);
            reservation.setRequestId(request.getRequestId());
            reservation.setStatus("GENERATING");
            reservation.setTargetsJson(targetsJson);
            reservation.setTopic(topic);
            mapper.insert(reservation);
            articleId = reservation.getId();
        } catch (DuplicateKeyException exception) {
            ContextArticle concurrent = findRequest(userId, request.getRequestId());
            if (concurrent != null) return existing(userId, concurrent);
            throw new BusinessException("请求正在处理，请稍后查看记录");
        }
        try {
            String wordPrompt = words.stream().map(word -> word.getWord() + "：" + primaryMeaning(word.getMeaning()))
                    .collect(Collectors.joining("\n"));
            String userPrompt = buildUserPrompt(topic, wordPrompt);
            for (int attempt = 0; attempt < 2; attempt++) {
                GeneratedText result = chatClient.prompt()
                        .system("你是英语教学短文作者。请写一篇适合英语学习者的连贯英文短文，正文 120 到 180 个英文词。" +
                                "每个目标词必须以给定的原形完整出现至少一次，且在该处使用给定词义。不要为了塞词牺牲自然性。" +
                                "只返回 title 和 content 两个字段；content 为纯英文段落，不包含 Markdown、HTML、答案或词汇清单。")
                        .user(userPrompt).call().entity(GeneratedText.class);
                List<ContextArticleVO.TargetWordVO> targets = validate(result, words);
                if (targets != null && meaningMatches(result.content(), wordPrompt)) {
                    mapper.update(null, new LambdaUpdateWrapper<ContextArticle>()
                            .eq(ContextArticle::getId, articleId).eq(ContextArticle::getStatus, "GENERATING")
                            .set(ContextArticle::getStatus, "READY")
                            .set(ContextArticle::getTitle, result.title().trim())
                            .set(ContextArticle::getContent, result.content().trim())
                            .set(ContextArticle::getTargetsJson, json(targets))
                            .set(ContextArticle::getModelName, MODEL_NAME));
                    return get(userId, articleId);
                }
            }
            throw new BusinessException("这组词暂时未能生成自然的短文，请换一组词重试");
        } catch (RuntimeException exception) {
            mapper.update(null, new LambdaUpdateWrapper<ContextArticle>()
                    .eq(ContextArticle::getId, articleId).eq(ContextArticle::getStatus, "GENERATING")
                    .set(ContextArticle::getStatus, "FAILED"));
            if (exception instanceof BusinessException) throw exception;
            throw new BusinessException("文章生成暂时失败，请稍后重试");
        }
    }

    private String primaryMeaning(String meaning) {
        return meaning.split("[；\\n]", 2)[0].trim();
    }

    String normalizeTopic(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String topic = requested.trim();
        if (!TOPICS.contains(topic)) throw new ValidationException("文章主题选项无效");
        return topic;
    }

    String buildUserPrompt(String topic, String wordPrompt) {
        return (topic == null ? "" : "主题：" + topic + "\n") + "目标词及预期词义：\n" + wordPrompt;
    }

    private boolean meaningMatches(String content, String meanings) {
        MeaningReview review = chatClient.prompt()
                .system("你是严格的英语语义校对员。检查短文中每个目标词是否以指定词义自然使用，且短文连贯、适合学习者。" +
                        "仅在全部符合时返回 approved=true，否则返回 false。")
                .user("目标词及词义：\n" + meanings + "\n短文：\n" + content)
                .call().entity(MeaningReview.class);
        return review != null && review.approved();
    }

    private ContextArticle findRequest(long userId, String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<ContextArticle>()
                .eq(ContextArticle::getUserId, userId).eq(ContextArticle::getRequestId, requestId));
    }

    private ContextArticleVO existing(long userId, ContextArticle previous) {
        if ("READY".equals(previous.getStatus())) return get(userId, previous.getId());
        throw new BusinessException("该请求正在生成或已失败，请刷新记录或重新发起生成");
    }

    List<ContextArticleVO.TargetWordVO> validate(GeneratedText result, List<WordDictionary> words) {
        if (result == null || result.title() == null || result.content() == null ||
                result.title().isBlank() || result.title().length() > 200 || result.content().length() > 3000 ||
                result.content().contains("<") || result.content().contains("#")) return null;
        String content = result.content().trim();
        int count = content.split("\\s+").length;
        if (count < 100 || count > 220) return null;
        List<ContextArticleVO.TargetWordVO> targets = new ArrayList<>();
        for (WordDictionary word : words) {
            Matcher matcher = Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(word.getWord()) + "(?![A-Za-z])").matcher(content);
            List<Integer> offsets = new ArrayList<>();
            while (matcher.find()) offsets.add(matcher.start());
            if (offsets.isEmpty()) return null;
            targets.add(new ContextArticleVO.TargetWordVO(Long.toString(word.getId()), word.getWord(), word.getMeaning(), offsets));
        }
        return targets;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("文章数据序列化失败", exception);
        }
    }

    @Override
    public ContextArticleVO get(long userId, long articleId) {
        ContextArticle article = mapper.selectOne(new LambdaQueryWrapper<ContextArticle>()
                .eq(ContextArticle::getId, articleId).eq(ContextArticle::getUserId, userId)
                .eq(ContextArticle::getStatus, "READY"));
        if (article == null) throw new BusinessException("文章不存在");
        return toVO(article);
    }

    @Override
    public List<ContextArticleSummaryVO> list(long userId) {
        return mapper.selectList(new LambdaQueryWrapper<ContextArticle>()
                        .eq(ContextArticle::getUserId, userId).eq(ContextArticle::getStatus, "READY")
                        .orderByDesc(ContextArticle::getId).last("LIMIT 30"))
                .stream().map(article -> new ContextArticleSummaryVO(Long.toString(article.getId()),
                        article.getTitle(), article.getTopic(), targets(article.getTargetsJson()).size(),
                        article.getCreateTime())).toList();
    }

    private ContextArticleVO toVO(ContextArticle article) {
        return new ContextArticleVO(Long.toString(article.getId()), article.getTitle(), article.getContent(),
                article.getTopic(), targets(article.getTargetsJson()), article.getCreateTime());
    }

    private List<ContextArticleVO.TargetWordVO> targets(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("文章词条数据无法读取", exception);
        }
    }

    @Override
    public ContextAttemptVO submitAttempt(long userId, long articleId, SubmitContextAttemptDTO request) {
        ContextArticleVO article = get(userId, articleId);
        int total = Math.min(3, article.targets().size());
        if (request == null || request.getAnswers() == null || request.getAnswers().size() != total ||
                request.getAnswers().stream().anyMatch(answer -> answer == null || answer.length() > 80)) {
            throw new ValidationException("请完成全部语境填空");
        }
        int correct = 0;
        for (int i = 0; i < total; i++) {
            if (article.targets().get(i).word().equalsIgnoreCase(request.getAnswers().get(i).trim())) correct++;
        }
        ContextAttempt attempt = new ContextAttempt();
        attempt.setArticleId(articleId);
        attempt.setUserId(userId);
        attempt.setAnswersJson(json(request.getAnswers()));
        attempt.setCorrectCount(correct);
        attempt.setTotalCount(total);
        attemptMapper.insert(attempt);
        return new ContextAttemptVO(correct, total);
    }
}
