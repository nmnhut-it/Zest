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
import java.util.Map;
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
            prompt.append("You are an AI assistant with access to tools/functions.\n\n");
            prompt.append("Available tools:\n");
            prompt.append("```\n");
            for (ToolSpecification tool : toolSpecifications) {
                prompt.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
                if (tool.parameters() != null) {
                    // Convert parameters to readable format
                    String paramsJson = tool.parameters().toString();
                    if (!paramsJson.isEmpty() && !paramsJson.equals("{}")) {
                        prompt.append("  Parameters: ").append(paramsJson).append("\n");
                    }
                }
            }
            prompt.append("```\n\n");
            
            prompt.append("IMPORTANT INSTRUCTIONS FOR TOOL USE:\n");
            prompt.append("1. When you need to use a tool, respond with ONLY this format:\n");
            prompt.append("   TOOL_CALL: {\"tool\": \"<tool_name>\", \"arguments\": {<arguments>}}\n");
            prompt.append("2. CRITICAL: Do NOT include ANY text before or after TOOL_CALL\n");
            prompt.append("3. Wrong: \"I'll read the file. TOOL_CALL: {...}\"\n");
            prompt.append("4. Right: \"TOOL_CALL: {...}\"\n");
            prompt.append("5. After receiving tool results, you can either:\n");
            prompt.append("   - Call another tool (using TOOL_CALL: format only)\n");
            prompt.append("   - Provide your final answer (regular text, no TOOL_CALL)\n");
            prompt.append("6. Make one tool call per response\n");
            prompt.append("7. Tool calls and text responses must be separate\n");
            prompt.append("\n");
        }
        
        // Add messages
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                prompt.append("System: ").append(message.text()).append("\n");
            } else if (message instanceof UserMessage) {
                prompt.append("User: ").append(message.text()).append("\n");
            } else if (message instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) message;
                if (aiMsg.hasToolExecutionRequests()) {
                    // Show the tool calls that were made
                    prompt.append("Assistant called tools: ");
                    for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                        prompt.append(req.name()).append(" with ").append(req.arguments()).append("\n");
                    }
                } else {
                    prompt.append("Assistant: ").append(message.text()).append("\n");
                }
            } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage) {
                dev.langchain4j.data.message.ToolExecutionResultMessage toolResult = 
                    (dev.langchain4j.data.message.ToolExecutionResultMessage) message;
                prompt.append("Tool Result:\n```\n").append(toolResult.text()).append("\n```\n");
                prompt.append("Based on this result, you can either call another tool or provide your final answer.\n");
            } else {
                prompt.append(message.type().name()).append(": ").append(message.text()).append("\n");
            }
        }
        
        prompt.append("Assistant: ");
        return prompt.toString();
    }
    
    private Response<AiMessage> parseResponseForTools(String response, List<ToolSpecification> toolSpecifications) {
        // First check for our TOOL_CALL marker
        if (!toolSpecifications.isEmpty() && response.contains("TOOL_CALL:")) {
            try {
                // Extract the tool call JSON after the marker
                int markerIndex = response.indexOf("TOOL_CALL:");
                if (markerIndex >= 0) {
                    // Check if there's explanatory text before the tool call
                    String beforeMarker = response.substring(0, markerIndex).trim();
                    boolean hasPreamble = !beforeMarker.isEmpty() && 
                        !beforeMarker.toLowerCase().matches(".*(i'll|i will|let me|going to|need to).*");
                    
                    // If there's substantial text before the tool call, we might want to show it
                    // But for now, we prioritize the tool call to ensure it gets executed
                    
                    String afterMarker = response.substring(markerIndex + "TOOL_CALL:".length()).trim();
                    
                    // Find the JSON object
                    int jsonStart = afterMarker.indexOf("{");
                    int jsonEnd = findMatchingBrace(afterMarker, jsonStart);
                    
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        String jsonStr = afterMarker.substring(jsonStart, jsonEnd + 1);
                        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                        
                        String toolName = json.get("tool").getAsString();
                        JsonObject arguments = json.has("arguments") && !json.get("arguments").isJsonNull() 
                            ? json.getAsJsonObject("arguments") 
                            : new JsonObject();
                        
                        // Validate tool exists
                        boolean validTool = toolSpecifications.stream()
                            .anyMatch(spec -> spec.name().equals(toolName));
                        
                        if (validTool) {
                            // Create tool execution request
                            ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                                .name(toolName)
                                .arguments(arguments.toString())
                                .build();
                            
                            // Return AI message with tool execution request
                            // Note: We ignore any text before/after the tool call to ensure execution
                            return Response.from(AiMessage.from(toolRequest));
                        }
                    }
                }
            } catch (Exception e) {
                // Log but don't fail - treat as regular response
                System.err.println("Failed to parse tool call: " + e.getMessage());
            }
        }
        
        // Fallback: Check for raw JSON format (for backward compatibility)
        if (!toolSpecifications.isEmpty() && response.contains("\"tool\"") && response.contains("{")) {
            try {
                // Try to extract JSON even without marker
                int jsonStart = response.indexOf("{");
                int jsonEnd = findMatchingBrace(response, jsonStart);
                
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    String jsonStr = response.substring(jsonStart, jsonEnd + 1);
                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                    
                    if (json.has("tool")) {
                        String toolName = json.get("tool").getAsString();
                        JsonObject arguments = json.has("arguments") && !json.get("arguments").isJsonNull()
                            ? json.getAsJsonObject("arguments")
                            : new JsonObject();
                        
                        // Verify this is actually a tool we know about
                        boolean validTool = toolSpecifications.stream()
                            .anyMatch(spec -> spec.name().equals(toolName));
                        
                        if (validTool) {
                            ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                                .name(toolName)
                                .arguments(arguments.toString())
                                .build();
                            
                            return Response.from(AiMessage.from(toolRequest));
                        }
                    }
                }
            } catch (Exception e) {
                // Silent fallback to regular response
            }
        }
        
        // Regular text response - no tool call detected
        return Response.from(AiMessage.from(response));
    }
    
    private int findMatchingBrace(String str, int startIndex) {
        if (startIndex < 0 || startIndex >= str.length()) {
            return -1;
        }
        
        int braceCount = 0;
        boolean inString = false;
        char prevChar = '\0';
        
        for (int i = startIndex; i < str.length(); i++) {
            char c = str.charAt(i);
            
            // Handle string literals
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            }
            
            if (!inString) {
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        return i;
                    }
                }
            }
            
            prevChar = c;
        }
        
        return -1;
    }
}