package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.*;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.utils.ChatboxUtilities;
import org.apache.commons.lang.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Action that executes a pipeline to generate intelligent code comments
 * and sends the generated prompt to the chat box for AI assistance.
 */
public class GenerateCodeCommentsAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GenerateCodeCommentsAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        CodeContext context = new CodeContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));

        // Execute the pipeline in a background task
        executeCommentGenerationPipeline(context);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return super.getActionUpdateThread();
    }

    /**
     * Executes the pipeline to generate code comments and send request to chat box.
     */
    private void executeCommentGenerationPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing Code Comment Request", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Create a pipeline for comment generation
                    TestGenerationPipeline pipeline = new TestGenerationPipeline()
                            .addStage(new ConfigurationStage())
                            .addStage(new TargetClassDetectionStage())
                            .addStage(new ClassAnalysisStage())
                            .addStage(new CodeCommentPromptCreationStage());

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
                        throw new PipelineExecutionException("Failed to generate code comment prompt");
                    }

                    // Send the prompt to the chat box and submit
                    indicator.setText("Sending code comment request to chat box...");
                    boolean success = sendPromptToChatBoxAndSubmit(project, prompt, context);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send code comment request to chat box");
                    }

                    indicator.setText("Code comment request sent successfully!");
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
    private boolean sendPromptToChatBoxAndSubmit(Project project, String prompt, CodeContext context) {
        LOG.info("Sending generated code comment prompt to chat box and submitting");

        // Activate browser tool window and send prompt asynchronously
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            ApplicationManager.getApplication().invokeLater(()->{
                toolWindow.activate(() -> {
                    // The ChatboxUtilities.sendTextAndSubmit method handles waiting for page load
                    WebBrowserService.getInstance(project).executeJavaScript("window.__text_to_replace_ide___ = '" + StringEscapeUtils.escapeJavaScript(context.getSelectedText()) +"';");
                    ChatboxUtilities.sendTextAndSubmit(project, prompt, true);
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
                Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Code Comment Generation Failed");
            });
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable only when a file is open in the editor
        Project project = e.getProject();
        boolean hasEditor = e.getData(CommonDataKeys.EDITOR) != null;
        boolean hasPsiFile = e.getData(CommonDataKeys.PSI_FILE) != null;
        boolean hasSelection = false;
        if (hasPsiFile && hasEditor) {
            SelectionModel selectionModel = e.getData(CommonDataKeys.EDITOR).getSelectionModel();
            if (selectionModel != null) {
                hasSelection = selectionModel.hasSelection();
            }
        }
        e.getPresentation().setEnabled(project != null && hasEditor && hasPsiFile && hasSelection);
    }
}