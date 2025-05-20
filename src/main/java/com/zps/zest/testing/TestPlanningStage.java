package com.zps.zest.testing;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for planning the test writing operation based on analysis results.
 * Creates a structured plan for writing comprehensive tests.
 */
public class TestPlanningStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestPlanningStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Planning test writing for comprehensive coverage");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }

        // Get class info and context from previous stages
        String classContext = context.getClassContext();
        String className = context.getClassName();
        if (classContext == null || classContext.isEmpty() || className == null || className.isEmpty()) {
            throw new PipelineExecutionException("Class context or name not available");
        }

        // Create a test writing state manager
        TestWritingStateManager stateManager = new TestWritingStateManager(project);

        // If there's an existing test writing in progress, prompt the user to resume or start fresh
        if (stateManager.isTestWritingInProgress()) {
            // In an actual implementation, we would show a dialog here.
            // For now, we'll just log a message and continue with a new test writing session.
            LOG.info("Existing test writing in progress - would normally prompt user to resume or start fresh");
            stateManager.clearTestWritingState();
        }

        // Create the test plan
        TestPlan plan = new TestPlan(
                "Test Suite for " + className,
                className,
                "Comprehensive test plan for " + className
        );

        // Determine test file path
        String testFilePath = generateTestFilePath(className);
        plan.setTestFilePath(testFilePath);

        // Save the initial empty plan
        if (!stateManager.savePlan(plan)) {
            throw new PipelineExecutionException("Failed to save initial test plan");
        }

        // Create and save the progress
        TestWritingProgress progress = new TestWritingProgress(plan.getName());
        if (!stateManager.saveProgress(progress)) {
            throw new PipelineExecutionException("Failed to save initial test writing progress");
        }

        // Build the prompt from template with context information
        String prompt = createPlanningPromptWithContext(classContext, className, testFilePath, context);

        // Store the prompt in the context
        context.setPrompt(prompt);

        LOG.info("Test planning stage completed successfully");
    }

    /**
     * Creates a planning prompt with full context information.
     */
    private String createPlanningPromptWithContext(String classContext, String className, String testFilePath, CodeContext context) {
        try {
            // Try to load the test planning prompt from resource template
            String templatePath = "/templates/test_planning.template";

            java.io.InputStream inputStream = getClass().getResourceAsStream(templatePath);
            if (inputStream != null) {
                String template = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                inputStream.close();

                // Get context information
                String junitVersion = context.getJunitVersion() != null ? context.getJunitVersion() : "JUnit 5";
                String mockitoAvailable = context.isMockitoPresent() ? "available" : "not available";
                String osName = System.getProperty("os.name", "Unknown");
                String terminalType = getTerminalType(osName);

                // Replace placeholders
                return template.replace("${classContext}", classContext)
                              .replace("${className}", className)
                              .replace("${testFilePath}", testFilePath)
                              .replace("${junitVersion}", junitVersion)
                              .replace("${mockitoAvailable}", mockitoAvailable)
                              .replace("${osName}", osName)
                              .replace("${terminalType}", terminalType)
                              .replace("${testCaseDescription}", "")
                              .replace("${stepDescription}", "");
            } else {
                throw new RuntimeException("Test planning template not found: " + templatePath);
            }
        } catch (Exception e) {
            LOG.error("Error loading test planning template", e);
            throw new RuntimeException("Failed to load test planning template: " + e.getMessage());
        }
    }
    /**
     * Determines the terminal type based on OS.
     */
    private String getTerminalType(String osName) {
        if (osName.toLowerCase().contains("windows")) {
            return "Command Prompt/PowerShell";
        } else if (osName.toLowerCase().contains("mac")) {
            return "Terminal (bash/zsh)";
        } else if (osName.toLowerCase().contains("linux")) {
            return "Terminal (bash)";
        } else {
            return "Unknown terminal";
        }
    }

    /**
     * Generates the test file path based on the class name.
     */
    private String generateTestFilePath(String className) {
        // Follow standard Java test conventions
        return "src/test/java/" + className.replace('.', '/') + "Test.java";
    }


}
