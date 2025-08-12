package com.zps.zest.langchain4j.tools;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
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
     * Simplified to focus on essential tools that guide AI exploration.
     */
    private void registerDefaultTools() {
        // Essential file operations (only tools that still exist and work)
        register(new ReadFileTool(project));
        register(new ListFilesInDirectoryTool(project));
        register(new CreateFileTool(project));
        register(new ReplaceInFileTool(project));
        
        // Other exploration tools have been removed with the cleanup
        
        LOG.info("Registered " + tools.size() + " essential exploration tools (focusing on AI-guided exploration)");
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
     * Simplified for AI-guided exploration approach.
     */
    public String getToolsDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Essential tools for AI-guided codebase exploration:\n\n");
        
        for (CodeExplorationTool tool : tools.values()) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n\n");
        }
        
        sb.append("EXPLORATION STRATEGY:\n");
        sb.append("1. Use glob_search to find files by patterns (**.java, **README*, etc.)\n");
        sb.append("2. Use grep_search with parallel patterns to find relevant code content\n");
        sb.append("3. Use list_files_in_directory to understand directory structures\n");
        sb.append("4. Use read_file to examine specific files found during search\n");
        sb.append("5. Use create_file and replace_in_file to implement solutions\n");
        sb.append("6. Always run multiple searches in parallel for comprehensive coverage\n");
        
        return sb.toString();
    }
    
    
    /**
     * Checks if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
