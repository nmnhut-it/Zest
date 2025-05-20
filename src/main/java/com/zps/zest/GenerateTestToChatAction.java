package com.zps.zest;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.utils.ChatboxUtilities;
import org.jetbrains.annotations.NotNull;

/**
 * Action that mimics the "Generate Test" action but sends the result to the chat box
 * instead of calling an LLM. This is useful for development and testing.
 */
public class GenerateTestToChatAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GenerateTestToChatAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        CodeContext context = new CodeContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));

        // Execute a modified pipeline in the background
        executeModifiedPipeline(context);
    }

    /**
     * Executes a modified pipeline that sends the prompt to the chat box
     * instead of calling an LLM.
     */
    private void executeModifiedPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing Test Generation Prompt", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Only execute the stages up to prompt creation
                    TestGenerationPipeline pipeline = new TestGenerationPipeline()
                            .addStage(new ConfigurationStage())
                            .addStage(new TargetClassDetectionStage())
                            .addStage(new ClassAnalysisStage())
                            .addStage(new TestPromptCreationStage());

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
                        throw new PipelineExecutionException("Failed to generate prompt");
                    }

                    // Send the prompt to the chat box
                    boolean success = sendPromptToChatBox(project, prompt);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send prompt to chat box");
                    }

                    indicator.setText("Prompt sent to chat box successfully!");
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
     * Sends the generated prompt to the chat box and activates the browser window.
     */
    private boolean sendPromptToChatBox(Project project, String prompt) {
        LOG.info("Sending generated prompt to chat box");

        // Format the prompt with a header
        String formattedPrompt = "# Generated Test Prompt\n\n" + prompt;

        // Send the prompt to the chat box
        boolean success = ChatboxUtilities.sendTextAndSubmit(project, formattedPrompt,false,"", false);
        
        // Activate browser tool window
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
        
        return success;
    }

    /**
     * Shows an error message on the UI thread.
     */
    private void showError(Project project, PipelineExecutionException e) {
        e.printStackTrace();
        LOG.error("Error in GenerateTestToChatAction: " + e.getMessage(), e);
        
        Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Test Prompt Generation Failed");
    }
}
