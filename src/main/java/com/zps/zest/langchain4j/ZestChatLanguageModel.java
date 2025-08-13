package com.zps.zest.langchain4j;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wrapper around ZestLLMService to provide LangChain4j ChatLanguageModel interface
 * for query transformers and other advanced RAG features.
 * Now supports tool calling for LangChain4j agents.
 */
public class ZestChatLanguageModel implements ChatLanguageModel {
    
    private final LLMService llmService;
    private final Gson gson = new Gson();
    
    public ZestChatLanguageModel(LLMService llmService) {
        this.llmService = llmService;
    }
    
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        // Delegate to the tool-supporting version with empty tool list
        return generate(messages, new ArrayList<>());
    }
    
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        try {
            // Build prompt with tool specifications if provided
            String prompt = buildPromptWithTools(messages, toolSpecifications);
                
            // Use existing LLMService for query
            LLMService.LLMQueryParams params = new LLMService.LLMQueryParams(prompt)
                .withModel("local-model")
                .withMaxTokens(4000)
                .withTimeout(30000);
                
            String response = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);
            
            if (response != null) {
                // Parse response for tool calls
                return parseResponseForTools(response, toolSpecifications);
            } else {
                return Response.from(AiMessage.from("Unable to process request"));
            }
            
        } catch (Exception e) {
            return Response.from(AiMessage.from("Error: " + e.getMessage()));
        }
    }
    
    private String buildPromptWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        StringBuilder prompt = new StringBuilder();
        
        // Add tool specifications to the prompt if provided
        if (!toolSpecifications.isEmpty()) {
            prompt.append("You have access to the following tools:\n\n");
            for (ToolSpecification tool : toolSpecifications) {
                prompt.append("Tool: ").append(tool.name()).append("\n");
                prompt.append("Description: ").append(tool.description()).append("\n");
                if (tool.parameters() != null) {
                    prompt.append("Parameters: ").append(tool.parameters().toString()).append("\n");
                }
                prompt.append("\n");
            }
            prompt.append("To use a tool, respond with a JSON object in this format:\n");
            prompt.append("{\"tool\": \"tool_name\", \"arguments\": {\"param1\": \"value1\"}}\n");
            prompt.append("\nConversation:\n\n");
        }
        
        // Add messages
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                prompt.append("System: ").append(message.text()).append("\n");
            } else if (message instanceof UserMessage) {
                prompt.append("User: ").append(message.text()).append("\n");
            } else if (message instanceof AiMessage) {
                prompt.append("Assistant: ").append(message.text()).append("\n");
            } else {
                prompt.append(message.type().name()).append(": ").append(message.text()).append("\n");
            }
        }
        
        prompt.append("Assistant: ");
        return prompt.toString();
    }
    
    private Response<AiMessage> parseResponseForTools(String response, List<ToolSpecification> toolSpecifications) {
        // Check if response contains a tool call
        if (!toolSpecifications.isEmpty() && response.contains("\"tool\"") && response.contains("\"arguments\"")) {
            try {
                // Extract JSON from response
                int jsonStart = response.indexOf("{");
                int jsonEnd = response.lastIndexOf("}") + 1;
                
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    String jsonStr = response.substring(jsonStart, jsonEnd);
                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                    
                    String toolName = json.get("tool").getAsString();
                    JsonObject arguments = json.getAsJsonObject("arguments");
                    
                    // Create tool execution request
                    ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                        .name(toolName)
                        .arguments(arguments.toString())
                        .build();
                    
                    // Return AI message with tool execution request
                    AiMessage aiMessage = AiMessage.from(toolRequest);
                    return Response.from(aiMessage);
                }
            } catch (Exception e) {
                // If parsing fails, treat as regular response
            }
        }
        
        // Regular text response
        return Response.from(AiMessage.from(response));
    }
}