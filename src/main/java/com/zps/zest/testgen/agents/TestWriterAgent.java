package com.zps.zest.testgen.agents;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.zps.zest.langchain4j.ZestChatLanguageModel;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.ui.model.GeneratedTestDisplayData;
import com.zps.zest.testgen.util.ExistingTestAnalyzer;
import com.zps.zest.testgen.util.TestMerger;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import dev.langchain4j.rag.RetrievalAugmentor;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

/**
 * Test writing agent using LangChain4j's streaming capabilities.
 * Generates complete test classes in a single response - no loops needed.
 */
public class TestWriterAgent extends StreamingBaseAgent {
    private final TestWritingTools writingTools;
    private final TestWritingAssistant assistant;
    
    public TestWriterAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "TestWriterAgent");
        this.writingTools = new TestWritingTools(langChainService, this);
        
        // Build the agent with streaming support
        this.assistant = AgenticServices
                .agentBuilder(TestWritingAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(100) // Allow many tool calls for all test methods
                .chatMemory(MessageWindowChatMemory.withMaxMessages(200))
                .tools(writingTools)
                .build();
    }
    
    /**
     * Streamlined interface - generates complete test class in one shot
     */
    public interface TestWritingAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test writing assistant that generates complete, high-quality test classes.
        
        CRITICAL: Generate the ENTIRE test class in ONE response using BATCH tools for maximum efficiency.
        
        PROCESS (use these tools in EXACT order):
        1. setPackageName - Set the package name (infer from target class)
        2. setTestClassName - Set the test class name AND file path based on project analysis
           - Analyze the project structure from the file listing provided
           - Choose appropriate path: "src/test/java/..." for Maven/Gradle, "test/..." for simpler layouts
           - Example: "UserServiceTest", "src/test/java/com/example/service/UserServiceTest.java"
        3. setTestFramework - Set the framework (JUnit5, JUnit4, TestNG, etc.)
        4. addMultipleImports - Add ALL common imports at once as a list
        5. addMultipleFieldDeclarations - Add ALL field declarations at once as a list
        6. addSetupMethod - Add setup method with structured parameters: methodName, methodBody, annotations, accessModifier (optional)
        7. addTeardownMethod - Add teardown method with structured parameters: methodName, methodBody, annotations, accessModifier (optional)  
        8. addMultipleTestMethods - Generate test methods in batches of 3-5 methods (optimal efficiency, not too many at once)
        
        CRITICAL VALIDATION RULES:
        - NEVER create duplicate method names - each test method must have a unique name
        - NEVER duplicate imports - check what's already added before adding more
        - NEVER create malformed field declarations - ensure proper syntax with semicolons
        - ALWAYS use proper method signatures: public void methodName() for test methods
        - ALWAYS include @Test annotation for each test method
        - VALIDATE that each test method has a complete body with proper assertions
        
        IMPORT MANAGEMENT:
        - Step 4 (addMultipleImports): Add framework imports + common testing imports (JUnit, assertions, mocking)
        - Step 8 (addMultipleTestMethods): requiredImports should ONLY contain imports SPECIFIC to that test method
        - The system will automatically deduplicate imports
        - Common imports to include in Step 4: org.junit.jupiter.api.Test, static org.junit.jupiter.api.Assertions.*, org.mockito.Mock, etc.
        
        FIELD DECLARATION RULES:
        - Each field must be a complete valid Java field declaration
        - Include access modifiers: private, protected, public
        - Include annotations if needed: @Mock, @InjectMocks, @Autowired
        - End each declaration with semicolon
        - Example: "@Mock private UserRepository userRepository;"
        
        TEST METHOD QUALITY STANDARDS:
        - Each test method must test ONE specific scenario
        - Use descriptive method names: testMethodName_WhenCondition_ThenExpectedResult
        - Include proper setup, execution, and verification (Given-When-Then pattern)
        - Use appropriate assertions: assertEquals, assertTrue, assertThrows, etc.
        - Mock external dependencies appropriately
        - Clean up resources in teardown if needed
        
        BATCHING STRATEGY:
        - Generate 3-5 test methods per addMultipleTestMethods call for optimal efficiency
        - Don't generate just 1 method (use batches), don't generate too many at once (>10)
        - Multiple batches are better than one huge batch for better error recovery
        
        PROJECT-SPECIFIC DEPENDENCIES:
        - ONLY use testing frameworks and libraries that are detected in the project's build configuration
        - If JUnit 5 is detected, use JUnit 5 annotations and assertions
        - If Mockito is available, prefer it for mocking
        - If Spring Boot Test is available, use Spring testing annotations (@SpringBootTest, @MockBean, etc.)
        - If Testcontainers is available, prefer it over mocking for database/external service tests
        - If AssertJ is available, use AssertJ assertions instead of JUnit assertions for better readability
        - DO NOT add dependencies that are not already in the project
        
        F.I.R.S.T Principles
                
        - Fast: Tests should run quickly
        - Independent: Tests shouldn't depend on each other
        - Repeatable: Same result every time
        - Self-validating: Pass or fail, no manual checking
        - Timely: Written close to production code. Avoid mocks as much as possible. 
        If it cannot be unit-tested, go for integration test with test containers and give clear comments with TODO and FIXME on the problems.
        You can leave comment on how to refactor for better testability if the method or class is not testable (highly coupling)
        
        REMEMBER:
        
        - Avoid testing implementation details instead of behavior
        - Avoid repeating the implementation - test the actual method. 
        - Avoid fitting tests to implementation 
        
        EXAMPLE RESPONSE:
        "Analyzing project structure and generating complete test class...

        Now I'll generate the test class using the available tools.

        IMPORTANT RULES FOR ANNOTATIONS:
        - When providing annotations, use the name WITHOUT the @ symbol
        - For test methods: use "Test" not "@Test"  
        - For setup methods: use "BeforeEach" not "@BeforeEach"
        - For teardown: use "AfterEach" not "@AfterEach"
        - For parameterized tests: use "ParameterizedTest" not "@ParameterizedTest"

        BATCHING RULES FOR addMultipleTestMethods:
        - Generate 3-5 test methods per call for optimal efficiency
        - Each TestMethodInput needs:
          * methodName - the name of the test method (e.g., "testUserCreation")
          * methodBody - just the code inside the method body, not the signature
          * scenarioName - optional, can be empty string
          * requiredImports - list of additional imports needed for this specific test
          * annotations - list of annotation names WITHOUT @ prefix

        IMPORTANT: 
        - Setup/teardown methods: Provide structured parameters separately (methodName, methodBody, annotations list, accessModifier)
        - Test methods: Include appropriate annotations (Test, ParameterizedTest, etc.)
        - Choose framework-appropriate annotations based on detected dependencies
        - Annotations should be provided as list without @ prefix (e.g., ["BeforeEach"] not ["@BeforeEach"])
        
        Generate ALL methods NOW using tools. Only use tool calls. 
        """)
        @dev.langchain4j.agentic.Agent
        String generateTest(String request);
    }
    
    /**
     * Generate tests using LangChain4j's agent orchestration.
     * Creates complete test class in one response - no loops needed.
     */
    @NotNull
    public CompletableFuture<TestGenerationResult> generateTests(@NotNull TestPlan testPlan, @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Generating tests for: " + testPlan.getTargetClass());
                
                // Notify UI
                notifyStart();
                sendToUI("✍️ Generating complete test class...\n\n");
                
                // Reset writing tools for new session
                writingTools.reset();
                writingTools.setTestPlan(testPlan);
                writingTools.setToolNotifier(this::sendToUI);
                
                // Build the test writing request
                String testRequest = buildTestWritingRequest(testPlan, context);
                
                // Send the request to UI
                sendToUI("📋 Request:\n" + testRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Try test generation with retry logic for tool calling failures
                TestGenerationResult result = generateTestsWithRetry(testRequest, testPlan, context);
                
                // Summary
                sendToUI("\n📊 Test Generation Summary:\n");
                sendToUI("  • Test class: " + result.getClassName() + "\n");
                sendToUI("  • Package: " + result.getPackageName() + "\n");
                sendToUI("  • Framework: " + result.getFramework() + "\n");
                sendToUI("  • Methods generated: " + result.getMethodCount() + "\n");
                sendToUI("  • Imports: " + result.getImports().size() + "\n");
                sendToUI("  • Fields: " + result.getFieldDeclarations().size() + "\n");
                notifyComplete();
                
                LOG.debug("Test generation complete: " + result.getMethodCount() + " test(s)");
                
                return result;
                
            } catch (Exception e) {
                LOG.error("Failed to generate tests", e);
                sendToUI("\n❌ Test generation failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Test generation failed: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Generate tests with retry logic for tool calling failures
     */
    private TestGenerationResult generateTestsWithRetry(String testRequest, TestPlan testPlan, TestContext context) {
        int maxRetries = 3;
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                sendToUI(String.format("🔄 Attempt %d/%d: Calling AI assistant...\n", attempt, maxRetries));
                
                // Call the AI assistant
                String response = assistant.generateTest(testRequest);
                
                // Try to build result from the response
                return buildResultWithRecovery(testPlan, context, response);
                
            } catch (Exception e) {
                lastException = e;
                sendToUI(String.format("❌ Attempt %d failed: %s\n", attempt, e.getMessage()));
                
                if (attempt < maxRetries) {
                    sendToUI("⏳ Retrying in 2 seconds...\n");
                    try {
                        Thread.sleep(2000); // Wait 2 seconds before retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Test generation interrupted", ie);
                    }
                } else {
                    sendToUI("❌ All retry attempts failed\n");
                }
            }
        }
        
        // All retries failed
        throw new RuntimeException("Test generation failed after " + maxRetries + " attempts", lastException);
    }
    
    /**
     * Build test result with recovery if AI failed to call tools properly.
     */
    private TestGenerationResult buildResultWithRecovery(TestPlan testPlan, TestContext context, String response) {
        try {
            // Try to build normal result from tool calls
            TestGenerationResult result = writingTools.buildTestGenerationResult(testPlan, context);
            
            // Check if we have minimal required data
            if (isValidResult(result)) {
                return result;
            }
            
            // Tools weren't called properly - attempt recovery
            sendToUI("\n⚠️ AI failed to call tools properly, attempting recovery...\n");
            return recoverFromFailedToolCalls(testPlan, context, response);
            
        } catch (Exception e) {
            sendToUI("\n❌ Tool result building failed: " + e.getMessage() + "\n");
            sendToUI("🔧 Attempting recovery from AI response...\n");
            return recoverFromFailedToolCalls(testPlan, context, response);
        }
    }
    
    /**
     * Check if the result contains the minimum required data.
     */
    private boolean isValidResult(TestGenerationResult result) {
        return result != null && 
               result.getClassName() != null && 
               !result.getClassName().isEmpty() &&
               result.getMethodCount() > 0;
    }
    
    /**
     * Recover test generation when AI fails to use tools properly.
     * Parses the raw response and extracts test code manually.
     */
    private TestGenerationResult recoverFromFailedToolCalls(TestPlan testPlan, TestContext context, String response) {
        sendToUI("🔄 Parsing AI response manually...\n");
        
        // Extract basic metadata
        String className = extractClassName(response, testPlan);
        String packageName = extractPackageName(response, testPlan);
        String framework = detectFramework(response);
        
        sendToUI("  • Detected class: " + className + "\n");
        sendToUI("  • Detected package: " + packageName + "\n");
        sendToUI("  • Detected framework: " + framework + "\n");
        
        // Extract imports, fields, and methods from response
        List<String> imports = extractImports(response);
        List<String> fields = extractFields(response);
        List<GeneratedTestMethod> methods = extractTestMethods(response, testPlan);
        
        sendToUI("  • Extracted " + imports.size() + " imports\n");
        sendToUI("  • Extracted " + fields.size() + " fields\n");
        sendToUI("  • Extracted " + methods.size() + " test methods\n");
        
        // Create result manually
        TestGenerationResult result = new TestGenerationResult(
            packageName,
            className,
            framework,
            imports,
            fields,
            "", // beforeAllCode
            "", // afterAllCode
            "", // beforeEachCode
            "", // afterEachCode
            methods,
            testPlan,
            context
        );
        
        sendToUI("✅ Recovery completed!\n");
        return result;
    }
    
    /**
     * Extract class name from AI response.
     */
    private String extractClassName(String response, TestPlan testPlan) {
        // Look for class declaration
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.contains("class ") && line.contains("Test")) {
                String[] parts = line.split("\\s+");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].equals("class")) {
                        String className = parts[i + 1].replaceAll("[^a-zA-Z0-9_]", "");
                        if (!className.isEmpty()) {
                            return className;
                        }
                    }
                }
            }
        }
        
        // Fallback: generate from target class
        String targetClass = testPlan.getTargetClass();
        if (targetClass != null && !targetClass.isEmpty()) {
            return targetClass + "Test";
        }
        
        return "GeneratedTest";
    }
    
    /**
     * Extract package name from AI response.
     */
    private String extractPackageName(String response, TestPlan testPlan) {
        // Look for package declaration
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("package ")) {
                String packageLine = line.trim();
                if (packageLine.endsWith(";")) {
                    return packageLine.substring(8, packageLine.length() - 1).trim();
                }
            }
        }
        
        // Fallback: use default test package
        return "com.example.test";
    }
    
    /**
     * Detect testing framework from response.
     */
    private String detectFramework(String response) {
        if (response.contains("@Test") && response.contains("org.junit.jupiter")) {
            return "JUnit5";
        } else if (response.contains("@Test") && response.contains("org.junit.Test")) {
            return "JUnit4";
        } else if (response.contains("@Test") && response.contains("testng")) {
            return "TestNG";
        }
        return "JUnit5"; // Default
    }
    
    /**
     * Extract import statements from response.
     */
    private List<String> extractImports(String response) {
        List<String> imports = new ArrayList<>();
        String[] lines = response.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                String importStr = trimmed.substring(7, trimmed.length() - 1).trim();
                if (!importStr.isEmpty() && !imports.contains(importStr)) {
                    imports.add(importStr);
                }
            }
        }
        
        // Add essential imports if missing
        if (imports.stream().noneMatch(imp -> imp.contains("Test"))) {
            imports.add("org.junit.jupiter.api.Test");
        }
        if (imports.stream().noneMatch(imp -> imp.contains("Assertions"))) {
            imports.add("static org.junit.jupiter.api.Assertions.*");
        }
        
        return imports;
    }
    
    /**
     * Extract field declarations from response.
     */
    private List<String> extractFields(String response) {
        List<String> fields = new ArrayList<>();
        String[] lines = response.split("\n");
        
        boolean inClass = false;
        boolean inMethod = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            if (trimmed.contains("class ") && trimmed.contains("{")) {
                inClass = true;
                continue;
            }
            
            if (inClass && (trimmed.contains("void ") || trimmed.contains("@Test"))) {
                inMethod = true;
                continue;
            }
            
            if (inMethod && trimmed.equals("}")) {
                inMethod = false;
                continue;
            }
            
            // Look for field declarations (private/protected/public + type + name + semicolon)
            if (inClass && !inMethod && 
                (trimmed.startsWith("private ") || trimmed.startsWith("protected ") || trimmed.startsWith("public ") || trimmed.startsWith("@")) &&
                trimmed.endsWith(";") && 
                !trimmed.contains("(") && !trimmed.contains("return")) {
                
                fields.add(trimmed);
            }
        }
        
        return fields;
    }
    
    /**
     * Extract test methods from response.
     */
    private List<GeneratedTestMethod> extractTestMethods(String response, TestPlan testPlan) {
        List<GeneratedTestMethod> methods = new ArrayList<>();
        String[] lines = response.split("\n");
        
        String currentMethodName = null;
        StringBuilder currentMethodBody = new StringBuilder();
        boolean inTestMethod = false;
        int braceCount = 0;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Look for method signature with @Test annotation or test method pattern
            if ((trimmed.contains("@Test") || 
                 (trimmed.contains("void ") && trimmed.contains("test") && trimmed.contains("("))) &&
                !inTestMethod) {
                
                // Extract method name
                if (trimmed.contains("void ")) {
                    int voidIndex = trimmed.indexOf("void ");
                    int parenIndex = trimmed.indexOf("(");
                    if (voidIndex >= 0 && parenIndex > voidIndex) {
                        currentMethodName = trimmed.substring(voidIndex + 5, parenIndex).trim();
                        inTestMethod = true;
                        braceCount = 0;
                        currentMethodBody = new StringBuilder();
                        
                        if (trimmed.contains("{")) {
                            braceCount = 1;
                        }
                    }
                }
            } else if (inTestMethod) {
                // Count braces to detect method end
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }
                
                if (braceCount == 0) {
                    // End of method
                    inTestMethod = false;
                    
                    if (currentMethodName != null && currentMethodBody.length() > 0) {
                        GeneratedTestMethod method = new GeneratedTestMethod.Builder(currentMethodName)
                            .methodBody(currentMethodBody.toString().trim())
                            .addAnnotation("Test")
                            .build();
                        
                        methods.add(method);
                        
                        // Send to UI
                        sendTestGenerated(new GeneratedTestDisplayData(
                            currentMethodName,
                            "scenario_" + currentMethodName.hashCode(),
                            currentMethodName,
                            currentMethodBody.toString().trim(),
                            GeneratedTestDisplayData.ValidationStatus.NOT_VALIDATED,
                            new ArrayList<>(),
                            currentMethodBody.toString().split("\n").length,
                            System.currentTimeMillis()
                        ));
                    }
                    
                    currentMethodName = null;
                    currentMethodBody = new StringBuilder();
                } else {
                    // Add line to method body (exclude the closing brace line)
                    if (!(braceCount == 0 && trimmed.equals("}"))) {
                        currentMethodBody.append(line).append("\n");
                    }
                }
            }
        }
        
        return methods;
    }
    
    /**
     * Build the test writing request with all necessary information.
     */
    private String buildTestWritingRequest(TestPlan testPlan, TestContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a complete test class for the following test plan.\n\n");
        
        // Provide project structure information via file listing
        String projectListing = generateProjectListing();
        prompt.append("PROJECT STRUCTURE:\n");
        prompt.append(projectListing).append("\n\n");
        
        prompt.append("INSTRUCTIONS:\n");
        prompt.append("• Analyze the project structure from the file listing above\n");
        prompt.append("• Determine if this is Maven (pom.xml), Gradle (build.gradle/.kts), or IntelliJ (.iml) project\n");
        prompt.append("• Choose appropriate test file path based on project conventions\n");
        prompt.append("• Use setTestClassName with BOTH class name AND your chosen file path\n");
        prompt.append("• Only use testing libraries you can identify from build files\n\n");
        
        // Target information
        prompt.append("TARGET CLASS INFO:\n");
        prompt.append("• Target Class: ").append(testPlan.getTargetClass()).append("\n");
        prompt.append("• Target Method(s): ").append(testPlan.getTargetMethods().stream().collect(Collectors.joining(", "))).append("\n");
        prompt.append("• Framework: ").append(context.getFrameworkInfo()).append("\n\n");
        
        // Test scenarios to implement
//        prompt.append("Test Scenarios to implement (" + testPlan.getTestScenarios().size() + " total):\n");
//        for (int i = 0; i < testPlan.getTestScenarios().size(); i++) {
//            TestPlan.TestScenario scenario = testPlan.getTestScenarios().get(i);
//            prompt.append("\n").append(i + 1).append(". ").append(scenario.getName()).append("\n");
//            prompt.append("   • Description: ").append(scenario.getDescription()).append("\n");
//            prompt.append("   • Type: ").append(scenario.getType().getDisplayName()).append("\n");
//            prompt.append("   • Priority: ").append(scenario.getPriority().getDisplayName()).append("\n");
//            prompt.append("   • Expected: ").append(scenario.getExpectedOutcome()).append("\n");
//        }
        
        // Add context for code generation - FULL CONTEXT, NO TRUNCATION
        prompt.append("\n\nCode Context:\n");
        String contextInfo = context.buildTestWriterContext(
            testPlan.getTargetClass(), 
            testPlan.getTargetMethods(),
            testPlan.getTestScenarios()
        );
        
        // Include full context - no truncation
        prompt.append(contextInfo);
        
        // Instructions
        prompt.append("\n\nIMPORTANT INSTRUCTIONS:\n");

        prompt.append("1. Each test method must be fully implemented\n");
        prompt.append("2. Use appropriate assertions and mocking, but try avoid mocking as much as possible. Use test containers for databases and third-party services if possible. \n");
        prompt.append("3. Follow the framework conventions (" + context.getFrameworkInfo() + ")\n");
        prompt.append("4. Strictly adhere to testing writing best practices.\n");

        return prompt.toString();
    }
    
    /**
     * Generate a 2-level file listing of the project for LLM analysis.
     */
    private String generateProjectListing() {
        StringBuilder listing = new StringBuilder();
        
        String basePath = project.getBasePath();
        if (basePath == null) {
            return "Could not access project files";
        }
        
        File projectRoot = new File(basePath);
        listing.append("Project Root: ").append(projectRoot.getName()).append("/\n");
        
        // List files and directories up to 2 levels deep
        listFiles(projectRoot, listing, 0, 2);
        
        return listing.toString();
    }
    
    /**
     * Recursively list files up to specified depth.
     */
    private void listFiles(File directory, StringBuilder listing, int currentDepth, int maxDepth) {
        if (currentDepth > maxDepth || !directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        // Sort files: directories first, then files, alphabetically
        java.util.Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        for (File file : files) {
            // Skip hidden files and common build/cache directories
            String name = file.getName();
            if (name.startsWith(".") && !name.equals(".gitignore") && !name.equals(".github")) {
                continue;
            }
            if (name.equals("node_modules") || name.equals("target") || name.equals("build") || 
                name.equals(".gradle") || name.equals(".idea")) {
                continue;
            }
            
            // Add indentation
            for (int i = 0; i < currentDepth; i++) {
                listing.append("  ");
            }
            
            if (file.isDirectory()) {
                listing.append("├── ").append(name).append("/\n");
                // Recurse into directory
                listFiles(file, listing, currentDepth + 1, maxDepth);
            } else {
                listing.append("├── ").append(name);
                
                // Highlight important files
                if (isBuildFile(name)) {
                    listing.append(" ← BUILD FILE");
                } else if (isConfigFile(name)) {
                    listing.append(" ← CONFIG");
                }
                
                listing.append("\n");
            }
        }
    }
    
    /**
     * Check if file is a build configuration file.
     */
    private boolean isBuildFile(String fileName) {
        return fileName.equals("pom.xml") || 
               fileName.equals("build.gradle") || 
               fileName.equals("build.gradle.kts") ||
               fileName.equals("settings.gradle") ||
               fileName.equals("settings.gradle.kts") ||
               fileName.endsWith(".iml") ||
               fileName.equals("build.sbt");
    }
    
    /**
     * Check if file is a configuration file.
     */
    private boolean isConfigFile(String fileName) {
        return fileName.equals("gradle.properties") ||
               fileName.equals("application.properties") ||
               fileName.equals("application.yml") ||
               fileName.equals("application.yaml") ||
               fileName.equals("package.json");
    }
    
    
    /**
     * Tools for building the test class.
     * These tools capture structured test data without string concatenation.
     * LangChain4j will call these tools as the assistant generates tests.
     */
    public static class TestWritingTools {
        private final ZestLangChain4jService langChainService;
        private TestClassMetadata.Builder metadataBuilder;
        private final List<GeneratedTestMethod> testMethods = new ArrayList<>();
        private final Map<String, TestPlan.TestScenario> scenarioMap = new HashMap<>();
        private Consumer<String> toolNotifier;
        private TestPlan currentTestPlan;
        private TestWriterAgent testWriterAgent; // Reference to parent agent
        
        public TestWritingTools(ZestLangChain4jService langChainService) {
            this(langChainService, null);
        }
        
        public TestWritingTools(ZestLangChain4jService langChainService, TestWriterAgent agent) {
            this.langChainService = langChainService;
            this.testWriterAgent = agent;
        }
        
        public void setToolNotifier(Consumer<String> notifier) {
            this.toolNotifier = notifier;
        }
        
        private void notifyTool(String toolName, String params) {
            if (toolNotifier != null) {
                SwingUtilities.invokeLater(() -> 
                    toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params)));
            }
        }
        
        public void reset() {
            metadataBuilder = new TestClassMetadata.Builder();
            testMethods.clear();
            scenarioMap.clear();
            currentTestPlan = null;
        }
        
        public void setTestPlan(TestPlan testPlan) {
            this.currentTestPlan = testPlan;
            // Map scenarios by name for easy lookup
            for (TestPlan.TestScenario scenario : testPlan.getTestScenarios()) {
                scenarioMap.put(scenario.getName(), scenario);
            }
        }
        
        @Tool("Set the package name for the test class")
        public String setPackageName(String packageName) {
            notifyTool("setPackageName", packageName);
            metadataBuilder.packageName(packageName);
            return "Package set to: " + packageName;
        }
        
        @Tool("Set the test class name and file path - LLM decides path based on project structure")
        public String setTestClassName(String className, String filePath) {
            notifyTool("setTestClassName", className + " at " + filePath);
            metadataBuilder.className(className);
            
            // Analyze project structure from LLM-chosen path
            String projectInfo = analyzeProjectStructureFromPath(filePath);
            if (!projectInfo.isEmpty()) {
                if (toolNotifier != null) {
                    toolNotifier.accept("📁 " + projectInfo + "\n");
                }
            }
            
            return "Test class name set to: " + className + " (path: " + filePath + ")";
        }
        
        @Tool("Add multiple import statements at once for efficiency")
        public String addMultipleImports(List<String> importStatements) {
            notifyTool("addMultipleImports", importStatements.size() + " imports");
            
            int addedCount = 0;
            int duplicateCount = 0;
            Set<String> existingImports = new HashSet<>();
            
            try {
                existingImports.addAll(metadataBuilder.build().getClassImports());
            } catch (Exception e) {
                // Builder may not be complete yet, that's ok
            }
            
            for (String importStatement : importStatements) {
                // Clean up the import statement
                String cleanImport = cleanImportStatement(importStatement);
                
                if (!cleanImport.isEmpty() && !existingImports.contains(cleanImport)) {
                    // Validate import statement
                    if (isValidImport(cleanImport)) {
                        metadataBuilder.addImport(cleanImport);
                        existingImports.add(cleanImport);
                        addedCount++;
                    }
                } else if (existingImports.contains(cleanImport)) {
                    duplicateCount++;
                }
            }
            
            String result = "Added " + addedCount + " imports in batch";
            if (duplicateCount > 0) {
                result += " (skipped " + duplicateCount + " duplicates)";
            }
            return result;
        }
        
        @Tool("Add multiple field declarations at once for efficiency")
        public String addMultipleFieldDeclarations(List<String> fieldDeclarations) {
            notifyTool("addMultipleFieldDeclarations", fieldDeclarations.size() + " fields");
            
            int addedCount = 0;
            int invalidCount = 0;
            Set<String> fieldNames = new HashSet<>();
            
            for (String fieldDeclaration : fieldDeclarations) {
                String cleanedField = validateAndCleanFieldDeclaration(fieldDeclaration);
                if (!cleanedField.isEmpty()) {
                    String fieldName = extractFieldName(cleanedField);
                    if (fieldName != null && !fieldNames.contains(fieldName)) {
                        metadataBuilder.addFieldDeclaration(cleanedField);
                        fieldNames.add(fieldName);
                        addedCount++;
                    } else if (fieldName != null) {
                        // Duplicate field name
                        invalidCount++;
                    }
                } else {
                    invalidCount++;
                }
            }
            
            String result = "Added " + addedCount + " field declarations in batch";
            if (invalidCount > 0) {
                result += " (skipped " + invalidCount + " invalid/duplicate fields)";
            }
            return result;
        }
        
        @Tool("Add setup method with structured parameters")
        public String addSetupMethod(String methodName, String methodBody, List<String> annotations, String accessModifier) {
            notifyTool("addSetupMethod", methodName + " with " + annotations.size() + " annotations");
            
            // Validate parameters
            if (methodName == null || methodName.trim().isEmpty()) {
                return "Failed: methodName is required";
            }
            if (methodBody == null || methodBody.trim().isEmpty()) {
                return "Failed: methodBody is required";
            }
            
            // Clean and validate method name
            String cleanMethodName = methodName.trim();
            
            // Clean annotations (remove @ prefix if present)
            List<String> cleanAnnotations = new ArrayList<>();
            if (annotations != null) {
                for (String annotation : annotations) {
                    String clean = annotation.trim();
                    if (clean.startsWith("@")) {
                        clean = clean.substring(1);
                    }
                    if (!clean.isEmpty()) {
                        cleanAnnotations.add(clean);
                    }
                }
            }
            
            // Store method body for backward compatibility
            metadataBuilder.beforeEachCode(methodBody.trim());
            
            // Create method for the merger
            GeneratedTestMethod.Builder methodBuilder = new GeneratedTestMethod.Builder(cleanMethodName)
                .methodBody(methodBody.trim());
            
            // Add annotations
            for (String annotation : cleanAnnotations) {
                methodBuilder.addAnnotation(annotation);
            }
            
            GeneratedTestMethod setupMethod = methodBuilder.build();
            testMethods.add(0, setupMethod); // Add at beginning
            
            return String.format("Setup method added: %s (access: %s, annotations: %s)", 
                cleanMethodName, 
                accessModifier != null ? accessModifier : "public",
                String.join(", ", cleanAnnotations));
        }
        
        @Tool("Add teardown method with structured parameters")
        public String addTeardownMethod(String methodName, String methodBody, List<String> annotations, String accessModifier) {
            notifyTool("addTeardownMethod", methodName + " with " + annotations.size() + " annotations");
            
            // Validate parameters
            if (methodName == null || methodName.trim().isEmpty()) {
                return "Failed: methodName is required";
            }
            if (methodBody == null || methodBody.trim().isEmpty()) {
                return "Failed: methodBody is required";
            }
            
            // Clean and validate method name
            String cleanMethodName = methodName.trim();
            
            // Clean annotations (remove @ prefix if present)
            List<String> cleanAnnotations = new ArrayList<>();
            if (annotations != null) {
                for (String annotation : annotations) {
                    String clean = annotation.trim();
                    if (clean.startsWith("@")) {
                        clean = clean.substring(1);
                    }
                    if (!clean.isEmpty()) {
                        cleanAnnotations.add(clean);
                    }
                }
            }
            
            // Store method body for backward compatibility
            metadataBuilder.afterEachCode(methodBody.trim());
            
            // Create method for the merger
            GeneratedTestMethod.Builder methodBuilder = new GeneratedTestMethod.Builder(cleanMethodName)
                .methodBody(methodBody.trim());
            
            // Add annotations
            for (String annotation : cleanAnnotations) {
                methodBuilder.addAnnotation(annotation);
            }
            
            GeneratedTestMethod teardownMethod = methodBuilder.build();
            testMethods.add(teardownMethod); // Add at end
            
            return String.format("Teardown method added: %s (access: %s, annotations: %s)", 
                cleanMethodName, 
                accessModifier != null ? accessModifier : "public",
                String.join(", ", cleanAnnotations));
        }
        
        @Tool("Add test methods in reasonable batches. Optimal batch size: 3-5 methods (not 1, not too many). Include only imports not yet added to the class.")
        public String addMultipleTestMethods(List<TestMethodInput> testMethodInputs) {
            notifyTool("addMultipleTestMethods", testMethodInputs.size() + " methods");
            
            // Track existing imports to avoid duplicates
            Set<String> existingImports = new HashSet<>();
            try {
                existingImports.addAll(metadataBuilder.build().getClassImports());
            } catch (Exception e) {
                // Builder may not be complete yet, that's ok
            }
            
            int addedMethods = 0;
            int addedImports = 0;
            int skippedMethods = 0;
            Set<String> methodNames = new HashSet<>();
            
            // Collect existing method names
            for (GeneratedTestMethod existingMethod : testMethods) {
                methodNames.add(existingMethod.getMethodName());
            }
            
            for (TestMethodInput input : testMethodInputs) {
                // Validate method input
                if (input.methodName == null || input.methodName.trim().isEmpty()) {
                    skippedMethods++;
                    continue;
                }
                
                if (input.methodBody == null || input.methodBody.trim().isEmpty()) {
                    skippedMethods++;
                    continue;
                }
                
                // Handle duplicate method names
                String methodName = input.methodName;
                if (methodNames.contains(methodName)) {
                    methodName = generateUniqueMethodName(methodName, methodNames);
                }
                methodNames.add(methodName);
                
                GeneratedTestMethod.Builder methodBuilder = new GeneratedTestMethod.Builder(methodName)
                        .methodBody(input.methodBody);
                
                // Don't assume @Test - let LLM decide annotations for setup/teardown/test methods
                if (input.annotations != null && !input.annotations.isEmpty()) {
                    for (String annotation : input.annotations) {
                        methodBuilder.addAnnotation(annotation);
                    }
                } else {
                    // Default to @Test if no annotations specified (backward compatibility)
                    methodBuilder.addAnnotation("Test");
                }
                
                // Add new imports for this method
                if (input.requiredImports != null) {
                    for (String importStr : input.requiredImports) {
                        String cleanImport = cleanImportStatement(importStr);
                        if (!cleanImport.isEmpty() && !existingImports.contains(cleanImport) && isValidImport(cleanImport)) {
                            metadataBuilder.addImport(cleanImport);
                            methodBuilder.addImport(cleanImport);
                            existingImports.add(cleanImport);
                            addedImports++;
                        }
                    }
                }
                
                // Try to match with a scenario
                if (input.scenarioName != null && !input.scenarioName.isEmpty()) {
                    TestPlan.TestScenario scenario = scenarioMap.get(input.scenarioName);
                    if (scenario != null) {
                        methodBuilder.scenario(scenario);
                    }
                }
                
                GeneratedTestMethod testMethod = methodBuilder.build();
                testMethods.add(testMethod);
                
                // Send generated test to UI
                if (testWriterAgent != null) {
                    GeneratedTestDisplayData testData = new GeneratedTestDisplayData(
                        methodName, // testName (use potentially renamed method name)
                        "scenario_" + (input.scenarioName != null ? input.scenarioName.hashCode() : methodName.hashCode()), // scenarioId
                        input.scenarioName != null ? input.scenarioName : methodName, // scenarioName
                        input.methodBody, // testCode
                        GeneratedTestDisplayData.ValidationStatus.NOT_VALIDATED, // validationStatus
                        new ArrayList<>(), // validationMessages
                        input.methodBody.split("\n").length, // lineCount
                        System.currentTimeMillis() // timestamp
                    );
                    testWriterAgent.sendTestGenerated(testData);
                }
                
                addedMethods++;
            }
            
            String result = String.format("Added %d test methods with %d new imports", addedMethods, addedImports);
            if (skippedMethods > 0) {
                result += String.format(" (skipped %d invalid methods)", skippedMethods);
            }
            return result;
        }
        
        /**
         * Clean import statement for consistent storage
         */
        private String cleanImportStatement(String importStr) {
            String clean = importStr.trim();
            if (clean.startsWith("import ")) {
                clean = clean.substring(7);
            }
            if (clean.startsWith("static ")) {
                clean = clean.substring(7);
            }
            if (clean.endsWith(";")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            return clean.trim();
        }
        
        /**
         * Validate import statement
         */
        private boolean isValidImport(String importStr) {
            if (importStr == null || importStr.trim().isEmpty()) {
                return false;
            }
            
            // Basic validation - should contain at least one dot and valid package structure
            return importStr.contains(".") && 
                   importStr.matches("^[a-zA-Z][a-zA-Z0-9_.]*[a-zA-Z0-9_*]$");
        }
        
        /**
         * Validate and clean field declaration
         */
        private String validateAndCleanFieldDeclaration(String fieldDecl) {
            String cleaned = fieldDecl.trim();
            
            // Ensure it ends with semicolon
            if (!cleaned.isEmpty() && !cleaned.endsWith(";")) {
                cleaned += ";";
            }
            
            // Basic validation - should contain at least type and name
            if (cleaned.split("\\s+").length < 2) {
                return "";
            }
            
            return cleaned;
        }
        
        /**
         * Extract field name from declaration
         */
        private String extractFieldName(String fieldDecl) {
            try {
                String[] parts = fieldDecl.replace(";", "").trim().split("\\s+");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String part = parts[i];
                    if (!part.startsWith("@") && !isJavaKeyword(part) && isValidJavaIdentifier(part)) {
                        return part;
                    }
                }
            } catch (Exception e) {
                // Log error but don't fail
            }
            return null;
        }
        
        /**
         * Generate unique method name by appending number
         */
        private String generateUniqueMethodName(String baseName, Set<String> existingNames) {
            int counter = 2;
            String candidate = baseName + counter;
            while (existingNames.contains(candidate)) {
                counter++;
                candidate = baseName + counter;
            }
            return candidate;
        }
        
        /**
         * Check if string is a Java keyword
         */
        private boolean isJavaKeyword(String word) {
            Set<String> keywords = Set.of("public", "private", "protected", "static", "final", 
                                         "abstract", "synchronized", "volatile", "transient");
            return keywords.contains(word);
        }
        
        /**
         * Check if string is a valid Java identifier
         */
        private boolean isValidJavaIdentifier(String identifier) {
            if (identifier.isEmpty() || !Character.isJavaIdentifierStart(identifier.charAt(0))) {
                return false;
            }
            
            for (int i = 1; i < identifier.length(); i++) {
                if (!Character.isJavaIdentifierPart(identifier.charAt(i))) {
                    return false;
                }
            }
            
            return true;
        }
        
        
        
        /**
         * Analyze project structure from file path to determine build system and conventions.
         */
        private String analyzeProjectStructureFromPath(String filePath) {
            StringBuilder info = new StringBuilder();
            
            if (filePath.contains("src/test/java") || filePath.contains("src\\test\\java")) {
                info.append("Maven/Gradle standard layout detected (src/test/java)");
                
                // Try to determine if it's Maven or Gradle by checking build files
                String projectRoot = extractProjectRoot(filePath);
                if (projectRoot != null) {
                    if (new File(projectRoot, "pom.xml").exists()) {
                        info.append(" - Maven project");
                    } else if (new File(projectRoot, "build.gradle").exists() || 
                              new File(projectRoot, "build.gradle.kts").exists()) {
                        info.append(" - Gradle project");
                    }
                    
                    // Analyze dependencies
                    String depInfo = analyzeDependenciesFromProjectRoot(projectRoot);
                    if (!depInfo.isEmpty()) {
                        info.append("\n").append(depInfo);
                    }
                }
            } else if (filePath.contains("src/test") || filePath.contains("src\\test")) {
                info.append("Custom test layout detected (src/test)");
            } else if (filePath.contains("/test/") || filePath.contains("\\test\\")) {
                info.append("Simple test layout detected (test directory)");
            } else {
                info.append("Custom project structure");
            }
            
            return info.toString();
        }
        
        /**
         * Extract project root directory from test file path.
         */
        private String extractProjectRoot(String filePath) {
            // Look for common markers that indicate project root
            String[] markers = {"src/test/java", "src\\test\\java", "src/test", "src\\test"};
            
            for (String marker : markers) {
                int index = filePath.indexOf(marker);
                if (index > 0) {
                    return filePath.substring(0, index - 1); // -1 to remove trailing separator
                }
            }
            
            return null;
        }
        
        /**
         * Analyze dependencies from project root directory.
         */
        private String analyzeDependenciesFromProjectRoot(String projectRoot) {
            StringBuilder deps = new StringBuilder();
            
            // Check Gradle files
            File gradleKts = new File(projectRoot, "build.gradle.kts");
            File gradleGroovy = new File(projectRoot, "build.gradle");
            
            if (gradleKts.exists()) {
                deps.append("Dependencies from build.gradle.kts: ");
                deps.append(extractDependenciesFromFile(gradleKts));
            } else if (gradleGroovy.exists()) {
                deps.append("Dependencies from build.gradle: ");
                deps.append(extractDependenciesFromFile(gradleGroovy));
            }
            
            // Check Maven pom.xml
            File pomFile = new File(projectRoot, "pom.xml");
            if (pomFile.exists()) {
                deps.append("Dependencies from pom.xml: ");
                deps.append(extractDependenciesFromFile(pomFile));
            }
            
            return deps.toString();
        }
        
        /**
         * Extract dependencies from a build file.
         */
        private String extractDependenciesFromFile(File buildFile) {
            StringBuilder deps = new StringBuilder();
            try {
                String content = java.nio.file.Files.readString(buildFile.toPath());
                
                List<String> foundDeps = new ArrayList<>();
                
                // Look for common test dependencies
                if (content.contains("junit-jupiter") || content.contains("org.junit.jupiter")) {
                    foundDeps.add("JUnit 5");
                } else if (content.contains("junit:junit") || content.contains("junit</artifactId>")) {
                    foundDeps.add("JUnit 4");
                }
                
                if (content.contains("mockito")) {
                    foundDeps.add("Mockito");
                }
                
                if (content.contains("testng")) {
                    foundDeps.add("TestNG");
                }
                
                if (content.contains("spring-boot-starter-test")) {
                    foundDeps.add("Spring Boot Test");
                }
                
                if (content.contains("testcontainers")) {
                    foundDeps.add("Testcontainers");
                }
                
                if (content.contains("assertj")) {
                    foundDeps.add("AssertJ");
                }
                
                deps.append(String.join(", ", foundDeps));
                
            } catch (Exception e) {
                deps.append("(could not analyze)");
            }
            
            return deps.toString();
        }
        
        // Input class for batch test method creation
        public static class TestMethodInput {
            public String methodName;
            public String methodBody;
            public String scenarioName;
            public List<String> requiredImports;  // Imports specific to this test method
            public List<String> annotations;     // Annotations for this method (e.g., @Test, @BeforeEach, @AfterEach)
            
            public TestMethodInput() {}
            
            public TestMethodInput(String methodName, String methodBody, String scenarioName) {
                this.methodName = methodName;
                this.methodBody = methodBody;
                this.scenarioName = scenarioName;
                this.requiredImports = new ArrayList<>();
                this.annotations = new ArrayList<>();
            }
            
            public TestMethodInput(String methodName, String methodBody, String scenarioName, List<String> requiredImports) {
                this.methodName = methodName;
                this.methodBody = methodBody;
                this.scenarioName = scenarioName;
                this.requiredImports = requiredImports != null ? requiredImports : new ArrayList<>();
                this.annotations = new ArrayList<>();
            }
            
            public TestMethodInput(String methodName, String methodBody, String scenarioName, List<String> requiredImports, List<String> annotations) {
                this.methodName = methodName;
                this.methodBody = methodBody;
                this.scenarioName = scenarioName;
                this.requiredImports = requiredImports != null ? requiredImports : new ArrayList<>();
                this.annotations = annotations != null ? annotations : new ArrayList<>();
            }
        }
        
        @Tool("Set the test framework being used")
        public String setTestFramework(String framework) {
            notifyTool("setTestFramework", framework);
            metadataBuilder.framework(framework);
            return "Framework set to: " + framework;
        }
        
        /**
         * Get statistics
         */
        public TestClassMetadata getMetadata() {
            return metadataBuilder.build();
        }
        
        public String getTestClassName() {
            try {
                return metadataBuilder.build().getClassName();
            } catch (Exception e) {
                return "UnknownTest";
            }
        }
        
        public String getPackageName() {
            try {
                return metadataBuilder.build().getPackageName();
            } catch (Exception e) {
                return "com.example.tests";
            }
        }
        
        public int getTestMethodCount() {
            return testMethods.size();
        }
        
        public int getTotalLines() {
            // Approximate line count
            return 10 + testMethods.size() * 10;
        }
        
        /**
         * Build complete test generation result from accumulated data.
         * Returns TestGenerationResult with all structured metadata.
         */
        public TestGenerationResult buildTestGenerationResult(TestPlan testPlan, TestContext context) {
            TestClassMetadata metadata = metadataBuilder.build();
            
            return new TestGenerationResult(
                metadata.getPackageName(),
                metadata.getClassName(),
                metadata.getFramework(),
                metadata.getClassImports(),
                metadata.getFieldDeclarations(),
                metadata.getBeforeAllCode(),
                metadata.getAfterAllCode(),
                metadata.getBeforeEachCode(),
                metadata.getAfterEachCode(),
                new ArrayList<>(testMethods),
                testPlan,
                context
            );
        }
        
        /**
         * Get the generated test metadata for use by merger
         */
        public TestClassMetadata getTestClassMetadata() {
            return metadataBuilder.build();
        }
        
        /**
         * Get all generated test methods
         */
        public List<GeneratedTestMethod> getTestMethods() {
            return new ArrayList<>(testMethods);
        }
    }
}