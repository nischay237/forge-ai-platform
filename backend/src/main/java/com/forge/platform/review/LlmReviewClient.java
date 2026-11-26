package com.forge.platform.review;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Isolated Spring AI provider client so Resilience4j @CircuitBreaker and @Retry proxies
 * genuinely intercept network/LLM failures and enforce bounded execution timeouts.
 */
@Component
public class LlmReviewClient {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final String openAiApiKey;
    private final int timeoutSeconds;

    public LlmReviewClient(
        ObjectProvider<ChatModel> chatModelProvider,
        @Value("${forge.ai.openai-api-key:}") String openAiApiKey,
        @Value("${forge.ai.timeout-seconds:20}") int timeoutSeconds
    ) {
        this.chatModelProvider = chatModelProvider;
        this.openAiApiKey = openAiApiKey;
        this.timeoutSeconds = Math.max(5, Math.min(90, timeoutSeconds));
    }

    public boolean isProviderAvailable() {
        return openAiApiKey != null
            && !openAiApiKey.isBlank()
            && !openAiApiKey.startsWith("disabled-")
            && chatModelProvider.getIfAvailable() != null;
    }

    @CircuitBreaker(name = "aiReviewEngine")
    @Retry(name = "aiReviewEngine")
    public String requestStructuredReview(String systemInstructions, String untrustedUserPayload) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel bean unavailable");
        }
        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemInstructions),
            new UserMessage(untrustedUserPayload)
        ));
        try {
            return CompletableFuture
                .supplyAsync(() -> chatModel.call(prompt).getResult().getOutput().getText())
                .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("LLM review call failed or timed out after " + timeoutSeconds + "s", ex);
        }
    }
}
