package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.handlers.ZestSmartTabHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Test action to verify the smart TAB handler is installed correctly.
 */
public class TestTabHandlerAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestTabHandlerAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            Messages.showErrorDialog("No editor or project available", "Test Tab Handler");
            return;
        }

        try {
            // Check if service is available
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            boolean hasCompletion = service.hasActiveCompletion(editor);
            
            // Check if handler is installed
            com.intellij.openapi.editor.actionSystem.EditorActionManager actionManager = 
                com.intellij.openapi.editor.actionSystem.EditorActionManager.getInstance();
            com.intellij.openapi.editor.actionSystem.EditorActionHandler tabHandler = actionManager.getActionHandler("EditorTab");
            boolean isZestHandler = tabHandler instanceof ZestSmartTabHandler;
            
            String message = String.format(
                "Zest Smart Tab Handler Test Results:\n\n" +
                "✓ Service Available: %s\n" +
                "✓ Active Completion: %s\n" +
                "✓ Handler Installed: %s\n" +
                "✓ Handler Type: %s\n\n" +
                "Status: %s",
                service != null ? "YES" : "NO",
                hasCompletion ? "YES" : "NO", 
                isZestHandler ? "YES" : "NO",
                tabHandler.getClass().getSimpleName(),
                isZestHandler ? "✅ WORKING" : "❌ NOT INSTALLED"
            );
            
            LOG.info("Tab Handler Test: " + message);
            Messages.showInfoMessage(message, "Zest Tab Handler Test");
            
        } catch (Exception ex) {
            String error = "Error testing tab handler: " + ex.getMessage();
            LOG.error(error, ex);
            Messages.showErrorDialog(error, "Test Tab Handler Error");
        }
    }
}
