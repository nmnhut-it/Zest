package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
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
public class SendCodeReviewToChatBox extends AnAction {
    private static final Logger LOG = Logger.getInstance(SendCodeReviewToChatBox.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        
        if (editor == null || psiFile == null) {
            Messages.showWarningDialog(project, "Please open a file to review", "No File Selected");
            return;
        }

        // Execute in a background task
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing Code Review", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                
                try {
                    indicator.setText("Analyzing code...");
                    indicator.setFraction(0.3);
                    
                    // Get the code to review
                    String codeContent = ReadAction.compute(() -> {
                        // Try to get the selected text first
                        String selectedText = editor.getSelectionModel().getSelectedText();
                        if (selectedText != null && !selectedText.isEmpty()) {
                            return selectedText;
                        }
                        
                        // Otherwise, try to get the current class
                        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
                        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                        if (psiClass != null) {
                            return psiClass.getText();
                        }
                        
                        // Fall back to the entire file
                        return psiFile.getText();
                    });
                    
                    String fileName = psiFile.getName();
                    String fileType = psiFile.getFileType().getDefaultExtension();
                    
                    indicator.setText("Creating review prompt...");
                    indicator.setFraction(0.6);
                    
                    // Create the review prompt
                    String prompt = createReviewPrompt(fileName, fileType, codeContent);
                    
                    indicator.setText("Sending to chat...");
                    indicator.setFraction(0.9);
                    
                    // Send to chat
                    boolean success = sendPromptToChatBoxAndSubmit(project, prompt);
                    if (!success) {
                        throw new Exception("Failed to send code review to chat");
                    }
                    
                    indicator.setText("Code review sent successfully!");
                    indicator.setFraction(1.0);
                    
                } catch (Exception ex) {
                    LOG.error("Failed to send code review", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, 
                            "Error: " + ex.getMessage(), 
                            "Code Review Failed");
                    });
                }
            }
        });
    }
    
    private String createReviewPrompt(String fileName, String fileType, String codeContent) {
        StringBuilder promptBuilder = new StringBuilder();
        
        // Add header with clear instructions
        promptBuilder.append("# CODE REVIEW REQUEST\n\n");
        promptBuilder.append("Please provide a detailed code review for the following code:\n\n");
        
        // Include file name and content
        promptBuilder.append("## File: ").append(fileName).append("\n\n");
        promptBuilder.append("```").append(fileType).append("\n");
        promptBuilder.append(codeContent);
        promptBuilder.append("\n```\n\n");
        
        // Specify review criteria
        promptBuilder.append("## Review Criteria\n\n");
        promptBuilder.append("Please analyze the code for:\n");
        promptBuilder.append("1. **Code quality and readability** - Is the code clean and easy to understand?\n");
        promptBuilder.append("2. **Potential bugs or edge cases** - Are there any logical errors or unhandled scenarios?\n");
        promptBuilder.append("3. **Performance considerations** - Are there any performance bottlenecks?\n");
        promptBuilder.append("4. **Best practices and design patterns** - Does it follow language conventions?\n");
        promptBuilder.append("5. **Error handling and robustness** - Is error handling adequate?\n");
        promptBuilder.append("6. **Security considerations** - Are there any security vulnerabilities?\n\n");
        
        // Request specific feedback format
        promptBuilder.append("## Requested Feedback Format\n\n");
        promptBuilder.append("Please provide:\n");
        promptBuilder.append("- **Summary**: Overall assessment of the code\n");
        promptBuilder.append("- **Strengths**: What aspects of the code are well-implemented\n");
        promptBuilder.append("- **Issues**: Specific problems identified (with line numbers if possible)\n");
        promptBuilder.append("- **Recommendations**: Suggested improvements with code examples\n");
        promptBuilder.append("- **Priority**: Which issues should be addressed first\n\n");
        
        return promptBuilder.toString();
    }

    /**
     * Sends the generated prompt to the simple chat dialog.
     */
    private boolean sendPromptToChatBoxAndSubmit(Project project, String prompt) {
        LOG.info("Sending generated code review prompt to chat dialog");

        try {
            ApplicationManager.getApplication().invokeLater(() -> {
                // Get the ChatUIService
                com.zps.zest.chatui.ChatUIService chatService = project.getService(com.zps.zest.chatui.ChatUIService.class);
                
                // Prepare for code review (adds system prompt if needed)
                chatService.prepareForCodeReview();
                
                // Open chat with the code review prompt and auto-send it
                chatService.openChatWithMessage(prompt, true);
                
                // Show success notification
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("Zest LLM")
                    .createNotification(
                        "Code Review Started",
                        "Code review request has been sent to the AI chat.",
                        com.intellij.notification.NotificationType.INFORMATION
                    )
                    .notify(project);
                
                LOG.info("Code review prompt sent to chat dialog successfully");
            });
            return true;
        } catch (Exception e) {
            LOG.error("Failed to open chat dialog", e);
            return false;
        }
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