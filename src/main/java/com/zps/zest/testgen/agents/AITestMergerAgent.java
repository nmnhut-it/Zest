package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
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
 * Replaces complex PSI-based merging with intelligent LLM-based merging
 */
public class AITestMergerAgent extends StreamingBaseAgent {
    private final TestMergingAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    private final ExistingTestAnalyzer existingTestAnalyzer;
    
    public AITestMergerAgent(@NotNull Project project,
                            @NotNull ZestLangChain4jService langChainService,
                            @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "AITestMergerAgent");
        this.existingTestAnalyzer = new ExistingTestAnalyzer(project);
        
        // Build the agent with streaming support
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(50);
        this.assistant = AgenticServices
                .agentBuilder(TestMergingAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(5) // Simple merging, fewer tools needed
                .chatMemory(chatMemory)
                .tools(new DummyTool()) // Dummy tool to satisfy parallelToolCalls requirement
                .build();
    }
    
    /**
     * AI assistant for intelligent test class merging
     */
    public interface TestMergingAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are an intelligent test merging assistant that creates complete, well-structured test classes.
        
        CORE RESPONSIBILITY:
        Generate a COMPLETE Java test class that intelligently merges new test methods with existing ones.
        
        MERGING RULES:
        1. **Preserve Existing Tests**: Never remove or modify existing test methods
        2. **Avoid Duplicates**: Don't add methods that already exist (check method names)
        3. **Framework Consistency**: Use the same testing framework as existing tests
        4. **Import Management**: Deduplicate imports, keep existing ones, add new ones as needed
        5. **Code Style**: Match the existing code style and patterns
        6. **Setup/Teardown**: Consolidate setup/teardown methods intelligently
        
        CONFLICT RESOLUTION:
        - If method names conflict: Add suffix like "2" or rename descriptively  
        - If framework conflicts: Prefer existing framework, adapt new tests
        - If import conflicts: Use fully qualified names when necessary
        - If setup conflicts: Merge setup code or use separate setup methods
        
        OUTPUT FORMAT:
        Return ONLY the complete Java test class code, including:
        - Package declaration
        - All necessary imports (deduplicated)
        - Class declaration with proper annotations
        - All field declarations
        - Setup/teardown methods (merged if applicable)
        - All existing test methods (preserved exactly)
        - All new test methods (adapted to match style)
        
        QUALITY STANDARDS:
        - Proper Java formatting and indentation
        - Complete method implementations with proper assertions
        - Consistent naming conventions
        - Proper annotation usage (@Test, @BeforeEach, etc.)
        - Clear, readable code structure
        
