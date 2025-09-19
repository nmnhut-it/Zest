package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.ui.model.GeneratedTestDisplayData;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Test writing agent using LangChain4j's streaming capabilities.
 * Generates complete test classes in a single response - no loops needed.
 */
public class TestWriterAgent extends StreamingBaseAgent {
    private final TestWritingAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    
    public TestWriterAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull NaiveLLMService naiveLlmService) {
        super(project, langChainService, naiveLlmService, "TestWriterAgent");
        
        // Build the simplified agent (with dummy tool for parallelToolCalls compatibility)
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(50);
        this.assistant = AgenticServices
                .agentBuilder(TestWritingAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(10) // Simple - just one response needed
                .chatMemory(chatMemory)
                .tools(new DummyTool()) // Dummy tool to satisfy parallelToolCalls requirement
                .build();
    }
    
    /**
     * Simplified interface - generates complete test class as a single string
     */
    public interface TestWritingAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test writing assistant that generates complete, high-quality Java test classes.

        CRITICAL: Generate the ENTIRE test class as a COMPLETE JAVA FILE in your response.

        OUTPUT FORMAT:
        Return the complete Java test class code wrapped in markdown code blocks:
        ```java
        // Your complete Java test class here
        ```

        The response must include:
        - Package declaration
        - All necessary imports
        - Class declaration with proper annotations
        - Field declarations (if needed)
        - Setup method (@BeforeEach) if needed
        - All test methods with @Test annotations
        - Teardown method (@AfterEach) if needed
        
        QUALITY STANDARDS:
        - Each test method tests ONE specific scenario
        - Use descriptive method names: testMethodName_WhenCondition_ThenExpectedResult
        - Include proper setup, execution, and verification (Given-When-Then pattern)
        - Use appropriate assertions: assertEquals, assertTrue, assertThrows, etc.
        - Proper Java formatting and indentation
        - Complete method implementations with assertions
        
        DEPENDENCY-AWARE TESTING STRATEGY:
        
        1. **PURE BUSINESS LOGIC** (no external dependencies):
           → Write UNIT TESTS - test actual logic directly, no mocking needed
           
        2. **DATABASE INTERACTIONS** (JPA, JDBC, repositories):
           → Use TESTCONTAINERS with appropriate database containers
           → Add @Container field with database setup
           → Use @Testcontainers annotation on class
           → Example containers: PostgreSQLContainer, MySQLContainer, MongoDBContainer
           
        3. **MESSAGE QUEUES** (Kafka, RabbitMQ, ActiveMQ):
           → Use TESTCONTAINERS with message broker containers
           → Example: KafkaContainer, RabbitMQContainer
           
        4. **EXTERNAL SERVICES** (Redis, Elasticsearch, etc.):
           → Use TESTCONTAINERS with service containers
           → Example: GenericContainer, ElasticsearchContainer
           
        5. **FILE SYSTEM OPERATIONS**:
           → Use @TempDir for temporary directories (JUnit 5)
           → Or Files.createTempDirectory() for other frameworks
           
        6. **HTTP CLIENTS/APIS** (last resort):
           → Prefer WireMock or MockWebServer over mocking
           → If no choice, use framework-appropriate mocking (Mockito, EasyMock, etc.)
           
        FRAMEWORK DETECTION AND ADAPTATION:
        FIRST analyze the provided context to detect the testing framework:
        - JUnit 4: Use @Test from org.junit.Test, @Before/@After for setup/teardown
        - JUnit 5: Use @Test from org.junit.jupiter.api.Test, @BeforeEach/@AfterEach
        - TestNG: Use @Test from org.testng.annotations.Test, @BeforeMethod/@AfterMethod
        - Spring Boot: Add @SpringBootTest, @TestConfiguration, @MockBean as needed
        - Testcontainers: Add @Testcontainers on class, @Container on fields
        
        ADAPT YOUR OUTPUT to use the DETECTED framework, not a default assumption.
        
        F.I.R.S.T Principles:
        - Fast: Tests should run quickly 
        - Independent: Tests shouldn't depend on each other
        - Repeatable: Same result every time
        - Self-validating: Pass or fail, no manual checking
        - Timely: Choose the RIGHT test type for the dependencies
        
        CONTAINER SETUP PATTERNS:
        
        **PostgreSQL Example:**
        ```java
        @Testcontainers
        class UserRepositoryTest {
            @Container
            static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");
        
            @DynamicPropertySource
            static void configureProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.datasource.url", postgres::getJdbcUrl);
                registry.add("spring.datasource.username", postgres::getUsername);  
                registry.add("spring.datasource.password", postgres::getPassword);
            }
        }
        ```
        
        **Kafka Example:**
        ```java
        @Testcontainers
        class MessageServiceTest {
            @Container
            static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"));
        
            @DynamicPropertySource
            static void kafkaProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
            }
        }
        ```
        
        **Redis Example:**
        ```java
        @Testcontainers
        class CacheServiceTest {
            @Container
            static GenericContainer<?> redis = new GenericContainer<>("redis:6-alpine")
                    .withExposedPorts(6379);
        
            @DynamicPropertySource
            static void redisProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.redis.host", redis::getHost);
                registry.add("spring.redis.port", redis::getFirstMappedPort);
            }
        }
        ```
        
        ASSERTION FRAMEWORKS (use what's detected):
        - **JUnit 4**: assertEquals(), assertTrue(), assertNotNull() from org.junit.Assert
        - **JUnit 5**: assertEquals(), assertTrue(), assertThrows() from org.junit.jupiter.api.Assertions
        - **AssertJ**: assertThat().isEqualTo(), assertThat().isTrue() from org.assertj.core.api.Assertions
        - **TestNG**: assertEquals(), assertTrue() from org.testng.Assert
        - **Hamcrest**: assertThat(result, is(equalTo(expected))) from org.hamcrest.MatcherAssert

        Generate the complete Java test class now. Do NOT use tools - return the complete test class directly.
        Remember to wrap your response in markdown code blocks:
        ```java
        // Your complete test class here
        ```
        """)
        @dev.langchain4j.agentic.Agent
        String generateTest(String request);
    }
    
    /**
     * Generate tests using simplified LLM approach.
     * Creates complete test class in one response - no complex tools needed.
     */
    @NotNull
    public CompletableFuture<TestGenerationResult> generateTests(@NotNull TestPlan testPlan, 
                                                                @NotNull ContextAgent.ContextGatheringTools contextTools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Generating tests for: " + testPlan.getTargetClass());
                
                // Notify UI
                notifyStart();
                sendToUI("✍️ Generating complete test class...\n\n");
                
                // Build the test writing request
                String testRequest = buildTestWritingRequest(testPlan, contextTools);
                
                // Send the request to UI
                sendToUI("📋 Request:\n" + testRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Try test generation with retry logic 
                TestGenerationResult result = generateTestsWithRetry(testRequest, testPlan, contextTools);
                
                // Summary
                sendToUI("\n📊 Test Generation Summary:\n");
                sendToUI("  • Test class: " + result.getClassName() + "\n");
                sendToUI("  • Package: " + result.getPackageName() + "\n");
                sendToUI("  • Framework: " + result.getFramework() + "\n");
                sendToUI("  • Methods generated: " + result.getMethodCount() + "\n");
                sendToUI("  • Lines of code: " + result.getCompleteTestClass().split("\n").length + "\n");
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
     * Get the chat memory for UI integration
     */
    @NotNull
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }
    
    /**
     * Generate tests with retry logic for tool calling failures
     */
    private TestGenerationResult generateTestsWithRetry(String testRequest, TestPlan testPlan, 
                                                       ContextAgent.ContextGatheringTools contextTools) {
        int maxRetries = 3;
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                sendToUI(String.format("🔄 Attempt %d/%d: Calling AI assistant...\n", attempt, maxRetries));
                
                // Call the AI assistant - chat memory preserves conversation context including previous errors
                String completeTestClass = assistant.generateTest(testRequest);
                
                // Parse the complete test class into a TestGenerationResult
                return parseCompleteTestClass(completeTestClass, testPlan, contextTools);
                
            } catch (Exception e) {
                lastException = e;
                sendToUI(String.format("❌ Attempt %d failed: %s\n", attempt, e.getMessage()));

                if (attempt < maxRetries) {
                    // Add error context to chat memory for self-correction
                    String errorFeedback = buildErrorFeedbackMessage(e, attempt);
                    sendToUI("🔄 Adding error context to conversation for self-correction...\n");

                    try {
                        // Let the assistant reason about the error and continue
                        assistant.generateTest(errorFeedback);
                    } catch (Exception feedbackException) {
                        LOG.warn("Error feedback failed, proceeding with basic retry: " + feedbackException.getMessage());
                    }

                    sendToUI("⏳ Retrying with error context in 2 seconds...\n");
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
     * Build error feedback message to help LLM self-correct on retry
     */
    private String buildErrorFeedbackMessage(Exception error, int attemptNumber) {
        StringBuilder feedback = new StringBuilder();
        
        feedback.append("PREVIOUS ATTEMPT FAILED - PLEASE ANALYZE AND CORRECT:\n\n");
        feedback.append("Attempt #").append(attemptNumber).append(" failed with error:\n");
        feedback.append("ERROR TYPE: ").append(error.getClass().getSimpleName()).append("\n");
        feedback.append("ERROR MESSAGE: ").append(error.getMessage()).append("\n\n");
        
        // Provide specific guidance based on error type
        if (error.getMessage() != null) {
            String errorMsg = error.getMessage().toLowerCase();
            
            if (errorMsg.contains("json") || errorMsg.contains("parse")) {
                feedback.append("JSON PARSING ERROR DETECTED:\n");
                feedback.append("- Check tool call syntax - ensure parameters match tool signatures exactly\n");
                feedback.append("- Verify all required parameters are provided\n");
                feedback.append("- Ensure string parameters are properly quoted\n");
                feedback.append("- Lists should be valid JSON arrays: [\"item1\", \"item2\"]\n\n");
            }
            
            if (errorMsg.contains("tool") || errorMsg.contains("function")) {
                feedback.append("TOOL CALL ERROR DETECTED:\n");
                feedback.append("- Use ONLY the available tool names exactly as defined\n");
                feedback.append("- Check parameter names and types match tool signatures\n");
                feedback.append("- Ensure all required parameters are provided\n");
                feedback.append("- Follow the exact parameter order specified in tool definitions\n\n");
            }
            
            if (errorMsg.contains("import") || errorMsg.contains("duplicate")) {
                feedback.append("IMPORT ERROR DETECTED:\n");
                feedback.append("- Check for duplicate imports - each import should be unique\n");
                feedback.append("- Validate import syntax: package.ClassName format\n");
                feedback.append("- Use addMultipleImports for batch import adding\n\n");
            }
            
            if (errorMsg.contains("method") || errorMsg.contains("name")) {
                feedback.append("METHOD ERROR DETECTED:\n");
                feedback.append("- Ensure method names are unique - no duplicates allowed\n");
                feedback.append("- Use valid Java method naming conventions\n");
                feedback.append("- Provide complete method bodies with proper assertions\n\n");
            }
            
            if (errorMsg.contains("rate limit") || errorMsg.contains("tpm") || errorMsg.contains("429")) {
                feedback.append("RATE LIMIT ERROR DETECTED:\n");
                feedback.append("- API rate limit was exceeded - request was too fast\n");
                feedback.append("- The system will automatically apply exponential backoff\n");
                feedback.append("- Consider reducing the complexity of the request\n");
                feedback.append("- This error will be automatically retried with longer delays\n\n");
            }
        }
        
        feedback.append("RECOVERY INSTRUCTIONS:\n");
        feedback.append("1. ANALYZE the previous error carefully\n");
        feedback.append("2. CORRECT the specific issue mentioned in the error\n");
        feedback.append("3. CONTINUE with the test generation using proper tool calls\n");
        feedback.append("4. Use the exact same process as before but AVOID the error condition\n\n");
        
        feedback.append("Please continue with test generation, taking care to avoid the above error.");
        
        return feedback.toString();
    }
    
    /**
     * Parse complete test class generated by AI into TestGenerationResult
     */
    private TestGenerationResult parseCompleteTestClass(String completeTestClass, TestPlan testPlan, ContextAgent.ContextGatheringTools contextTools) {
        sendToUI("🔄 Parsing generated test class...\n");
        
        // Clean up the response (remove markdown if present)
        String cleanTestClass = cleanGeneratedCode(completeTestClass);
        
        // Extract metadata from the complete test class
        String className = extractClassName(cleanTestClass, testPlan);
        String packageName = extractPackageName(cleanTestClass, testPlan);
        String framework = detectFramework(cleanTestClass);
        
        sendToUI("  • Detected class: " + className + "\n");
        sendToUI("  • Detected package: " + packageName + "\n");
        sendToUI("  • Detected framework: " + framework + "\n");
        
        // Extract components from the complete class
        List<String> imports = extractImports(cleanTestClass);
        List<String> fields = extractFields(cleanTestClass);
        List<GeneratedTestMethod> methods = extractTestMethods(cleanTestClass, testPlan);
        String beforeEachCode = extractBeforeEachCode(cleanTestClass);
        String afterEachCode = extractAfterEachCode(cleanTestClass);
        
        sendToUI("  • Extracted " + imports.size() + " imports\n");
        sendToUI("  • Extracted " + fields.size() + " fields\n");
        sendToUI("  • Extracted " + methods.size() + " test methods\n");
        
        // Send individual test methods to UI for display (maintains existing UI behavior)
        sendToUI("📤 Sending individual test methods to UI...\n");
        for (GeneratedTestMethod method : methods) {
            // Try to find matching scenario for better context
            String scenarioName = findMatchingScenario(method.getMethodName(), testPlan);
            String scenarioId = scenarioName != null ? 
                "scenario_" + scenarioName.hashCode() : 
                "scenario_" + method.getMethodName().hashCode();
            
            // Create display data with better scenario association and complete class context
            GeneratedTestDisplayData displayData = new GeneratedTestDisplayData(
                method.getMethodName(),
                scenarioId,
                scenarioName != null ? scenarioName : method.getMethodName(),
                buildCompleteMethodCode(method), // Include method signature and annotations
                GeneratedTestDisplayData.ValidationStatus.NOT_VALIDATED,
                new ArrayList<>(),
                method.getMethodBody().split("\n").length + 2, // +2 for signature and closing brace
                System.currentTimeMillis(),
                cleanTestClass // Pass complete class context
            );
            
            sendTestGenerated(displayData);
        }
        
        // Create result with the complete test class
        TestGenerationResult result = new TestGenerationResult(
            packageName,
            className,
            framework,
            imports,
            fields,
            "", // beforeAllCode
            "", // afterAllCode
            beforeEachCode,
            afterEachCode,
            methods,
            testPlan,
            contextTools,
            cleanTestClass // Store the complete test class
        );
        
        sendToUI("✅ Parsing completed!\n");
        return result;
    }
    
    /**
     * Clean generated code by removing markdown formatting
     */
    private String cleanGeneratedCode(String code) {
        // Remove markdown code blocks
        String cleaned = code.replaceAll("```java\n?", "").replaceAll("```\n?", "");
        
        // Remove any leading/trailing whitespace
        cleaned = cleaned.trim();
        
        return cleaned;
    }
    
    /**
     * Extract BeforeEach method body from test class
     */
    private String extractBeforeEachCode(String testCode) {
        String[] lines = testCode.split("\n");
        boolean inBeforeEach = false;
        boolean inMethodBody = false;
        StringBuilder beforeEachCode = new StringBuilder();
        int braceCount = 0;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            if (trimmed.startsWith("@BeforeEach")) {
                inBeforeEach = true;
                continue;
            }
            
            if (inBeforeEach && !inMethodBody && trimmed.contains("setUp") && trimmed.contains("{")) {
                inMethodBody = true;
                if (trimmed.endsWith("{")) {
                    braceCount = 1;
                }
                continue;
            }
            
            if (inMethodBody) {
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }
                
                if (braceCount == 0) {
                    break; // End of method
                }
                
                // Add the line to beforeEach code (without the method braces)
                beforeEachCode.append(line.trim()).append("\n");
            }
        }
        
        return beforeEachCode.toString().trim();
    }
    
    /**
     * Extract AfterEach method body from test class
     */
    private String extractAfterEachCode(String testCode) {
        String[] lines = testCode.split("\n");
        boolean inAfterEach = false;
        boolean inMethodBody = false;
        StringBuilder afterEachCode = new StringBuilder();
        int braceCount = 0;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            if (trimmed.startsWith("@AfterEach")) {
                inAfterEach = true;
                continue;
            }
            
            if (inAfterEach && !inMethodBody && (trimmed.contains("tearDown") || trimmed.contains("cleanUp")) && trimmed.contains("{")) {
                inMethodBody = true;
                if (trimmed.endsWith("{")) {
                    braceCount = 1;
                }
                continue;
            }
            
            if (inMethodBody) {
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }
                
                if (braceCount == 0) {
                    break; // End of method
                }
                
                // Add the line to afterEach code
                afterEachCode.append(line.trim()).append("\n");
            }
        }
        
        return afterEachCode.toString().trim();
    }
    
    /**
     * Try to find a matching scenario for a test method based on method name
     */
    private String findMatchingScenario(String methodName, TestPlan testPlan) {
        String lowerMethodName = methodName.toLowerCase();
        
        // Try to match with scenario names
        for (TestPlan.TestScenario scenario : testPlan.getTestScenarios()) {
            String scenarioName = scenario.getName().toLowerCase();
            
            // Direct match
            if (lowerMethodName.contains(scenarioName.replace(" ", "")) || 
                scenarioName.replace(" ", "").contains(lowerMethodName.replace("test", ""))) {
                return scenario.getName();
            }
            
            // Keyword matching
            String[] scenarioWords = scenarioName.split("\\s+");
            String[] methodWords = lowerMethodName.replace("test", "").split("(?=[A-Z])|_");
            
            int matches = 0;
            for (String scenarioWord : scenarioWords) {
                for (String methodWord : methodWords) {
                    if (scenarioWord.length() > 3 && methodWord.length() > 3 && 
                        (scenarioWord.contains(methodWord) || methodWord.contains(scenarioWord))) {
                        matches++;
                        break;
                    }
                }
            }
            
            // If we have good word overlap, consider it a match
            if (matches >= 2 || (matches >= 1 && scenarioWords.length <= 2)) {
                return scenario.getName();
            }
        }
        
        return null; // No good match found
    }
    
    /**
     * Build complete method code including annotations and signature
     */
    private String buildCompleteMethodCode(GeneratedTestMethod method) {
        StringBuilder methodCode = new StringBuilder();
        
        // Add annotations
        for (String annotation : method.getAnnotations()) {
            methodCode.append("    @").append(annotation).append("\n");
        }
        
        // Add method signature
        methodCode.append("    public void ").append(method.getMethodName()).append("() {\n");
        
        // Add method body with proper indentation
        String[] bodyLines = method.getMethodBody().split("\n");
        for (String line : bodyLines) {
            if (!line.trim().isEmpty()) {
                // Ensure proper indentation (8 spaces for method body)
                if (line.startsWith("    ")) {
                    methodCode.append("    ").append(line).append("\n");
                } else {
                    methodCode.append("        ").append(line.trim()).append("\n");
                }
            }
        }
        
        // Close method
        methodCode.append("    }\n");
        
        return methodCode.toString();
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
     * Extract test methods from response with better annotation and boundary detection.
     */
    private List<GeneratedTestMethod> extractTestMethods(String response, TestPlan testPlan) {
        List<GeneratedTestMethod> methods = new ArrayList<>();
        String[] lines = response.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // Look for @Test annotation
            if (line.startsWith("@Test")) {
                // Extract method starting from this annotation
                GeneratedTestMethod method = extractSingleMethod(lines, i, testPlan);
                if (method != null) {
                    methods.add(method);
                }
            }
        }
        
        return methods;
    }
    
    /**
     * Extract a single test method starting from an annotation line
     */
    private GeneratedTestMethod extractSingleMethod(String[] lines, int startIndex, TestPlan testPlan) {
        List<String> annotations = new ArrayList<>();
        StringBuilder methodBody = new StringBuilder();
        String methodName = null;
        int currentIndex = startIndex;
        
        // Collect annotations
        while (currentIndex < lines.length && lines[currentIndex].trim().startsWith("@")) {
            String annotation = lines[currentIndex].trim().substring(1); // Remove @
            annotations.add(annotation);
            currentIndex++;
        }
        
        // Find method signature
        while (currentIndex < lines.length) {
            String line = lines[currentIndex].trim();
            
            if (line.contains("void ") && line.contains("(")) {
                // Extract method name
                int voidIndex = line.indexOf("void ");
                int parenIndex = line.indexOf("(");
                if (voidIndex >= 0 && parenIndex > voidIndex) {
                    methodName = line.substring(voidIndex + 5, parenIndex).trim();
                    
                    // Find method body
                    if (line.contains("{")) {
                        currentIndex++;
                        break;
                    }
                }
            }
            currentIndex++;
        }
        
        if (methodName == null) {
            return null; // Could not parse method
        }
        
        // Extract method body
        int braceCount = 1; // We've seen the opening brace
        while (currentIndex < lines.length && braceCount > 0) {
            String line = lines[currentIndex];
            
            // Count braces
            for (char c : line.toCharArray()) {
                if (c == '{') braceCount++;
                if (c == '}') braceCount--;
            }
            
            // Add line to body if we're still inside the method
            if (braceCount > 0) {
                methodBody.append(line).append("\n");
            }
            
            currentIndex++;
        }
        
        // Build the method
        GeneratedTestMethod.Builder builder = new GeneratedTestMethod.Builder(methodName)
            .methodBody(methodBody.toString().trim());
        
        // Add annotations
        for (String annotation : annotations) {
            builder.addAnnotation(annotation);
        }
        
        return builder.build();
    }
    
    /**
     * Build the test writing request with all necessary information.
     */
    private String buildTestWritingRequest(TestPlan testPlan, ContextAgent.ContextGatheringTools contextTools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a complete Java test class for the following requirements.\n\n");
        
        // Target information
        prompt.append("=== TARGET CLASS INFO ===\n");
        prompt.append("Target Class: ").append(testPlan.getTargetClass()).append("\n");
        prompt.append("Target Method(s): ").append(testPlan.getTargetMethods().stream().collect(Collectors.joining(", "))).append("\n");
        prompt.append("Testing Framework: ").append(contextTools.getFrameworkInfo()).append("\n\n");
        
        // Test scenarios to implement
        prompt.append("=== TEST SCENARIOS TO IMPLEMENT ===\n");
        for (int i = 0; i < testPlan.getTestScenarios().size(); i++) {
            TestPlan.TestScenario scenario = testPlan.getTestScenarios().get(i);
            prompt.append((i + 1)).append(". ").append(scenario.getName()).append("\n");
            prompt.append("   Description: ").append(scenario.getDescription()).append("\n");
            prompt.append("   Type: ").append(scenario.getType().getDisplayName()).append("\n");
            prompt.append("   Priority: ").append(scenario.getPriority().getDisplayName()).append("\n");

            // Include test inputs if available
            if (!scenario.getInputs().isEmpty()) {
                prompt.append("   Test Inputs: \n");
                for (String input : scenario.getInputs()) {
                    prompt.append("      - ").append(input).append("\n");
                }
            }

            // Include expected outcome
            prompt.append("   Expected Outcome: ").append(scenario.getExpectedOutcome()).append("\n");
            prompt.append("\n");
        }
        
        // Add context for code generation
        prompt.append("=== CODE CONTEXT ===\n");
        String contextInfo = buildTestWriterContext(contextTools, testPlan);
        prompt.append(contextInfo);
        
        // Instructions for complete test class
        prompt.append("\n=== INSTRUCTIONS ===\n");
        prompt.append("Generate a COMPLETE Java test class that:\n");
        prompt.append("1. Uses package name based on target class: ").append(inferPackageName(testPlan.getTargetClass())).append("\n");
        prompt.append("2. Class name: ").append(testPlan.getTargetClass()).append("Test\n");
        prompt.append("3. Implements ALL scenarios listed above as separate @Test methods\n");
        prompt.append("4. Uses ").append(contextTools.getFrameworkInfo()).append(" testing framework\n");
        prompt.append("5. Includes proper imports, setup/teardown if needed\n");
        prompt.append("6. Each test method has complete implementation with assertions\n");
        prompt.append("7. Follows dependency-aware testing practices (unit vs integration)\n\n");
        
        prompt.append("Return ONLY the complete Java test class code - no explanations needed.");
        
        return prompt.toString();
    }
    
    /**
     * Infer package name for test class based on target class
     */
    private String inferPackageName(String targetClass) {
        // Simple inference - add .test if not present
        if (targetClass.contains(".")) {
            String[] parts = targetClass.split("\\.");
            StringBuilder packageName = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) packageName.append(".");
                packageName.append(parts[i]);
            }
            // Add test package if not present
            if (!packageName.toString().contains("test")) {
                return packageName.toString() + ".test";
            }
            return packageName.toString();
        }
        return "com.example.test";
    }
    
    /**
     * Build test writer context using direct tool access
     */
    private String buildTestWriterContext(ContextAgent.ContextGatheringTools contextTools, TestPlan testPlan) {
        StringBuilder info = new StringBuilder();
        
        info.append("Test Generation Context:\n");
        info.append("Target Class: ").append(testPlan.getTargetClass()).append("\n");
        info.append("Target Methods: ").append(String.join(", ", testPlan.getTargetMethods())).append("\n");
        info.append("Testing Framework: ").append(contextTools.getFrameworkInfo()).append("\n\n");
        
        // Add context notes with insights
        List<String> contextNotes = contextTools.getContextNotes();
        if (!contextNotes.isEmpty()) {
            info.append("=== CONTEXT INSIGHTS ===\n");
            for (String note : contextNotes) {
                info.append("• ").append(note).append("\n");
            }
            info.append("\n");
        }
        
        // Add analyzed class implementations
        Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
        if (!analyzedClasses.isEmpty()) {
            info.append("=== ALL ANALYZED CLASSES ===\n");
            for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
                info.append("From ").append(entry.getKey()).append(":\n");
                info.append(entry.getValue()).append("\n\n");
            }
        }
        
        // Add related files
        Map<String, String> readFiles = contextTools.getReadFiles();
        if (!readFiles.isEmpty()) {
            info.append("=== RELATED FILES ===\n");
            for (Map.Entry<String, String> entry : readFiles.entrySet()) {
                info.append("File: ").append(entry.getKey()).append("\n");
                info.append("```\n").append(entry.getValue()).append("\n```\n\n");
            }
        }
        
        return info.toString();
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
     * Dummy tool to satisfy parallelToolCalls requirement when no real tools are needed
     */
    public static class DummyTool {
        
        @Tool("Mark task as done - no-op tool for compatibility")
        public String markDone(String task) {
            return "Task marked as done: " + task;
        }
    }
}
