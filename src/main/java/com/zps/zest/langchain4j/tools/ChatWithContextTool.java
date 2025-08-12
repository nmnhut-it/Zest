package com.zps.zest.langchain4j.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool for chatting with automatic context retrieval.
 * Follows the CodeExplorationTool pattern.
 */
public class ChatWithContextTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(ChatWithContextTool.class);
    private static final Gson GSON = new Gson();
    
    private final ZestLangChain4jService langChainService;
    
    public ChatWithContextTool(@NotNull Project project) {
        super(project, "chat_with_context", 
            "Chat with automatic context retrieval from the codebase and conversation history");
        this.langChainService = project.getService(ZestLangChain4jService.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // message parameter
        JsonObject messageParam = new JsonObject();
        messageParam.addProperty("type", "string");
        messageParam.addProperty("description", "The chat message or question");
        properties.add("message", messageParam);
        
        // conversation_history parameter (optional)
        JsonObject historyParam = new JsonObject();
        historyParam.addProperty("type", "array");
        historyParam.addProperty("description", "Previous conversation history (optional)");
        
        JsonObject historyItemSchema = new JsonObject();
        historyItemSchema.addProperty("type", "string");
        historyParam.add("items", historyItemSchema);
        
        properties.add("conversation_history", historyParam);
        
        schema.add("properties", properties);
        
        // Required parameters
        JsonArray required = new JsonArray();
        required.add("message");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        return false; // This tool makes LLM calls, doesn't need IntelliJ read access
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        try {
            // Extract parameters
            String message = getRequiredString(parameters, "message");
            if (message.trim().isEmpty()) {
                return ToolResult.error("Message parameter is required");
            }
            
            // Extract conversation history
            List<String> conversationHistory = new ArrayList<>();
            JsonElement historyElement = parameters.get("conversation_history");
            if (historyElement != null && historyElement.isJsonArray()) {
                JsonArray historyArray = historyElement.getAsJsonArray();
                for (JsonElement item : historyArray) {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                        conversationHistory.add(item.getAsString());
                    }
                }
            }
            
            LOG.info("Processing chat message with " + conversationHistory.size() + " history items");
            
            // Execute chat with context synchronously (since we need to return ToolResult)
            String response = langChainService.chatWithContext(message, conversationHistory).get(30, java.util.concurrent.TimeUnit.SECONDS);
            
            JsonObject metadata = createMetadata();
            metadata.addProperty("message", message);
            metadata.addProperty("historyItems", conversationHistory.size());
            metadata.addProperty("contextUsed", true);
            
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("response", response);
            result.addProperty("message", "Chat completed successfully");
            result.addProperty("context_used", true);
            result.addProperty("history_items", conversationHistory.size());
            
            return ToolResult.success(result.toString(), metadata);
                
        } catch (Exception e) {
            LOG.error("Error in chat_with_context tool", e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }
    
    @Override
    public ToolCategory getCategory() {
        return ToolCategory.AI;
    }
}