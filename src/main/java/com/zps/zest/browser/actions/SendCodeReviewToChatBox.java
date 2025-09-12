package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Action that sends the current code to the chat for review.
 * Simplified version without the pipeline framework.
 */
public class SendCodeReviewToChatBox extends BaseChatAction {

    @Override
    protected boolean isActionAvailable(@NotNull AnActionEvent e) {
        return e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) != null;
    }

    @Override
    protected void showUnavailableMessage(Project project) {
        Messages.showWarningDialog(project, "Please open a file to review", "No File Selected");
    }

    @Override
    protected String getTaskTitle() {
        return "Preparing Code Review";
    }

    @Override
    protected String getErrorTitle() {
        return "Code Review Failed";
    }

    @Override
    protected String getChatPreparationMethod() {
        return "prepareForCodeReview";
    }

    @Override
    protected String getNotificationTitle() {
        return "Code Review Started";
    }

    @Override
    protected String getNotificationMessage() {
        return "Code review request has been sent to the AI chat.";
    }
    @Override
    protected String createPrompt(@NotNull AnActionEvent e) throws Exception {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        
        if (editor == null || psiFile == null) {
            throw new Exception("No file or editor available");
        }
        
        // Get the code to review
        String codeContent = ReadAction.compute(() -> {
            String selectedText = editor.getSelectionModel().getSelectedText();
            if (selectedText != null && !selectedText.isEmpty()) {
                return selectedText;
            }
            
            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (psiClass != null) {
                return psiClass.getText();
            }
            
            return psiFile.getText();
        });
        
        return createReviewPrompt(psiFile.getName(), psiFile.getFileType().getDefaultExtension(), codeContent);
    }
    
    private String createReviewPrompt(String fileName, String fileType, String codeContent) {
        return
               "**" + fileName + "**\n" +
               "```" + fileType + "\n" + codeContent + "\n```\n\n" +
               "Analyze: quality, bugs, performance, security, best practices, and test-ability\n" +
               "If the code is hard to be unit-tested or integration-tested, please point out and suggest improvements\n" +
               "Style: Be concise, specific, actionable. Use bullet points and proper line breaks.\n" +
               "No more than 20 words on each paragraph.\n";
    }


    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable only when a file is open in the editor
        Project project = e.getProject();
        boolean hasEditor = e.getData(CommonDataKeys.EDITOR) != null;
        boolean hasPsiFile = e.getData(CommonDataKeys.PSI_FILE) != null;
        
        e.getPresentation().setEnabled(project != null && hasEditor && hasPsiFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}