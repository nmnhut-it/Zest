package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.*;
import com.zps.zest.browser.utils.ChatboxUtilities;
import org.jetbrains.annotations.NotNull;

/**
 * Action that executes the code review pipeline up to the prompt creation stage
 * and sends the generated prompt to the chat box.
 */
public class SendCodeReviewToChatBox extends AnAction {
    private static final Logger LOG = Logger.getInstance(SendCodeReviewToChatBox.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        CodeContext context = new CodeContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));

        // Execute the pipeline in a background task
        executePartialPipeline(context);
    }

    /**
     * Executes the pipeline up to the prompt creation stage and sends the result to chat box.
     */
    private void executePartialPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing Code Review Prompt", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Create a partial pipeline ending at code review prompt creation
                    TestGenerationPipeline pipeline = new TestGenerationPipeline()
                            .addStage(new ConfigurationStage())
                            .addStage(new TargetClassDetectionStage())
                            .addStage(new ClassAnalysisStage())
                            .addStage(new CodeReviewPromptCreationStage());

                    // Execute each stage with progress updates
                    int totalStages = pipeline.getStageCount();
                    for (int i = 0; i < totalStages; i++) {
                        PipelineStage stage = pipeline.getStage(i);
                        String stageName = stage.getClass().getSimpleName()
                                .replace("Stage", "")
                                .replaceAll("([A-Z])", " $1").trim();

                        indicator.setText("Stage " + (i+1) + "/" + totalStages + ": " + stageName);
                        indicator.setFraction((double) i / totalStages);

                        // Process the current stage
                        stage.process(context);

                        // Update progress
                        indicator.setFraction((double) (i+1) / totalStages);
                    }

                    // Get the prompt from the context
                    String prompt = context.getPrompt();
                    if (prompt == null || prompt.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate code review prompt");
                    }

                    // Send the prompt to the chat box and submit
                    indicator.setText("Sending code review prompt to chat box...");
                    boolean success = sendPromptToChatBoxAndSubmit(project, prompt);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send code review prompt to chat box");
                    }

                    indicator.setText("Code review prompt sent to chat box successfully!");
                    indicator.setFraction(1.0);

                } catch (PipelineExecutionException e) {
                    showError(project, e);
                } catch (Exception e) {
                    showError(project, new PipelineExecutionException("Unexpected error", e));
                }
            }
        });
    }

    /**
     * Sends the generated prompt to the chat box, submits it, and activates the browser window.
     */
    private boolean sendPromptToChatBoxAndSubmit(Project project, String prompt) {
        LOG.info("Sending generated code review prompt to chat box and submitting");

        // Activate browser tool window and send prompt asynchronously
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            ApplicationManager.getApplication().invokeLater(()->{
                toolWindow.activate(() -> {
                    // The ChatboxUtilities.sendTextAndSubmit method handles waiting for page load
                    ChatboxUtilities.clickNewChatButton(project);

                    ChatboxUtilities.sendTextAndSubmit(project, prompt, true, ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode());
                });
            });

            return true;
        }
        
        return false;
    }

    /**
     * Shows an error message on the UI thread.
     */
    private void showError(Project project, PipelineExecutionException e) {
        e.printStackTrace();
        LOG.error("Pipeline execution error: " + e.getMessage(), e);
        
        if (project != null && !project.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Code Review Generation Failed");
            });
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