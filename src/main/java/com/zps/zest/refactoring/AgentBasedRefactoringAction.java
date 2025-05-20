package com.zps.zest.refactoring;

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
import com.zps.zest.*;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Action that orchestrates the agent-based refactoring for testability.
 * This enhanced version uses a multi-step pipeline with state persistence.
 */
public class AgentBasedRefactoringAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AgentBasedRefactoringAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        CodeContext context = new CodeContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));
        context.useTestWrightModel(false); // Use code model instead of test model

        // Execute the pipeline
        executeRefactoringPipeline(context);
    }

    /**
     * Executes the refactoring pipeline with appropriate stages.
     */
    private void executeRefactoringPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Agent-Based Refactoring for Testability", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Check if there's a refactoring in progress
                    RefactoringStateManager stateManager = new RefactoringStateManager(project);
                    boolean isRefactoringInProgress = stateManager.isRefactoringInProgress();

                    // If there is a refactoring state, check if it's aborted or completed
                    if (isRefactoringInProgress) {
                        RefactoringProgress progress = stateManager.loadProgress();
                        if (progress != null && (progress.getStatus() == RefactoringStatus.ABORTED ||
                                progress.getStatus() == RefactoringStatus.COMPLETED)) {
                            // Clear the state to start a new refactoring
                            LOG.info("Found aborted or completed refactoring. Clearing state to start fresh.");
                            stateManager.clearRefactoringState();
                            isRefactoringInProgress = false;

                            // Close the tool window if it's open
                            RefactoringToolWindow.checkAndCloseIfNoRefactoring(project);
                        }
                    } else {
                        // Make sure the tool window is closed if no refactoring is in progress
                        RefactoringToolWindow.checkAndCloseIfNoRefactoring(project);
                    }

                    WebBrowserService.getInstance(project).getBrowserPanel().switchToAgentMode();
                    AgentBasedRefactoringPipeline pipeline;

                    if (isRefactoringInProgress) {
                        // Create a pipeline for resuming an existing refactoring
                        pipeline = new AgentBasedRefactoringPipeline()
                                .addStage(new ConfigurationStage())
                                .addStage(new RefactoringExecutionStage());

                        LOG.info("Resuming existing refactoring");
                    } else {
                        // Create a pipeline for starting a new refactoring
                        pipeline = new AgentBasedRefactoringPipeline()
                                .addStage(new ConfigurationStage())
                                .addStage(new TargetClassDetectionStage())
                                .addStage(new ClassAnalysisStage())
                                .addStage(new RefactoringPlanningStage())
                                .addStage(new ChatboxLlmApiCallStage(false))
                                .addStage(new RefactoringPlanAnalysisStage())
                                .addStage(new RefactoringExecutionStage());

                        LOG.info("Starting new refactoring pipeline");
                    }

                    // Execute each stage with progress updates
                    int totalStages = pipeline.getStageCount();
                    for (int i = 0; i < totalStages; i++) {
                        PipelineStage stage = pipeline.getStage(i);
                        String stageName = stage.getClass().getSimpleName()
                                .replace("Stage", "")
                                .replaceAll("([A-Z])", " $1").trim();

                        indicator.setText("Stage " + (i + 1) + "/" + totalStages + ": " + stageName);
                        indicator.setFraction((double) i / totalStages);

                        // Process the current stage
                        try {
                            stage.process(context);
                        } catch (PipelineExecutionException e) {
                            showError(project, e);
                            break;
                        }

                        // Update progress
                        indicator.setFraction((double) (i + 1) / totalStages);
                    }

                    indicator.setText("Refactoring process initiated successfully!");
                    indicator.setFraction(1.0);

                } catch (Exception e) {
                    showError(project, new PipelineExecutionException("Unexpected error", e));
                }
            }
        });
    }

    /**
     * Shows an error message on the UI thread.
     */
    private void showError(Project project, PipelineExecutionException e) {
        e.printStackTrace();
        LOG.error("Error in AgentBasedRefactoringAction: " + e.getMessage(), e);
        
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Refactoring for Testability Failed");
        });
    }
}

/**
 * Pipeline for the agent-based refactoring process.
 */
class AgentBasedRefactoringPipeline {
    private final List<PipelineStage> stages = new ArrayList<>();

    /**
     * Adds a stage to the pipeline.
     *
     * @param stage The stage to add
     * @return This pipeline instance for method chaining
     */
    public AgentBasedRefactoringPipeline addStage(PipelineStage stage) {
        stages.add(stage);
        return this;
    }

    /**
     * Gets the number of stages in the pipeline.
     *
     * @return The number of stages
     */
    public int getStageCount() {
        return stages.size();
    }

    /**
     * Gets a stage by index.
     *
     * @param index The index of the stage
     * @return The stage at the given index
     */
    public PipelineStage getStage(int index) {
        return stages.get(index);
    }
}
