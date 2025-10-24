package com.zps.zest.testgen.agents;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.codeInsight.navigation.MethodImplementationsSearch;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.psi.util.MethodSignatureUtil;
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
    private com.zps.zest.testgen.ui.StreamingEventListener uiEventListener = null; // UI event listener

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

        🚨 TOKEN OPTIMIZATION RULES (CRITICAL):
        - NEVER repeat the entire test code when making fixes
        - ALWAYS use applySimpleFix() for small changes
        - Use updateTestCode() ONLY for major structural changes
        - Each fix should be minimal and precise
        - Report token savings: "💰 Saved ~X tokens by using incremental fix"

        TOOL USAGE TRACKING (mandatory for each response):
        📊 Track your tool usage:
        - Report after each tool call: "🔧 Tool usage: [X/50] calls used"
        - List recent tools: [setNewTestCode, findExistingTest, applySimpleFix, etc.]
        - Be mindful of the 50 tool call limit per session

        RESPONSE FORMAT:
        After each tool usage, briefly report:
        📍 Phase: [Setup|Discovery|Merging|Validation|Fixing|Completion]
        🔧 Tools used: X/50
        ⚡ Next: [Specific next action]
        💰 Token savings (if applicable)

        WORKFLOW:
        NOTE: Test code is AUTO-INITIALIZED - already set when you start!
        The new test is already loaded and visible in the UI.

        1. Use findExistingTest(targetClass) to check for existing test class
           This displays existing test in left panel if found
        2. If existing test found, merge it with new test and use updateTestCode()
           Each updateTestCode() refreshes the UI - use sparingly for major changes
        3. If no existing test, the new test is already the final version - skip to step 4
        4. Validate and fix the test code (see validation workflow below)
           Each applySimpleFix() shows live in the UI with line highlighting
        5. Call recordMergedResult() with the final test details
        6. Call markMergingDone() - THIS IS MANDATORY (see completion section)

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

        VALIDATION WORKFLOW (MANDATORY - OPTIMIZE TOKENS):
        After merging/updating, call validateCurrentTestCode() to check for issues
        A. If validation returns MANY errors (>10), ANALYZE THE PATTERN:
           - Library dependency errors (junit, testcontainers, mockito, assertions)
           - Project import errors (RedisConfig, internal classes)
           - Actual code issues

        B. USE suppressValidationErrors() FOR LIBRARY ERRORS:
           If you see many "Cannot resolve symbol" for libraries, suppress them:
           - suppressValidationErrors("Cannot resolve symbol '(junit|testcontainers|mockito|Container|Test|BeforeAll|AfterAll)'",
                                      "External test framework dependencies")
           - suppressValidationErrors("Cannot resolve method '(assert\\w+|assertEquals|assertTrue|assertNotNull)'",
                                      "Test assertion methods from framework")
                                      
           Then validateCurrentTestCode() again to see ONLY fixable issues

        C. For remaining VALIDATION_FAILED issues:
           - STOP AND ANALYZE the root cause before fixing
           - Project imports ARE fixable - try to add them
           - Use lookupMethod(className, methodName) to verify correct signatures
           - Use applySimpleFix(oldText, newText) for EACH issue separately
           - NEVER use updateTestCode() for validation fixes
           - Apply fixes incrementally - one line/import at a time

        D. Repeat until VALIDATION_PASSED or fixes exhausted (MAX 10 real fix attempts after suppression)
        E. Call recordMergedResult() with your final code
        F. 🚨 CRITICAL: You MUST call markMergingDone() when:
            - Validation passes (include "validation passed" in reason) OR
            - You've attempted 3+ fixes (include "max fixes attempted" in reason) OR
            - Any unrecoverable error occurs (include error description)
            - NEVER end without calling markMergingDone() - this is MANDATORY!

        FIX OPTIMIZATION EXAMPLES:
        ✅ GOOD: applySimpleFix("import java.util.List;", "import java.util.List;\nimport org.junit.jupiter.api.Test;")
        ❌ BAD: updateTestCode(entireTestClassWith1000Lines) // Wastes tokens!

        VALIDATION FAILURE ANALYSIS (CRITICAL):
        When validation fails, BEFORE attempting any fix:

        1. CHECK ERROR VOLUME - If >10 similar errors:
           USE suppressValidationErrors() FIRST!
           Example: 30 errors about junit/testcontainers → Suppress them, focus on real issues

        2. DIAGNOSE THE ROOT CAUSE of remaining errors:
           - "cannot find symbol" → Check if library (suppress) or project class (fix)
           - "package does not exist" → Missing dependency in build file
           - "incompatible types" → Wrong API version or incorrect usage
           - Syntax errors → Typos or malformed code

        3. UNDERSTAND WHY IT FAILED - Think deeply:
           "This error occurs because..." and explain the actual cause
           Example: "RedisConfig not found - this is a project class, need to import it"

        3. DOCUMENT UNFIXABLE ISSUES IN CODE:
           If you cannot fix an issue, ADD A COMMENT in the test code:
           // FIXME: [describe the issue]
           // FIXME: Missing dependency: org.testcontainers:testcontainers
           // FIXME: This test requires manual configuration of [resource]

           Use applySimpleFix() to add these comments above the problematic line:
           applySimpleFix("@Test", "// FIXME: Add @SpringBootTest if this is a Spring integration test\n    @Test")
           After that, ALWAYS suppress the error.
           
        4. FIX STRATEGICALLY:
           - Missing libraries → Add FIXME comment explaining dependency needed
           - Wrong API → Add FIXME comment with correct version needed
           - Configuration issues → Add FIXME comment with setup instructions
           - Missing imports → Try to add the import, or comment if unknown

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

        COMPLETION CHECKLIST:
        Before calling markMergingDone(), ensure:
        ✓ All fixable issues have been addressed
        ✓ Unfixable issues have TODO/FIXME/NOTE comments added
        ✓ The test code is as complete as possible
        ✓ recordMergedResult() has been called with the final code

        You MUST call markMergingDone() when:
        - Validation passes (VALIDATION_PASSED)
        - OR you've exhausted reasonable fix attempts (document remaining issues)
        Include a summary in the reason: "Validation passed" or "Completed with X TODO comments for manual fixes"
        """)
        @dev.langchain4j.agentic.Agent
        String mergeAndFixTestClass(String request);
    }
    
    /**
     * Set the UI event listener for live updates
     */
    public void setUiEventListener(@Nullable com.zps.zest.testgen.ui.StreamingEventListener listener) {
        this.uiEventListener = listener;
    }

    /**
     * Merge generated test class with existing test file (if any)
     */
    @NotNull
    public CompletableFuture<MergedTestClass> mergeTests(@NotNull TestGenerationResult result,
                                                         @Nullable ContextAgent.ContextGatheringTools contextTools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting AI-based test merging for: " + result.getClassName());

                // Reset for new session
                lastMergedResult = null;
                mergingTools.reset();

                // Notify UI
                notifyStart();
                sendToUI("🤖 AI-based test merging starting...\n\n");

                // AUTO-INITIALIZE: Set the new test code immediately for UI display
                String testClassName = result.getClassName();
                String newTestCode = result.getCompleteTestClass();
                if (newTestCode == null || newTestCode.isEmpty()) {
                    newTestCode = generateCompleteTestClass(result);
                }

                // Automatically call setNewTestCode to initialize and show in UI immediately
                mergingTools.setNewTestCode(testClassName, newTestCode);
                sendToUI("✅ Test code initialized: " + testClassName + "\n");

                // Build the merging request with context notes
                String mergeRequest = buildMergeRequest(result, contextTools);

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
                    sendToUI("\n⚠️ Merging reached maximum iterations. Auto-completing merge process.\n");

                    // Force completion to ensure clean exit
                    mergingTools.markMergingDone("Auto-completed after " + iteration + " iterations without explicit completion");
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
    private String buildMergeRequest(TestGenerationResult result, @Nullable ContextAgent.ContextGatheringTools contextTools) {
        StringBuilder request = new StringBuilder();

        request.append("MERGE TASK: Intelligently merge the new test class with any existing test class\n\n");

        // Target class information
        request.append("Target Class: ").append(result.getTargetClass()).append("\n");
        request.append("Test Class Name: ").append(result.getClassName()).append("\n");
        request.append("Package: ").append(result.getPackageName()).append("\n");
        request.append("Framework: ").append(result.getFramework()).append("\n\n");

        // Include project dependencies if available
        if (contextTools != null) {
            String projectDeps = contextTools.getProjectDependencies();
            if (projectDeps != null && !projectDeps.isEmpty()) {
                request.append("🔧 PROJECT DEPENDENCIES:\n");
                request.append(projectDeps);
                request.append("\nThese are the available test frameworks and libraries in the project.\n");
                request.append("Use this information to:\n");
                request.append("  - Know which test frameworks are actually available\n");
                request.append("  - Suppress validation errors for known libraries\n");
                request.append("  - Write tests using the correct framework versions\n\n");
            }

            java.util.List<String> contextNotes = contextTools.getContextNotes();
            if (!contextNotes.isEmpty()) {
                request.append("🔍 CRITICAL CONTEXT NOTES (from code analysis):\n");
                request.append("These are important technical details discovered during context gathering:\n");
                for (String note : contextNotes) {
                    request.append("  • ").append(note).append("\n");
                }
                request.append("\nConsider these notes when merging and fixing tests - they contain crucial information about:\n");
                request.append("  - API contracts and requirements\n");
                request.append("  - Specific configuration values and formats\n");
                request.append("  - Dependencies and constraints\n");
                request.append("  - Edge cases and error conditions\n\n");
            }
        }

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
        request.append("📌 NOTE: Test code is ALREADY SET for you! The new test is loaded and visible in UI.\n\n");
        request.append("1. Use findExistingTest(\"").append(result.getTargetClass()).append("\") to check for existing tests\n");
        request.append("2. If existing test found, merge intelligently and use updateTestCode() to save merged version\n");
        request.append("3. If no existing test, the new test is already your final version - skip to validation\n");
        request.append("4. Use validateCurrentTestCode() to check for issues\n");
        request.append("5. If many library errors (>10), use suppressValidationErrors() to filter them first\n");
        request.append("6. Use applySimpleFix() to fix remaining real issues (one at a time for live UI updates)\n");
        request.append("7. Call recordMergedResult() with the final validated test details\n");
        request.append("8. 🚨 CRITICAL: Call markMergingDone() - This is MANDATORY! Include reason:\n");
        request.append("   - \"Validation passed\" if successful\n");
        request.append("   - \"Max fixes attempted (X/10)\" if tried multiple fixes\n");
        request.append("   - \"Error: [description]\" if encountered issues\n");
        request.append("9. Throughout the process, report your tool usage: \"🔧 Tools used: X/50\"\n");

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
        // Try to find test roots from project modules using content entries
        com.intellij.openapi.module.ModuleManager moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project);
        for (com.intellij.openapi.module.Module module : moduleManager.getModules()) {
            com.intellij.openapi.roots.ModuleRootManager rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module);
            // Iterate through content entries to find test source folders
            for (com.intellij.openapi.roots.ContentEntry contentEntry : rootManager.getContentEntries()) {
                for (com.intellij.openapi.roots.SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                    // Check if this is a test source root
                    if (sourceFolder.isTestSource() && sourceFolder.getFile() != null) {
                        return sourceFolder.getFile().getPath();
                    }
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
     * Find the best test source root as a VirtualFile for proper classpath context
     */
    private com.intellij.openapi.vfs.VirtualFile findBestTestSourceRootVirtualFile() {
        // Try to find test roots from project modules using content entries
        com.intellij.openapi.module.ModuleManager moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project);
        for (com.intellij.openapi.module.Module module : moduleManager.getModules()) {
            com.intellij.openapi.roots.ModuleRootManager rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module);
            // Iterate through content entries to find test source folders
            for (com.intellij.openapi.roots.ContentEntry contentEntry : rootManager.getContentEntries()) {
                for (com.intellij.openapi.roots.SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                    // Check if this is a test source root
                    if (sourceFolder.isTestSource() && sourceFolder.getFile() != null) {
                        return sourceFolder.getFile();
                    }
                }
            }
        }

        // Fallback: try to find by path
        String testRootPath = findBestTestSourceRoot();
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(testRootPath);
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
        private final java.util.Set<String> suppressionPatterns = new java.util.HashSet<>(); // Patterns to suppress in validation

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

            // Fire live UI event for immediate display
            if (uiEventListener != null) {
                uiEventListener.onTestCodeSet(className, testCode, false);
            }

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

            // Fire live UI event for immediate display
            if (uiEventListener != null && currentTestClassName != null) {
                uiEventListener.onTestCodeUpdated(currentTestClassName, updatedCode);
            }

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

                        // Fire UI event for existing test display
                        if (uiEventListener != null) {
                            uiEventListener.onTestCodeSet(targetClassName + "Test", existingCode, true);
                        }

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

                // Find test source root for proper classpath context
                com.intellij.openapi.vfs.VirtualFile testSourceRoot = findBestTestSourceRootVirtualFile();

                // Create virtual file for validation with test source root as parent
                // This ensures IntelliJ uses test classpath (with JUnit, Mockito, etc.)
                LightVirtualFile virtualFile = new LightVirtualFile(
                    className + ".java",
                    javaFileType,
                    testCode
                );

                // Set the parent to test source root for proper scope resolution
                if (testSourceRoot != null) {
                    virtualFile.setOriginalFile(testSourceRoot);
                }

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

                // Filter issues based on suppression patterns
                java.util.List<CodeSmellInfo> filteredIssues = new java.util.ArrayList<>();
                java.util.List<String> suppressedDescriptions = new java.util.ArrayList<>();

                for (CodeSmellInfo issue : issues) {
                    String description = issue.getDescription();
                    boolean suppressed = false;

                    // Check if this issue matches any suppression pattern
                    for (String pattern : suppressionPatterns) {
                        try {
                            if (description.matches(pattern) ||
                                java.util.regex.Pattern.compile(pattern).matcher(description).find()) {
                                suppressed = true;
                                suppressedDescriptions.add(String.format("Line %d: %s", issue.getStartLine(), description));
                                break;
                            }
                        } catch (Exception e) {
                            // If pattern matching fails, don't suppress
                            LOG.warn("Error matching suppression pattern: " + pattern, e);
                        }
                    }

                    if (!suppressed) {
                        filteredIssues.add(issue);
                    }
                }

                // Log suppression stats if any
                if (!suppressedDescriptions.isEmpty()) {
                    LOG.info("Suppressed " + suppressedDescriptions.size() + " validation errors");
                }

                if (filteredIssues.isEmpty()) {
                    // Fire UI event for validation success
                    if (uiEventListener != null) {
                        String successMsg = suppressedDescriptions.isEmpty() ?
                            "VALIDATION_PASSED" :
                            "VALIDATION_PASSED (after suppressing " + suppressedDescriptions.size() + " library errors)";
                        uiEventListener.onValidationStatusChanged(successMsg, null);
                    }
                    return suppressedDescriptions.isEmpty() ?
                        "VALIDATION_PASSED: No issues found" :
                        "VALIDATION_PASSED: No issues after suppressing " + suppressedDescriptions.size() + " library dependency errors";
                }

                // Format remaining issues for AI to fix
                java.util.List<String> issueDescriptions = new java.util.ArrayList<>();
                StringBuilder result = new StringBuilder("VALIDATION_FAILED:\n");
                for (CodeSmellInfo issue : filteredIssues) {
                    String issueDesc = String.format("Line %d: %s", issue.getStartLine(), issue.getDescription());
                    result.append("- ").append(issueDesc).append("\n");
                    issueDescriptions.add(issueDesc);
                }

                // Add note about suppressed errors if any
                if (!suppressedDescriptions.isEmpty()) {
                    result.append("\nNote: ").append(suppressedDescriptions.size())
                          .append(" library dependency errors were suppressed.\n");
                }

                // Fire UI event for validation failures
                if (uiEventListener != null) {
                    uiEventListener.onValidationStatusChanged("VALIDATION_FAILED", issueDescriptions);
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

                // Fire UI event for live fix display - try to detect line number
                if (uiEventListener != null) {
                    Integer lineNumber = findLineNumber(testCode, oldText);
                    uiEventListener.onFixApplied(oldText, newText, lineNumber);

                    // Also update the full test code display
                    if (currentTestClassName != null) {
                        uiEventListener.onTestCodeUpdated(currentTestClassName, currentWorkingTestCode);
                    }
                }

                return "SUCCESS: Replacement applied to current test code.";

            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @Tool("Suppress validation errors matching a pattern to focus on fixable issues. " +
              "Use this to ignore library dependency errors that cannot be fixed. " +
              "Example patterns: 'Cannot resolve symbol .*(junit|testcontainers|mockito).*' " +
              "or 'Cannot resolve method .*(assert\\w+).*'")
        public String suppressValidationErrors(String pattern, String reason) {
            notifyTool("suppressValidationErrors", "pattern: " + pattern.substring(0, Math.min(pattern.length(), 50)) + "...");

            try {
                // Test if pattern is valid regex
                java.util.regex.Pattern.compile(pattern);
                suppressionPatterns.add(pattern);

                // Count how many errors this will suppress (if we have current validation errors)
                String estimatedImpact = "";
                if (currentWorkingTestCode != null) {
                    // Just indicate pattern was added, actual impact will be seen on next validation
                    estimatedImpact = " Pattern added for filtering.";
                }

                return "SUPPRESSED: " + reason + " (pattern: " + pattern + ")" + estimatedImpact;
            } catch (java.util.regex.PatternSyntaxException e) {
                return "ERROR: Invalid regex pattern - " + e.getMessage();
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

        @Tool("Look up method signatures using fully qualified class name and method name to get correct signatures from project or library classes")
        public String lookupMethod(String className, String methodName) {
            notifyTool("lookupMethod", className + "." + methodName);

            return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<String>) () -> {
                    try {
                        com.intellij.psi.JavaPsiFacade facade = com.intellij.psi.JavaPsiFacade.getInstance(project);
                        com.intellij.psi.PsiClass psiClass = facade.findClass(className,
                            com.intellij.psi.search.GlobalSearchScope.allScope(project));

                        if (psiClass == null) {
                            return "Class not found: " + className;
                        }

                        com.intellij.psi.PsiMethod[] methods = psiClass.findMethodsByName(methodName, true);
                        if (methods.length == 0) {
                            return "Method not found: " + methodName;
                        }

                        StringBuilder result = new StringBuilder();
                        for (com.intellij.psi.PsiMethod method : methods) {
                            // Return type
                            if (method.getReturnType() != null) {
                                result.append(method.getReturnType().getPresentableText()).append(" ");
                            }
                            // Method name and parameters
                            result.append(methodName).append("(");
                            com.intellij.psi.PsiParameter[] params = method.getParameterList().getParameters();
                            for (int i = 0; i < params.length; i++) {
                                if (i > 0) result.append(", ");
                                result.append(params[i].getType().getPresentableText());
                            }
                            result.append(")\n");
                        }
                        return result.toString();
                    } catch (Exception e) {
                        return "ERROR: " + e.getMessage();
                    }
                }
            );
        }

        public boolean isMergingComplete() {
            return mergingComplete;
        }

        public void reset() {
            currentWorkingTestCode = null;
            currentTestClassName = null;
            mergingComplete = false;
            suppressionPatterns.clear();
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

        private Integer findLineNumber(String code, String searchText) {
            String[] lines = code.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(searchText)) {
                    return i + 1; // Line numbers are 1-based
                }
            }
            return null;
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