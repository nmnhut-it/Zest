package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.util.LLMUsage;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Chat language model using NaiveLLMService for LLM interactions.
 */
public class ZestChatLanguageModel implements ChatModel {
    private static final Logger LOG = Logger.getInstance(ZestChatLanguageModel.class);

    private final NaiveLLMService llmService;
    private final LLMUsage usage;

    public ZestChatLanguageModel(@NotNull NaiveLLMService llmService, @NotNull LLMUsage usage) {
        this.llmService = llmService;
        this.usage = usage;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            String prompt = messagesToString(request.messages());

            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(prompt)
                    .withMaxTokens(8000)
                    .withTimeout(120000);

            String response = llmService.queryWithParams(params, usage);

            if (response == null) {
                response = "";
            }

            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();

        } catch (Exception e) {
            LOG.error("Chat request failed", e);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("Error: " + e.getMessage()))
                    .build();
        }
    }

    private String messagesToString(List<ChatMessage> messages) {
        return messages.stream()
                .map(msg -> msg.type().name() + ": " + getMessageText(msg))
                .collect(Collectors.joining("\n"));
    }

    private String getMessageText(ChatMessage message) {
        if (message instanceof dev.langchain4j.data.message.UserMessage) {
            return ((dev.langchain4j.data.message.UserMessage) message).singleText();
        } else if (message instanceof dev.langchain4j.data.message.SystemMessage) {
            return ((dev.langchain4j.data.message.SystemMessage) message).text();
        } else if (message instanceof AiMessage) {
            return ((AiMessage) message).text();
        }
        return message.toString();
    }

    public void cancelAll() {
        // No-op for now
    }

    public void reset() {
        // No-op for now
    }
}