        DO NOT include explanations or markdown - just return the complete Java class.
        """)
        @dev.langchain4j.agentic.Agent
        String mergeTestClass(String request);
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
                
                // Notify UI
                notifyStart();
                sendToUI("🤖 AI-based test merging starting...\n\n");
                
                // Build the merging request
                String mergeRequest = buildMergeRequest(result, contextTools);
                
                // Send the request to UI
                sendToUI("📋 Merge Request:\n" + mergeRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");
                
                // Let AI generate the complete merged test class
                String mergedTestCode = assistant.mergeTestClass(mergeRequest);
                
                // Send response to UI
                sendToUI(mergedTestCode);
                sendToUI("\n" + "-".repeat(40) + "\n");
                
                // Extract metadata from the merged result
                String className = extractClassName(mergedTestCode, result.getClassName());
                String packageName = extractPackageName(mergedTestCode, result.getPackageName());
                int methodCount = countTestMethods(mergedTestCode);
                
                // Create the merged test class result
                MergedTestClass mergedTestClass = new MergedTestClass(
                    className,
                    packageName,
                    mergedTestCode,
                    className + ".java",
                    determineOutputPath(result, contextTools),
                    methodCount,
                    result.getFramework()
                );
                
                // Summary
                sendToUI("\n📊 Merge Summary:\n");
                sendToUI("  • Final class: " + className + "\n");
                sendToUI("  • Package: " + packageName + "\n");
                sendToUI("  • Total methods: " + methodCount + "\n");
                sendToUI("  • Framework: " + result.getFramework() + "\n");
                notifyComplete();
                
                LOG.debug("AI test merging complete: " + methodCount + " total methods");
                
                return mergedTestClass;
                
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
    private String buildMergeRequest(TestGenerationResult result, ContextAgent.ContextGatheringTools contextTools) {
        StringBuilder request = new StringBuilder();
        
        request.append("MERGE TASK: Create a complete test class by intelligently merging new and existing tests\n\n");
        
        // New test class information
        request.append("=== NEW TEST CLASS TO MERGE ===\n");
        request.append("Package: ").append(result.getPackageName()).append("\n");
        request.append("Class: ").append(result.getClassName()).append("\n");
        request.append("Framework: ").append(result.getFramework()).append("\n");
        request.append("Methods to add: ").append(result.getMethodCount()).append("\n\n");
        
        // Generate the new test class first
        String newTestClass = generateCompleteTestClass(result);
        request.append("NEW TEST CLASS CODE:\n");
        request.append("```java\n").append(newTestClass).append("\n```\n\n");
        
        // Check for existing test file
        try {
            ExistingTestAnalyzer.ExistingTestClass existingTest = 
                existingTestAnalyzer.findExistingTestClass(result.getTargetClass());
            
            if (existingTest != null) {
                request.append("=== EXISTING TEST CLASS FOUND ===\n");
                request.append("Existing file: ").append(existingTest.getFilePath()).append("\n");
                request.append("Existing class: ").append(existingTest.getClassName()).append("\n");
                request.append("Existing methods: ").append(existingTest.getTestMethodCount()).append("\n");
                
                // Get source code from PSI class
                String existingCode = getSourceCodeFromPsiClass(existingTest.getPsiClass());
                if (existingCode != null && !existingCode.isEmpty()) {
                    request.append("\nEXISTING TEST CLASS CODE:\n");
                    request.append("```java\n").append(existingCode).append("\n```\n\n");
                } else {
                    request.append("(Could not read existing test class code)\n\n");
                }
                
                request.append("MERGE INSTRUCTIONS:\n");
                request.append("1. Keep ALL existing test methods exactly as they are\n");
                request.append("2. Add new test methods from the new class, avoiding duplicates\n");
                request.append("3. Merge imports (deduplicate, keep all necessary ones)\n");
                request.append("4. Consolidate setup/teardown methods intelligently\n");
                request.append("5. Use the existing framework and code style\n");
                request.append("6. Resolve any naming conflicts by renaming new methods\n");
            } else {
                request.append("=== NO EXISTING TEST CLASS FOUND ===\n");
                request.append("This is a new test class - return the new test class as-is.\n");
            }
        } catch (Exception e) {
            LOG.warn("Could not analyze existing test class", e);
            request.append("=== EXISTING TEST ANALYSIS FAILED ===\n");
            request.append("Could not check for existing tests: ").append(e.getMessage()).append("\n");
            request.append("Proceeding with new test class only.\n");
        }
        
        request.append("\nGenerate the complete merged Java test class.");
        
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
     * Extract class name from merged test code
     */
    private String extractClassName(String testCode, String fallback) {
        // Look for class declaration
        String[] lines = testCode.split("\n");
        for (String line : lines) {
            if (line.contains("class ") && line.contains("{")) {
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
        return fallback;
    }
    
    /**
     * Extract package name from merged test code
     */
    private String extractPackageName(String testCode, String fallback) {
        // Look for package declaration
        String[] lines = testCode.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("package ")) {
                String packageLine = line.trim();
                if (packageLine.endsWith(";")) {
                    return packageLine.substring(8, packageLine.length() - 1).trim();
                }
            }
        }
        return fallback;
    }
    
    /**
     * Count test methods in merged test code
     */
    private int countTestMethods(String testCode) {
        int count = 0;
        String[] lines = testCode.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("@Test")) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Determine output path for the test file
     */
    private String determineOutputPath(TestGenerationResult result, ContextAgent.ContextGatheringTools contextTools) {
        // Try to get framework-specific path
        String testPath = "src/test/java/" + result.getPackageName().replace('.', '/') + "/" + result.getClassName() + ".java";
        
        // Check if existing test file has a different path
        try {
            ExistingTestAnalyzer.ExistingTestClass existingTest = 
                existingTestAnalyzer.findExistingTestClass(result.getTargetClass());
            if (existingTest != null) {
                return existingTest.getFilePath();
            }
        } catch (Exception e) {
            LOG.warn("Could not determine existing test path", e);
        }
        
        return testPath;
    }
    
    /**
     * Get source code from PSI class
     */
    private String getSourceCodeFromPsiClass(com.intellij.psi.PsiClass psiClass) {
        if (psiClass == null) {
            return null;
        }
        
        try {
            // Get the containing file and extract its text
            com.intellij.psi.PsiFile containingFile = psiClass.getContainingFile();
            if (containingFile != null) {
                return containingFile.getText();
            }
        } catch (Exception e) {
            LOG.warn("Could not extract source code from PSI class: " + psiClass.getName(), e);
        }
        
        return null;
    }
    
    @NotNull
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }
    
    /**
     * Dummy tool to satisfy parallelToolCalls requirement when no real tools are needed
     */
    public static class DummyTool {
        
        @Tool("Mark merge task as done - no-op tool for compatibility")
        public String markMergeDone(String status) {
            return "Merge status: " + status;
        }
    }
}