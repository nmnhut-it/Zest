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
 * Tool for executing multiple tasks in sequence as a workflow.
 * Follows the CodeExplorationTool pattern.
 */
public class WorkflowTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(WorkflowTool.class);
    private static final Gson GSON = new Gson();
    
    private final ZestLangChain4jService langChainService;
    
    public WorkflowTool(@NotNull Project project) {
        super(project, "execute_workflow", 
            "Execute multiple tasks in sequence, where each task can use context from previous tasks");
        this.langChainService = project.getService(ZestLangChain4jService.class);
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // tasks parameter
        JsonObject tasksParam = new JsonObject();
        tasksParam.addProperty("type", "array");
        tasksParam.addProperty("description", "Array of task descriptions to execute in sequence");
        
        JsonObject itemsSchema = new JsonObject();
        itemsSchema.addProperty("type", "string");
        tasksParam.add("items", itemsSchema);
        
        properties.add("tasks", tasksParam);
        
        schema.add("properties", properties);
        
        // Required parameters
        JsonArray required = new JsonArray();
        required.add("tasks");
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
            // Extract tasks array
            JsonElement tasksElement = parameters.get("tasks");
            if (tasksElement == null || !tasksElement.isJsonArray()) {
                return ToolResult.error("Tasks parameter must be an array");
            }
            
            JsonArray tasksArray = tasksElement.getAsJsonArray();
            if (tasksArray.size() == 0) {
                return ToolResult.error("At least one task is required");
            }
            
            List<String> tasks = new ArrayList<>();
            for (JsonElement taskElement : tasksArray) {
                if (taskElement.isJsonPrimitive() && taskElement.getAsJsonPrimitive().isString()) {
                    tasks.add(taskElement.getAsString());
                } else {
                    return ToolResult.error("All tasks must be strings");
                }
            }
            
            LOG.info("Executing workflow with " + tasks.size() + " tasks");
            
            // Execute workflow synchronously (since we need to return ToolResult)
            ZestLangChain4jService.WorkflowResult result = langChainService.executeWorkflow(tasks).get(120, java.util.concurrent.TimeUnit.SECONDS);
            
            JsonObject metadata = createMetadata();
            metadata.addProperty("totalTasks", tasks.size());
            metadata.addProperty("success", result.isSuccess());
            
            JsonObject response = new JsonObject();
            response.addProperty("success", result.isSuccess());
            response.addProperty("summary", result.getSummary());
            
            // Add detailed task results
            JsonArray taskResults = new JsonArray();
            for (int i = 0; i < result.getTaskResults().size(); i++) {
                ZestLangChain4jService.TaskResult taskResult = result.getTaskResults().get(i);
                
                JsonObject taskJson = new JsonObject();
                taskJson.addProperty("task_index", i);
                taskJson.addProperty("task_description", tasks.get(i));
                taskJson.addProperty("success", taskResult.isSuccess());
                taskJson.addProperty("message", taskResult.getMessage());
                
                if (taskResult.getResult() != null) {
                    taskJson.addProperty("result", taskResult.getResult());
                }
                
                if (!taskResult.getSteps().isEmpty()) {
                    taskJson.add("steps", GSON.toJsonTree(taskResult.getSteps()));
                }
                
                taskResults.add(taskJson);
            }
            
            response.add("task_results", taskResults);
            response.addProperty("total_tasks", tasks.size());
            response.addProperty("completed_tasks", result.getTaskResults().size());
            
            metadata.addProperty("completedTasks", result.getTaskResults().size());
            
            return ToolResult.success(response.toString(), metadata);
                
        } catch (Exception e) {
            LOG.error("Error in execute_workflow tool", e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }
    
    @Override
    public ToolCategory getCategory() {
        return ToolCategory.AI;
    }
}