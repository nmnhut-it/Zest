package com.zps.zest.testgen.agents;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.testFramework.LightVirtualFile;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.util.ExistingTestAnalyzer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * AI-based test merger that generates complete, merged test classes
 * Uses agentic architecture with tools for file access and result recording
 */
public class AITestMergerAgent extends StreamingBaseAgent {
    private final TestMergingAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    private final TestMergingTools mergingTools;
    private String lastExistingTestCode = null; // Store for UI display
    private MergedTestClass lastMergedResult = null; // Store the merged result

    public AITestMergerAgent(@NotNull Project project,
                            @NotNull ZestLangChain4jService langChainService,
                            @NotNull NaiveLLMService naiveLlmService) {
        super(project, langChainService, naiveLlmService, "AITestMergerAgent");
        this.mergingTools = new TestMergingTools(project, this::sendToUI);

        // Build the agent with streaming support
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(50);
        this.assistant = AgenticServices
                .agentBuilder(TestMergingAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(50) // Allow multiple tool calls for the workflow
                .chatMemory(chatMemory)
                .tools(mergingTools) // Use the actual merging tools
                .build();

    }
    
    /**
     * AI assistant for intelligent test class merging using tools
     */
    public interface TestMergingAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are an intelligent test merging coordinator that orchestrates test class merging using tools.

        TOOL USAGE TRACKING (mandatory for each response):
        📊 Track your tool usage:
        - Report after each tool call: "🔧 Tool usage: [X/50] calls used"
        - List recent tools: [setNewTestCode, findExistingTest, validateCurrentTestCode, etc.]
        - Be mindful of the 50 tool call limit per session

        RESPONSE FORMAT:
        After each tool usage, briefly report:
        📍 Phase: [Setup|Discovery|Merging|Validation|Completion]
        🔧 Tools used: X/50
        ⚡ Next: [Specific next action]

        WORKFLOW:
        1. Use setNewTestCode(className, testCode) to set the new test code to work with
        2. Use findExistingTest(targetClass) to check for existing test class
        3. If existing test found, merge it with new test and use updateTestCode() to save merged version
        4. If no existing test, the new test becomes the final version
        5. Validate and fix the test code (see validation workflow below)
        6. Call recordMergedResult() with the final test details

        MERGING RULES:
        1. **Preserve Existing Tests**: Never remove or modify existing test methods
        2. **Avoid Duplicates**: Skip test methods that already exist (same method name)
        3. **Framework Consistency**: Use the same testing framework as existing tests
        4. **Import Management**: Merge imports intelligently (remove duplicates, keep all needed)
        5. **Code Style**: Match the existing code style and patterns
        6. **Setup/Teardown**: Consolidate @BeforeEach/@AfterEach methods intelligently

        TEST WRITING PRINCIPLES (apply when merging):
        1. **PREFER TESTCONTAINERS over mocking** for:
           - Database interactions (PostgreSQL, MySQL, MongoDB containers)
           - Message queues (Kafka, RabbitMQ containers)
           - External services (Redis, Elasticsearch containers)

        2. **F.I.R.S.T Principles**:
           - Fast: Tests should run quickly
           - Independent: No test dependencies
           - Repeatable: Same result every time
           - Self-validating: Clear pass/fail
           - Timely: Right test approach for dependencies

        3. **Test Method Standards**:
           - Name format: testMethod_WhenCondition_ThenExpectedResult
           - Test ONE scenario per method
           - Use Given-When-Then pattern
           - Include meaningful assertions

        CONFLICT RESOLUTION:
        - Method name conflicts: Add numeric suffix or rename descriptively
        - Framework conflicts: Prefer existing framework
        - Import conflicts: Use fully qualified names when needed
        - Setup conflicts: Merge or use separate setup methods

        VALIDATION WORKFLOW (MANDATORY):
        7. After merging/updating, call validateCurrentTestCode() to check for issues
        8. If validation returns VALIDATION_FAILED:
           - STOP AND ANALYZE the root cause before fixing
           - Use applySimpleFix(oldText, newText) to fix issues one by one
           - After fixing all issues, call validateCurrentTestCode() again
        9. Repeat until VALIDATION_PASSED or fixes exhausted
        10. Call recordMergedResult() with your final code
        11. Call markMergingDone() with completion status

        VALIDATION FAILURE ANALYSIS (CRITICAL):
        When validation fails, BEFORE attempting any fix:

        1. DIAGNOSE THE ROOT CAUSE:
           - "cannot find symbol" → Missing import or unavailable library
           - "package does not exist" → Missing dependency in build file
           - "incompatible types" → Wrong API version or incorrect usage
           - Syntax errors → Typos or malformed code

        2. UNDERSTAND WHY IT FAILED - Think deeply:
           "This error occurs because..." and explain the actual cause
           Example: "TestContainers class not found means the library isn't in classpath"

        3. DOCUMENT IN CODE COMMENTS:
           When using external libraries, add:
           // Requires: org.testcontainers:testcontainers:1.x.x
           // If compilation fails, add to pom.xml or build.gradle

        4. FIX STRATEGICALLY:
           - Missing libraries → Add TODO comment and try alternative approach
           - Wrong API → Check project's actual version
           - Missing imports → Add the correct import statement

        LIBRARY DEPENDENCY AWARENESS:
        Common test dependencies to document:
        - TestContainers: // Requires: org.testcontainers:testcontainers
        - Mockito: // Requires: org.mockito:mockito-core
        - AssertJ: // Requires: org.assertj:assertj-core
        - RestAssured: // Requires: io.rest-assured:rest-assured

        AFTER VALIDATION:
        Call recordMergedResult with these parameters:
        - packageName: The package declaration
        - fileName: The .java filename (e.g., "MyTest.java")
        - methodCount: String count of @Test methods (e.g., "12")
        - framework: "JUnit5", "JUnit4", or "TestNG"

        COMPLETION:
        You MUST call markMergingDone() when:
        - Validation passes (VALIDATION_PASSED)
        - OR you've exhausted reasonable fix attempts
        Provide a clear reason in the markMergingDone call
        """)
        @dev.langchain4j.agentic.Agent
        String mergeAndFixTestClass(String request);
    }
    
    /**
     * Merge generated test class with existing test file (if any)
     */
    @NotNull
    public CompletableFuture<MergedTestClass> mergeTests(@NotNull TestGenerationResult result,
                                                         @NotNull ContextAgent.ContextGatheringTools contextTools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting AI-based test merging for: " + result.getClassName());

                // Reset for new session
                lastMergedResult = null;
                mergingTools.reset();

                // Notify UI
                notifyStart();
                sendToUI("🤖 AI-based test merging starting...\n\n");

                // Build the merging request
                String mergeRequest = buildMergeRequest(result);

                // Send the request to UI
                sendToUI("📋 Merge Request:\n" + mergeRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Keep merging until explicitly marked as done
                int maxIterations = 5; // Safety limit to prevent infinite loops
                int iteration = 0;

                while (!mergingTools.isMergingComplete() && iteration < maxIterations) {
                    iteration++;

                    try {
                        // For first iteration, use full merge request; for subsequent iterations, continue
                        String promptToUse = (iteration == 1) ? mergeRequest :
                            "Continue merging and validation. Remember to call markMergingDone when complete.";

                        // Let the AI orchestrate the merging using tools
                        String response = assistant.mergeAndFixTestClass(promptToUse);

                        // Send response to UI
                        sendToUI(response);
                        sendToUI("\n" + "-".repeat(40) + "\n");

                        // Check if merging is now done
                        if (mergingTools.isMergingComplete()) {
                            sendToUI("✅ Merging marked as complete by assistant.\n");
                            break;
                        }

                        // If not done and not at max iterations, show continuation message
                        if (!mergingTools.isMergingComplete() && iteration < maxIterations) {
                            sendToUI("\n🔄 Continuing merge process (iteration " + (iteration + 1) + ")...\n");
                            sendToUI("-".repeat(40) + "\n");
                        }

                    } catch (Exception e) {
                        LOG.warn("Merge agent encountered an error but continuing", e);
                        sendToUI("\n⚠️ Merge agent stopped: " + e.getMessage());
                        sendToUI("\nContinuing with available result...\n");
                        break; // Exit loop on error
                    }
                }

                if (iteration >= maxIterations && !mergingTools.isMergingComplete()) {
                    LOG.warn("Merging reached maximum iterations without completion");
                    sendToUI("\n⚠️ Merging reached maximum iterations. Proceeding with current result.\n");
                }

                // Check if the AI successfully recorded the result
                if (lastMergedResult == null) {
                    // Fallback: AI didn't use recordMergedResult tool properly
                    LOG.warn("AI did not record merged result, creating fallback");

                    // Try to get the actual test code from MergingTools
                    String mergedCode = mergingTools.getCurrentTestCode();

                    // If no current test code in MergingTools, try to reconstruct from original
                    if (mergedCode == null || mergedCode.equals("NO_CURRENT_TEST_CODE")) {
                        LOG.warn("No current test code in MergingTools, checking for complete test class");

                        // First, check if we have a complete test class already
                        if (result.getCompleteTestClass() != null && !result.getCompleteTestClass().isEmpty()) {
                            mergedCode = result.getCompleteTestClass();
                            sendToUI("✅ Using complete test class from generation result\n");
                        } else {
                            LOG.warn("No complete test class, reconstructing from test methods");

                            // Reconstruct the full test class from the original test methods
                            StringBuilder reconstructed = new StringBuilder();
                            reconstructed.append("package ").append(result.getPackageName()).append(";\n\n");

                            // Add imports
                            for (String importStmt : result.getImports()) {
                                reconstructed.append(importStmt).append("\n");
                            }
                            reconstructed.append("\n");

                            // Add class declaration
                            reconstructed.append("public class ").append(result.getClassName()).append(" {\n\n");

                            // Add field declarations
                            for (String fieldDecl : result.getFieldDeclarations()) {
                                reconstructed.append("    ").append(fieldDecl).append("\n");
                            }
                            if (!result.getFieldDeclarations().isEmpty()) {
                                reconstructed.append("\n");
                            }

                            // Add setup/teardown methods if present
                            if (result.getBeforeEachCode() != null && !result.getBeforeEachCode().isEmpty()) {
                                reconstructed.append("    @BeforeEach\n");
                                reconstructed.append("    public void setUp() {\n");
                                reconstructed.append("        ").append(result.getBeforeEachCode()).append("\n");
                                reconstructed.append("    }\n\n");
                            }

                            // Add all test methods
                            for (GeneratedTestMethod method : result.getTestMethods()) {
                                String methodCode = method.getCompleteMethodCode();
                                // Indent the method code
                                String[] lines = methodCode.split("\n");
                                for (String line : lines) {
                                    reconstructed.append("    ").append(line).append("\n");
                                }
                                reconstructed.append("\n");
                            }

                            reconstructed.append("}\n");
                            mergedCode = reconstructed.toString();

                            sendToUI("⚠️ Using reconstructed test code from original methods\n");
                        }
                    } else {
                        sendToUI("✅ Using test code from MergingTools\n");
                    }

                    // Force validation before creating the result
                    sendToUI("\n🔍 Enforcing validation...\n");
                    String validationResult = mergingTools.validateTestCode(result.getClassName(), mergedCode);
                    sendToUI("Validation result: " + validationResult + "\n");

                    // If validation failed, attempt auto-fix
                    if (validationResult.startsWith("VALIDATION_FAILED")) {
                        sendToUI("⚠️ Validation failed, attempting auto-fix...\n");

                        // Ask AI to fix the issues
                        String fixRequest = "Fix these validation issues in the test class:\n\n" +
                                          validationResult + "\n\n" +
                                          "Test class to fix:\n```java\n" + mergedCode + "\n```\n\n" +
                                          "Return ONLY the fixed Java code, no explanations.";

                        String fixedCode = assistant.mergeAndFixTestClass(fixRequest);

                        // Validate again
                        String revalidationResult = mergingTools.validateTestCode(result.getClassName(), fixedCode);
                        sendToUI("Re-validation result: " + revalidationResult + "\n");

                        if (revalidationResult.startsWith("VALIDATION_PASSED")) {
                            mergedCode = fixedCode;
                            sendToUI("✅ Validation passed after auto-fix\n");
                        } else {
                            sendToUI("⚠️ Validation still has issues, proceeding with best effort\n");
                            // Use the fixed version even if not perfect
                            mergedCode = fixedCode;
                        }
                    }

                    // Count actual test methods in the merged code
                    int actualMethodCount = countTestMethods(mergedCode);

                    lastMergedResult = new MergedTestClass(
                        result.getClassName(),
                        result.getPackageName(),
                        mergedCode,
                        result.getClassName() + ".java",
                        mergingTools.determineOutputPath(result.getClassName(), result.getPackageName()),
                        actualMethodCount,
                        result.getFramework()
                    );
                } else {
                    // AI used recordMergedResult, but let's still validate
                    sendToUI("\n🔍 Validating AI-merged result...\n");
                    String validationResult = mergingTools.validateTestCode(
                        lastMergedResult.getClassName(),
                        lastMergedResult.getFullContent()
                    );

                    if (validationResult.startsWith("VALIDATION_FAILED")) {
                        sendToUI("⚠️ AI-merged result has validation issues:\n" + validationResult + "\n");

                        // Attempt to fix
                        String fixRequest = "Fix these validation issues in the test class:\n\n" +
                                          validationResult + "\n\n" +
                                          "Test class to fix:\n```java\n" + lastMergedResult.getFullContent() + "\n```\n\n" +
                                          "Return ONLY the fixed Java code, no explanations.";

                        String fixedCode = assistant.mergeAndFixTestClass(fixRequest);

                        // Re-validate
                        String revalidationResult = mergingTools.validateTestCode(lastMergedResult.getClassName(), fixedCode);

                        if (revalidationResult.startsWith("VALIDATION_PASSED")) {
                            // Update the merged result with fixed code
                            lastMergedResult = new MergedTestClass(
                                lastMergedResult.getClassName(),
                                lastMergedResult.getPackageName(),
                                fixedCode,
                                lastMergedResult.getFileName(),
                                lastMergedResult.getFullFilePath(),
                                lastMergedResult.getMethodCount(),
                                lastMergedResult.getFramework()
                            );
                            sendToUI("✅ Validation passed after auto-fix\n");
                        } else {
                            sendToUI("⚠️ Could not fully resolve validation issues\n");
                        }
                    } else {
                        sendToUI("✅ Validation passed\n");
                    }
                }

                // Summary
                sendToUI("\n📊 Merge Summary:\n");
                sendToUI("  - Final class: " + lastMergedResult.getClassName() + "\n");
                sendToUI("  - Package: " + lastMergedResult.getPackageName() + "\n");
                sendToUI("  - Total methods: " + lastMergedResult.getMethodCount() + "\n");
                sendToUI("  • Framework: " + lastMergedResult.getFramework() + "\n");
                notifyComplete();

                LOG.debug("AI test merging complete: " + lastMergedResult.getMethodCount() + " total methods");

                return lastMergedResult;

            } catch (Exception e) {
                LOG.error("AI test merging failed", e);
                sendToUI("\n❌ AI test merging failed: " + e.getMessage() + "\n");
                throw new RuntimeException("AI test merging failed: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Build the merge request with all necessary context
     */
    private String buildMergeRequest(TestGenerationResult result) {
        StringBuilder request = new StringBuilder();

        request.append("MERGE TASK: Intelligently merge the new test class with any existing test class\n\n");

        // Target class information
        request.append("Target Class: ").append(result.getTargetClass()).append("\n");
        request.append("Test Class Name: ").append(result.getClassName()).append("\n");
        request.append("Package: ").append(result.getPackageName()).append("\n");
        request.append("Framework: ").append(result.getFramework()).append("\n\n");

        // New test class to merge
        request.append("NEW TEST CLASS TO MERGE:\n");
        request.append("```java\n");

        // Use the complete test class from TestWriterAgent if available
        String newTestClass = result.getCompleteTestClass();
        if (newTestClass == null || newTestClass.isEmpty()) {
            // Fallback to generating from components if complete class not available
            newTestClass = generateCompleteTestClass(result);
        }
        request.append(newTestClass);
        request.append("\n```\n\n");

        request.append("INSTRUCTIONS:\n");
        request.append("Remember to track your tool usage throughout the process (e.g., \"🔧 Tools used: 3/50\").\n\n");
        request.append("1. First use setNewTestCode(\"").append(result.getClassName()).append("\", <test_code>) to set the new test to work with\n");
        request.append("2. Use findExistingTest(\"").append(result.getTargetClass()).append("\") to check for existing tests\n");
        request.append("3. If existing test found, merge intelligently and use updateTestCode() to save merged version\n");
        request.append("4. If no existing test, the new test becomes the final version\n");
        request.append("5. Use validateCurrentTestCode() and applySimpleFix() to fix any issues\n");
        request.append("6. Call recordMergedResult() with the final validated test details\n");
        request.append("7. Call markMergingDone() when validation passes or fixes exhausted\n");
        request.append("8. Throughout the process, report your tool usage: \"🔧 Tools used: X/50\"\n");

        return request.toString();
    }
    
    /**
     * Generate complete test class from TestGenerationResult
     */
    private String generateCompleteTestClass(TestGenerationResult result) {
        StringBuilder testClass = new StringBuilder();
        
        // Package declaration
        if (!result.getPackageName().isEmpty()) {
            testClass.append("package ").append(result.getPackageName()).append(";\n\n");
        }
        
        // Imports
        for (String importStr : result.getImports()) {
            if (importStr.contains("static ")) {
                testClass.append("import static ").append(importStr.replace("static ", "")).append(";\n");
            } else {
                testClass.append("import ").append(importStr).append(";\n");
            }
        }
        testClass.append("\n");
        
        // Class declaration
        testClass.append("public class ").append(result.getClassName()).append(" {\n\n");
        
        // Field declarations
        for (String field : result.getFieldDeclarations()) {
            testClass.append("    ").append(field);
            if (!field.endsWith(";")) {
                testClass.append(";");
            }
            testClass.append("\n");
        }
        if (!result.getFieldDeclarations().isEmpty()) {
            testClass.append("\n");
        }
        
        // BeforeEach method
        if (!result.getBeforeEachCode().isEmpty()) {
            testClass.append("    @BeforeEach\n");
            testClass.append("    public void setUp() {\n");
            String[] lines = result.getBeforeEachCode().split("\n");
            for (String line : lines) {
                testClass.append("        ").append(line).append("\n");
            }
            testClass.append("    }\n\n");
        }
        
        // Test methods
        for (GeneratedTestMethod method : result.getTestMethods()) {
            // Annotations
            for (String annotation : method.getAnnotations()) {
                testClass.append("    @").append(annotation).append("\n");
            }
            
            // Method signature
            testClass.append("    public void ").append(method.getMethodName()).append("() {\n");
            
            // Method body
            String[] bodyLines = method.getMethodBody().split("\n");
            for (String line : bodyLines) {
                if (!line.trim().isEmpty()) {
                    testClass.append("        ").append(line).append("\n");
                }
            }
            
            testClass.append("    }\n\n");
        }
        
        // AfterEach method
        if (!result.getAfterEachCode().isEmpty()) {
            testClass.append("    @AfterEach\n");
            testClass.append("    public void tearDown() {\n");
            String[] lines = result.getAfterEachCode().split("\n");
            for (String line : lines) {
                testClass.append("        ").append(line).append("\n");
            }
            testClass.append("    }\n\n");
        }
        
        testClass.append("}\n");
        
        return testClass.toString();
    }
    
    /**
     * Find the best test source root from project modules
     */
    private String findBestTestSourceRoot() {
        // Try to find test roots from project modules
        com.intellij.openapi.module.ModuleManager moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project);
        for (com.intellij.openapi.module.Module module : moduleManager.getModules()) {
            com.intellij.openapi.roots.ModuleRootManager rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module);
            // Get test source roots (true = test roots only)
            com.intellij.openapi.vfs.VirtualFile[] testRoots = rootManager.getSourceRoots(true);
            for (com.intellij.openapi.vfs.VirtualFile testRoot : testRoots) {
                if (testRoot.getPath().contains("test")) {
                    return testRoot.getPath();
                }
            }
        }
        
        // Fallback to conventional paths
        String basePath = project.getBasePath();
        if (basePath == null) {
            return "src/test/java"; // Last resort fallback
        }
        
        // Check common test directories using File to handle separators correctly
        java.io.File baseDir = new java.io.File(basePath);
        
        java.io.File srcTestJava = new java.io.File(baseDir, "src/test/java");
        if (srcTestJava.exists()) {
            return srcTestJava.getAbsolutePath();
        }
        
        java.io.File srcTestKotlin = new java.io.File(baseDir, "src/test/kotlin");
        if (srcTestKotlin.exists()) {
            return srcTestKotlin.getAbsolutePath();
        }
        
        java.io.File testJava = new java.io.File(baseDir, "test/java");
        if (testJava.exists()) {
            return testJava.getAbsolutePath();
        }
        
        java.io.File test = new java.io.File(baseDir, "test");
        if (test.exists()) {
            return test.getAbsolutePath();
        }
        
        // Default to standard Maven/Gradle structure
        return new java.io.File(baseDir, "src/test/java").getAbsolutePath();
    }

    /**
     * Count the number of @Test methods in the code
     */
    private int countTestMethods(String code) {
        int count = 0;
        String[] lines = code.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("@Test")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Auto-fix issues in test class
     */
    @NotNull
    public CompletableFuture<String> autoFixTestClass(@NotNull String testClassCode, @NotNull String issues) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting auto-fix for test class");
                
                String fixRequest = "FIX TASK: Fix all issues in this test class\n\n" +
                    "Issues found:\n" + issues + "\n\n" +
                    "Test Class to fix:\n```java\n" + testClassCode + "\n```\n\n" +
                    "Generate the complete FIXED Java test class.";
                
                // Use the AI assistant to fix
                String fixedCode = assistant.mergeAndFixTestClass(fixRequest);
                
                return fixedCode;
                
            } catch (Exception e) {
                LOG.error("Auto-fix failed", e);
                throw new RuntimeException("Auto-fix failed: " + e.getMessage(), e);
            }
        });
    }
    
    
    @NotNull
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }
    
    /**
     * Get the last existing test code that was found during merging
     * @return The existing test code or null if no existing test was found
     */
    @Nullable
    public String getLastExistingTestCode() {
        return lastExistingTestCode;
    }
    
    /**
     * Test merging tools - concrete implementations for file access and result recording
     */
    public class TestMergingTools {
        private final Project project;
        private final ExistingTestAnalyzer existingTestAnalyzer;
        private final java.util.function.Consumer<String> toolNotifier;
        private String currentWorkingTestCode = null; // Maintains the current version of test code being worked on
        private String currentTestClassName = null; // The class name of the current test
        private boolean mergingComplete = false; // Track if merging is complete

        public TestMergingTools(@NotNull Project project,
                               @Nullable java.util.function.Consumer<String> toolNotifier) {
            this.project = project;
            this.existingTestAnalyzer = new ExistingTestAnalyzer(project);
            this.toolNotifier = toolNotifier;
        }

        private void notifyTool(String toolName, String params) {
            if (toolNotifier != null) {
                toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params));
            }
        }

        @Tool("Set the new test code that needs to be merged")
        public String setNewTestCode(String className, String testCode) {
            notifyTool("setNewTestCode", className);
            currentTestClassName = className;
            currentWorkingTestCode = testCode;
            return "New test code set for " + className + " (" + testCode.length() + " characters)";
        }

        @Tool("Get the current working test code")
        public String getCurrentTestCode() {
            notifyTool("getCurrentTestCode", currentTestClassName != null ? currentTestClassName : "none");
            if (currentWorkingTestCode == null) {
                return "NO_CURRENT_TEST_CODE";
            }
            return currentWorkingTestCode;
        }

        @Tool("Update the current working test code with merged or fixed version")
        public String updateTestCode(String updatedCode) {
            notifyTool("updateTestCode", currentTestClassName != null ? currentTestClassName : "updating");
            if (currentWorkingTestCode == null) {
                return "ERROR: No current test code to update";
            }
            currentWorkingTestCode = updatedCode;
            return "Test code updated (" + updatedCode.length() + " characters)";
        }

        @Tool("Find existing test class for the target class if it exists")
        public String findExistingTest(String targetClassName) {
            notifyTool("findExistingTest", targetClassName);

            try {
                ExistingTestAnalyzer.ExistingTestClass existingTest =
                    com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<ExistingTestAnalyzer.ExistingTestClass>) () ->
                            existingTestAnalyzer.findExistingTestClass(targetClassName)
                    );

                if (existingTest != null) {
                    String existingCode = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<String>) () ->
                            getSourceCodeFromPsiClass(existingTest.getPsiClass())
                    );

                    if (existingCode != null && !existingCode.isEmpty()) {
                        lastExistingTestCode = existingCode;
                        return "EXISTING_TEST_FOUND:\n" + existingCode;
                    }
                }

                lastExistingTestCode = null;
                return "NO_EXISTING_TEST";

            } catch (Exception e) {
                LOG.warn("Error finding existing test for: " + targetClassName, e);
                return "ERROR: " + e.getMessage();
            }
        }

        @Tool("Record the merged test class result from current working code")
        public String recordMergedResult(String packageName, String fileName,
                                        String methodCount, String framework) {
            if (currentWorkingTestCode == null || currentTestClassName == null) {
                return "ERROR: No current test code to record. Use setNewTestCode and merge first.";
            }
            notifyTool("recordMergedResult", currentTestClassName);

            try {
                String className = currentTestClassName;
                String fullContent = currentWorkingTestCode;

                // Determine the output path
                String outputPath = determineOutputPath(className, packageName);

                // Create MergedTestClass object
                lastMergedResult = new MergedTestClass(
                    className,
                    packageName,
                    fullContent,
                    fileName,
                    outputPath,
                    Integer.parseInt(methodCount),
                    framework
                );

                return "RECORDED: " + className + " with " + methodCount + " test methods";

            } catch (Exception e) {
                LOG.error("Failed to record merged result", e);
                return "ERROR: " + e.getMessage();
            }
        }

        @Tool("Validate and determine the output path for the test class")
        public String validateTestPath(String packageName, String className) {
            notifyTool("validateTestPath", className);

            try {
                String path = determineOutputPath(className, packageName);
                return "PATH: " + path;
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @Tool("Validate current working test code using IntelliJ's code analysis")
        public String validateCurrentTestCode() {
            if (currentWorkingTestCode == null || currentTestClassName == null) {
                return "ERROR: No current test code to validate. Use setNewTestCode first.";
            }
            notifyTool("validateCurrentTestCode", currentTestClassName);

            return validateTestCode(currentTestClassName, currentWorkingTestCode);
        }

        // Keep the original method for backward compatibility but not as a tool
        public String validateTestCode(String className, String testCode) {
            notifyTool("validateTestCode", className);

            try {
                // Get Java file type
                com.intellij.openapi.fileTypes.FileType javaFileType =
                    com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("java");

                // Create virtual file for validation
                LightVirtualFile virtualFile = new LightVirtualFile(
                    className + ".java",
                    javaFileType,
                    testCode
                );

                // Run validation with ProgressManager in a background task
                java.util.concurrent.CompletableFuture<java.util.List<CodeSmellInfo>> future =
                    new java.util.concurrent.CompletableFuture<>();

                com.intellij.openapi.progress.ProgressManager.getInstance().run(
                    new com.intellij.openapi.progress.Task.Backgroundable(project, "Validating test code", false) {
                        @Override
                        public void run(@org.jetbrains.annotations.NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                            try {
                                indicator.setText("Analyzing test code for issues...");
                                indicator.setIndeterminate(true);

                                CodeSmellDetector detector = CodeSmellDetector.getInstance(project);
                                java.util.List<CodeSmellInfo> detectedIssues = detector.findCodeSmells(
                                    java.util.Arrays.asList(virtualFile)
                                ).stream().filter(v->v.getSeverity().equals(HighlightSeverity.ERROR)).toList();
                                future.complete(detectedIssues);
                            } catch (Exception e) {
                                LOG.warn("Error in code smell detection", e);
                                future.complete(java.util.Collections.emptyList());
                            }
                        }
                    }
                );

                // Wait for the result with a timeout
                java.util.List<CodeSmellInfo> issues;
                try {
                    issues = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    LOG.warn("Validation timeout", e);
                    return "VALIDATION_SKIPPED: Timeout during validation";
                }

                if (issues.isEmpty()) {
                    return "VALIDATION_PASSED: No issues found";
                }

                // Format issues for AI to fix
                StringBuilder result = new StringBuilder("VALIDATION_FAILED:\n");
                for (CodeSmellInfo issue : issues) {
                    result.append(String.format("- Line %d: %s\n",
                        issue.getStartLine(),
                        issue.getDescription()));
                }
                return result.toString();

            } catch (Exception e) {
                LOG.warn("Error validating test code", e);
                // Don't block on validation errors - let the code proceed
                return "VALIDATION_SKIPPED: " + e.getMessage();
            }
        }

        @Tool("Apply simple text replacement to fix code issue in current test")
        public String applySimpleFix(String oldText, String newText) {
            if (currentWorkingTestCode == null) {
                return "ERROR: No current test code to fix. Use setNewTestCode first.";
            }
            notifyTool("applySimpleFix", "Replacing text");

            try {
                String testCode = currentWorkingTestCode;
                if (!testCode.contains(oldText)) {
                    return "ERROR: Text not found. Check exact match including whitespace.";
                }

                // Check for uniqueness
                int firstIndex = testCode.indexOf(oldText);
                int lastIndex = testCode.lastIndexOf(oldText);
                if (firstIndex != lastIndex) {
                    return "ERROR: Multiple occurrences found. Be more specific.";
                }

                // Apply the fix to the current working code
                currentWorkingTestCode = testCode.replace(oldText, newText);
                return "SUCCESS: Replacement applied to current test code.";

            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @Tool("Mark merging as complete when validation passes or maximum attempts reached")
        public String markMergingDone(String reason) {
            notifyTool("markMergingDone", reason);
            mergingComplete = true;

            // Summary of what was accomplished
            StringBuilder summary = new StringBuilder();
            summary.append("MERGING_COMPLETE: ").append(reason).append("\n");

            if (currentTestClassName != null) {
                summary.append("✅ Test class: ").append(currentTestClassName).append("\n");
                if (currentWorkingTestCode != null) {
                    int methodCount = countTestMethods(currentWorkingTestCode);
                    summary.append("📊 Test methods: ").append(methodCount).append("\n");
                }
            }

            return summary.toString();
        }

        public boolean isMergingComplete() {
            return mergingComplete;
        }

        public void reset() {
            currentWorkingTestCode = null;
            currentTestClassName = null;
            mergingComplete = false;
        }

        private int countTestMethods(String testCode) {
            int count = 0;
            String[] lines = testCode.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("@Test") ||
                    line.contains("@org.junit.Test") ||
                    line.contains("@org.junit.jupiter.api.Test")) {
                    count++;
                }
            }
            return count;
        }

        private String determineOutputPath(String className, String packageName) {
            // Check if existing test file has a path
            try {
                ExistingTestAnalyzer.ExistingTestClass existingTest =
                    com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<ExistingTestAnalyzer.ExistingTestClass>) () ->
                            existingTestAnalyzer.findExistingTestClass(className.replace("Test", ""))
                    );
                if (existingTest != null) {
                    return existingTest.getFilePath();
                }
            } catch (Exception e) {
                LOG.warn("Could not determine existing test path", e);
            }

            // Find the best test source root
            String testSourceRoot = findBestTestSourceRoot();

            // Build the full path
            java.io.File testDir = new java.io.File(testSourceRoot);
            String packagePath = packageName.replace('.', java.io.File.separatorChar);
            java.io.File packageDir = packagePath.isEmpty() ? testDir : new java.io.File(testDir, packagePath);
            java.io.File testFile = new java.io.File(packageDir, className + ".java");

            return testFile.getAbsolutePath();
        }

        private String getSourceCodeFromPsiClass(com.intellij.psi.PsiClass psiClass) {
            if (psiClass == null) {
                return null;
            }

            try {
                com.intellij.psi.PsiFile containingFile = psiClass.getContainingFile();
                if (containingFile != null) {
                    return containingFile.getText();
                }
            } catch (Exception e) {
                LOG.warn("Could not extract source code from PSI class: " + psiClass.getName(), e);
            }

            return null;
        }
    }
}