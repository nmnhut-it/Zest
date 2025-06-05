package com.zps.zest.langchain4j.tools.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.util.indexing.FileBasedIndex;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for tools that need to access IntelliJ indices safely.
 */
public abstract class ThreadSafeIndexTool extends ThreadSafeCodeExplorationTool {
    
    protected ThreadSafeIndexTool(@NotNull Project project, String name, String description) {
        super(project, name, description);
    }
    
    /**
     * Ensures indices are ready before executing.
     */
    protected CodeExplorationTool.ToolResult executeWithIndices(IndexOperation operation) {
        // Check if we're in dumb mode
        if (DumbService.isDumb(project)) {
            return CodeExplorationTool.ToolResult.error("Project indices are being updated. Please try again in a moment.");
        }
        
        // Ensure we're in a read action for index access
        return ReadAction.compute(() -> {
            try {

                
                return operation.execute();
            } catch (Exception e) {
                return CodeExplorationTool.ToolResult.error("Index operation failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Operation to execute with index access.
     */
    @FunctionalInterface
    protected interface IndexOperation {
        CodeExplorationTool.ToolResult execute() throws Exception;
    }
}
