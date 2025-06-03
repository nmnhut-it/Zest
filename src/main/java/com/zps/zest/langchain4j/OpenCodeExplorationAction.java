package com.zps.zest.langchain4j;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.zps.zest.langchain4j.ui.CodeExplorationAndCodingPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Action to open the code exploration and coding assistant.
 */
public class OpenCodeExplorationAction extends AnAction {
    
    private static final String TOOL_WINDOW_ID = "Code Explorer";
    
    public OpenCodeExplorationAction() {
        super("Code Explorer & Assistant", 
              "Open the AI-powered code exploration and coding assistant", 
              AllIcons.Actions.Search);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get or create tool window
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
        
        if (toolWindow == null) {
            // Register tool window if not exists
            toolWindowManager.registerToolWindow(
                TOOL_WINDOW_ID,
                true,
                com.intellij.openapi.wm.ToolWindowAnchor.BOTTOM,
                project,
                true
            );
            toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
            
            if (toolWindow != null) {
                // Create content
                CodeExplorationAndCodingPanel panel = new CodeExplorationAndCodingPanel(project);
                Content content = ContentFactory.SERVICE.getInstance()
                    .createContent(panel, "", false);
                toolWindow.getContentManager().addContent(content);
                
                // Set icon
                toolWindow.setIcon(AllIcons.Actions.Search);
            }
        }
        
        if (toolWindow != null) {
            toolWindow.show();
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
    
    /**
     * Tool window factory for persistent tool window.
     */
    public static class CodeExplorationToolWindowFactory implements ToolWindowFactory {
        @Override
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
            CodeExplorationAndCodingPanel panel = new CodeExplorationAndCodingPanel(project);
            Content content = ContentFactory.SERVICE.getInstance()
                .createContent(panel, "", false);
            toolWindow.getContentManager().addContent(content);
        }
    }
}
