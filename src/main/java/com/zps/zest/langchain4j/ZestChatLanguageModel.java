package com.zps.zest.langchain4j;

import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wrapper around ZestLLMService to provide LangChain4j ChatLanguageModel interface
 * for query transformers and other advanced RAG features.
 */
public class ZestChatLanguageModel implements ChatLanguageModel {
    
    private final LLMService llmService;
    
    public ZestChatLanguageModel(LLMService llmService) {
        this.llmService = llmService;
    }
    
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            // Convert messages to a single prompt string
            String prompt = messages.stream()
                .map(message -> message.type().name() + ": " + message.text())
                .collect(Collectors.joining("\n"));
                
            // Use existing LLMService for query
            LLMService.LLMQueryParams params = new LLMService.LLMQueryParams(prompt)
                .withModel("local-model")
                .withMaxTokens(4000)
                .withTimeout(30000);
                
            String response = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);
            
            if (response != null) {
                return Response.from(AiMessage.from(response));
            } else {
                return Response.from(AiMessage.from("Unable to process request"));
            }
            
        } catch (Exception e) {
            return Response.from(AiMessage.from("Error: " + e.getMessage()));
        }
    }
}