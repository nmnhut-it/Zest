package com.zps.zest;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.tools.*;
import com.zps.zest.rag.RagSearchTool;
import com.zps.zest.langchain4j.EnhancedRagSearchTool;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry for managing and executing agent tools.
 */
public class AgentToolRegistry {
    private static final Logger LOG = Logger.getInstance(AgentToolRegistry.class);

    public final Project project;
    private final Map<String, AgentTool> tools = new HashMap<>();

    public AgentToolRegistry(Project project) {
        this.project = project;
        registerDefaultTools();
    }

    /**
     * Registers a tool with the registry.
     */
    public void register(AgentTool tool) {
        tools.put(tool.getName(), tool);
        LOG.info("Registered agent tool: " + tool.getName());
    }

    void removeAll(){
        this.tools.clear();
    }
    /**
     * Gets a tool by name.
     */
    public AgentTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Gets all registered tool names.
     */
    public Set<String> getToolNames() {
        return tools.keySet();
    }

    /**
     * Registers the default set of tools.
     * This is where you would add your custom tool implementations.
     */
    private void registerDefaultTools() {
        register(new ReadFileTool(project));
        register(new FindMethodsTool(project));
        register(new SearchClassesTool(project));
        register(new GetProjectStructureTool(project));
        register(new GetCurrentClassInfoTool(project));
        register(new CreateFileTool(project));
        register(new FindReferencesTool(project));
        register(new ListFilesTool(project));
        register(new AnalyzeCodeProblemsTool(project));
        register(new QuickAnalyzeCurrentFileTool(project));
        register(new SearchByRegexTool(project)); // Register the new tool
        register(new ReplaceInFileTool(project)); // Register the new tool
        register(new FollowUpQuestionTool(project)); // Register the new tool
        register(new RagSearchTool(project)); // Register the RAG search tool
        register(new EnhancedRagSearchTool(project)); // Register the enhanced LangChain4j RAG search tool

//        // Add the RAG tool
//        ConfigurationManager config = ConfigurationManager.getInstance(project);
//        register(new RagSearchTool(
//                project,
//                config.getOpenWebUIRagEndpoint(),
//                config.getAuthToken()
//        ));
    }
}