package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Pipeline stage that enhances the test code based on LLM analysis.
 * This stage should be placed before TestFileCreationStage to prepare the best possible code.
 */
class SelfReflectionStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(SelfReflectionStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        String testCode = context.getTestCode();
        if (testCode == null || testCode.isEmpty()) {
            throw new PipelineExecutionException("No test code available for enhancement");
        }

        Project project = context.getProject();

        Task.WithResult<String, Exception> task = new Task.WithResult<String, Exception>(
                project, "Enhancing Test Code", true) {
            @Override
            protected String compute(@NotNull ProgressIndicator indicator) throws Exception {
                indicator.setIndeterminate(false);

                // First pass: Enhance test coverage and edge cases
                indicator.setText("Enhancing test coverage...");
                indicator.setFraction(0.3);

                String enhancedCode = enhanceTestCoverage(context, testCode);
                if (enhancedCode == null) {
                    LOG.warn("Test coverage enhancement failed, keeping original code");
                    enhancedCode = testCode;
                }

                // Second pass: Check for potential compilation issues
                indicator.setText("Checking for potential compilation issues...");
                indicator.setFraction(0.7);

                String checkedCode = checkForCompilationIssues(context, enhancedCode);
                if (checkedCode == null) {
                    LOG.warn("Compilation check failed, keeping previously enhanced code");
                    checkedCode = enhancedCode;
                }

                indicator.setText("Test enhancement completed");
                indicator.setFraction(1.0);

                return checkedCode;
            }
        };

        try {
            String enhancedCode = ProgressManager.getInstance().run(task);
            context.setTestCode(enhancedCode);
            LOG.info("Test code enhancement completed successfully");
        } catch (Exception e) {
            LOG.error("Error during test enhancement: " + e.getMessage(), e);
            // Continue with the pipeline using the original code
        }
    }

    /**
     * Enhances test coverage with additional test cases and edge cases.
     */
    private String enhanceTestCoverage(CodeContext context, String testCode) {
        try {
            // Create a prompt for enhancing test coverage
            String prompt = createEnhanceTestCoveragePrompt(
                    context.getClassName(),
                    context.getClassContext(),
                    testCode
            );

            return callLlmForImprovement(context, prompt, "enhancing test coverage");
        } catch (Exception e) {
            LOG.error("Error enhancing test coverage: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Checks for potential compilation issues before creating the file.
     */
    private String checkForCompilationIssues(CodeContext context, String testCode) {
        try {
            // Create a prompt for checking potential compilation issues
            String prompt = createCompilationCheckPrompt(
                    context.getClassName(),
                    context.getPackageName(),
                    testCode
            );

            return callLlmForImprovement(context, prompt, "checking compilation issues");
        } catch (Exception e) {
            LOG.error("Error checking compilation issues: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Helper method to call the LLM for code improvement.
     */
    private String callLlmForImprovement(CodeContext context, String prompt, String operation) {
        try {
            // Create a temporary context for the API call
            CodeContext tempContext = new CodeContext();
            tempContext.setProject(context.getProject());
            tempContext.setConfig(context.getConfig());
            tempContext.setPrompt(prompt);

            // Call the LLM API
            LlmApiCallStage apiCallStage = new LlmApiCallStage();
            apiCallStage.process(tempContext);

            // Extract the improved code
            CodeExtractionStage extractionStage = new CodeExtractionStage();
            extractionStage.process(tempContext);

            String improvedCode = tempContext.getTestCode();
            if (improvedCode == null || improvedCode.isEmpty()) {
                LOG.warn("LLM returned empty code when " + operation);
                return null;
            }

            return improvedCode;
        } catch (Exception e) {
            LOG.error("Error when " + operation + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates a prompt for enhancing test coverage and edge cases.
     */
    private String createEnhanceTestCoveragePrompt(String className, String classContext, String testCode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Enhance this test class for ").append(className)
                .append(" to improve coverage and edge case testing.\n\n");

        prompt.append("CLASS INFORMATION:\n");
        if (classContext != null && !classContext.isEmpty()) {
            prompt.append(classContext.length() > 1000 ?
                    classContext.substring(0, 1000) + "...[additional class info omitted]" :
                    classContext);
        }
        prompt.append("\n\n");

        prompt.append("CURRENT TEST CODE:\n```java\n");
        prompt.append(testCode);
        prompt.append("\n```\n\n");

        prompt.append("Please enhance this test with:\n");
        prompt.append("1. Tests for ALL public methods that aren't yet covered\n");
        prompt.append("2. Edge case tests (null inputs, empty collections, boundary values)\n");
        prompt.append("3. Exception path testing where appropriate\n");
        prompt.append("4. Tests for any important business logic\n");
        prompt.append("5. More descriptive test method names\n");
        prompt.append("6. Meaningful assertion messages\n\n");

        prompt.append("Maintain existing imports and correct compilation syntax while adding these improvements.\n\n");

        prompt.append("Return ONLY the enhanced Java code without any explanations or markdown formatting.");

        return prompt.toString();
    }

    /**
     * Creates a prompt for checking potential compilation issues before file creation.
     */
    private String createCompilationCheckPrompt(String className, String packageName, String testCode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Review this test class for ").append(className)
                .append(" in package ").append(packageName)
                .append(" to identify and fix any potential compilation issues.\n\n");

        prompt.append("CURRENT TEST CODE:\n```java\n");
        prompt.append(testCode);
        prompt.append("\n```\n\n");

        prompt.append("Check for and fix the following potential issues:\n");
        prompt.append("1. Ensure all imports are correct and complete\n");
        prompt.append("2. Verify the package declaration is correct: ").append(packageName).append("\n");
        prompt.append("3. Make sure all referenced classes have proper imports\n");
        prompt.append("4. Check that all variables are initialized before use\n");
        prompt.append("5. Verify that methods are called with the correct parameter types\n");
        prompt.append("6. Ensure proper use of assertion methods with class prefixes\n");
        prompt.append("7. Fix any syntax errors or typos\n\n");

        prompt.append("Return ONLY the checked and fixed Java code without any explanations or markdown formatting.");

        return prompt.toString();
    }
}