package com.zps.zest.langchain4j.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import org.jetbrains.annotations.NotNull;

/**
 * Tool for executing LLM tasks with optional context retrieval.
 * Follows the CodeExplorationTool pattern.
 */
public class TaskExecutionTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(TaskExecutionTool.class);
    private static final Gson GSON = new Gson();
    
    private final ZestLangChain4jService langChainService;
    
    public TaskExecutionTool(@NotNull Project project) {
        super(project, "execute_task", 
            "Execute an LLM task with optional context retrieval and provide step-by-step results");
        this.langChainService = project.getService(ZestLangChain4jService.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // task parameter
        JsonObject taskParam = new JsonObject();
        taskParam.addProperty("type", "string");
        taskParam.addProperty("description", "The task description to execute");
        properties.add("task", taskParam);
        
        // use_retrieval parameter (optional)
        JsonObject useRetrievalParam = new JsonObject();
        useRetrievalParam.addProperty("type", "boolean");
        useRetrievalParam.addProperty("description", "Whether to use context retrieval (default: true)");
        useRetrievalParam.addProperty("default", true);
        properties.add("use_retrieval", useRetrievalParam);
        
        // additional_context parameter (optional)
        JsonObject contextParam = new JsonObject();
        contextParam.addProperty("type", "string");
        contextParam.addProperty("description", "Additional context to include in the task");
        properties.add("additional_context", contextParam);
        
        schema.add("properties", properties);
        
        // Required parameters
        JsonArray required = new JsonArray();
        required.add("task");
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
            String task = getRequiredString(parameters, "task");
            if (task.trim().isEmpty()) {
                return ToolResult.error("Task parameter is required");
            }
            
            boolean useRetrieval = getOptionalBoolean(parameters, "use_retrieval", true);
            String additionalContext = getOptionalString(parameters, "additional_context", null);
            
            LOG.info("Executing task: " + task);
            
            // Execute task synchronously (since we need to return ToolResult)
            ZestLangChain4jService.TaskResult result = langChainService.executeTask(task, useRetrieval, additionalContext).get(60, java.util.concurrent.TimeUnit.SECONDS);
            
            JsonObject metadata = createMetadata();
            metadata.addProperty("task", task);
            metadata.addProperty("useRetrieval", useRetrieval);
            metadata.addProperty("success", result.isSuccess());
            
            if (result.isSuccess()) {
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", result.getMessage());
                
                if (result.getResult() != null) {
                    response.addProperty("result", result.getResult());
                }
                
                if (!result.getSteps().isEmpty()) {
                    response.add("steps", GSON.toJsonTree(result.getSteps()));
                }
                
                response.addProperty("used_retrieval", useRetrieval);
                
                metadata.addProperty("stepCount", result.getSteps().size());
                
                return ToolResult.success(response.toString(), metadata);
            } else {
                return ToolResult.error("Task execution failed: " + result.getMessage());
            }
                
        } catch (Exception e) {
            LOG.error("Error in execute_task tool", e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }
    
    @Override
    public ToolCategory getCategory() {
        return ToolCategory.AI;
    }
}