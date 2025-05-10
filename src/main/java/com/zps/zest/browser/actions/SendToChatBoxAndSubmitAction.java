package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.browser.utils.ChatboxUtilities;
import org.jetbrains.annotations.NotNull;

/**
 * Action to send selected text to the chat box and submit it.
 * Uses the improved ChatboxUtilities class for more reliable operation.
 */
public class SendToChatBoxAndSubmitAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(SendToChatBoxAndSubmitAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        // Get selected text
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        LOG.info("Sending selected text to chat box and submitting using ChatboxUtilities");

        // Use the new utility method to send text and submit
        boolean success = ChatboxUtilities.sendTextAndSubmit(project, selectedText, false,ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode());
        
        if (success) {
            LOG.info("Successfully sent text and clicked submit button");
        } else {
            LOG.warn("Failed to send text and click submit button");
        }

        // Activate browser tool window
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable only when text is selected in the editor
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        boolean hasSelection = editor != null && editor.getSelectionModel().hasSelection();
        e.getPresentation().setEnabled(project != null && hasSelection);
    }
}
