package com.zps.zest.langchain4j.tools;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.settings.ConfigurationManager;
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
        // Search and navigation tools
        register(new GrepSearchTool(project));  // Blazing fast ripgrep-based search
        register(new ListFilesInDirectoryTool(project));
        
        // Essential file operations
        register(new ReadFileTool(project));
        register(new CreateFileTool(project));
        register(new ReplaceInFileTool(project));
        
        // LangChain4j RAG and Agent tools
//        register(new RetrievalTool(project));
//        register(new TaskExecutionTool(project));
//        register(new WorkflowTool(project));
//        register(new ChatWithContextTool(project));
        
        // Other exploration tools have been removed with the cleanup
        
        LOG.info("Registered " + tools.size() + " essential exploration tools (including ripgrep search)");
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
        sb.append("1. Use grep_search for blazing fast pattern/regex searching across codebase\n");
        sb.append("2. Use list_files_in_directory to understand directory structures\n");
        sb.append("3. Use read_file to examine specific files found during search\n");
        sb.append("4. Use create_file and replace_in_file to implement solutions\n");
        sb.append("5. Combine grep_search with file operations for efficient exploration\n");
        sb.append("6. Use contextLines in grep_search to see surrounding code\n");
        sb.append("7. Use filePattern/excludePattern to narrow search scope\n");
        
        return sb.toString();
    }
    
    
    /**
     * Checks if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
