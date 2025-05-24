package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Test action to manually trigger completion and test Tab behavior.
 */
public class TestTabCompletionAction extends AnAction {
    
    public TestTabCompletionAction() {
        super("🧪 Trigger Test Completion", 
              "Force trigger Zest completion to test Tab key behavior", 
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        
        if (editor == null || project == null) {
            Messages.showErrorDialog("No editor or project available", "Test Completion");
            return;
        }

        try {
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            
            // Check current state
            boolean hadCompletion = service.hasActiveCompletion(editor);
            
            if (hadCompletion) {
                Messages.showInfoMessage(
                    "There's already an active completion!\n\n" +
                    "Try pressing Tab now to see if it accepts the completion.\n" +
                    "If Tab cancels instead of accepting, use 'Diagnose Tab Completion Issue' action.",
                    "Active Completion Found"
                );
                return;
            }
            
            // Force trigger completion
            service.triggerCompletion(editor, true);
            
            // Give a moment for completion to appear
            Thread.sleep(100);
            
            boolean hasCompletionNow = service.hasActiveCompletion(editor);
            
            if (hasCompletionNow) {
                Messages.showInfoMessage(
                    "✅ Completion triggered successfully!\n\n" +
                    "Now try pressing Tab to accept it.\n\n" +
                    "Expected: Tab accepts the completion\n" +
                    "Problem: Tab cancels the completion\n\n" +
                    "If Tab cancels, use 'Diagnose Tab Completion Issue' action.",
                    "Test Completion Ready"
                );
            } else {
                Messages.showInfoMessage(
                    "No completion was generated.\n\n" +
                    "This could be because:\n" +
                    "• The cursor is not in a suitable position\n" +
                    "• The API is not responding\n" +
                    "• The completion service is disabled\n\n" +
                    "Try placing the cursor after some code (e.g., 'buffer.') and run again.",
                    "No Completion Generated"
                );
            }
            
        } catch (Exception ex) {
            Messages.showErrorDialog(
                "Error triggering completion: " + ex.getMessage(),
                "Test Failed"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
    }
}
