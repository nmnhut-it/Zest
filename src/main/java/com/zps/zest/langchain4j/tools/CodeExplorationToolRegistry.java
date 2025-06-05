package com.zps.zest.langchain4j.tools;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.tools.impl.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Registry for code exploration tools used by the autonomous agent.
 */
@Service(Service.Level.PROJECT)
public final class CodeExplorationToolRegistry {
    private static final Logger LOG = Logger.getInstance(CodeExplorationToolRegistry.class);
    
    private final Map<String, CodeExplorationTool> tools = new HashMap<>();
    private final Project project;
    
    public CodeExplorationToolRegistry(@NotNull Project project) {
        this.project = project;
        registerDefaultTools();
    }
    
    /**
     * Registers default code exploration tools.
     */
    private void registerDefaultTools() {
        // Core search tools
        register(new SearchCodeTool(project));
        register(new FindByNameTool(project));
//        register(new FindSimilarTool(project));
        register(new FindRelationshipsTool(project));
        
        // File and content tools
        register(new ReadFileTool(project));
        register(new ListFilesInDirectoryTool(project));
        
        // Structural analysis tools
        register(new FindCallersTool(project));
        register(new FindImplementationsTool(project));
        register(new FindMethodsTool(project));
        register(new GetClassInfoTool(project));
        
        // Navigation tools
        register(new GetCurrentContextTool(project));
        register(new FindUsagesTool(project));
        
        LOG.info("Registered " + tools.size() + " code exploration tools");
    }
    
    /**
     * Registers a tool in the registry.
     */
    public void register(CodeExplorationTool tool) {
        tools.put(tool.getName(), tool);
        LOG.debug("Registered tool: " + tool.getName());
    }
    
    /**
     * Gets a tool by name.
     */
    public CodeExplorationTool getTool(String name) {
        return tools.get(name);
    }
    
    /**
     * Gets all registered tools.
     */
    public Collection<CodeExplorationTool> getAllTools() {
        return new ArrayList<>(tools.values());
    }
    
    /**
     * Gets tool names and descriptions for LLM context.
     */
    public String getToolsDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n\n");
        
        for (CodeExplorationTool tool : tools.values()) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n");
            sb.append("  Parameters: ").append(tool.getParameterSchema().toString()).append("\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Checks if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
