package com.easychat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI大模型配置类
 * Spring AI会根据application.properties中的配置自动创建ChatModel，
 * 这里基于ChatModel构建ChatClient供业务使用。
 */
@Configuration
public class AiChatConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
