package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Handles execution of the test generation pipeline in a background task
 * with an option to run silently without visible progress indicators.
 */
public class BackgroundPipelineExecutor {

    /**
     * Executes the test generation pipeline in a background task.
     *
     * @param context The context containing information needed for test generation
     * @param silent If true, executes without showing progress UI
     */
    public static void executeInBackground(TestGenerationContext context, boolean silent) {
        Project project = context.getProject();
        if (project == null) return;

        if (silent) {
            // Execute in a non-modal background task without UI
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    // Create and execute the pipeline
                    TestGenerationPipeline pipeline = createPipeline();
                    executePipelineSilently(pipeline, context);
                } catch (PipelineExecutionException e) {
                    showError(project, e);
                } catch (Exception e) {
                    showError(project, new PipelineExecutionException("Unexpected error", e));
                }
            });
        } else {
            // Use the standard visible task with progress indicators
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Class Test Suite", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(false);

                    // Create the pipeline with stages
                    TestGenerationPipeline pipeline = createPipeline();

                    try {
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

                        indicator.setText("Test class generated successfully!");
                        indicator.setFraction(1.0);

                    } catch (PipelineExecutionException e) {
                        showError(project, e);
                    } catch (Exception e) {
                        showError(project, new PipelineExecutionException("Unexpected error", e));
                    }
                }
            });
        }
    }

    /**
     * Execute the pipeline without progress indicators.
     */
    private static void executePipelineSilently(TestGenerationPipeline pipeline,
                                                TestGenerationContext context) throws PipelineExecutionException {
        // Execute each stage without UI updates
        int totalStages = pipeline.getStageCount();
        for (int i = 0; i < totalStages; i++) {
            PipelineStage stage = pipeline.getStage(i);
            stage.process(context);
        }
    }

    /**
     * Default execute method that shows the progress UI (for backwards compatibility)
     */
    public static void executeInBackground(TestGenerationContext context) {
        executeInBackground(context, false);
    }

    /**
     * Creates the pipeline with all stages in the correct order.
     */
    private static TestGenerationPipeline createPipeline() {
        return new TestGenerationPipeline()
                .addStage(new ConfigurationStage())
                .addStage(new TargetClassDetectionStage())
                .addStage(new ClassAnalysisStage())
                .addStage(new PromptCreationStage())
                .addStage(new LlmApiCallStage())
                .addStage(new CodeExtractionStage())
                .addStage(new ImportHandlingStage())  // New stage for handling imports before file creation
                .addStage(new TestFileCreationStage())
                ;
    }

    /**
     * Shows an error message on the UI thread.
     */
    private static void showError(Project project, PipelineExecutionException e) {
        e.printStackTrace();
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Test Generation Failed");
        });
    }
}