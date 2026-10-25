package com.hope.xueling.english.vocab.service.impl;

import com.hope.xueling.english.vocab.domain.dto.WordDictionaryDTO;
import com.hope.xueling.english.vocab.domain.dto.WordTranslationDTO;
import com.hope.xueling.english.vocab.service.IWordDefinitionService;
import com.hope.xueling.english.vocab.util.WordNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 翻译服务实现类
 * @author 谢光湘
 * @since 2026/1/26
 */
@Slf4j
@Service
public class WordDefinitionServiceImpl implements IWordDefinitionService {
    private final ChatClient deepseekFlashChatClient;

    public WordDefinitionServiceImpl(@Qualifier("deepseekFlashChatClient") ChatClient deepseekFlashChatClient) {
        this.deepseekFlashChatClient = deepseekFlashChatClient;
    }

    /**
     * 调用大模型生成结构化词典条目，并做严格校验：返回词必须与输入词规范化后一致、义项不能为空、长度超限或格式异常时返回 null
     * @param word 待查的英文单词或短语
     * @return 词典条目，校验不通过时返回 null
     */
    @Override
    public WordDictionaryDTO translateWord(String word) {
        String systemPrompt = """
                你是英汉词典助手。只处理给出的英文单词或短语，输出 JSON 对象，不要 Markdown。
                格式：{"word":"原输入词","phonetic":"音标","translations":[{"pos":"n.","meanings":["中文义项"]}],"example":"英文例句","exampleTranslation":"例句翻译"}。
                一个词性可有多个义项；词性使用 n.、v.、adj. 等常见缩写。不能确认释义时返回 translations: []。
                不要改写 word，也不要凭空生成其他词条。音标或例句不能确认时用空字符串。
                """;
        WordDictionaryDTO result = deepseekFlashChatClient.prompt()
                .system(systemPrompt)
                .user("请翻译：" + word)
                .call()
                .entity(WordDictionaryDTO.class);
        if (result == null || result.getWord() == null || result.getWord().isBlank()) {
            return null;
        }
        try {
            if (!WordNormalizer.normalize(result.getWord()).equals(WordNormalizer.normalize(word))) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        List<WordTranslationDTO> translations = result.getTranslations();
        if (translations == null || translations.isEmpty() || translations.stream().anyMatch(item ->
                item == null || item.getMeanings() == null || item.getMeanings().isEmpty() ||
                        item.getMeanings().stream().anyMatch(s -> s == null || s.isBlank()))) {
            return null;
        }
        result.setMeaning(translations.stream().map(item ->
                (item.getPos() == null || item.getPos().isBlank() ? "其他" : item.getPos()) + " " +
                        item.getMeanings().stream().filter(Objects::nonNull).collect(Collectors.joining("；")))
                .collect(Collectors.joining("\n")));
        if (result.getMeaning().length() > 6000 ||
                (result.getExample() != null && result.getExample().length() > 500) ||
                (result.getExampleTranslation() != null && result.getExampleTranslation().length() > 500)) {
            return null;
        }
        return result;
    }
}
