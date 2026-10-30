package com.hope.xueling.common.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI配置类
 * @author 谢光湘
 * @since 2026/1/26
 */
@Configuration
public class AIConfig {

    /**
     * 配置豆包聊天客户端
     * @param doubaoChatModel 豆包聊天模型
     * @return ChatClient
     */
    @Bean("doubaoChatClient")
    public ChatClient doubaoChatClient(@Qualifier("springDoubaoChatModel") ChatModel doubaoChatModel){
        return ChatClient.builder(doubaoChatModel)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }

    /**
     * 配置deepseek聊天客户端
     * @param deepseekChatModel deepseek聊天模型
     * @return ChatClient
     */
    @Bean("deepseekChatClient")
    public ChatClient deepseekChatClient(@Qualifier("springDeepseekChatModel") ChatModel deepseekChatModel) {
        return ChatClient.builder(deepseekChatModel)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }

    /**
     * 配置词典翻译专用的 DeepSeek Flash 客户端
     * @param deepseekFlashChatModel DeepSeek Flash 模型
     * @return ChatClient
     */
    @Bean("deepseekFlashChatClient")
    public ChatClient deepseekFlashChatClient(@Qualifier("springDeepseekFlashChatModel") ChatModel deepseekFlashChatModel) {
        return ChatClient.builder(deepseekFlashChatModel)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }

    /**
     * 词汇语境文章生成与用义复核使用硅基流动模型
     * @param siliconFlowChatModel 硅基流动聊天模型
     * @return ChatClient
     */
    @Bean("siliconFlowChatClient")
    public ChatClient siliconFlowChatClient(@Qualifier("springSiliconFlowChatModel") ChatModel siliconFlowChatModel) {
        return ChatClient.builder(siliconFlowChatModel)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }
}
