package com.zps.zest.testgen.agents;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.util.ExistingTestAnalyzer;
import com.zps.zest.testgen.util.TestMerger;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TestWriterAgent extends BaseAgent {
    
    public TestWriterAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "TestWriterAgent");
    }
    
    /**
     * Generate tests based on the test plan and context
     */
    @NotNull
    public CompletableFuture<List<GeneratedTest>> generateTests(@NotNull TestPlan testPlan, @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[TestWriterAgent] Generating tests for: " + testPlan.getTargetClass());
                
                List<GeneratedTest> generatedTests = new ArrayList<>();
                
                // Generate tests for each scenario
                for (TestPlan.TestScenario scenario : testPlan.getTestScenarios()) {
                    String task = "Generate a " + scenario.getType().getDisplayName() + " for scenario: " + scenario.getName() + 
                                 ". Description: " + scenario.getDescription() + 
                                 ". Expected outcome: " + scenario.getExpectedOutcome();
                    
                    String contextInfo = buildContextInfo(testPlan, context, scenario);
                    
                    // Execute ReAct workflow for this test
                    String testResult = executeReActTask(task, contextInfo).join();
                    
                    // Parse the test result into a GeneratedTest
                    GeneratedTest test = parseGeneratedTest(testResult, scenario, testPlan, context);
                    if (test != null) {
                        generatedTests.add(test);
                    }
                }
                
                if (generatedTests.isEmpty()) {
                    throw new RuntimeException("No tests could be generated from the test plan");
                }
                
                LOG.info("[TestWriterAgent] Generated " + generatedTests.size() + " tests successfully");
                return generatedTests;
                
            } catch (Exception e) {
                LOG.error("[TestWriterAgent] Failed to generate tests", e);
                throw new RuntimeException("Test generation failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Write a test to an actual file (uses file creation tools)
     */
    @NotNull
    public String writeTestFile(@NotNull GeneratedTest test) {
        return ApplicationManager.getApplication().runWriteAction((Computable<String>) () -> {
            try {
                // Determine test file path
                String testDirectory = determineTestDirectory();
                String testFilePath = Paths.get(testDirectory, test.getFileName()).toString();
                
                // Create directory if it doesn't exist
                Path testDirPath = Paths.get(testDirectory);
                if (!Files.exists(testDirPath)) {
                    Files.createDirectories(testDirPath);
                }
                
                // Write the test file
                Files.writeString(Paths.get(testFilePath), test.getFullContent());
                
                // Refresh the VFS
                VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
                
                LOG.info("[TestWriterAgent] Written test file: " + testFilePath);
                return testFilePath;
                
            } catch (Exception e) {
                LOG.error("[TestWriterAgent] Failed to write test file: " + test.getFileName(), e);
                throw new RuntimeException("Failed to write test file: " + e.getMessage());
            }
        });
    }
    
    /**
     * Merge generated tests with an existing test class
     */
    @NotNull
    public CompletableFuture<TestMerger.MergeResult> mergeTestsWithExisting(
            @NotNull ExistingTestAnalyzer.ExistingTestClass existingTestClass,
            @NotNull List<GeneratedTest> generatedTests) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[TestWriterAgent] Merging " + generatedTests.size() + " tests into existing class: " + 
                        existingTestClass.getClassName());
                
                TestMerger merger = new TestMerger(project);
                TestMerger.MergeResult result = merger.mergeTestsIntoClass(existingTestClass, generatedTests);
                
                if (result.isSuccess()) {
                    LOG.info("[TestWriterAgent] Successfully merged tests: " + result.getMessage());
                } else {
                    LOG.error("[TestWriterAgent] Failed to merge tests: " + result.getMessage());
                }
                
                return result;
                
            } catch (Exception e) {
                LOG.error("[TestWriterAgent] Error merging tests with existing class", e);
                return new TestMerger.MergeResult(
                    false, 
                    "Merge failed: " + e.getMessage(), 
                    0, 
                    Collections.emptyList()
                );
            }
        });
    }
    
    private String buildContextInfo(@NotNull TestPlan testPlan, @NotNull TestContext context, @NotNull TestPlan.TestScenario scenario) {
        StringBuilder info = new StringBuilder();
        
        info.append("Test Generation Context:\n");
        info.append("Target Class: ").append(testPlan.getTargetClass()).append("\n");
        info.append("Target Method: ").append(testPlan.getTargetMethod()).append("\n");
        info.append("Testing Framework: ").append(context.getFrameworkInfo()).append("\n");
        info.append("Test Type: ").append(scenario.getType().getDisplayName()).append("\n");
        info.append("Priority: ").append(scenario.getPriority().getDisplayName()).append("\n");
        info.append("Expected Inputs: ").append(String.join(", ", scenario.getInputs())).append("\n\n");
        
        // Add existing test patterns
        if (!context.getExistingTestPatterns().isEmpty()) {
            info.append("Existing Test Patterns to Follow:\n");
            for (String pattern : context.getExistingTestPatterns()) {
                info.append("- ").append(pattern).append("\n");
            }
            info.append("\n");
        }
        
        // Add relevant dependencies
        if (!context.getDependencies().isEmpty()) {
            info.append("Dependencies to Consider:\n");
            context.getDependencies().forEach((key, value) -> 
                info.append("- ").append(key).append(" (").append(value).append(")\n"));
            info.append("\n");
        }
        
        // Add code context samples
        if (!context.getCodeContext().isEmpty()) {
            info.append("Relevant Code Context:\n");
            for (ZestLangChain4jService.ContextItem item : context.getCodeContext().subList(0, Math.min(3, context.getCodeContext().size()))) {
                info.append("From ").append(item.getFilePath()).append(":\n");
                info.append(item.getContent().substring(0, Math.min(300, item.getContent().length()))).append("...\n\n");
            }
        }
        
        return info.toString();
    }
    
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        String lowerReasoning = reasoning.toLowerCase();
        
        if (lowerReasoning.contains("analyze") || lowerReasoning.contains("understand") || lowerReasoning.contains("examine")) {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze test requirements and context", reasoning);
        } else if (lowerReasoning.contains("search") || lowerReasoning.contains("find") || lowerReasoning.contains("look up")) {
            return new AgentAction(AgentAction.ActionType.SEARCH, "Search for test examples and patterns", reasoning);
        } else if (lowerReasoning.contains("generate") || lowerReasoning.contains("write") || lowerReasoning.contains("create")) {
            return new AgentAction(AgentAction.ActionType.GENERATE, "Generate test code", reasoning);
        } else if (lowerReasoning.contains("complete") || lowerReasoning.contains("done") || lowerReasoning.contains("finished")) {
            return new AgentAction(AgentAction.ActionType.COMPLETE, "Test generation completed", reasoning);
        } else {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze current situation", reasoning);
        }
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        switch (action.getType()) {
            case ANALYZE:
                return analyzeTestRequirements(action.getParameters());
            case SEARCH:
                return searchTestExamples(action.getParameters());
            case GENERATE:
                return generateTestCode(action.getParameters());
            case COMPLETE:
                return action.getParameters();
            default:
                return "Unknown action: " + action.getType();
        }
    }
    
    private String analyzeTestRequirements(@NotNull String parameters) {
        String prompt = "Analyze the test requirements and provide a detailed breakdown:\n\n" +
                       parameters + "\n\n" +
                       "Provide analysis on:\n" +
                       "1. What specific behavior needs to be tested\n" +
                       "2. What test setup is required\n" +
                       "3. What assertions should be made\n" +
                       "4. What mocking might be needed\n" +
                       "5. What edge cases to consider\n\n" +
                       "Analysis:";
        
        return queryLLM(prompt, 1000);
    }
    
    private String searchTestExamples(@NotNull String parameters) {
        try {
            // Use RAG to find similar test examples
            String searchQuery = "test examples " + extractTestType(parameters) + " " + extractClassName(parameters);
            
            ZestLangChain4jService.RetrievalResult result = langChainService
                .retrieveContext(searchQuery, 5, 0.7).join();
            
            if (result.isSuccess() && !result.getItems().isEmpty()) {
                StringBuilder examples = new StringBuilder();
                examples.append("Found similar test examples:\n\n");
                
                for (ZestLangChain4jService.ContextItem item : result.getItems()) {
                    examples.append("Example from ").append(item.getFilePath()).append(":\n");
                    examples.append(item.getContent()).append("\n\n");
                }
                
                return examples.toString();
            } else {
                return "No similar test examples found. Will create from scratch.";
            }
            
        } catch (Exception e) {
            LOG.warn("[TestWriterAgent] Test example search failed", e);
            return "Example search failed: " + e.getMessage();
        }
    }
    
    private String generateTestCode(@NotNull String parameters) {
        String prompt = "Generate complete, compilable test code based on the following requirements:\n\n" +
                       parameters + "\n\n" +
                       "Requirements:\n" +
                       "1. Generate a complete Java test class with proper package declaration\n" +
                       "2. Include all necessary imports\n" +
                       "3. Use appropriate testing framework annotations\n" +
                       "4. Include proper setup and teardown methods if needed\n" +
                       "5. Generate realistic test data and assertions\n" +
                       "6. Follow best practices for naming and structure\n" +
                       "7. Include proper error handling and edge case testing\n" +
                       "8. Add meaningful comments explaining the test logic\n\n" +
                       "Format the response as:\n" +
                       "PACKAGE: [package name]\n" +
                       "CLASS_NAME: [test class name]\n" +
                       "IMPORTS:\n" +
                       "[import statements]\n" +
                       "TEST_CODE:\n" +
                       "[complete test class code]\n\n" +
                       "Generated Test:";
        
        return queryLLM(prompt, 3000);
    }
    
    private String extractTestType(@NotNull String parameters) {
        if (parameters.toLowerCase().contains("integration")) {
            return "integration";
        } else if (parameters.toLowerCase().contains("unit")) {
            return "unit";
        }
        return "test";
    }
    
    private String extractClassName(@NotNull String parameters) {
        // Simple extraction - in a real implementation this would be more sophisticated
        if (parameters.contains("Target Class:")) {
            String line = Arrays.stream(parameters.split("\n"))
                .filter(l -> l.contains("Target Class:"))
                .findFirst()
                .orElse("");
            return line.substring(line.indexOf(":") + 1).trim();
        }
        return "class";
    }
    
    private GeneratedTest parseGeneratedTest(@NotNull String testResult, @NotNull TestPlan.TestScenario scenario, 
                                           @NotNull TestPlan testPlan, @NotNull TestContext context) {
        try {
            String[] lines = testResult.split("\n");
            
            String packageName = "";
            String className = "";
            List<String> imports = new ArrayList<>();
            StringBuilder testCode = new StringBuilder();
            
            String currentSection = "";
            
            for (String line : lines) {
                line = line.trim();
                
                if (line.startsWith("PACKAGE:")) {
                    packageName = line.substring("PACKAGE:".length()).trim();
                } else if (line.startsWith("CLASS_NAME:")) {
                    className = line.substring("CLASS_NAME:".length()).trim();
                } else if (line.equals("IMPORTS:")) {
                    currentSection = "IMPORTS";
                } else if (line.equals("TEST_CODE:")) {
                    currentSection = "TEST_CODE";
                } else if (!line.isEmpty()) {
                    switch (currentSection) {
                        case "IMPORTS":
                            if (!line.startsWith("TEST_CODE:")) {
                                imports.add(line.replace("import ", "").replace(";", ""));
                            }
                            break;
                        case "TEST_CODE":
                            testCode.append(line).append("\n");
                            break;
                    }
                }
            }
            
            // Fallback values if parsing failed
            if (className.isEmpty()) {
                className = testPlan.getTargetClass() + "Test";
            }
            if (packageName.isEmpty()) {
                packageName = inferPackageName(testPlan.getTargetClass());
            }
            if (imports.isEmpty()) {
                imports = getDefaultImports(context.getFrameworkInfo());
            }
            if (testCode.length() == 0) {
                testCode.append(generateFallbackTest(className, scenario, context.getFrameworkInfo()));
            }
            
            String fileName = className + ".java";
            String testName = extractTestMethodName(testCode.toString());
            
            List<String> annotations = extractAnnotations(testCode.toString());
            
            return new GeneratedTest(
                testName,
                className,
                testCode.toString(),
                scenario,
                fileName,
                packageName,
                imports,
                annotations,
                context.getFrameworkInfo()
            );
            
        } catch (Exception e) {
            LOG.error("[TestWriterAgent] Failed to parse generated test", e);
            return createFallbackTest(scenario, testPlan, context);
        }
    }
    
    private String inferPackageName(@NotNull String targetClass) {
        // Simple package inference - assumes target class has package info
        int lastDot = targetClass.lastIndexOf('.');
        if (lastDot > 0) {
            return targetClass.substring(0, lastDot) + ".test";
        }
        return "com.test";
    }
    
    private List<String> getDefaultImports(@NotNull String framework) {
        List<String> imports = new ArrayList<>();
        
        if (framework.contains("JUnit 5")) {
            imports.add("org.junit.jupiter.api.Test");
            imports.add("org.junit.jupiter.api.BeforeEach");
            imports.add("org.junit.jupiter.api.AfterEach");
            imports.add("org.junit.jupiter.api.Assertions.*");
        } else if (framework.contains("JUnit 4")) {
            imports.add("org.junit.Test");
            imports.add("org.junit.Before");
            imports.add("org.junit.After");
            imports.add("org.junit.Assert.*");
        }
        
        imports.add("org.mockito.Mock");
        imports.add("org.mockito.MockitoAnnotations");
        
        return imports;
    }
    
    private String generateFallbackTest(@NotNull String className, @NotNull TestPlan.TestScenario scenario, @NotNull String framework) {
        StringBuilder code = new StringBuilder();
        
        code.append("public class ").append(className).append(" {\n\n");
        
        if (framework.contains("JUnit 5")) {
            code.append("    @Test\n");
            code.append("    void ").append(scenarioToMethodName(scenario.getName())).append("() {\n");
        } else {
            code.append("    @Test\n");
            code.append("    public void ").append(scenarioToMethodName(scenario.getName())).append("() {\n");
        }
        
        code.append("        // ").append(scenario.getDescription()).append("\n");
        code.append("        // TODO: Implement test logic\n");
        code.append("        fail(\"Test not implemented\");\n");
        code.append("    }\n");
        code.append("}\n");
        
        return code.toString();
    }
    
    private String extractTestMethodName(@NotNull String testCode) {
        // Extract method name from test code
        String[] lines = testCode.split("\n");
        for (String line : lines) {
            if (line.trim().contains("void ") && line.contains("()")) {
                String methodLine = line.trim();
                int voidIndex = methodLine.indexOf("void ");
                int parenIndex = methodLine.indexOf("()");
                if (voidIndex >= 0 && parenIndex > voidIndex) {
                    return methodLine.substring(voidIndex + 5, parenIndex).trim();
                }
            }
        }
        return "generatedTest";
    }
    
    private List<String> extractAnnotations(@NotNull String testCode) {
        List<String> annotations = new ArrayList<>();
        String[] lines = testCode.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("@")) {
                annotations.add(trimmed);
            }
        }
        
        return annotations;
    }
    
    private String scenarioToMethodName(@NotNull String scenarioName) {
        return scenarioName.toLowerCase()
                .replaceAll("[^a-zA-Z0-9]", " ")
                .trim()
                .replaceAll("\\s+", "_");
    }
    
    private GeneratedTest createFallbackTest(@NotNull TestPlan.TestScenario scenario, @NotNull TestPlan testPlan, @NotNull TestContext context) {
        String className = testPlan.getTargetClass() + "Test";
        String packageName = inferPackageName(testPlan.getTargetClass());
        List<String> imports = getDefaultImports(context.getFrameworkInfo());
        String testCode = generateFallbackTest(className, scenario, context.getFrameworkInfo());
        
        return new GeneratedTest(
            scenarioToMethodName(scenario.getName()),
            className,
            testCode,
            scenario,
            className + ".java",
            packageName,
            imports,
            List.of("@Test"),
            context.getFrameworkInfo()
        );
    }
    
    private String determineTestDirectory() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            throw new RuntimeException("Project base path is null");
        }
        
        // Try common test directory patterns
        String[] testPaths = {
            "src/test/java",
            "src/test",
            "test/java",
            "test"
        };
        
        for (String testPath : testPaths) {
            Path fullPath = Paths.get(basePath, testPath);
            if (Files.exists(fullPath)) {
                return fullPath.toString();
            }
        }
        
        // Create default test directory
        String defaultPath = Paths.get(basePath, "src", "test", "java").toString();
        try {
            Files.createDirectories(Paths.get(defaultPath));
            return defaultPath;
        } catch (IOException e) {
            LOG.error("Failed to create test directory", e);
            throw new RuntimeException("Could not create test directory: " + e.getMessage());
        }
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a test writing agent that generates comprehensive, compilable test code using best practices and existing patterns";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(
            AgentAction.ActionType.ANALYZE,
            AgentAction.ActionType.SEARCH,
            AgentAction.ActionType.GENERATE,
            AgentAction.ActionType.COMPLETE
        );
    }
}