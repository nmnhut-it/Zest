package com.zps.zest.testing;

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
 * Action that orchestrates the agent-based test writing process.
 * This enhanced version uses a multi-step pipeline with state persistence.
 */
public class AgentBasedTestWritingAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AgentBasedTestWritingAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        CodeContext context = new CodeContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));
        context.useTestWrightModel(true); // Use test model for test writing

        // Execute the pipeline
        executeTestWritingPipeline(context);
    }

    /**
     * Executes the test writing pipeline with appropriate stages.
     */
    private void executeTestWritingPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Agent-Based Test Writing", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Check if there's a test writing in progress
                    TestWritingStateManager stateManager = new TestWritingStateManager(project);
                    boolean isTestWritingInProgress = stateManager.isTestWritingInProgress();

                    // If there is a test writing state, check if it's aborted or completed
                    if (isTestWritingInProgress) {
                        TestWritingProgress progress = stateManager.loadProgress();
                        if (progress != null && (progress.getStatus() == TestWritingStatus.ABORTED ||
                                progress.getStatus() == TestWritingStatus.COMPLETED)) {
                            // Clear the state to start a new test writing session
                            LOG.info("Found aborted or completed test writing. Clearing state to start fresh.");
                            stateManager.clearTestWritingState();
                            isTestWritingInProgress = false;

                            // Close the tool window if it's open
                            TestWritingToolWindow.checkAndCloseIfNoTestWriting(project);
                        }
                    } else {
                        // Make sure the tool window is closed if no test writing is in progress
                        TestWritingToolWindow.checkAndCloseIfNoTestWriting(project);
                    }

                    AgentBasedTestWritingPipeline pipeline;
                    WebBrowserService.getInstance(project).getBrowserPanel().switchToAgentMode();

                    if (isTestWritingInProgress) {
                        // Create a pipeline for resuming an existing test writing session
                        pipeline = new AgentBasedTestWritingPipeline()
                                .addStage(new TestConfigurationStage())
                                .addStage(new TestExecutionStage());

                        LOG.info("Resuming existing test writing session");
                    } else {
                        // Create a pipeline for starting a new test writing session
                        pipeline = new AgentBasedTestWritingPipeline()
                                .addStage(new TestConfigurationStage())
                                .addStage(new TargetClassDetectionStage())
                                .addStage(new ClassAnalysisStage()) // Add ClassAnalysisStage
                                .addStage(new TestAnalysisStage())
                                .addStage(new TestPlanningStage())
                                .addStage(new ChatboxLlmApiCallStage())
                                .addStage(new TestPlanAnalysisStage())
                                .addStage(new TestExecutionStage());

                        LOG.info("Starting new test writing pipeline");
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

                    indicator.setText("Test writing process initiated successfully!");
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
        LOG.error("Error in AgentBasedTestWritingAction: " + e.getMessage(), e);
        
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Test Writing Failed");
        });
    }
}

/**
 * Pipeline for the agent-based test writing process.
 */
class AgentBasedTestWritingPipeline {
    private final List<PipelineStage> stages = new ArrayList<>();

    /**
     * Adds a stage to the pipeline.
     *
     * @param stage The stage to add
     * @return This pipeline instance for method chaining
     */
    public AgentBasedTestWritingPipeline addStage(PipelineStage stage) {
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
