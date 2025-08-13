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
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.PsiDocumentManager;
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

public class TestWriterAgent extends StreamingBaseAgent {
    
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
                
                // Notify start of test generation for each scenario
                notifyStream("\n📝 Generating tests for " + testPlan.getTestScenarios().size() + " scenarios...\n\n");
                
                // Generate tests for each scenario
                int scenarioNum = 1;
                for (TestPlan.TestScenario scenario : testPlan.getTestScenarios()) {
                    notifyStream("--- Test " + scenarioNum + "/" + testPlan.getTestScenarios().size() + " ---\n");
                    notifyStream("🎯 Scenario: " + scenario.getName() + "\n");
                    notifyStream("📝 Type: " + scenario.getType().getDisplayName() + "\n");
                    
                    String task = "Generate ONE test for: " + scenario.getName() + 
                                 " (" + scenario.getType().getDisplayName() + ")";
                    
                    String contextInfo = buildContextInfo(testPlan, context, scenario);
                    
                    // Direct generation for faster response
                    notifyStream("🤔 Generating test code...\n");
                    String testResult = generateDirectTest(scenario, testPlan, context, contextInfo);
                    
                    // Parse the test result into a GeneratedTest
                    GeneratedTest test = parseGeneratedTest(testResult, scenario, testPlan, context);
                    if (test != null) {
                        generatedTests.add(test);
                        notifyStream("✅ Test generated: " + test.getTestName() + "\n");
                        
                        // Notify UI to update progressively with the new test
                        notifyStream("TEST_GENERATED: " + test.getTestName() + " | CLASS: " + test.getTestClassName() + "\n");
                    } else {
                        notifyStream("⚠️ Failed to generate test for scenario: " + scenario.getName() + "\n");
                    }
                    
                    notifyStream("\n");
                    scenarioNum++;
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
        // This method should be called from a background thread
        // The actual write operation needs to be done on EDT with write action
        try {
            // Determine test file path (can be done on any thread)
            String testDirectory = determineTestDirectory();
            String testFilePath = Paths.get(testDirectory, test.getFileName()).toString();
            
            // Create directory if it doesn't exist (IO operation, not PSI)
            Path testDirPath = Paths.get(testDirectory);
            if (!Files.exists(testDirPath)) {
                Files.createDirectories(testDirPath);
            }
            
            // Write the test file (IO operation, not PSI)
            Files.writeString(Paths.get(testFilePath), test.getFullContent());
            
            // Refresh the VFS and format - this needs to be on EDT
            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
                
                // Format the newly created file
                VirtualFile vFile = VirtualFileManager.getInstance().findFileByUrl("file://" + testFilePath);
                if (vFile != null) {
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
                        if (psiFile != null) {
                            CodeStyleManager.getInstance(project).reformat(psiFile);
                        }
                    });
                }
            });
            
            LOG.info("[TestWriterAgent] Written test file: " + testFilePath);
            return testFilePath;
            
        } catch (Exception e) {
            LOG.error("[TestWriterAgent] Failed to write test file: " + test.getFileName(), e);
            throw new RuntimeException("Failed to write test file: " + e.getMessage());
        }
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
        String prompt = "What to test (one line):\n" +
                       parameters + "\n\n" +
                       "Output: main behavior only.\n\n" +
                       "Behavior:";
        
        return queryLLM(prompt, 100); // Reduced from 1000
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
        String prompt = "Generate ONE test method:\n" +
                       parameters + "\n\n" +
                       "Keep it simple. Max 20 lines.\n\n" +
                       "Format:\n" +
                       "PACKAGE: packageName\n" +
                       "CLASS_NAME: TestClassName\n" +
                       "IMPORTS:\n" +
                       "import lines (max 5)\n" +
                       "TEST_CODE:\n" +
                       "test method only\n\n" +
                       "Test:";
        
        return queryLLM(prompt, 1000); // Reduced from 3000
    }
    
    /**
     * Generate test directly without ReAct loop for faster response
     */
    private String generateDirectTest(@NotNull TestPlan.TestScenario scenario,
                                     @NotNull TestPlan testPlan,
                                     @NotNull TestContext context,
                                     @NotNull String contextInfo) {
        // Determine if we need setup based on dependencies
        boolean needsSetup = !testPlan.getDependencies().isEmpty() || 
                            scenario.getType() == TestPlan.TestScenario.Type.INTEGRATION;
        
        String className = testPlan.getTargetClass() + "Test";
        String methodName = scenarioToMethodName(scenario.getName());
        
        String prompt = "Generate a complete test for: " + scenario.getName() + "\n\n" +
                       "Context:\n" + contextInfo + "\n\n" +
                       "Output this format EXACTLY:\n" +
                       "PACKAGE: " + inferPackageName(testPlan.getTargetClass()) + "\n" +
                       "CLASS_NAME: " + className + "\n" +
                       "IMPORTS:\n" +
                       "import org.junit.jupiter.api.Test\n" +
                       "import org.junit.jupiter.api.BeforeEach\n" +
                       "import static org.junit.jupiter.api.Assertions.*\n";
        
        if (!testPlan.getDependencies().isEmpty()) {
            prompt += "import org.mockito.Mock\n" +
                     "import org.mockito.MockitoAnnotations\n" +
                     "import static org.mockito.Mockito.*\n";
        }
        
        prompt += "TEST_SETUP:\n";
        if (needsSetup) {
            prompt += "    private " + testPlan.getTargetClass() + " target;\n";
            for (String dep : testPlan.getDependencies()) {
                prompt += "    @Mock\n    private " + dep + " mock" + dep + ";\n";
            }
            prompt += "\n    @BeforeEach\n" +
                     "    void setUp() {\n" +
                     "        MockitoAnnotations.openMocks(this);\n" +
                     "        target = new " + testPlan.getTargetClass() + "();\n" +
                     "        // TODO: Initialize target with mocks\n" +
                     "    }\n";
        } else {
            prompt += "NONE\n";
        }
        
        prompt += "TEST_CODE:\n" +
                 "    @Test\n" +
                 "    void " + methodName + "() {\n" +
                 "        // " + scenario.getDescription() + "\n" +
                 "        // Given\n" +
                 "        // TODO: Setup test data\n" +
                 "        \n" +
                 "        // When\n" +
                 "        // TODO: Execute method\n" +
                 "        \n" +
                 "        // Then\n" +
                 "        // TODO: Assert results\n" +
                 "        assertNotNull(\"Test implemented\");\n" +
                 "    }\n\n" +
                 "Generate proper test with Given-When-Then structure. Max 20 lines. Be concise.\n\n" +
                 "Test:";
        
        // Stream the generation
        if (streamingConsumer != null) {
            notifyStream("\n📝 Writing test with setup...\n");
            String result = queryLLM(prompt, 700);
            notifyStream("\n✅ Test written\n");
            return result;
        } else {
            return queryLLM(prompt, 700);
        }
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
            // Clean markdown formatting from the result first
            String cleanedResult = cleanMarkdownFormatting(testResult);
            String[] lines = cleanedResult.split("\n");
            
            String packageName = inferPackageName(testPlan.getTargetClass());
            String className = testPlan.getTargetClass() + "Test";
            List<String> imports = new ArrayList<>();
            StringBuilder testSetup = new StringBuilder();
            StringBuilder testCode = new StringBuilder();
            
            String currentSection = "";
            boolean foundTestCode = false;
            boolean foundTestSetup = false;
            
            for (String line : lines) {
                String trimmed = line.trim();
                
                // Look for markers
                if (trimmed.contains("PACKAGE:") && trimmed.length() > 8) {
                    packageName = trimmed.substring(trimmed.indexOf("PACKAGE:") + 8).trim();
                } else if (trimmed.contains("CLASS_NAME:") && trimmed.length() > 11) {
                    className = trimmed.substring(trimmed.indexOf("CLASS_NAME:") + 11).trim();
                } else if (trimmed.contains("IMPORTS:")) {
                    currentSection = "IMPORTS";
                } else if (trimmed.contains("TEST_SETUP:")) {
                    currentSection = "TEST_SETUP";
                    foundTestSetup = true;
                } else if (trimmed.contains("TEST_CODE:")) {
                    currentSection = "TEST_CODE";
                    foundTestCode = true;
                } else if (!trimmed.isEmpty() && currentSection.equals("IMPORTS")) {
                    // Parse import lines
                    if (trimmed.startsWith("import ")) {
                        imports.add(trimmed.replace("import ", "").replace(";", "").trim());
                    } else if (!trimmed.contains("TEST_SETUP") && !trimmed.contains("TEST_CODE")) {
                        // Assume it's an import without "import" keyword
                        imports.add(trimmed.replace(";", "").trim());
                    }
                } else if (currentSection.equals("TEST_SETUP") && !trimmed.equals("NONE")) {
                    testSetup.append(line).append("\n");
                } else if (!trimmed.isEmpty() && currentSection.equals("TEST_CODE")) {
                    testCode.append(line).append("\n");
                } else if (!trimmed.isEmpty() && !foundTestCode && trimmed.contains("@Test")) {
                    // If we see @Test without TEST_CODE marker, assume this is the test code
                    currentSection = "TEST_CODE";
                    foundTestCode = true;
                    testCode.append(line).append("\n");
                }
            }
            
            // Default imports if none found
            if (imports.isEmpty()) {
                imports = getDefaultImports(context.getFrameworkInfo());
            }
            
            // Combine setup and test code
            StringBuilder fullTestCode = new StringBuilder();
            if (testSetup.length() > 0) {
                fullTestCode.append(testSetup);
                fullTestCode.append("\n");
            }
            fullTestCode.append(testCode);
            
            // Generate simple test if no code found
            if (testCode.length() == 0) {
                fullTestCode.append(generateSimpleTest(scenario, context.getFrameworkInfo()));
            }
            
            String fileName = className + ".java";
            String testName = extractTestMethodName(testCode.toString());
            if (testName.equals("generatedTest")) {
                testName = scenarioToMethodName(scenario.getName());
            }
            
            List<String> annotations = Arrays.asList("@Test");
            if (testSetup.length() > 0 && testSetup.toString().contains("@BeforeEach")) {
                annotations = Arrays.asList("@Test", "@BeforeEach");
            }
            
            return new GeneratedTest(
                testName,
                className,
                fullTestCode.toString(),
                scenario,
                fileName,
                packageName,
                imports,
                annotations,
                context.getFrameworkInfo()
            );
            
        } catch (Exception e) {
            LOG.error("[TestWriterAgent] Failed to parse test, using fallback", e);
            return createFallbackTest(scenario, testPlan, context);
        }
    }
    
    private String generateSimpleTest(@NotNull TestPlan.TestScenario scenario, @NotNull String framework) {
        StringBuilder code = new StringBuilder();
        String methodName = scenarioToMethodName(scenario.getName());
        
        if (framework.contains("JUnit 5")) {
            code.append("    @Test\n");
            code.append("    void ").append(methodName).append("() {\n");
        } else {
            code.append("    @Test\n");
            code.append("    public void ").append(methodName).append("() {\n");
        }
        
        code.append("        // ").append(scenario.getDescription()).append("\n");
        code.append("        // TODO: Implement test\n");
        code.append("        assertNotNull(\"Test not implemented\");\n");
        code.append("    }\n");
        
        return code.toString();
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
    
    private String cleanMarkdownFormatting(@NotNull String text) {
        // Remove markdown code blocks
        text = text.replaceAll("```java\\s*\n", "");
        text = text.replaceAll("```\\s*\n", "");
        text = text.replaceAll("\n```\\s*", "");
        text = text.replaceAll("```", "");
        
        // Remove any leading/trailing backticks
        text = text.replaceAll("^`+", "");
        text = text.replaceAll("`+$", "");
        
        // Remove markdown bold/italic markers that might appear
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("__(.*?)__", "$1");
        
        return text.trim();
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
    
    @NotNull
    @Override
    protected String buildActionPrompt(@NotNull AgentAction action) {
        switch (action.getType()) {
            case ANALYZE:
                return analyzeTestRequirements(action.getParameters());
                
            case SEARCH:
                return "Find test pattern (one example):\n" +
                       action.getParameters() + "\n\n" +
                       "Output: ONE example. Max 10 lines.\n\n" +
                       "Example:";
                       
            case GENERATE:
                return generateTestCode(action.getParameters());
                
            default:
                return action.getParameters();
        }
    }
}