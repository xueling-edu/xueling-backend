package com.hope.xueling.common.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 大语言模型配置类
 * @author 谢光湘
 * @since 2026/1/26
 */
@Configuration
public class LLMConfig {
    /**
     * 豆包模型的相关配置
     */
    @Value("${llm.doubao.api-key}")
    private String doubaoApiKey;
    /**
     * 豆包模型名称
     */
    @Value("${llm.doubao.model}")
    private String doubaoModel;
    /**
     * 豆包 OpenAI 兼容接口地址
     */
    @Value("${llm.doubao.url}")
    private String doubaoUrl;
    /**
     * DeepSeekChat模型的相关配置
     */
    @Value("${llm.deepseek.api-key}")
    private String deepSeekApiKey;
    /**
     * DeepSeek OpenAI 兼容接口地址
     */
    @Value("${llm.deepseek.url}")
    private String deepSeekUrl;
    /**
     * DeepSeek 对话模型名称
     */
    @Value("${llm.deepseek.chat-model}")
    private String deepSeekChatModel;
    /**
     * DeepSeek Flash 模型名称，供词典缺失释义时使用
     */
    @Value("${llm.deepseek.flash-model:deepseek-flash}")
    private String deepSeekFlashModel;
    /**
     * DeepSeek 推理模型名称
     */
    @Value("${llm.deepseek.reasoner-model}")
    private String deepSeekReasonerModel;
    /**
     * 硅基流动 OpenAI 兼容接口配置，供词汇语境文章生成使用
     */
    @Value("${llm.siliconflow.api-key}")
    private String siliconFlowApiKey;
    @Value("${llm.siliconflow.url}")
    private String siliconFlowUrl;
    @Value("${llm.siliconflow.model}")
    private String siliconFlowModel;
    /**
     * 智普模型相关配置
     */
    @Value("${llm.zhipu.api-key}")
    private String zhipuApiKey;
    /**
     * 智谱 OpenAI 兼容接口地址
     */
    @Value("${llm.zhipu.url}")
    private String zhipuUrl;
    /**
     * 智谱图像生成模型名称
     */
    @Value("${llm.zhipu.image-model}")
    private String zhipuImageModel;



    /**
     * 配置豆包聊天模型
     * @return ChatModel
     */
    @Bean("springDoubaoChatModel")
    public ChatModel doubaoChatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(doubaoApiKey)
                .baseUrl(doubaoUrl)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(doubaoModel)
                        .build())
                .build();
    }


    /**
     * 配置DeepSeek聊天模型
     * @return ChatModel
     */
    @Bean("springDeepseekChatModel")
    public ChatModel deepseekChatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(deepSeekApiKey)
                .baseUrl(deepSeekUrl)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(deepSeekChatModel)
                        .build())
                .build();
    }

    /**
     * 配置词典翻译专用的 DeepSeek Flash 模型
     * @return ChatModel
     */
    @Bean("springDeepseekFlashChatModel")
    public ChatModel deepseekFlashChatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(deepSeekApiKey)
                .baseUrl(deepSeekUrl)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(deepSeekFlashModel)
                        .build())
                .build();
    }

    /**
     * 词汇语境文章专用模型。OpenAiApi 会将 completionsPath 接在基础地址后。
     * @return ChatModel
     */
    @Bean("springSiliconFlowChatModel")
    public ChatModel siliconFlowChatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(siliconFlowApiKey)
                .baseUrl(siliconFlowUrl)
                .completionsPath("/v1/chat/completions")
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(siliconFlowModel)
                        .build())
                .build();
    }


    //langchain4j

    /**
     * 配置deepseek-chat模型
     * @return ChatLanguageModel
     */
    @Bean("DeepseekChatModel")
    public ChatLanguageModel DeepSeekChatModel() {
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(deepSeekUrl)
                .modelName(deepSeekChatModel)
                .apiKey(deepSeekApiKey)
                .maxTokens(10000)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(600))
                .build();
    }

    /**
     * 配置deepseek-chat流式输出模型
     * @return ChatLanguageModel
     */
    @Bean("DeepseekChatStreamingModel")
    public StreamingChatLanguageModel DeepSeekChatStreamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(deepSeekUrl)
                .modelName(deepSeekChatModel)
                .apiKey(deepSeekApiKey)
                .maxTokens(10000)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(600))
                .build();
    }
    /**
     * 配置deepseek-reasoner模型
     * @return ChatLanguageModel
     */
    @Bean("DeepseekReasonerModel")
    public ChatLanguageModel DeepSeekReasonerModel() {
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(deepSeekUrl)
                .modelName(deepSeekReasonerModel)
                .apiKey(deepSeekApiKey)
                .maxTokens(10000)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(600))
                .build();
    }
    /**
     * 配置deepseek-reasoner流式输出模型
     * @return ChatLanguageModel
     */
     @Bean("DeepseekReasonerStreamingModel")
     public StreamingChatLanguageModel DeepSeekReasonerStreamingModel() {
         return OpenAiStreamingChatModel.builder()
                 .baseUrl(deepSeekUrl)
                 .modelName(deepSeekReasonerModel)
                 .apiKey(deepSeekApiKey)
                 .maxTokens(10000)
                 .logRequests(true)
                 .logResponses(true)
                 .timeout(Duration.ofSeconds(600))
                 .build();
     }

    /**
     * 配置智普图像模型
     * @return ChatLanguageModel
     */
    @Bean("ZhiPuImageModel")
    public ImageModel ZhiPuImageModel() {
        return OpenAiImageModel.builder()
                .baseUrl(zhipuUrl)
                .modelName(zhipuImageModel)
                .apiKey(zhipuApiKey)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
