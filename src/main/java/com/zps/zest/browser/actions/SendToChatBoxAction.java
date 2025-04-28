package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to send selected text to the chat box in ZPS Chat.
 */
public class SendToChatBoxAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(SendToChatBoxAction.class);

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

        LOG.info("Sending selected text to chat box");

        // Get browser service
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        
        // Use JavaScript to insert text into the chat box
        String script = 
                "var chatInput = chatInput || document.getElementById('chat-input');" +
                "if (chatInput) {" +
                "  chatInput.innerHTML = '<p>" + escapeJavaScriptString(selectedText) + "</p>';" +
                "}";
        
        browserService.executeJavaScript(script);

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
    
    /**
     * Escapes a string for use in JavaScript.
     */
    private String escapeJavaScriptString(String str) {
        if (str == null) {
            return "";
        }
        
        return str.replace("\\", "\\\\")
                 .replace("'", "\\'")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("<", "&lt;")
                 .replace(">", "&gt;");
    }
}
