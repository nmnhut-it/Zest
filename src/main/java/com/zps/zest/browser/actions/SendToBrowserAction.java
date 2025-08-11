package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to send selected text to ZPS Chat.
 */
public class SendToBrowserAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(SendToBrowserAction.class);

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

        LOG.info("Sending selected text to browser");

        // Get browser service
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        
        // Open chat in editor if not already open
        com.zps.zest.browser.actions.OpenChatInEditorAction.Companion.openChatInSplitEditor(project, "main");
        
        // Send text to browser
        browserService.sendTextToBrowser(selectedText);
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
