package com.zps.zest.autocompletion2.debug;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

/**
 * Test actions for Zest Autocomplete v2.
 * These can be added to the plugin menu for testing.
 */
public class TestActions {
    
    /**
     * Action to show diagnostic information.
     */
    public static class DiagnosticAction extends AnAction {
        public DiagnosticAction() {
            super("🔍 Autocomplete v2 Diagnostic", 
                  "Show diagnostic information for Zest Autocomplete v2", 
                  null);
        }
        
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor != null) {
                DebugTools.showDiagnostic(editor);
            }
        }
        
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
        }
    }
    
    /**
     * Action to create a test completion.
     */
    public static class CreateTestCompletionAction extends AnAction {
        public CreateTestCompletionAction() {
            super("🧪 Create Test Completion", 
                  "Create a test completion to verify Tab functionality", 
                  null);
        }
        
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor != null) {
                DebugTools.createTestCompletion(editor);
            }
        }
        
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
        }
    }
    
    /**
     * Action to demonstrate progressive Tab acceptance.
     */
    public static class ProgressiveDemoAction extends AnAction {
        public ProgressiveDemoAction() {
            super("🔄 Progressive Tab Demo", 
                  "Demonstrate progressive Tab acceptance (word → line → full)", 
                  null);
        }
        
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor != null) {
                DebugTools.demonstrateProgressive(editor);
            }
        }
        
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
        }
    }
    
    /**
     * Action to install/reinstall the Tab handler.
     */
    public static class InstallTabHandlerAction extends AnAction {
        public InstallTabHandlerAction() {
            super("⚙️ Install Tab Handler", 
                  "Install or reinstall the Tab handler for Zest Autocomplete v2", 
                  null);
        }
        
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            com.zps.zest.autocompletion2.core.TabHandler.install();
            
            com.intellij.openapi.ui.Messages.showInfoMessage(
                e.getProject(),
                "✅ Tab handler installed successfully!\n\n" +
                "Zest Autocomplete v2 Tab handling is now active.",
                "Tab Handler Installed"
            );
        }
        
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null);
        }
    }
    
    /**
     * Action to log debug information.
     */
    public static class DebugLogAction extends AnAction {
        public DebugLogAction() {
            super("📝 Log Debug Info", 
                  "Log detailed debug information to console", 
                  null);
        }
        
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor != null) {
                DebugTools.logDebugInfo(editor);
                
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    e.getProject(),
                    "Debug information logged to console.\n\n" +
                    "Check the IDE logs for detailed output.",
                    "Debug Info Logged"
                );
            }
        }
        
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
        }
    }
}
