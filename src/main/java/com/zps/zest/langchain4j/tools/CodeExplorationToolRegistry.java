package com.zps.zest.langchain4j.tools;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.tools.impl.*;
import com.zps.zest.langchain4j.tools.impl.GenerateTestPlanTool;
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
        
        // File manipulation tools
        register(new CreateFileTool(project));
        register(new ReplaceInFileTool(project));
        
        // Project structure tools
        register(new GetProjectStructureTool(project));
        
        // Test plan generation tools
        register(new GenerateTestPlanTool(project));
        
        // Documentation search tool (if enabled)
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        if (config.isDocsSearchEnabled()) {
            register(new SearchDocsTool(project));
            LOG.info("Registered documentation search tool");
        }
        
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
        sb.append("Tools are organized by exploration phase:\n\n");
        
        // Group tools by category
        Map<String, List<CodeExplorationTool>> toolsByCategory = new HashMap<>();
        toolsByCategory.put("DISCOVERY", new ArrayList<>());
        toolsByCategory.put("ANALYSIS", new ArrayList<>());
        toolsByCategory.put("DETAIL", new ArrayList<>());
        
        // Categorize tools
        for (CodeExplorationTool tool : tools.values()) {
            String category = categorizeToolType(tool.getName());
            toolsByCategory.get(category).add(tool);
        }
        
        // Output by category with strategy hints
        sb.append("**DISCOVERY TOOLS** (start here for broad understanding):\n");
        for (CodeExplorationTool tool : toolsByCategory.get("DISCOVERY")) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n");
            sb.append("  Parameters: ").append(tool.getParameterSchema().toString()).append("\n");
        }
        
        sb.append("\n**ANALYSIS TOOLS** (dive deeper into specific elements):\n");
        for (CodeExplorationTool tool : toolsByCategory.get("ANALYSIS")) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n");
            sb.append("  Parameters: ").append(tool.getParameterSchema()).append("\n");
        }
        
        sb.append("\n**DETAIL TOOLS** (examine implementation specifics):\n");
        for (CodeExplorationTool tool : toolsByCategory.get("DETAIL")) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n");
            sb.append("  Parameters: ").append(tool.getParameterSchema()).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Categorizes a tool by its type/purpose.
     */
    private String categorizeToolType(String toolName) {
        // Discovery tools - broad search
        if (toolName.contains("search") || toolName.contains("find_by_name") || 
            toolName.equals("list_files_in_directory") || toolName.equals("get_current_context") ||
            toolName.equals("get_project_structure")) {
            return "DISCOVERY";
        }
        // Analysis tools - relationships and structure
        else if (toolName.contains("find_callers") || toolName.contains("find_implementations") || 
                 toolName.contains("find_relationships") || toolName.contains("find_usages")) {
            return "ANALYSIS";
        }
        // Detail tools - specific content and file operations
        else if (toolName.contains("generate_test_plan")) {
            return "ANALYSIS";
        }
        else {
            return "DETAIL";
        }
    }
    
    /**
     * Checks if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
