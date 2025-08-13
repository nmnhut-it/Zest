package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Agent responsible for merging generated tests into a single file and handling existing test files
 */
public class TestMergerAgent extends StreamingBaseAgent {
    
    public TestMergerAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "TestMergerAgent");
    }
    
    /**
     * Merge multiple generated tests into a cohesive test class
     */
    @NotNull
    public CompletableFuture<MergedTestResult> mergeTests(@NotNull List<GeneratedTest> tests, 
                                                          @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[TestMergerAgent] Merging " + tests.size() + " generated tests");
                
                if (tests.isEmpty()) {
                    throw new RuntimeException("No tests to merge");
                }
                
                // Group tests by class
                Map<String, List<GeneratedTest>> testsByClass = tests.stream()
                    .collect(Collectors.groupingBy(GeneratedTest::getTestClassName));
                
                List<MergedTestClass> mergedClasses = new ArrayList<>();
                
                for (Map.Entry<String, List<GeneratedTest>> entry : testsByClass.entrySet()) {
                    String className = entry.getKey();
                    List<GeneratedTest> classTests = entry.getValue();
                    
                    notifyStream("\n🔧 Merging " + classTests.size() + " tests for " + className + "\n");
                    
                    // Check for existing test file
                    ExistingTestInfo existingTest = findExistingTestFile(className);
                    
                    MergedTestClass mergedClass;
                    if (existingTest != null) {
                        notifyStream("📁 Found existing test file: " + existingTest.getFilePath() + "\n");
                        mergedClass = mergeWithExistingTests(classTests, existingTest, context);
                    } else {
                        notifyStream("📝 Creating new test class\n");
                        mergedClass = createNewMergedTestClass(classTests, context);
                    }
                    
                    // Fix imports and organize
                    mergedClass = fixImportsAndOrganize(mergedClass, context);
                    
                    // Apply final formatting
                    mergedClass = formatTestClass(mergedClass);
                    
                    mergedClasses.add(mergedClass);
                    notifyStream("✅ Merged and formatted test class ready: " + className + "\n");
                }
                
                return new MergedTestResult(mergedClasses, true, "Successfully merged " + tests.size() + " tests");
                
            } catch (Exception e) {
                LOG.error("[TestMergerAgent] Failed to merge tests", e);
                throw new RuntimeException("Test merging failed: " + e.getMessage());
            }
        });
    }
    
    @Nullable
    private ExistingTestInfo findExistingTestFile(@NotNull String testClassName) {
        try {
            // Search for existing test files
            PsiFile[] files = FilenameIndex.getFilesByName(
                project, 
                testClassName + ".java", 
                GlobalSearchScope.projectScope(project)
            );
            
            if (files.length > 0) {
                PsiFile testFile = files[0];
                VirtualFile vFile = testFile.getVirtualFile();
                if (vFile != null) {
                    String content = new String(vFile.contentsToByteArray());
                    return new ExistingTestInfo(vFile.getPath(), content, testFile);
                }
            }
            
            // Also check common test directories
            String[] testPaths = {"src/test/java", "test", "tests"};
            String basePath = project.getBasePath();
            if (basePath != null) {
                for (String testPath : testPaths) {
                    Path fullPath = Paths.get(basePath, testPath);
                    // Search recursively for the test file
                    Path testFilePath = findTestFileInDirectory(fullPath, testClassName + ".java");
                    if (testFilePath != null && Files.exists(testFilePath)) {
                        String content = Files.readString(testFilePath);
                        return new ExistingTestInfo(testFilePath.toString(), content, null);
                    }
                }
            }
            
        } catch (Exception e) {
            LOG.warn("[TestMergerAgent] Error searching for existing test file", e);
        }
        
        return null;
    }
    
    @Nullable
    private Path findTestFileInDirectory(@NotNull Path directory, @NotNull String fileName) {
        try {
            if (!Files.exists(directory)) {
                return null;
            }
            
            return Files.walk(directory)
                .filter(path -> path.getFileName().toString().equals(fileName))
                .findFirst()
                .orElse(null);
                
        } catch (IOException e) {
            LOG.debug("Error searching directory: " + directory, e);
            return null;
        }
    }
    
    @NotNull
    private MergedTestClass mergeWithExistingTests(@NotNull List<GeneratedTest> newTests,
                                                   @NotNull ExistingTestInfo existingTest,
                                                   @NotNull TestContext context) {
        // Use LLM to intelligently merge new tests with existing ones
        String prompt = buildMergePrompt(newTests, existingTest, context);
        
        notifyStream("🤖 Analyzing existing tests and merging...\n");
        String mergedContent = queryLLM(prompt, 2000);
        
        // Parse the merged content
        return parseMergedTestClass(mergedContent, newTests.get(0));
    }
    
    @NotNull
    private MergedTestClass createNewMergedTestClass(@NotNull List<GeneratedTest> tests,
                                                     @NotNull TestContext context) {
        GeneratedTest firstTest = tests.get(0);
        
        // Collect all unique imports
        Set<String> allImports = new LinkedHashSet<>();
        for (GeneratedTest test : tests) {
            allImports.addAll(test.getImports());
        }
        
        // Combine all test methods
        StringBuilder testMethods = new StringBuilder();
        StringBuilder setupCode = new StringBuilder();
        boolean hasSetup = false;
        
        for (GeneratedTest test : tests) {
            String content = test.getTestContent();
            
            // Extract setup code from first test
            if (!hasSetup && content.contains("@BeforeEach")) {
                int beforeEachStart = content.indexOf("@BeforeEach");
                int methodEnd = findMethodEnd(content, beforeEachStart);
                if (methodEnd > 0) {
                    setupCode.append(content, beforeEachStart, methodEnd);
                    hasSetup = true;
                }
            }
            
            // Extract test methods
            int testIndex = content.indexOf("@Test");
            while (testIndex >= 0) {
                int methodEnd = findMethodEnd(content, testIndex);
                if (methodEnd > 0) {
                    testMethods.append("\n    ").append(content, testIndex, methodEnd).append("\n");
                    testIndex = content.indexOf("@Test", methodEnd);
                } else {
                    break;
                }
            }
        }
        
        // Build complete class
        StringBuilder fullClass = new StringBuilder();
        
        // Add class declaration and fields
        fullClass.append("public class ").append(firstTest.getTestClassName()).append(" {\n\n");
        
        // Add setup if exists
        if (hasSetup) {
            fullClass.append("    ").append(setupCode).append("\n\n");
        }
        
        // Add all test methods
        fullClass.append(testMethods);
        
        fullClass.append("}\n");
        
        return new MergedTestClass(
            firstTest.getTestClassName(),
            firstTest.getPackageName(),
            new ArrayList<>(allImports),
            fullClass.toString(),
            null // No existing file path for new class
        );
    }
    
    private int findMethodEnd(@NotNull String content, int startIndex) {
        int braceCount = 0;
        boolean inMethod = false;
        
        for (int i = startIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                braceCount++;
                inMethod = true;
            } else if (c == '}') {
                braceCount--;
                if (inMethod && braceCount == 0) {
                    return i + 1;
                }
            }
        }
        
        return -1;
    }
    
    @NotNull
    private MergedTestClass fixImportsAndOrganize(@NotNull MergedTestClass testClass,
                                                  @NotNull TestContext context) {
        String prompt = "Fix, organize and format this test class:\n\n" +
                       "Package: " + testClass.getPackageName() + "\n" +
                       "Current imports:\n" + String.join("\n", testClass.getImports()) + "\n\n" +
                       "Test code:\n" + testClass.getContent() + "\n\n" +
                       "Instructions:\n" +
                       "1. Add any missing imports\n" +
                       "2. Remove unused imports\n" +
                       "3. Sort imports properly (java.*, javax.*, then others)\n" +
                       "4. Fix any compilation issues\n" +
                       "5. Format code with proper indentation (4 spaces)\n" +
                       "6. Add proper spacing between methods\n" +
                       "7. Format according to Java conventions\n" +
                       "8. Ensure @Test annotations are on separate lines\n" +
                       "9. Add blank lines between test methods\n\n" +
                       "Output format:\n" +
                       "IMPORTS:\n" +
                       "import statements\n" +
                       "FIXED_CODE:\n" +
                       "properly formatted test class code\n";
        
        notifyStream("🔧 Fixing imports, organizing and formatting code...\n");
        String result = queryLLM(prompt, 1500);
        
        // Parse the result
        List<String> fixedImports = new ArrayList<>();
        String fixedCode = testClass.getContent();
        
        String[] lines = result.split("\n");
        boolean inImports = false;
        boolean inCode = false;
        StringBuilder codeBuilder = new StringBuilder();
        
        for (String line : lines) {
            if (line.startsWith("IMPORTS:")) {
                inImports = true;
                inCode = false;
            } else if (line.startsWith("FIXED_CODE:")) {
                inImports = false;
                inCode = true;
            } else if (inImports && line.trim().startsWith("import ")) {
                fixedImports.add(line.trim().replace("import ", "").replace(";", ""));
            } else if (inCode) {
                codeBuilder.append(line).append("\n");
            }
        }
        
        if (codeBuilder.length() > 0) {
            fixedCode = codeBuilder.toString();
        }
        
        return new MergedTestClass(
            testClass.getClassName(),
            testClass.getPackageName(),
            fixedImports.isEmpty() ? testClass.getImports() : fixedImports,
            fixedCode,
            testClass.getExistingFilePath()
        );
    }
    
    @NotNull
    private String buildMergePrompt(@NotNull List<GeneratedTest> newTests,
                                    @NotNull ExistingTestInfo existingTest,
                                    @NotNull TestContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Merge new test methods with existing test class:\n\n");
        
        prompt.append("EXISTING TEST FILE:\n");
        prompt.append(existingTest.getContent()).append("\n\n");
        
        prompt.append("NEW TEST METHODS TO ADD:\n");
        for (GeneratedTest test : newTests) {
            prompt.append("// Test: ").append(test.getTestName()).append("\n");
            prompt.append(test.getTestContent()).append("\n\n");
        }
        
        prompt.append("INSTRUCTIONS:\n");
        prompt.append("1. Add new test methods to existing class\n");
        prompt.append("2. Avoid duplicate test names (rename if needed)\n");
        prompt.append("3. Merge setup methods if both exist\n");
        prompt.append("4. Keep existing tests intact\n");
        prompt.append("5. Fix any conflicts\n\n");
        
        prompt.append("Output the complete merged test class.\n");
        
        return prompt.toString();
    }
    
    @NotNull
    private MergedTestClass parseMergedTestClass(@NotNull String content, @NotNull GeneratedTest template) {
        // Clean markdown formatting first
        content = cleanMarkdownFormatting(content);
        
        // Extract package and imports
        String packageName = template.getPackageName();
        List<String> imports = new ArrayList<>();
        
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("package ")) {
                packageName = line.replace("package ", "").replace(";", "").trim();
            } else if (line.startsWith("import ")) {
                imports.add(line.replace("import ", "").replace(";", "").trim());
            }
        }
        
        return new MergedTestClass(
            template.getTestClassName(),
            packageName,
            imports,
            content,
            null
        );
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
        
        // Remove markdown bold/italic markers
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("__(.*?)__", "$1");
        
        return text.trim();
    }
    
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        String lowerReasoning = reasoning.toLowerCase();
        
        if (lowerReasoning.contains("analyze") || lowerReasoning.contains("check")) {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze tests to merge", reasoning);
        } else if (lowerReasoning.contains("merge") || lowerReasoning.contains("combine")) {
            return new AgentAction(AgentAction.ActionType.GENERATE, "Merge test methods", reasoning);
        } else if (lowerReasoning.contains("complete") || lowerReasoning.contains("done")) {
            return new AgentAction(AgentAction.ActionType.COMPLETE, "Merging completed", reasoning);
        } else {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Continue analysis", reasoning);
        }
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        switch (action.getType()) {
            case ANALYZE:
                return "Analyzing test structure and dependencies";
            case GENERATE:
                return "Merging test methods and fixing imports";
            case COMPLETE:
                return action.getParameters();
            default:
                return "Unknown action: " + action.getType();
        }
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a test merging agent that combines multiple tests and handles existing test files";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(
            AgentAction.ActionType.ANALYZE,
            AgentAction.ActionType.GENERATE,
            AgentAction.ActionType.COMPLETE
        );
    }
    
    @NotNull
    @Override
    protected String buildActionPrompt(@NotNull AgentAction action) {
        return action.getParameters();
    }
    
    /**
     * Apply final formatting to the test class
     */
    @NotNull
    private MergedTestClass formatTestClass(@NotNull MergedTestClass testClass) {
        // Ensure proper formatting
        String content = testClass.getContent();
        
        // Fix indentation
        content = fixIndentation(content);
        
        // Add spacing between methods
        content = addMethodSpacing(content);
        
        return new MergedTestClass(
            testClass.getClassName(),
            testClass.getPackageName(),
            testClass.getImports(),
            content,
            testClass.getExistingFilePath()
        );
    }
    
    private String fixIndentation(String content) {
        StringBuilder formatted = new StringBuilder();
        String[] lines = content.split("\n");
        int indentLevel = 0;
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Decrease indent for closing braces
            if (trimmed.startsWith("}")) {
                indentLevel--;
            }
            
            // Add proper indentation
            if (!trimmed.isEmpty()) {
                formatted.append("    ".repeat(Math.max(0, indentLevel)));
                formatted.append(trimmed).append("\n");
            } else {
                formatted.append("\n");
            }
            
            // Increase indent for opening braces
            if (trimmed.endsWith("{")) {
                indentLevel++;
            }
        }
        
        return formatted.toString();
    }
    
    private String addMethodSpacing(String content) {
        // Add blank lines between test methods
        return content.replaceAll("(}\\s*\\n)(\\s*@Test)", "$1\n$2")
                     .replaceAll("(}\\s*\\n)(\\s*@BeforeEach)", "$1\n$2")
                     .replaceAll("(}\\s*\\n)(\\s*@AfterEach)", "$1\n$2");
    }
    
    /**
     * Result of merging tests
     */
    public static class MergedTestResult {
        private final List<MergedTestClass> mergedClasses;
        private final boolean success;
        private final String message;
        
        public MergedTestResult(@NotNull List<MergedTestClass> mergedClasses,
                               boolean success,
                               @NotNull String message) {
            this.mergedClasses = mergedClasses;
            this.success = success;
            this.message = message;
        }
        
        @NotNull
        public List<MergedTestClass> getMergedClasses() {
            return mergedClasses;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        @NotNull
        public String getMessage() {
            return message;
        }
    }
    
    /**
     * Represents a merged test class
     */
    public static class MergedTestClass {
        private final String className;
        private final String packageName;
        private final List<String> imports;
        private final String content;
        private final String existingFilePath;
        
        public MergedTestClass(@NotNull String className,
                              @NotNull String packageName,
                              @NotNull List<String> imports,
                              @NotNull String content,
                              @Nullable String existingFilePath) {
            this.className = className;
            this.packageName = packageName;
            this.imports = imports;
            this.content = content;
            this.existingFilePath = existingFilePath;
        }
        
        @NotNull
        public String getClassName() {
            return className;
        }
        
        @NotNull
        public String getPackageName() {
            return packageName;
        }
        
        @NotNull
        public List<String> getImports() {
            return imports;
        }
        
        @NotNull
        public String getContent() {
            return content;
        }
        
        @Nullable
        public String getExistingFilePath() {
            return existingFilePath;
        }
        
        @NotNull
        public String getFullContent() {
            StringBuilder full = new StringBuilder();
            full.append("package ").append(packageName).append(";\n\n");
            
            for (String imp : imports) {
                full.append("import ").append(imp).append(";\n");
            }
            
            full.append("\n").append(content);
            return full.toString();
        }
    }
    
    /**
     * Information about existing test file
     */
    private static class ExistingTestInfo {
        private final String filePath;
        private final String content;
        private final PsiFile psiFile;
        
        public ExistingTestInfo(@NotNull String filePath,
                               @NotNull String content,
                               @Nullable PsiFile psiFile) {
            this.filePath = filePath;
            this.content = content;
            this.psiFile = psiFile;
        }
        
        @NotNull
        public String getFilePath() {
            return filePath;
        }
        
        @NotNull
        public String getContent() {
            return content;
        }
        
        @Nullable
        public PsiFile getPsiFile() {
            return psiFile;
        }
    }
}