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

import java.util.ArrayList;
import java.util.List;

/**
 * Action that analyzes a class for testability and provides refactoring suggestions
 * to make it more unit testable.
 */
public class RefactorForTestabilityAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(RefactorForTestabilityAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        CodeContext context = new CodeContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));

        // Execute the pipeline
        executeTestabilityPipeline(context);
    }

    /**
     * Executes the testability analysis pipeline.
     */
    private void executeTestabilityPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Analyzing Class Testability", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Create and execute the testability pipeline
                    TestabilityPipeline pipeline = new TestabilityPipeline()
                            .addStage(new ConfigurationStage())
                            .addStage(new TargetClassDetectionStage())
                            .addStage(new ClassAnalysisStage())
                            .addStage(new TestabilityAnalysisStage());

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

                    // Get the analysis result from the context
                    String prompt = context.getPrompt();
                    if (prompt == null || prompt.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate testability analysis");
                    }

                    // Send the analysis to the chat box
                    boolean success = sendAnalysisToChatBox(project, prompt);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send analysis to chat box");
                    }

                    indicator.setText("Testability analysis sent to chat box successfully!");
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
     * Sends the testability analysis to the chat box and activates the browser window.
     */
    private boolean sendAnalysisToChatBox(Project project, String analysis) {
        LOG.info("Sending testability analysis to chat box");

        // Send the analysis to the chat box
        boolean success = ChatboxUtilities.sendTextAndSubmit(project, analysis, false, ConfigurationManager.getInstance(project).getCodeSystemPrompt());
        
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
        LOG.error("Error in RefactorForTestabilityAction: " + e.getMessage(), e);
        
        Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Testability Analysis Failed");
    }
}

/**
 * Pipeline for analyzing class testability and providing refactoring suggestions.
 */
class TestabilityPipeline {
    private final List<PipelineStage> stages = new ArrayList<>();

    /**
     * Adds a stage to the pipeline.
     *
     * @param stage The stage to add
     * @return This pipeline instance for method chaining
     */
    public TestabilityPipeline addStage(PipelineStage stage) {
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
/**
 * Stage that analyzes class testability and creates a refactoring prompt.
 * This stage evaluates testability issues and provides an interactive approach
 * for implementing refactoring suggestions.
 */
class TestabilityAnalysisStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestabilityAnalysisStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Analyzing testability and creating refactoring prompt");

        // Get the class context and analysis from previous stages
        String classContext = context.getClassContext();
        if (classContext == null || classContext.isEmpty()) {
            throw new PipelineExecutionException("Class context not available");
        }

        // Build the prompt for the LLM
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Interactive Testability Analysis and Refactoring\n\n");

        // Two-phase approach instruction
        prompt.append("## Instructions\n");
        prompt.append("Analyze the provided Java class for testability issues, then implement changes sequentially with user confirmation.\n\n");

        // Analysis phase
        prompt.append("### Phase 1: Analysis\n");
        prompt.append("First, determine if the class is already highly testable. If it is, explain why and stop there.\n");
        prompt.append("If not, identify ALL testability issues and number them (1, 2, 3...). Use these criteria:\n\n");

        // Keep the full assessment criteria
        prompt.append("- **Dependency Injection**: How dependencies are managed\n");
        prompt.append("- **External Resources**: Usage of files, databases, network, etc.\n");
        prompt.append("- **Static Dependencies**: Reliance on static methods/classes\n");
        prompt.append("- **Encapsulation**: Access modifiers and information hiding\n");
        prompt.append("- **Method Complexity**: Length and complexity of methods\n");
        prompt.append("- **Inheritance**: Deep inheritance hierarchies\n\n");

        // Implementation phase
        prompt.append("### Phase 2: Implementation\n");
        prompt.append("After listing all issues, ask: \"Would you like me to implement Change #1?\" Wait for user confirmation.\n");
        prompt.append("For each change:\n");
        prompt.append("1. Explain the issue and why it hurts testability\n");
        prompt.append("2. Show the specific code changes needed\n");
        prompt.append("3. Explain how this improves testability\n");
        prompt.append("4. After implementing, ask about the next change: \"Would you like me to implement Change #2?\" and so on.\n\n");

        prompt.append("## Class to Analyze\n\n");
        prompt.append(classContext);

        // Store the prompt in the context
        context.setPrompt(prompt.toString());

        LOG.info("Interactive testability analysis prompt created successfully");
    }
}