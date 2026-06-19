package com.taskscope.workers.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnthropicLlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private final AnthropicClient client;

    public AnthropicLlmClient(@Value("${anthropic.api-key:}") String apiKey) {
        // api-key가 비어 있으면 ANTHROPIC_API_KEY 환경변수에서 자동으로 읽음
        this.client = apiKey.isBlank()
                ? AnthropicOkHttpClient.fromEnv()
                : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        log.info("[anthropic] client initialized (apiKey from {})",
                apiKey.isBlank() ? "env" : "config");
    }

    /**
     * @param model        "claude-haiku-4-5" or "claude-sonnet-4-6"
     * @param systemPrompt worker-specific instruction
     * @param userMessage  diff + task context
     * @return Anthropic Message response (usage 포함)
     */
    public Message call(String model, String systemPrompt, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(2048L)
                .system(systemPrompt)
                .addUserMessage(userMessage)
                .build();
        return client.messages().create(params);
    }
}
