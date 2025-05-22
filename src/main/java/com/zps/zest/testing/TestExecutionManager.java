package com.zps.zest.testing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.TestFrameworkUtils;
import com.zps.zest.browser.utils.ChatboxUtilities;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Manages the execution of test writing steps by coordinating with the LLM.
 * This class handles the interaction with the chat interface and tracks progress.
 */
public class TestExecutionManager {
    public static final String REVIEW_AND_FINALIZE_TESTS = "Review and Finalize Tests";
    private static final Logger LOG = Logger.getInstance(TestExecutionManager.class);
    private final Project project;
    private final TestWritingStateManager stateManager;

    /**
     * Creates a new test execution manager.
     *
     * @param project The project to execute test writing for
     */
    public TestExecutionManager(Project project) {
        this.project = project;
        this.stateManager = new TestWritingStateManager(project);
    }

    /**
     * Executes a single test writing step and updates progress.
     *
     * @param plan     The test plan
     * @param progress The current progress
     * @return true if the step was executed successfully, false otherwise
     */
    public boolean executeTestCase(TestPlan plan, TestWritingProgress progress) {
        LOG.info("Executing test case: " + progress.getCurrentTest());

        // Get the current scenario and test case
        TestScenario currentScenario;
        TestCase currentTestCase;

        try {
            currentScenario = plan.getScenarios().get(progress.getCurrentScenarioIndex());
            currentTestCase = currentScenario.getTestCases().get(progress.getCurrentTestCaseIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid scenario or test case index", e);
            return false;
        }

        // Extract target class name from the plan
        String className = plan.getTargetClass();
        String packageName = "";

        // Get fresh class context
        PsiClass targetClass = findTargetClass(packageName, className);
        String updatedClassContext = null;

        if (targetClass != null) {
            // Get the actual package name from the found class
            packageName = targetClass.getContainingFile() instanceof PsiJavaFile ?
                    ((PsiJavaFile) targetClass.getContainingFile()).getPackageName() : "";

            // Use ClassAnalyzer to get fresh class context with any recent user modifications
            updatedClassContext = ClassAnalyzer.collectClassContext(targetClass);
            LOG.info("Using fresh class context from ClassAnalyzer for test case: " + progress.getCurrentTest());
        } else {
            LOG.error("Could not find target class: " + className);
            return false;
        }

        if (updatedClassContext == null) {
            LOG.error("No class context available");
            return false;
        }

        // Remember if this is a continuation of a conversation or a new chat
        boolean isNewChat = isNewChatNeeded(progress);

        // Create the execution prompt with the fresh context
        String executionPrompt = createTestCaseExecutionPrompt(
                plan, progress, currentScenario, currentTestCase,
                updatedClassContext, packageName, isNewChat);

        // If it's a new chat, first click the new chat button
        if (isNewChat) {
            boolean newChatClicked = ChatboxUtilities.clickNewChatButton(project);
            if (!newChatClicked) {
                LOG.warn("Failed to click new chat button");
                // Continue anyway, it might work
            }
        }

        // Send the prompt to the chat box
        boolean sent = sendPromptToChatBox(executionPrompt, currentTestCase);
        if (!sent) {
            LOG.error("Failed to send execution prompt to chat box");
            return false;
        }

        // Update the test case status
        currentTestCase.setStatus(TestStatus.IN_PROGRESS);

        // Save the updated progress
        return stateManager.saveProgress(progress);
    }

    /**
     * Determines if a new chat is needed based on the progress.
     */
    private boolean isNewChatNeeded(TestWritingProgress progress) {
        return true;

//        // If this is the first test case, always start a new chat
//        if (progress.getCurrentScenarioIndex() == 0 && progress.getCurrentTestCaseIndex() == 0) {
//            return true;
//        }
//
//        // Check if it's been more than 30 minutes since the last update
//        if (progress.getLastUpdateDate() != null) {
//            long elapsedTime = System.currentTimeMillis() - progress.getLastUpdateDate().getTime();
//            long thirtyMinutesInMillis = 30 * 60 * 1000;
//
//            if (elapsedTime > thirtyMinutesInMillis) {
//                return true;
//            }
//        }
//
//        // Otherwise, continue the same chat
//        return false;
    }

    /**
     * Attempts to find the target class in the project.
     * Uses the available class name information and tries multiple strategies.
     */
    private PsiClass findTargetClass(String packageName, String className) {
        try {
            return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<PsiClass>) () -> {
                // Get all Java files
                com.intellij.psi.search.PsiShortNamesCache cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
                PsiClass[] classes = cache.getClassesByName(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project));

                // If we have a package name, try to find an exact match first
                if (packageName != null && !packageName.isEmpty()) {
                    for (PsiClass psiClass : classes) {
                        if (psiClass.getContainingFile() instanceof PsiJavaFile) {
                            PsiJavaFile javaFile = (PsiJavaFile) psiClass.getContainingFile();
                            if (javaFile.getPackageName().equals(packageName)) {
                                LOG.info("Found target class by exact package match: " + packageName + "." + className);
                                return psiClass;
                            }
                        }
                    }
                }

                // If no match with package or no package provided, just return the first class with matching name
                if (classes.length > 0) {
                    PsiClass foundClass = classes[0];
                    String foundPackage = foundClass.getContainingFile() instanceof PsiJavaFile ?
                            ((PsiJavaFile) foundClass.getContainingFile()).getPackageName() : "unknown";
                    LOG.info("Found target class by name only: " + foundPackage + "." + className);
                    return foundClass;
                }

                LOG.warn("Could not find target class: " + className);
                return null;
            });
        } catch (Exception e) {
            LOG.error("Error finding target class", e);
            return null;
        }
    }
    /**
     * Creates a prompt for executing a specific test case.
     */
    private String createTestCaseExecutionPrompt(TestPlan plan, TestWritingProgress progress,
                                                 TestScenario currentScenario, TestCase currentTestCase,
                                                 String classContext, String packageName, boolean isNewChat) {

        try {
            // Load the appropriate template based on the test case type
            String templatePath = currentTestCase.getTitle().equals(REVIEW_AND_FINALIZE_TESTS)
                    ? "/templates/review_tests.template"
                    : "/templates/test_case_execution.template";

            // Load resource from classpath
            java.io.InputStream inputStream = getClass().getResourceAsStream(templatePath);
            if (inputStream == null) {
                throw new RuntimeException("Template resource not found: " + templatePath);
            }

            // Read the template file
            String template = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            inputStream.close();

            // Replace placeholders with actual values
            String testFilePath = currentTestCase.getTestFilePath() == null ? plan.getTestFilePath() : currentTestCase.getTestFilePath();

            // Get framework information using static utility methods
            String junitVersion = TestFrameworkUtils.detectJUnitVersion(project);
            String mockitoAvailable = TestFrameworkUtils.isMockitoAvailable(project) ? "available" : "not available";
            String mockitoVersion = TestFrameworkUtils.detectMockitoVersion(project);
            String buildTool = TestFrameworkUtils.detectBuildTool(project);
            String frameworksSummary = TestFrameworkUtils.getFrameworksSummary(project);
            String recommendedAssertions = TestFrameworkUtils.getRecommendedAssertionStyle(project);
            String environmentInfo = TestFrameworkUtils.getEnvironmentInfo();
            String completeFrameworkInfo = TestFrameworkUtils.getCompleteFrameworkInfo(project);

            // Get environment information
            String osName = System.getProperty("os.name", "Unknown");
            String terminalType = TestFrameworkUtils.getTerminalType(osName);

            // Collect test class structure information
            String testClassStructure = collectTestClassStructureInfo(testFilePath);

            template = template.replace("${targetClass}", plan.getTargetClass())
                    .replace("${testCaseTitle}", currentTestCase.getTitle())
                    .replace("${scenarioTitle}", currentScenario.getTitle())
                    .replace("${currentTestCaseNumber}", String.valueOf(progress.getCurrentTestCaseIndex() + 1))
                    .replace("${totalTestCasesInScenario}", String.valueOf(currentScenario.getTestCases().size()))
                    .replace("${currentScenarioNumber}", String.valueOf(progress.getCurrentScenarioIndex() + 1))
                    .replace("${totalScenarios}", String.valueOf(plan.getScenarios().size()))
                    .replace("${packageName}", packageName != null ? packageName : "")
                    .replace("${classContext}", classContext)
                    .replace("${testCaseDescription}", currentTestCase.getDescription())
                    .replace("${testFilePath}", testFilePath != null ? testFilePath : "")
                    .replace("${junitVersion}", junitVersion)
                    .replace("${mockitoAvailable}", mockitoAvailable)
                    .replace("${mockitoVersion}", mockitoVersion != null ? mockitoVersion : "N/A")
                    .replace("${buildTool}", buildTool)
                    .replace("${frameworksSummary}", frameworksSummary)
                    .replace("${recommendedAssertions}", recommendedAssertions)
                    .replace("${environmentInfo}", environmentInfo)
                    .replace("${completeFrameworkInfo}", completeFrameworkInfo)
                    .replace("${osName}", osName)
                    .replace("${terminalType}", terminalType)
                    .replace("${testClassStructure}", testClassStructure);

            // Replace optional fields
            if (currentTestCase.getTestMethodName() != null && !currentTestCase.getTestMethodName().isEmpty()) {
                template = template.replace("${testMethodName}", currentTestCase.getTestMethodName());
            } else {
                template = template.replace("${testMethodName}", "");
                // Remove the line if empty
                template = template.replace("\n**Test Method:** \n\n", "");
            }

            if (currentTestCase.getSetup() != null && !currentTestCase.getSetup().isEmpty()) {
                template = template.replace("${setup}", currentTestCase.getSetup());
            } else {
                template = template.replace("${setup}", "");
                template = template.replace("\n**Setup:** \n\n", "");
            }

            if (currentTestCase.getAssertions() != null && !currentTestCase.getAssertions().isEmpty()) {
                template = template.replace("${assertions}", currentTestCase.getAssertions());
            } else {
                template = template.replace("${assertions}", "");
                template = template.replace("\n**Assertions:** \n\n", "");
            }

            return template;
        } catch (Exception e) {
            LOG.error("Error creating test case execution prompt from template", e);
            throw new RuntimeException("Failed to load test case execution template: " + e.getMessage());
        }
    }

    /**
     * Collects test class structure information if the test class exists.
     *
     * @param testFilePath The path to the test file
     * @return A string representation of the test class structure, or empty if class doesn't exist
     */
    private String collectTestClassStructureInfo(String testFilePath) {
        if (testFilePath == null || testFilePath.isEmpty()) {
            return "Test class will be created.";
        }

        try {
            // Try to find the test class
            PsiClass testClass = ClassAnalyzer.findTestClass(project, testFilePath);

            if (testClass == null) {
                return "Test class will be created at: " + testFilePath;
            }

            StringBuilder structureInfo = new StringBuilder();
            structureInfo.append("## Existing Test Class Structure\n\n");
            structureInfo.append("```java\n");
            structureInfo.append(ClassAnalyzer.collectTestClassStructure(testClass));
            structureInfo.append("\n```\n\n");

            // Collect subclass structures if any
            String subclassStructures = ClassAnalyzer.collectTestSubclassStructures(project, testClass);
            if (!subclassStructures.isEmpty()) {
                structureInfo.append("## Test Subclasses\n\n");
                structureInfo.append(subclassStructures);
            }

            return structureInfo.toString();

        } catch (Exception e) {
            LOG.warn("Error collecting test class structure: " + e.getMessage());
            return "Test class structure could not be analyzed. Test class will be created or updated at: " + testFilePath;
        }
    }
    /**
     * Creates a built-in test case execution prompt as a fallback.
     */
    private String createBuiltInTestCaseExecutionPrompt(TestPlan plan, TestWritingProgress progress,
                                                        TestScenario currentScenario, TestCase currentTestCase,
                                                        String classContext, String packageName, boolean isNewChat) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("# Test Case Implementation Request\n\n");
        
        prompt.append("## Context\n");
        prompt.append("- **Target Class**: ").append(plan.getTargetClass()).append("\n");
        prompt.append("- **Test File**: ").append(plan.getTestFilePath()).append("\n");
        prompt.append("- **Scenario**: ").append(currentScenario.getTitle()).append("\n");
        prompt.append("- **Test Case**: ").append(currentTestCase.getTitle()).append("\n");
        prompt.append("- **Progress**: Test Case ").append(progress.getCurrentTestCaseIndex() + 1)
               .append(" of ").append(currentScenario.getTestCases().size())
               .append(" in Scenario ").append(progress.getCurrentScenarioIndex() + 1)
               .append(" of ").append(plan.getScenarios().size()).append("\n\n");
        
        prompt.append("## Test Case Details\n");
        prompt.append("**Description**: ").append(currentTestCase.getDescription()).append("\n\n");
        
        if (currentTestCase.getTestMethodName() != null && !currentTestCase.getTestMethodName().isEmpty()) {
            prompt.append("**Suggested Method Name**: ").append(currentTestCase.getTestMethodName()).append("\n\n");
        }
        
        if (currentTestCase.getSetup() != null && !currentTestCase.getSetup().isEmpty()) {
            prompt.append("**Setup Requirements**: ").append(currentTestCase.getSetup()).append("\n\n");
        }
        
        if (currentTestCase.getAssertions() != null && !currentTestCase.getAssertions().isEmpty()) {
            prompt.append("**Expected Assertions**: ").append(currentTestCase.getAssertions()).append("\n\n");
        }
        
        prompt.append("## Class Under Test\n");
        prompt.append("```java\n");
        prompt.append(classContext);
        prompt.append("\n```\n\n");
        
        prompt.append("## Instructions\n");
        prompt.append("Please write a complete JUnit test method for this test case. ");
        prompt.append("Include all necessary imports, setup, execution, and assertions. ");
        prompt.append("Make sure the test is comprehensive and follows best practices for unit testing.");
        
        return prompt.toString();
    }

    /**
     * Sends a prompt to the chat box and activates the browser window.
     */
    private boolean sendPromptToChatBox(String prompt, TestCase currentTestCase) {
        try {
            String systemPrompt = "";
            String templatePath;
            
            // Choose the appropriate system prompt template based on the test case type
            if (isTestReviewStep(currentTestCase)) {
                templatePath = "/templates/test_review_system_prompt.template";
            } else {
                templatePath = "/templates/test_execution_system_prompt.template";
            }
            
            // Load the system prompt from template
            java.io.InputStream inputStream = getClass().getResourceAsStream(templatePath);
            if (inputStream != null) {
                systemPrompt = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                inputStream.close();
                LOG.info("Loaded system prompt from template: " + templatePath);
            } else {
                LOG.warn("System prompt template not found: " + templatePath + ", using fallback");
                systemPrompt = "You are a specialized Java test writing expert with superior expertise in creating comprehensive unit tests. "
                        + "\nAnalyze code requirements and implement precise test cases that ensure thorough coverage and reliable validation.";
                
                // Only add /no_think for test execution (not review)
                if (!isTestReviewStep(currentTestCase)) {
                    systemPrompt += "\n/no_think";
                }
            }
            
            // Send the prompt to the chat box
            boolean success = ChatboxUtilities.sendTextAndSubmit(project, prompt, false, systemPrompt, true, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);

            // Activate browser tool window
            ApplicationManager.getApplication().invokeLater(() -> {
                ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
                if (toolWindow != null) {
                    toolWindow.activate(null);
                }
            });

            return success;
        } catch (Exception e) {
            LOG.error("Error sending prompt to chat box", e);
            return false;
        }
    }

    /**
     * Completes the current test case and moves to the next one.
     *
     * @return true if there are more test cases to execute, false if the test writing is complete
     */
    public boolean completeCurrentTestCaseAndMoveToNext() {
        // Load the current plan and progress
        TestPlan plan = stateManager.loadPlan();
        TestWritingProgress progress = stateManager.loadProgress();

        if (plan == null || progress == null) {
            LOG.error("Failed to load test plan or progress");
            return false;
        }

        // Get the current scenario and test case
        TestScenario currentScenario;
        TestCase currentTestCase;

        try {
            currentScenario = plan.getScenarios().get(progress.getCurrentScenarioIndex());
            currentTestCase = currentScenario.getTestCases().get(progress.getCurrentTestCaseIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid scenario or test case index", e);
            return false;
        }

        // Record the summary of this completed test case
        String completedTest = currentScenario.getTitle() + " - " + currentTestCase.getTitle();
        progress.addCompletedTest(completedTest);

        // Mark the current test case as complete
        currentTestCase.setStatus(TestStatus.COMPLETED);
        progress.markTestCaseComplete(currentTestCase.getId());

        // Check if this was the last test case of the current scenario
        boolean isLastTestCaseOfScenario = (progress.getCurrentTestCaseIndex() >= currentScenario.getTestCases().size() - 1);

        // Check if current test case is a test review step
        boolean isTestReviewStep = isTestReviewStep(currentTestCase);

        // If this is the last test case of the scenario and not a review step, check for issues and try to review
        if (isLastTestCaseOfScenario && !isTestReviewStep) {
            LOG.info("Completed all test cases in scenario: " + currentScenario.getTitle() + ". Checking for issues.");

            // Create a placeholder entry for issues to be filled with actual data
            // from the chat interaction
            progress.addIssuesAfterScenario(currentScenario.getId(), new ArrayList<>());

            // Create a special test case to review the scenario
            createTestReviewCase(currentScenario);

            // Don't move to next scenario yet - stay at the review step
            progress.setCurrentTestCaseIndex(currentScenario.getTestCases().size() - 1);
            stateManager.saveProgress(progress);
            stateManager.savePlan(plan);
            return true;
        }

        // Move to the next test case or scenario
        if (!isLastTestCaseOfScenario) {
            // More test cases in the current scenario
            progress.setCurrentTestCaseIndex(progress.getCurrentTestCaseIndex() + 1);
        } else {
            // This is either a review step or the last test case of the scenario
            // If it's a review step, or if there are no issues, move to the next scenario
            if (progress.getCurrentScenarioIndex() < plan.getScenarios().size() - 1) {
                // Move to the next scenario
                progress.setCurrentScenarioIndex(progress.getCurrentScenarioIndex() + 1);
                progress.setCurrentTestCaseIndex(0);
            } else {
                // All test cases are complete - generate final report
                progress.markComplete();
                stateManager.saveProgress(progress);

                // Generate and show final report
                generateFinalReport(plan, progress);

                // Clear the test writing state
                stateManager.clearTestWritingState();

                // Only close the tool window when all test cases are complete
                TestWritingToolWindow.checkAndCloseIfNoTestWriting(project);
                return false;
            }
        }

        // Save the updated progress and plan
        stateManager.saveProgress(progress);
        stateManager.savePlan(plan);
        return true;
    }

    /**
     * Skips the current test case and moves to the next one.
     *
     * @return true if there are more test cases to execute, false if the test writing is complete
     */
    public boolean skipCurrentTestCaseAndMoveToNext() {
        // Load the current plan and progress
        TestPlan plan = stateManager.loadPlan();
        TestWritingProgress progress = stateManager.loadProgress();

        if (plan == null || progress == null) {
            LOG.error("Failed to load test plan or progress");
            return false;
        }

        // Get the current scenario and test case
        TestScenario currentScenario;
        TestCase currentTestCase;

        try {
            currentScenario = plan.getScenarios().get(progress.getCurrentScenarioIndex());
            currentTestCase = currentScenario.getTestCases().get(progress.getCurrentTestCaseIndex());
        } catch (IndexOutOfBoundsException e) {
            LOG.error("Invalid scenario or test case index", e);
            return false;
        }

        // Mark the current test case as skipped
        currentTestCase.setStatus(TestStatus.SKIPPED);
        progress.markTestCaseSkipped(currentTestCase.getId());

        // Move to the next test case
        if (progress.getCurrentTestCaseIndex() < currentScenario.getTestCases().size() - 1) {
            progress.setCurrentTestCaseIndex(progress.getCurrentTestCaseIndex() + 1);
        } else if (progress.getCurrentScenarioIndex() < plan.getScenarios().size() - 1) {
            progress.setCurrentScenarioIndex(progress.getCurrentScenarioIndex() + 1);
            progress.setCurrentTestCaseIndex(0);
        } else {
            // All test cases are complete or skipped
            progress.markComplete();
            stateManager.saveProgress(progress);

            // Generate and show final report
            generateFinalReport(plan, progress);

            // Clear the test writing state
            stateManager.clearTestWritingState();

            // Only close the tool window when all test cases are complete
            TestWritingToolWindow.checkAndCloseIfNoTestWriting(project);
            return false;
        }

        // Save the updated progress and plan
        stateManager.saveProgress(progress);
        stateManager.savePlan(plan);
        return true;
    }

    boolean isTestReviewStep(TestCase testCase) {
        return testCase.getTitle().equals(REVIEW_AND_FINALIZE_TESTS);
    }

    /**
     * Creates a new test case to review tests after completing a scenario.
     *
     * @param scenario The current scenario
     */
    private void createTestReviewCase(TestScenario scenario) {
        // Create a description of the test review
        StringBuilder reviewDescription = new StringBuilder();
        reviewDescription.append("Now that we've completed implementing tests for this scenario, let's review and finalize them.\n\n");
        reviewDescription.append("First, I'll check for any compilation errors or issues:\n\n");
        reviewDescription.append("```\n");
        reviewDescription.append("tool_get_project_problems_post\n");
        reviewDescription.append("```\n\n");
        reviewDescription.append("Just call the tool, do nothing just yet.");

        // Create a new test case
        TestCase reviewCase = new TestCase();
        reviewCase.setId(scenario.getTestCases().size() + 1); // Generate a new ID
        reviewCase.setTitle(REVIEW_AND_FINALIZE_TESTS);
        reviewCase.setDescription(reviewDescription.toString());
        reviewCase.setStatus(TestStatus.PENDING);
        reviewCase.setScenarioId(scenario.getId());

        // Add the test case to the scenario
        scenario.getTestCases().add(reviewCase);
    }

    /**
     * Generates a final report of the test writing process and displays it.
     */
    private void generateFinalReport(TestPlan plan, TestWritingProgress progress) {
        StringBuilder report = new StringBuilder();

        // Report header
        report.append("# Test Writing Completed: ").append(plan.getName()).append("\n\n");
        report.append("## Target Class: ").append(plan.getTargetClass()).append("\n");
        report.append("## Test File: ").append(plan.getTestFilePath()).append("\n\n");

        // Statistics
        int totalTestCases = 0;
        for (TestScenario scenario : plan.getScenarios()) {
            totalTestCases += scenario.getTestCases().size();
        }

        report.append("## Statistics\n");
        report.append("- **Start Date:** ").append(formatDate(progress.getStartDate())).append("\n");
        report.append("- **End Date:** ").append(formatDate(progress.getLastUpdateDate())).append("\n");
        report.append("- **Total Scenarios:** ").append(plan.getScenarios().size()).append("\n");
        report.append("- **Total Test Cases:** ").append(totalTestCases).append("\n");
        report.append("- **Completed Test Cases:** ").append(progress.getCompletedTestCaseIds().size()).append("\n");
        report.append("- **Skipped Test Cases:** ").append(progress.getSkippedTestCaseIds().size()).append("\n");
        report.append("- **Failed Test Cases:** ").append(progress.getFailedTestCaseIds().size()).append("\n\n");

        // Summary of tests by scenario
        report.append("## Test Cases Implemented by Scenario\n");
        List<String> completedTests = progress.getCompletedTests();
        if (completedTests != null && !completedTests.isEmpty()) {
            // Group by scenario
            Map<String, List<String>> testsByScenario = new java.util.HashMap<>();

            for (String test : completedTests) {
                String[] parts = test.split(" - ", 2);
                if (parts.length >= 2) {
                    String scenarioTitle = parts[0];
                    String testCaseTitle = parts[1];

                    if (!testsByScenario.containsKey(scenarioTitle)) {
                        testsByScenario.put(scenarioTitle, new java.util.ArrayList<>());
                    }
                    testsByScenario.get(scenarioTitle).add(testCaseTitle);
                } else {
                    // Fallback if the format is unexpected
                    if (!testsByScenario.containsKey("Other")) {
                        testsByScenario.put("Other", new java.util.ArrayList<>());
                    }
                    testsByScenario.get("Other").add(test);
                }
            }

            // Display by scenario
            for (Map.Entry<String, List<String>> entry : testsByScenario.entrySet()) {
                report.append("### ").append(entry.getKey()).append("\n");
                for (String testCase : entry.getValue()) {
                    report.append("- ").append(testCase).append("\n");
                }
                report.append("\n");
            }
        } else {
            report.append("No test cases were implemented.\n\n");
        }

        // Conclusion
        report.append("## Conclusion\n");
        report.append("This test writing session has created comprehensive tests for ").append(plan.getTargetClass());
        report.append(" by implementing ").append(plan.getScenarios().size()).append(" test scenarios. ");
        report.append("The tests provide thorough coverage and validation, which will lead to more reliable and maintainable software.");

        // Show the final report
        String finalReport = report.toString();

        // Send the report to the browser
        ApplicationManager.getApplication().invokeLater(() -> {
            // First show a notification about the complete test writing
            Messages.showInfoMessage(
                    project,
                    "The test writing process has been completed successfully! A final report has been generated.",
                    "Test Writing Complete"
            );

            // Send the report to the chat box
            boolean sent = ChatboxUtilities.sendTextAndSubmit(project,
                    "# Test Writing Complete - Final Report\n\n" +
                            "Here is the final report for the completed test writing session:\n\n" +
                            finalReport,
                    true,
                    null,
                    false,
                    ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);

            if (!sent) {
                LOG.warn("Failed to send final report to chat box");
            }
        });
    }

    /**
     * Formats a date for the report.
     */
    private String formatDate(Date date) {
        if (date == null) {
            return "Unknown";
        }

        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
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
     * Aborts the test writing process.
     */
    public void abortTestWriting() {
        // Load the current progress
        TestWritingProgress progress = stateManager.loadProgress();

        if (progress != null) {
            // Mark the test writing as aborted
            progress.markAborted();
            stateManager.saveProgress(progress);
        }

        // Clear the test writing state
        stateManager.clearTestWritingState();

        // Close the tool window
        TestWritingToolWindow.checkAndCloseIfNoTestWriting(project);

        // Show abort message
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(
                    project,
                    "The test writing process has been aborted.",
                    "Test Writing Aborted"
            );
        });
    }
}
