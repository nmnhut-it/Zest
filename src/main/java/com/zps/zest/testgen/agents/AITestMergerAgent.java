package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
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
                .maxSequentialToolsInvocations(10) // Allow multiple tool calls for the workflow
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

        WORKFLOW:
        1. Use findExistingTest(targetClass) to check for existing test class
        2. Analyze the new test class and existing test (if found)
        3. Intelligently merge them following the rules below
        4. Call recordMergedResult() with the merged test details

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

        AFTER MERGING:
        Call recordMergedResult with these parameters:
        - className: The final test class name
        - packageName: The package declaration
        - fullContent: The complete merged Java code
        - fileName: The .java filename
        - methodCount: String count of @Test methods (e.g., "12")
        - framework: "JUnit5", "JUnit4", or "TestNG"

        The merged class should be complete, compilable, and follow best practices.
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

                // Reset the last merged result
                lastMergedResult = null;

                // Notify UI
                notifyStart();
                sendToUI("🤖 AI-based test merging starting...\n\n");

                // Build the merging request
                String mergeRequest = buildMergeRequest(result);

                // Send the request to UI
                sendToUI("📋 Merge Request:\n" + mergeRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Let the AI orchestrate the merging using tools
                String response = assistant.mergeAndFixTestClass(mergeRequest);

                // Send response to UI
                sendToUI(response);
                sendToUI("\n" + "-".repeat(40) + "\n");

                // Check if the AI successfully recorded the result
                if (lastMergedResult == null) {
                    // Fallback: AI didn't use recordMergedResult tool properly
                    LOG.warn("AI did not record merged result, creating fallback");
                    lastMergedResult = new MergedTestClass(
                        result.getClassName(),
                        result.getPackageName(),
                        response, // Assume the response is the merged code
                        result.getClassName() + ".java",
                        mergingTools.determineOutputPath(result.getClassName(), result.getPackageName()),
                        result.getMethodCount(),
                        result.getFramework()
                    );
                }

                // Summary
                sendToUI("\n📊 Merge Summary:\n");
                sendToUI("  • Final class: " + lastMergedResult.getClassName() + "\n");
                sendToUI("  • Package: " + lastMergedResult.getPackageName() + "\n");
                sendToUI("  • Total methods: " + lastMergedResult.getMethodCount() + "\n");
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
        request.append("1. First use findExistingTest(\"").append(result.getTargetClass()).append("\") to check for existing tests\n");
        request.append("2. If existing test is found, merge intelligently following the merging rules\n");
        request.append("3. If no existing test, the new test class becomes the final result\n");
        request.append("4. Call recordMergedResult() with the final merged test class details\n");

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
     * Review test class for issues and return detailed analysis
     */
    @NotNull
    public CompletableFuture<String> reviewTestClass(@NotNull String testClassCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting test class review");
                
                String reviewRequest = "REVIEW TASK: Analyze this test class for issues\n\n" +
                    "Please identify:\n" +
                    "1. COMPILATION_ERRORS: Missing imports, syntax errors, undefined references\n" +
                    "2. LOGICAL_ISSUES: Missing assertions, empty methods, incomplete setup\n" +
                    "3. QUALITY_IMPROVEMENTS: Poor naming, missing annotations, no documentation\n\n" +
                    "Test Class:\n```java\n" + testClassCode + "\n```\n\n" +
                    "Format your response EXACTLY as:\n" +
                    "COMPILATION_ERRORS:\n" +
                    "- Line X: Description\n\n" +
                    "LOGICAL_ISSUES:\n" +
                    "- Line X: Description\n\n" +
                    "QUALITY_IMPROVEMENTS:\n" +
                    "- Line X: Description\n\n" +
                    "SUGGESTIONS:\n" +
                    "- Suggestion text";
                
                // Use the AI assistant to review
                String reviewResult = assistant.mergeAndFixTestClass(reviewRequest);
                
                // If AI returns code instead of review, extract issues from the improvements made
                if (reviewResult.startsWith("package ")) {
                    return extractIssuesFromComparison(testClassCode, reviewResult);
                }
                
                return reviewResult;
                
            } catch (Exception e) {
                LOG.error("Test review failed", e);
                return "REVIEW_ERROR:\n- Failed to analyze test class: " + e.getMessage();
            }
        });
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

        @Tool("Record the merged test class result")
        public String recordMergedResult(String className, String packageName,
                                        String fullContent, String fileName,
                                        String methodCount, String framework) {
            notifyTool("recordMergedResult", className);

            try {
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