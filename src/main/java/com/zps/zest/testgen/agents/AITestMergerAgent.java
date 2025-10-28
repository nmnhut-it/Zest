package com.zps.zest.testgen.agents;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.codeInsight.navigation.MethodImplementationsSearch;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.testFramework.LightVirtualFile;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.util.ExistingTestAnalyzer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

/**
 * AI-based test merger that generates complete, merged test classes
 * Uses agentic architecture with tools for file access and result recording
 */
public class AITestMergerAgent extends StreamingBaseAgent {
    private final TestMergingAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    private final TestMergingTools mergingTools;
    private final TestFixStrategy fixStrategy;
    private String lastExistingTestCode = null; // Store for UI display
    private MergedTestClass lastMergedResult = null; // Store the merged result
    private com.zps.zest.testgen.ui.StreamingEventListener uiEventListener = null; // UI event listener

    public AITestMergerAgent(@NotNull Project project,
                            @NotNull ZestLangChain4jService langChainService,
                            @NotNull NaiveLLMService naiveLlmService,
                            @NotNull TestFixStrategy fixStrategy) {
        super(project, langChainService, naiveLlmService, "AITestMergerAgent");
        this.fixStrategy = fixStrategy;
        this.mergingTools = new TestMergingTools(project, this::sendToUI, null); // contextTools passed later

        // Build the agent with streaming support
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(200);

        this.assistant = AgenticServices
                .agentBuilder(TestMergingAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(50) // Allow multiple tool calls for the workflow
                .chatMemory(chatMemory)
                .tools(mergingTools) // Use the actual merging tools
                .build();

        LOG.info("AITestMergerAgent initialized with strategy: " + fixStrategy);
    }

    /**
     * Get the system prompt based on configured strategy
     */
    private String getSystemPrompt() {
        return (fixStrategy == TestFixStrategy.FULL_REWRITE_ONLY) ?
                buildFullRewritePrompt() : buildTwoPhasePrompt();
    }

    /**
     * Wrap user request with strategy-specific system prompt
     */
    private String wrapWithSystemPrompt(String userRequest) {
        return getSystemPrompt() + "\n\n---\n\n" + userRequest;
    }

    /**
     * AI assistant for intelligent test class merging using tools
     * Note: Strategy-specific system prompt is prepended to each request via wrapWithSystemPrompt()
     */
    public interface TestMergingAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test merging agent. Follow the instructions provided in each request.
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
     * Get the merging tools for direct access
     */
    public TestMergingTools getMergingTools() {
        return mergingTools;
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
     * Automatically enhance test code with imports (Java-based, deterministic).
     * This prevents 70-80% of validation errors proactively.
     */
    public String autoEnhanceImports(String testCode, String targetClass,
                                     String framework,
                                     ContextAgent.ContextGatheringTools contextTools) {
        try {
            // 1. Extract imports from target class
            java.util.List<String> targetImports = extractImportsFromTargetClass(targetClass, contextTools);
            LOG.info("Extracted " + targetImports.size() + " imports from target class: " + targetClass);

            // 2. Get common framework imports
            java.util.List<String> frameworkImports = getCommonFrameworkImports(framework);
            LOG.info("Added " + frameworkImports.size() + " common framework imports for: " + framework);

            // 3. Extract existing imports from test code (preserve them!)
            java.util.List<String> existingTestImports = extractImportsFromCode(testCode);
            LOG.info("Extracted " + existingTestImports.size() + " existing imports from test code");

            // 4. Combine all imports (deduplicate)
            java.util.Set<String> allImports = new java.util.LinkedHashSet<>();
            allImports.addAll(existingTestImports); // Add existing test imports first
            allImports.addAll(targetImports);
            allImports.addAll(frameworkImports);

            // 5. Find package declaration in test code
            java.util.regex.Pattern packagePattern = java.util.regex.Pattern.compile("^package\\s+[^;]+;", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher matcher = packagePattern.matcher(testCode);

            if (!matcher.find()) {
                LOG.warn("No package declaration found in test code");
                return testCode; // No package found, return as-is
            }

            String packageDeclaration = matcher.group();
            int packageEnd = matcher.end();

            // 6. Build import block
            String importBlock = allImports.stream()
                    .sorted()
                    .map(imp -> "import " + imp + ";")
                    .collect(java.util.stream.Collectors.joining("\n"));

            // 7. Remove any existing imports to avoid duplicates
            String before = testCode.substring(0, packageEnd);
            String after = testCode.substring(packageEnd);
            after = after.replaceAll("(?m)^import\\s+.*?;\\s*\n", "");

            String enhanced = before + "\n\n" + importBlock + "\n\n" + after.trim();
            LOG.info("Auto-enhanced test code with " + allImports.size() + " total imports");

            return enhanced;

        } catch (Exception e) {
            LOG.warn("Auto-enhance imports failed, continuing with original: " + e.getMessage(), e);
            return testCode;
        }
    }

    private java.util.List<String> extractImportsFromTargetClass(String targetClass,
                                                                 ContextAgent.ContextGatheringTools contextTools) {
        java.util.List<String> imports = new java.util.ArrayList<>();

        if (contextTools == null) {
            LOG.warn("No context tools available for import extraction");
            return imports;
        }

        // Get analyzed classes from context
        java.util.Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
        String targetClassCode = analyzedClasses.get(targetClass);

        if (targetClassCode == null || targetClassCode.isEmpty()) {
            LOG.warn("Target class not found in analyzed classes: " + targetClass);
            return imports;
        }

        // Extract import statements (both regular and static)
        java.util.regex.Pattern importPattern = java.util.regex.Pattern.compile(
                "^import\\s+(static\\s+)?([^;]+);",
                java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = importPattern.matcher(targetClassCode);

        while (matcher.find()) {
            String staticKeyword = matcher.group(1);
            String importPath = matcher.group(2).trim();
            if (!importPath.isEmpty()) {
                if (staticKeyword != null) {
                    imports.add("static " + importPath);
                } else {
                    imports.add(importPath);
                }
            }
        }

        LOG.info("Extracted " + imports.size() + " imports from " + targetClass);
        return imports;
    }

    private java.util.List<String> getCommonFrameworkImports(String framework) {
        java.util.List<String> imports = new java.util.ArrayList<>();

        if (framework == null) {
            LOG.warn("No framework specified, using JUnit 5 defaults");
            framework = "JUnit 5";
        }

        String lower = framework.toLowerCase();

        if (lower.contains("junit 5") || lower.contains("jupiter")) {
            imports.add("org.junit.jupiter.api.Test");
            imports.add("org.junit.jupiter.api.BeforeEach");
            imports.add("org.junit.jupiter.api.AfterEach");
            imports.add("static org.junit.jupiter.api.Assertions.*");
        } else if (lower.contains("junit 4") || (lower.contains("junit") && !lower.contains("5"))) {
            imports.add("org.junit.Test");
            imports.add("org.junit.Before");
            imports.add("org.junit.After");
            imports.add("static org.junit.Assert.*");
        } else if (lower.contains("testng")) {
            imports.add("org.testng.annotations.Test");
            imports.add("org.testng.annotations.BeforeMethod");
            imports.add("org.testng.annotations.AfterMethod");
            imports.add("static org.testng.Assert.*");
        }

        LOG.info("Added " + imports.size() + " common " + framework + " imports");
        return imports;
    }

    private java.util.List<String> extractImportsFromCode(String code) {
        java.util.List<String> imports = new java.util.ArrayList<>();

        java.util.regex.Pattern importPattern = java.util.regex.Pattern.compile(
                "^import\\s+(static\\s+)?([^;]+);",
                java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = importPattern.matcher(code);

        while (matcher.find()) {
            String staticKeyword = matcher.group(1);
            String importPath = matcher.group(2).trim();
            if (!importPath.isEmpty()) {
                if (staticKeyword != null) {
                    imports.add("static " + importPath);
                } else {
                    imports.add(importPath);
                }
            }
        }

        return imports;
    }

    /**
     * Data class for existing test information
     */
    public static class ExistingTestInfo {
        private final String code;
        private final String filePath;

        public ExistingTestInfo(String code, String filePath) {
            this.code = code;
            this.filePath = filePath;
        }

        public String getCode() { return code; }
        public String getFilePath() { return filePath; }
    }

    /**
     * PHASE 1: Find existing test class for target class (if any).
     * Returns null if no existing test found.
     */
    @Nullable
    public ExistingTestInfo findExistingTest(@NotNull String targetClass) {
        try {
            LOG.debug("Phase 1: Finding existing test for: " + targetClass);
            sendToUI("🔍 Phase 1: Searching for existing test class...\n");

            ExistingTestAnalyzer analyzer = new ExistingTestAnalyzer(project);
            ExistingTestAnalyzer.ExistingTestClass existingTest =
                com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                    (com.intellij.openapi.util.Computable<ExistingTestAnalyzer.ExistingTestClass>) () ->
                        analyzer.findExistingTestClass(targetClass)
                );

            if (existingTest != null) {
                String existingCode = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                    (com.intellij.openapi.util.Computable<String>) () ->
                        getSourceCodeFromPsiClass(existingTest.getPsiClass())
                );

                if (existingCode != null && !existingCode.isEmpty()) {
                    lastExistingTestCode = existingCode;
                    sendToUI("✅ Found existing test: " + existingTest.getFilePath() + "\n");

                    if (uiEventListener != null) {
                        uiEventListener.onTestCodeSet(targetClass + "Test", existingCode, true);
                    }

                    return new ExistingTestInfo(existingCode, existingTest.getFilePath());
                }
            }

            sendToUI("ℹ️ No existing test found\n");
            return null;

        } catch (Exception e) {
            LOG.warn("Error finding existing test for: " + targetClass, e);
            sendToUI("⚠️ Error finding existing test: " + e.getMessage() + "\n");
            return null;
        }
    }

    /**
     * PHASE 2: Merge new test with existing test using tools (AI only if needed).
     * Saves LLM call if no existing test found.
     */
    @NotNull
    public String mergeUsingTools(@Nullable ExistingTestInfo existing,
                                  @NotNull String newTestCode,
                                  @NotNull String className,
                                  @NotNull String targetClass,
                                  @Nullable ContextAgent.ContextGatheringTools contextTools) {
        try {
            // Initialize working code
            mergingTools.setNewTestCode(className, newTestCode);

            // If no existing test, skip merge (saves LLM call!)
            if (existing == null) {
                sendToUI("ℹ️ No existing test - skipping merge (LLM call saved)\n");
                if (uiEventListener != null) {
                    uiEventListener.onTestCodeSet(className, newTestCode, false);
                }
                return newTestCode;
            }

            // Existing test found - let AI merge using tools
            sendToUI("🤖 Merging with existing test using tools...\n");

            String mergePrompt = buildToolBasedMergePrompt(existing, newTestCode, targetClass, contextTools);
            assistant.mergeAndFixTestClass(wrapWithSystemPrompt(mergePrompt));

            String mergedCode = mergingTools.getCurrentTestCode();
            sendToUI("✅ Merge complete\n");

            return mergedCode;

        } catch (Exception e) {
            LOG.error("Error in merge phase", e);
            sendToUI("❌ Merge failed: " + e.getMessage() + "\n");
            throw new RuntimeException("Merge phase failed", e);
        }
    }

    /**
     * PHASE 5: Fix validation errors using chain-of-thought reasoning + exploration tools.
     */
    @NotNull
    public String fixUsingTools(@NotNull String className,
                               @NotNull java.util.List<String> errors,
                               @Nullable ContextAgent.ContextGatheringTools contextTools,
                               int maxAttempts) {
        try {
            sendToUI("\n🔧 Fixing validation errors with chain-of-thought reasoning...\n");
            sendToUI("Errors to fix: " + errors.size() + "\n");

            String fixPrompt = buildChainOfThoughtFixPrompt(errors, contextTools);

            // AI investigates and fixes using tools
            assistant.mergeAndFixTestClass(wrapWithSystemPrompt(fixPrompt));

            String fixedCode = mergingTools.getCurrentTestCode();
            sendToUI("✅ Fix phase complete\n");

            return fixedCode;

        } catch (Exception e) {
            LOG.error("Error in fix phase", e);
            sendToUI("❌ Fix failed: " + e.getMessage() + "\n");
            return mergingTools.getCurrentTestCode(); // Return current state
        }
    }

    /**
     * PHASE 7: Review for logic bugs using self-questioning + exploration.
     */
    @NotNull
    public String reviewLogicUsingTools(@NotNull String className,
                                       @Nullable ContextAgent.ContextGatheringTools contextTools) {
        try {
            sendToUI("\n📋 Reviewing for logic bugs with self-questioning...\n");

            String reviewPrompt = buildSelfQuestioningReviewPrompt(contextTools);

            // AI asks/answers questions and fixes logic bugs
            assistant.mergeAndFixTestClass(wrapWithSystemPrompt(reviewPrompt));

            String reviewedCode = mergingTools.getCurrentTestCode();
            sendToUI("✅ Logic review complete\n");

            return reviewedCode;

        } catch (Exception e) {
            LOG.warn("Error in logic review, accepting current code", e);
            sendToUI("⚠️ Logic review failed: " + e.getMessage() + "\n");
            return mergingTools.getCurrentTestCode();
        }
    }

    private String buildToolBasedMergePrompt(ExistingTestInfo existing, String newCode,
                                            String targetClass, ContextAgent.ContextGatheringTools contextTools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("MERGE TASK: Merge new test methods into existing test.\n\n");
        prompt.append("EXISTING TEST:\n```java\n").append(existing.getCode()).append("\n```\n\n");
        prompt.append("NEW METHODS TO ADD:\n```java\n").append(newCode).append("\n```\n\n");

        // Include context notes for informed merging
        if (contextTools != null) {
            java.util.List<String> notes = contextTools.getContextNotes();
            if (!notes.isEmpty()) {
                prompt.append("CONTEXT NOTES:\n");
                for (String note : notes) {
                    prompt.append("- ").append(note).append("\n");
                }
                prompt.append("\n");
            }
        }

        prompt.append("INSTRUCTIONS:\n");
        prompt.append("1. Use updateTestCode() to set the merged result\n");
        prompt.append("2. Preserve all existing test methods\n");
        prompt.append("3. Add only new tests that don't duplicate existing ones\n");
        prompt.append("4. Merge setup/teardown intelligently\n");
        prompt.append("5. Call markMergingDone() when complete\n");

        return prompt.toString();
    }

    private String buildChainOfThoughtFixPrompt(java.util.List<String> errors,
                                               ContextAgent.ContextGatheringTools contextTools) {
        StringBuilder prompt = new StringBuilder();

        // FIRST: Show dependencies BEFORE errors - this is critical context!
        if (contextTools != null) {
            java.util.List<String> notes = contextTools.getContextNotes();
            String depAnalysis = notes.stream()
                    .filter(note -> note.contains("[DEPENDENCY_ANALYSIS]"))
                    .findFirst()
                    .orElse(null);

            if (depAnalysis != null) {
                prompt.append("**AVAILABLE PROJECT DEPENDENCIES**\n");
                prompt.append("```\n");
                // Remove the [DEPENDENCY_ANALYSIS] marker for cleaner display
                String cleanAnalysis = depAnalysis.replace("[DEPENDENCY_ANALYSIS]", "").trim();
                prompt.append(cleanAnalysis);
                prompt.append("\n```\n\n");
                prompt.append("⚠️ CRITICAL: Only use dependencies listed above! Do not invent or assume libraries.\n\n");
            }
        }

        prompt.append("FIX VALIDATION ERRORS - Investigate and fix iteratively.\n\n");

        prompt.append("CURRENT TEST CODE:\n");
        prompt.append("```java\n");
        prompt.append(mergingTools.getCurrentTestCode());
        prompt.append("\n```\n\n");

        prompt.append("ERRORS TO FIX:\n");
        for (String error : errors) {
            prompt.append("- ").append(error).append("\n");
        }
        prompt.append("\n");

        prompt.append("CHAIN-OF-THOUGHT PROCESS (Include BOTH reasoning AND tool calls):\n\n");

        prompt.append("For EACH error, follow this narrative flow:\n\n");

        prompt.append("STEP 1 - Analyze:\n");
        prompt.append("  Explain: I see error at line X: [description]\n");
        prompt.append("  Explain: This is due to: [root cause - wrong signature/missing import/type mismatch]\n");
        prompt.append("  Explain: I need to: [what needs to be verified or fixed]\n\n");

        prompt.append("STEP 2 - Investigate (if something is unclear):\n");
        prompt.append("  Explain: However, I don't know [specific thing - e.g., correct method signature]\n");
        prompt.append("  Explain: I'll look up [what you need to find out] to verify\n");
        prompt.append("  Action: State your intent naturally, the framework will invoke the tool\n\n");

        prompt.append("STEP 3 - Interpret:\n");
        prompt.append("  Explain: The lookup shows: [actual result]\n");
        prompt.append("  Explain: This means: [what you learned and how it affects the fix]\n\n");

        prompt.append("STEP 4 - Fix:\n");
        prompt.append("  Explain: I'll fix this by: [describe the change you'll make]\n");
        prompt.append("  ⚠️ IMPORTANT: Include line numbers from validation error (e.g., line 29)\n");
        prompt.append("  Action: State your fix intent with line range naturally, the framework will apply it\n\n");

        prompt.append("STEP 5 - Confirm:\n");
        prompt.append("  Explain: Fix applied successfully. Moving to next error.\n\n");

        prompt.append("EXAMPLE FULL WORKFLOW for one error:\n\n");
        prompt.append("I see error at line 29: getInstance(ConnectionManager) cannot be applied\n");
        prompt.append("This is due to wrong parameter type being passed to getInstance\n");
        prompt.append("I need to verify what getInstance actually accepts\n\n");

        prompt.append("However, I don't know the correct signature for getInstance\n");
        prompt.append("I'll look up the method signature for getInstance in ProfileConnectionPool\n");
        prompt.append("Checking the actual parameters it expects now\n\n");

        prompt.append("The lookup shows: getInstance(ApplicationConfig config) - static method\n");
        prompt.append("This means the method expects ApplicationConfig, not ConnectionManager\n\n");

        prompt.append("I'll fix this by changing ConnectionManager to ApplicationConfig on line 29\n");
        prompt.append("Applying fix with line range: applySimpleFix from line 29 to 29\n");
        prompt.append("Replacing getInstance(connectionManager) with getInstance(applicationConfig) at line 29\n\n");

        prompt.append("Fix applied successfully. Moving to next error.\n\n");

        prompt.append("AVAILABLE TOOLS:\n");
        prompt.append("Investigation: Look up methods/classes, read files, search code patterns\n");
        prompt.append("Fixing: Add imports, apply exact/regex fixes, update full test code\n");
        prompt.append("Validation: Validate current test code for errors\n\n");

        prompt.append("REGEX FIX TIPS:\n");
        prompt.append("Use applyRegexFix when whitespace might differ:\n");
        prompt.append("- \\\\s+ for any whitespace (spaces/tabs/newlines)\n");
        prompt.append("- \\\\s* for optional whitespace\n");
        prompt.append("- Escape special chars: \\\\( \\\\) \\\\[ \\\\] \\\\{ \\\\}\n");
        prompt.append("Example: 'getUserById\\\\(\\\\s*123\\\\s*\\\\)' → matches varied spacing\n\n");

        prompt.append("🎯 LINE RANGE PARAMETERS (use to avoid non-unique errors):\n");
        prompt.append("Both applySimpleFix and applyRegexFix accept optional startLine/endLine:\n");
        prompt.append("- applySimpleFix(oldText, newText, startLine, endLine)\n");
        prompt.append("- applyRegexFix(pattern, replacement, startLine, endLine)\n\n");
        prompt.append("When to specify line ranges:\n");
        prompt.append("✅ ALWAYS when validation error shows specific line number\n");
        prompt.append("✅ When same pattern appears multiple times in test\n");
        prompt.append("✅ When you get 'Multiple occurrences found' error\n\n");
        prompt.append("Example: Error at line 45 → use applySimpleFix(..., ..., 45, 45)\n\n");

        prompt.append("After fixing all errors:\n");
        prompt.append("  Explain: All errors have been addressed, validating now\n");
        prompt.append("  Action: Validate the current test code\n");
        prompt.append("  If passed: Mark merging done with validation passed message\n");
        prompt.append("  If failed: Continue fixing (max 3 attempts)\n\n");

        prompt.append("KEY PRINCIPLE:\n");
        prompt.append("✅ Include BOTH your reasoning (explain) AND actions (state intent)\n");
        prompt.append("✅ Express intent naturally - framework handles tool invocation\n");
        prompt.append("✅ Always explain what you learned and what it means\n");
        prompt.append("✅ Maintain narrative flow: Explain → Act → Learn → Fix\n\n");

        prompt.append("PREVENT INFINITE LOOPS:\n\n");
        prompt.append("1. If you see 'Cannot resolve symbol SomeClass':\n");
        prompt.append("   → FIRST: Use findImportForClass('SomeClass') to verify it exists\n");
        prompt.append("   → If 'CLASS_NOT_FOUND': The class does NOT exist in this project\n");
        prompt.append("   → Don't try to fix imports or keep using it - it won't work!\n");
        prompt.append("   → Think: what similar classes exist? Look them up with findImportForClass()\n");
        prompt.append("   → Only use classes that findImportForClass() confirms exist\n\n");

        prompt.append("2. Max 2 attempts per unique error:\n");
        prompt.append("   → If applyRegexFix fails: switch to applySimpleFix with EXACT text from line numbers\n");
        prompt.append("   → If both fail twice: acknowledge limitation and move to next error\n");
        prompt.append("   → Don't retry the same failed approach 3+ times\n\n");

        prompt.append("3. When fix tools fail:\n");
        prompt.append("   → Read the error message carefully - it shows actual code with line numbers\n");
        prompt.append("   → Copy EXACT text from the numbered lines\n");
        prompt.append("   → If pattern doesn't match, the code might be different than expected\n");

        return prompt.toString();
    }

    private String buildSelfQuestioningReviewPrompt(ContextAgent.ContextGatheringTools contextTools) {
        StringBuilder prompt = new StringBuilder();

        // FIRST: Show dependencies prominently at the top!
        if (contextTools != null) {
            java.util.List<String> notes = contextTools.getContextNotes();
            String depAnalysis = notes.stream()
                    .filter(note -> note.contains("[DEPENDENCY_ANALYSIS]"))
                    .findFirst()
                    .orElse(null);

            if (depAnalysis != null) {
                prompt.append("**AVAILABLE PROJECT DEPENDENCIES**\n");
                prompt.append("```\n");
                String cleanAnalysis = depAnalysis.replace("[DEPENDENCY_ANALYSIS]", "").trim();
                prompt.append(cleanAnalysis);
                prompt.append("\n```\n\n");
                prompt.append("⚠️ CRITICAL: Only use dependencies listed above! Do not invent libraries.\n\n");
            }

            // Show OTHER context notes separately (non-dependency notes)
            java.util.List<String> otherNotes = notes.stream()
                    .filter(note -> !note.contains("[DEPENDENCY_ANALYSIS]"))
                    .toList();

            if (!otherNotes.isEmpty()) {
                prompt.append("📋 ADDITIONAL CONTEXT:\n");
                for (String note : otherNotes) {
                    prompt.append("- ").append(note).append("\n");
                }
                prompt.append("\n");
            }
        }

        prompt.append("LOGIC BUG REVIEW - Ask questions and investigate using tools.\n\n");

        prompt.append("LOGIC BUGS TO CHECK:\n");
        prompt.append("1. Wrong assertions (expected vs actual swapped)\n");
        prompt.append("2. Wrong framework/libraries (not actually in project)\n");
        prompt.append("3. Miscalculations or wrong test values\n");
        prompt.append("4. Incorrect mock behavior\n");
        prompt.append("5. Wrong method signatures or annotations\n");
        prompt.append("6. Classes that don't exist:\n");
        prompt.append("   → If you see 'Cannot resolve symbol SomeClass' in validation:\n");
        prompt.append("   → FIRST: Use findImportForClass('SomeClass') to check if it exists\n");
        prompt.append("   → If not found: Think what similar classes might exist in project\n");
        prompt.append("   → Look up alternatives with findImportForClass('AlternativeClass')\n");
        prompt.append("   → DON'T keep trying to import or use a class that doesn't exist\n");
        prompt.append("   → If truly no alternative: use different approach (mocks, stubs, or remove feature)\n\n");

        prompt.append("SELF-QUESTIONING PROCESS (Mix reasoning with tool usage):\n\n");

        prompt.append("For each potential issue, use this flow:\n\n");

        prompt.append("STEP 1 - Ask yourself:\n");
        prompt.append("  Write: I'm checking if [specific concern about the code]\n\n");

        prompt.append("STEP 2 - State what you'll do:\n");
        prompt.append("  Write: To verify this, I'll look up [what you want to find out]\n");
        prompt.append("  Action: Express intent naturally, framework handles invocation\n\n");

        prompt.append("STEP 3 - State what you learned:\n");
        prompt.append("  Write: The lookup shows: [result]\n");
        prompt.append("  Write: This means: [interpretation]\n\n");

        prompt.append("STEP 4 - Decide action:\n");
        prompt.append("  Write: This is [correct/incorrect], so I'll [fix it / leave it]\n");
        prompt.append("  If fixing: State fix intent naturally, framework handles it\n\n");

        prompt.append("EXAMPLE 1 - Checking method signature:\n\n");
        prompt.append("I'm checking if getInstance in ProfileConnectionPool has the correct signature\n\n");

        prompt.append("To verify this, I'll look up the method signature to see what parameters it actually takes\n");
        prompt.append("Looking up getInstance in com.zps.logaggregator.database.ProfileConnectionPool now\n\n");

        prompt.append("The lookup shows: getInstance(ApplicationConfig config) - static method\n");
        prompt.append("This means the test is using the wrong parameter type - it passes ConnectionManager but needs ApplicationConfig\n\n");

        prompt.append("This is incorrect, so I'll fix it by changing the parameter type\n");
        prompt.append("Replacing getInstance(connectionManager) with getInstance(applicationConfig)\n\n");

        prompt.append("EXAMPLE 2 - Checking class availability:\n\n");
        prompt.append("I'm checking if SomeExternalClass is actually in the project dependencies\n\n");

        prompt.append("To verify, I'll use findImportForClass to check if the class exists\n");
        prompt.append("Looking up: findImportForClass('SomeExternalClass')\n");
        prompt.append("Result shows: CLASS_NOT_FOUND - no class with that name exists\n");
        prompt.append("This means the class is not available in this project\n\n");

        prompt.append("This is incorrect, so I need to find an alternative that exists\n");
        prompt.append("Let me look up what similar classes exist with findImportForClass()\n\n");

        prompt.append("AVAILABLE TOOLS:\n");
        prompt.append("- lookupClass - Check if classes exist and get their structure\n");
        prompt.append("- lookupMethod - Get actual method signatures from classes\n");
        prompt.append("- readFile - Read source code files for context\n");
        prompt.append("- searchCode - Find usage examples in the codebase\n");
        prompt.append("- addImport - Add missing import statements to the test class\n");
        prompt.append("- applySimpleFix - Apply exact text replacements\n");
        prompt.append("- applyRegexFix - Apply fixes using regex (handles whitespace variations)\n\n");

        prompt.append("After reviewing all potential issues:\n");
        prompt.append("  If you made changes: Explain what you fixed, then validate the test code\n");
        prompt.append("  Always: Mark merging done with a summary of the logic review\n\n");

        prompt.append("BALANCE:\n");
        prompt.append("✅ Explain your thought process throughout\n");
        prompt.append("✅ State your intent naturally - framework invokes tools automatically\n");
        prompt.append("✅ Explain what you learned from lookups and what it means\n");
        prompt.append("✅ Keep a flowing narrative: Question → Investigate → Learn → Act\n");

        return prompt.toString();
    }

    private String getSourceCodeFromPsiClass(com.intellij.psi.PsiClass psiClass) {
        if (psiClass == null) return null;
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

    /**
     * Build simplified system prompt for FULL_REWRITE_ONLY strategy
     */
    private String buildFullRewritePrompt() {
        return """
        # Test Merger Agent - Full Rewrite Strategy

        ## 🚨 CRITICAL: Always Call markMergingDone()

        After completing your task, you **MUST** call `markMergingDone(reason)`.

        **When to call:**
        - ✅ Validation passed (0 errors) → `markMergingDone("Validation passed")`
        - ✅ Rewrote code, validated, all fixed → `markMergingDone("All errors fixed")`
        - ⚠️ Can't fix remaining errors → `markMergingDone("Unable to fix N errors: [reasons]")`

        **NO EXCEPTIONS** - The workflow waits for this signal.

        ---

        ## Workflow

        1. Receive validation errors and current test code
        2. Analyze ALL errors together - look for patterns
        3. Investigate unknowns (lookup methods/classes, read files)
        4. Write ONE complete fixed version with `updateTestCode()`
        5. Validate with `validateCurrentTestCode()`
        6. Call `recordMergedResult()` + `markMergingDone()`

        ---

        ## Your Task

        **Think holistically:**
        - Missing class (e.g., RedisContainer)? → Remove ALL usages
        - Wrong field access pattern? → Fix ALL occurrences
        - Import issues? → Fix ALL imports together
        - Method signature errors? → Fix ALL calls

        **ONE complete rewrite** - No incremental fixes.

        ---

        ## Tools Available

        ### Investigation
        - `lookupMethod(className, methodName)` - Get method signatures
        - `lookupClass(className)` - Get class structure
        - `findImportForClass(classNames)` - Batch lookup imports (comma-separated)
        - `readFile(path)` - Read source files
        - `searchCode(query, ...)` - Search codebase

        ### Fix
        - `updateTestCode(code)` - Replace entire test (use ONCE)
        - `validateCurrentTestCode()` - Validate after rewrite

        ### Completion
        - `recordMergedResult(pkg, file, methods, framework)` - Record result
        - `markMergingDone(reason)` - **MANDATORY** - Signal completion

        ---

        ## Anti-Loop Rules

        1. **Class not found?**
           - FIRST: `findImportForClass('ClassName')`
           - If `CLASS_NOT_FOUND` → Don't use it, find alternatives
           - Only use classes that `findImportForClass()` confirms exist

        2. **One rewrite only**
           - You get ONE chance to fix all errors
           - Make it count - analyze thoroughly before rewriting

        3. **Track progress**
           - Before rewrite: N errors
           - After rewrite + validation: X errors
           - If X > 0 → Explain why in `markMergingDone()`

        ---

        ## Example Flow

        ```
        I see 50 validation errors. Analyzing patterns:

        1. UserProfile field access (30 errors) - using getters but class has public fields
        2. RedisContainer not found (15 errors) - dependency missing
        3. assertThat ambiguous (5 errors) - need explicit cast

        Investigating:
        - Checking if RedisContainer exists... [findImportForClass]
        - Result: CLASS_NOT_FOUND
        - Checking UserProfile structure... [lookupClass]
        - Result: Has public fields userId, email, etc.

        Now rewriting complete test code with fixes:
        - Remove all RedisContainer usages
        - Change result.getUserId() → result.userId (all 30 occurrences)
        - Cast assertThat((Object) result)

        [Calls updateTestCode with complete fixed code]

        Validating... [validateCurrentTestCode]
        Result: VALIDATION_PASSED - 0 errors

        Recording result... [recordMergedResult]
        Marking done... [markMergingDone("Validation passed - all 50 errors fixed")]
        ```

        ---

        ## Completion Checklist

        Before finishing, ensure you:
        - [ ] Called `updateTestCode()` with complete fixed code
        - [ ] Called `validateCurrentTestCode()` to check results
        - [ ] Called `recordMergedResult()` with final details
        - [ ] Called `markMergingDone()` with clear reason

        **Always end with `markMergingDone()`** ✅
        """;
    }

    /**
     * Build full system prompt for TWO_PHASE strategy (current implementation)
     */
    private String buildTwoPhasePrompt() {
        return """
        You are an intelligent test merging coordinator using a TWO-PHASE FIX STRATEGY.

        **TWO-PHASE FIX STRATEGY**
        ```
        📊 Phase 1: BULK FIX (Full rewrite with complete understanding)
        📊 Phase 2: INCREMENTAL FIXES (Targeted surgical fixes)

        TOKEN OPTIMIZATION:
        - Phase 1: One complete rewrite
        - Phase 2: Small incremental fixes (saves ~4500 tokens vs another rewrite)
        - Report savings: "💰 Phase 2 saved ~X tokens"

        TOOL USAGE TRACKING (mandatory):
        📊 Report after each tool call: "🔧 Tool usage: [X/50] calls used"
        Be mindful of the 50 tool call limit per session
        ```

        **WORKFLOW OVERVIEW**
        ```

        NOTE: Test code is AUTO-INITIALIZED with imports from target class!
        → Imports from the class under test: ✅ Already added
        → Common framework imports (JUnit/TestNG): ✅ Already added

        1. Use findExistingTest(targetClass) to check for existing test class
        2. If existing test found, merge it with new test and use updateTestCode()
        3. If no existing test, the new test is already the final version - skip to step 4
        4. Validate the test code → enters TWO-PHASE FIX STRATEGY
        5. Call recordMergedResult() with final code
        6. Call markMergingDone() - THIS IS MANDATORY

        MERGING RULES:
        1. **Preserve Existing Tests**: Never remove or modify existing test methods
        2. **Avoid Duplicates**: Skip test methods that already exist (same method name)
        3. **Framework Consistency**: Use the same testing framework as existing tests
        4. **Import Management**: Merge imports intelligently (remove duplicates, keep all needed)
        5. **Code Style**: Match the existing code style and patterns
        6. **Setup/Teardown**: Consolidate @BeforeEach/@AfterEach methods intelligently
        ```

        **PHASE 1: BULK FIX - FULL REWRITE WITH AWARENESS**
        ```

        When you receive validation errors, this is PHASE 1.

        YOUR TASK:
        1. **Read and understand the ENTIRE test code** (use getCurrentTestCode())
        2. **Analyze ALL validation errors together** - look for patterns
        3. **Rewrite the complete test code** with all fixes applied
        4. Use updateTestCode() ONCE with the fully corrected code

        THINK HOLISTICALLY:
        - Missing class? (e.g., RedisContainer not found) → Remove all usages
        - Wrong field access pattern? (e.g., result.userId when should use getter) → Fix ALL occurrences
        - Import issues? → Review and fix ALL imports together
        - Ambiguous method calls? → Fix the pattern everywhere

        PHASE 1 RESPONSE FORMAT:

        ```
        📊 PHASE 1: BULK FIX - Analyzing all [N] errors

        Pattern Analysis:
        1. [Error category] ([count] errors - lines X-Y)
           Root cause: [explanation]
           Fix: [what you'll do globally]

        2. [Error category] ([count] errors - lines X-Y)
           Root cause: [explanation]
           Fix: [what you'll do globally]

        [Continue for all error patterns]

        Now performing complete rewrite with all fixes applied...
        ```

        Then call: updateTestCode(entirelyFixedCode)

        ⚠️ PHASE 1 RULES:
        - ❌ Don't call applySimpleFix() or applyRegexFix() in Phase 1
        - ❌ Don't try to "incrementally" fix during Phase 1
        - ✅ Make ONE complete rewrite with your full understanding
        - ✅ This gives you complete awareness of the test structure

        PHASE 1 TOOLS ALLOWED:
        • ✅ lookupMethod() - Understand method signatures
        • ✅ lookupClass() - Understand class structure
        • ✅ findImportForClass() - Batch check imports
        • ✅ readFile() - Read source files for context
        • ✅ searchCode() - Find examples in codebase
        • ✅ updateTestCode() - **The ONE rewrite call**
        • ❌ applySimpleFix() - NOT in Phase 1
        • ❌ applyRegexFix() - NOT in Phase 1
        ```

        **PHASE 2: INCREMENTAL FIXES - SURGICAL PRECISION**
        ```

        After Phase 1 rewrite, validate again. If errors remain, this is PHASE 2.

        YOUR TASK:
        - Call validateCurrentTestCode() to get FRESH errors
        - For each error, use applySimpleFix() or applyRegexFix()
        - Re-validate after EVERY 3-5 fixes to get updated line numbers

        ⚠️ LINE NUMBER FRESHNESS:
        CRITICAL: Line numbers become STALE after each fix!

        After each fix:
        - Old: Error at line 45
        - After your fix: That code might now be at line 47!
        - Solution: Re-validate every 3-5 fixes

        CIRCUIT BREAKER:
        Max 2 attempts per unique error:
        - Attempt 1 fails? Try different approach
        - Attempt 2 fails? SKIP and move to next error
        - Report: "⚠️ Skipped error at line X after 2 attempts"

        PROGRESS TRACKING:
        After each validation:
        - Previous: 50 errors
        - Current: 35 errors
        - Progress: ✅ Fixed 15 errors

        OR if errors increased:
        - Current: 55 errors
        - Progress: ❌ FIXES MAKING IT WORSE - STOP AND ANALYZE

        PHASE 2 RESPONSE FORMAT:

        ```
        📊 PHASE 2: INCREMENTAL FIXES - [N] errors remaining

        Error 1 at line X: [description]
        Root cause: [explanation]
        Investigating: [what you'll look up]
        [Tool call]
        Found: [result]
        Fixing: [what you'll change]
        [applySimpleFix call]
        ✅ Fixed

        [After 3-5 fixes:]
        Re-validating to get fresh line numbers...
        [validateCurrentTestCode call]
        Progress: [old count] → [new count] errors

        [Continue...]
        ```

        PHASE 2 TOOLS ALLOWED:
        • ✅ validateCurrentTestCode() - Get fresh errors
        • ✅ applySimpleFix() - Targeted fixes with line ranges
        • ✅ applyRegexFix() - Pattern-based fixes
        • ✅ lookupMethod() - If still need clarification
        • ✅ lookupClass() - If still need clarification
        • ✅ addImport() - Add missing imports
        • ❌ updateTestCode() - Use only if Phase 2 completely fails

        🎯 LINE RANGE TARGETING (CRITICAL):
        Both applySimpleFix and applyRegexFix support optional startLine/endLine parameters.

        When to use line ranges:
        • ✅ ALWAYS specify line ranges when validation shows line numbers
        • ✅ Use when fixing multiple similar errors on different lines
        • ✅ Use when the same code pattern appears multiple times

        Example with line range:
        applySimpleFix("getUserId()", "getUserId(123L)", 45, 45)  // Only line 45
        applyRegexFix("UserService\\s*userService", "UserService userService", 30, 35)  // Lines 30-35

        ⚠️ If you get "Multiple occurrences found" error:
        1. Check the error message - it shows line numbers for each occurrence
        2. Specify startLine and endLine to target the exact line from validation output
        3. The line numbers match validation error output (1-based)
        ```

        **PREVENT INFINITE LOOPS**
        ```

        1. If you see 'Cannot resolve symbol SomeClass':
           → FIRST: Use findImportForClass('SomeClass') to verify it exists
           → If 'CLASS_NOT_FOUND': The class does NOT exist in this project
           → Don't try to fix imports or keep using it - it won't work!
           → Think: what similar classes exist? Look them up with findImportForClass()
           → Only use classes that findImportForClass() confirms exist

        2. Max 2 attempts per unique error:
           → If applyRegexFix fails: switch to applySimpleFix with EXACT text from line numbers
           → If both fail twice: acknowledge limitation and move to next error
           → Don't retry the same failed approach 3+ times

        3. When fix tools fail:
           → Read the error message carefully - it shows actual code with line numbers
           → Copy EXACT text from the numbered lines
           → If pattern doesn't match, the code might be different than expected

        4. Track your progress:
           → If error count doesn't decrease after 5 fixes: STOP
           → If error count INCREASES: Your fixes are corrupting the code - STOP
           → Call getCurrentTestCode() to see actual state
           → If code is corrupted, acknowledge and use updateTestCode() to restore
        ```

        **CODE QUALITY REVIEW (After validation passes)**
        ```

        Once VALIDATION_PASSED:
        1. Call reviewTestQuality() - returns structured feedback
        2. For EACH issue found:
           - Analyze the problem
           - Investigate if needed (use lookupMethod/lookupClass)
           - Apply fix using applySimpleFix()
        3. Priority: CRITICAL (wrong logic) → MAJOR (quality) → MINOR (style)
        4. Max 3 review fix rounds
        5. Re-validate after review fixes
        ```

        **COMPLETION**
        ```

        ✅ SUCCESS:
        - Phase 1 reduces errors by >80%
        - Phase 2 fixes remaining errors or hits max attempts
        - Call recordMergedResult() and markMergingDone()

        ⚠️ PARTIAL SUCCESS:
        - Some errors skipped after max attempts
        - Report which errors remain
        - Call markMergingDone("Completed with N unresolved errors")

        ❌ FAILURE:
        - Phase 1 doesn't reduce errors significantly
        - Phase 2 increases error count
        - Acknowledge limitation
        - Call markMergingDone("Unable to resolve validation errors")

        ALWAYS call markMergingDone() - THIS IS MANDATORY
        ```
        """;
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
                String fixedCode = assistant.mergeAndFixTestClass(wrapWithSystemPrompt(fixRequest));
                
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
        private ContextAgent.ContextGatheringTools contextTools; // Context from analysis phase
        private String currentWorkingTestCode = null; // Maintains the current version of test code being worked on
        private String currentTestClassName = null; // The class name of the current test
        private boolean mergingComplete = false; // Track if merging is complete
        private final java.util.Set<String> suppressionPatterns = new java.util.HashSet<>(); // Patterns to suppress in validation
        private final com.zps.zest.testgen.tools.LookupMethodTool lookupMethodTool; // Delegate to full implementation
        private final com.zps.zest.testgen.tools.LookupClassTool lookupClassTool; // Delegate to full implementation

        public TestMergingTools(@NotNull Project project,
                               @Nullable java.util.function.Consumer<String> toolNotifier,
                               @Nullable ContextAgent.ContextGatheringTools contextTools) {
            this.project = project;
            this.existingTestAnalyzer = new ExistingTestAnalyzer(project);
            this.toolNotifier = toolNotifier;
            this.contextTools = contextTools;
            this.lookupMethodTool = new com.zps.zest.testgen.tools.LookupMethodTool(project);
            this.lookupClassTool = new com.zps.zest.testgen.tools.LookupClassTool(project);
        }

        public void setContextTools(ContextAgent.ContextGatheringTools contextTools) {
            this.contextTools = contextTools;
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
                    issues = future.get(300, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    LOG.warn("Validation timeout after 5 minutes", e);
                    return "VALIDATION_SKIPPED: Timeout after 5 minutes";
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

                // Format remaining issues with Rust-like code context
                java.util.List<String> issueDescriptions = new java.util.ArrayList<>();
                StringBuilder result = new StringBuilder("VALIDATION_FAILED:\n\n");

                String[] codeLines = testCode.split("\n");

                for (CodeSmellInfo issue : filteredIssues) {
                    int lineNum = issue.getStartLine();
                    String errorMsg = issue.getDescription();

                    // Build Rust-like error with code context
                    StringBuilder errorBlock = new StringBuilder();
                    errorBlock.append("Error at Line ").append(lineNum).append(":\n");

                    // Show 2 lines before, error line, 2 lines after
                    int startLine = Math.max(0, lineNum - 3);  // -3 because lineNum is 1-based
                    int endLine = Math.min(codeLines.length, lineNum + 2);

                    for (int i = startLine; i < endLine; i++) {
                        String linePrefix = (i == lineNum - 1) ? " →  " : "    ";  // Arrow for error line
                        errorBlock.append(String.format("%s%4d | %s\n", linePrefix, i + 1, codeLines[i]));
                    }

                    // Add error description with indentation
                    errorBlock.append("         |\n");
                    errorBlock.append("         | ").append(errorMsg).append("\n\n");

                    result.append(errorBlock.toString());

                    // Also add simple description for issueDescriptions list
                    String simpleDesc = String.format("Line %d: %s", lineNum, errorMsg);
                    issueDescriptions.add(simpleDesc);
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

        @Tool("Apply simple text replacement to fix code issue in current test. " +
              "IMPORTANT: If text appears multiple times, you MUST specify startLine and endLine to target the exact occurrence. " +
              "Line numbers match validation error output (1-based). " +
              "Example: applySimpleFix('getUserId()', 'getUserId(123L)', 45, 45) - replaces only on line 45")
        public String applySimpleFix(
                @P("Exact text to find and replace (must match exactly including whitespace)") String oldText,
                @P("New text to replace with") String newText,
                @P("Optional: Start line number (1-based, inclusive). Specify to avoid non-unique match errors.") Integer startLine,
                @P("Optional: End line number (1-based, inclusive). Specify to avoid non-unique match errors.") Integer endLine) {
            if (currentWorkingTestCode == null) {
                return "ERROR: No current test code to fix. Use setNewTestCode first.";
            }
            notifyTool("applySimpleFix", startLine != null ? "Lines " + startLine + "-" + endLine : "Replacing text");

            try {
                String testCode = currentWorkingTestCode;
                String searchScope = testCode;
                int scopeStartOffset = 0;

                // If line range specified, limit search scope
                if (startLine != null && endLine != null) {
                    LineRangeExtraction extraction = extractLineRange(testCode, startLine, endLine);
                    if (extraction == null) {
                        return "ERROR: Invalid line range. startLine=" + startLine + ", endLine=" + endLine +
                               ". Total lines: " + testCode.split("\n").length;
                    }
                    searchScope = extraction.text;
                    scopeStartOffset = extraction.startOffset;
                }

                // Check if text exists in scope
                if (!searchScope.contains(oldText)) {
                    if (startLine != null) {
                        StringBuilder error = new StringBuilder();
                        error.append("ERROR: Text not found in lines ").append(startLine).append("-").append(endLine).append(".\n\n");

                        // Show actual code with line numbers
                        error.append("📄 Actual code with line numbers:\n");
                        String[] scopeLines = searchScope.split("\n", -1);
                        for (int i = 0; i < scopeLines.length; i++) {
                            int lineNum = startLine + i;
                            error.append(String.format("%5d | %s\n", lineNum, scopeLines[i]));
                        }

                        error.append("\n💡 Suggestions:\n");
                        error.append("1. Line numbers may have changed - code was modified by previous fixes\n");
                        error.append("2. Try LARGER scope if text spans more lines (e.g., lines ")
                             .append(Math.max(1, startLine - 3)).append("-").append(endLine + 3).append(")\n");
                        error.append("3. Copy exact text from the line numbers shown above\n");
                        error.append("4. If code looks completely different, skip this fix and move to next error\n");
                        return error.toString();
                    }
                    return "ERROR: Text not found. Check exact match including whitespace.";
                }

                // Check for uniqueness within scope
                int firstIndex = searchScope.indexOf(oldText);
                int lastIndex = searchScope.lastIndexOf(oldText);
                if (firstIndex != lastIndex) {
                    int occurrences = countOccurrences(searchScope, oldText);
                    if (startLine != null) {
                        return "ERROR: Multiple occurrences (" + occurrences + ") found even within lines " + startLine + "-" + endLine +
                               ". Narrow the line range or include more surrounding context in oldText.";
                    }
                    return "ERROR: Multiple occurrences (" + occurrences + ") found in file. " +
                           "Use startLine and endLine parameters to specify which occurrence to replace.\n" +
                           "Example: applySimpleFix(\"" + oldText.substring(0, Math.min(30, oldText.length())) + "...\", \"...\", 45, 50)";
                }

                // Calculate actual offsets for replacement
                int replaceStartOffset = scopeStartOffset + firstIndex;
                int replaceEndOffset = replaceStartOffset + oldText.length();

                // Apply the fix to the current working code
                currentWorkingTestCode = testCode.substring(0, replaceStartOffset) +
                                        newText +
                                        testCode.substring(replaceEndOffset);

                // Fire UI event for live fix display
                if (uiEventListener != null) {
                    Integer lineNumber = findLineNumber(testCode, oldText);
                    uiEventListener.onFixApplied(oldText, newText, lineNumber);

                    // Also update the full test code display
                    if (currentTestClassName != null) {
                        uiEventListener.onTestCodeUpdated(currentTestClassName, currentWorkingTestCode);
                    }
                }

                String rangeInfo = startLine != null ? " (lines " + startLine + "-" + endLine + ")" : "";
                return "SUCCESS: Replacement applied to current test code" + rangeInfo + ".";

            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        private int countOccurrences(String text, String pattern) {
            int count = 0;
            int index = 0;
            while ((index = text.indexOf(pattern, index)) != -1) {
                count++;
                index += pattern.length();
            }
            return count;
        }

        @Tool("Apply fix using regex pattern for flexible whitespace matching. " +
              "Use \\\\s+ for any whitespace, \\\\s* for optional whitespace. " +
              "Escape special regex chars: \\\\( \\\\) \\\\[ \\\\] \\\\{ \\\\} \\\\. \\\\* \\\\+ \\\\? " +
              "IMPORTANT: If pattern matches multiple times, specify startLine and endLine to target exact occurrence. " +
              "Example: applyRegexFix('getUserById\\\\(\\\\s*123\\\\s*\\\\)', 'getUserById(123L)', 45, 45)")
        public String applyRegexFix(
                @P("Regex pattern to match (use \\\\s+ for whitespace, escape special chars)") String regexPattern,
                @P("Replacement text (can use $1, $2 for capture groups)") String replacement,
                @P("Optional: Start line number (1-based, inclusive). Specify to avoid non-unique match errors.") Integer startLine,
                @P("Optional: End line number (1-based, inclusive). Specify to avoid non-unique match errors.") Integer endLine) {
            if (currentWorkingTestCode == null) {
                return "ERROR: No current test code to fix. Use setNewTestCode first.";
            }
            notifyTool("applyRegexFix", startLine != null ? "Lines " + startLine + "-" + endLine : "Pattern: " + regexPattern.substring(0, Math.min(regexPattern.length(), 50)));

            try {
                String testCode = currentWorkingTestCode;
                String searchScope = testCode;
                int scopeStartOffset = 0;

                // If line range specified, limit search scope
                if (startLine != null && endLine != null) {
                    LineRangeExtraction extraction = extractLineRange(testCode, startLine, endLine);
                    if (extraction == null) {
                        return "ERROR: Invalid line range. startLine=" + startLine + ", endLine=" + endLine +
                               ". Total lines: " + testCode.split("\n").length;
                    }
                    searchScope = extraction.text;
                    scopeStartOffset = extraction.startOffset;
                }

                // Compile the regex pattern
                Pattern pattern;
                try {
                    pattern = Pattern.compile(regexPattern);
                } catch (PatternSyntaxException e) {
                    return "ERROR: Invalid regex pattern - " + e.getMessage();
                }

                // Find all matches in scope
                Matcher matcher = pattern.matcher(searchScope);
                java.util.List<String> matches = new java.util.ArrayList<>();
                java.util.List<Integer> positions = new java.util.ArrayList<>();

                while (matcher.find()) {
                    matches.add(matcher.group());
                    positions.add(matcher.start());
                }

                // Check match count
                if (matches.isEmpty()) {
                    if (startLine != null) {
                        StringBuilder error = new StringBuilder();
                        error.append("ERROR: Pattern not found in lines ").append(startLine).append("-").append(endLine).append(".\n\n");

                        // Show actual code with line numbers
                        error.append("📄 Actual code with line numbers:\n");
                        String[] scopeLines = searchScope.split("\n", -1);
                        for (int i = 0; i < scopeLines.length; i++) {
                            int lineNum = startLine + i;
                            error.append(String.format("%5d | %s\n", lineNum, scopeLines[i]));
                        }

                        error.append("\n💡 Suggestions:\n");
                        error.append("1. Line numbers may have changed - code was modified by previous fixes\n");
                        error.append("2. Try LARGER scope if pattern spans more lines (e.g., lines ")
                             .append(Math.max(1, startLine - 3)).append("-").append(endLine + 3).append(")\n");
                        error.append("3. Use applySimpleFix() with exact text from line numbers above\n");
                        error.append("4. If code looks completely different, skip this fix and move to next error\n");

                        return error.toString();
                    }
                    return "ERROR: Pattern not found in test code. Check regex syntax and escaping.";
                }

                if (matches.size() > 1) {
                    StringBuilder error = new StringBuilder("ERROR: Multiple matches (" + matches.size() + ") found");
                    if (startLine != null) {
                        error.append(" even within lines ").append(startLine).append("-").append(endLine);
                        error.append(". Make pattern more specific or narrow the line range.\n");
                    } else {
                        error.append(" in file. Use startLine and endLine parameters to specify which occurrence.\n");
                    }
                    error.append("Matches found:\n");
                    for (int i = 0; i < Math.min(matches.size(), 5); i++) {
                        Integer lineNum = findLineNumber(testCode, matches.get(i));
                        error.append("  - Line ").append(lineNum != null ? lineNum : "?")
                             .append(": ").append(matches.get(i).substring(0, Math.min(matches.get(i).length(), 50)))
                             .append("\n");
                    }
                    if (startLine == null) {
                        error.append("Example: applyRegexFix(\"").append(regexPattern.substring(0, Math.min(30, regexPattern.length())))
                             .append("...\", \"...\", ").append(findLineNumber(testCode, matches.get(0))).append(", ")
                             .append(findLineNumber(testCode, matches.get(0))).append(")");
                    }
                    return error.toString();
                }

                // Single match found - apply replacement
                String matchedText = matches.get(0);
                int matchStartInScope = positions.get(0);

                // Calculate actual offset in full test code
                int actualMatchStart = scopeStartOffset + matchStartInScope;

                // Apply replacement at the specific location
                matcher = pattern.matcher(testCode);
                StringBuilder result = new StringBuilder();
                int lastEnd = 0;
                boolean replaced = false;

                while (matcher.find()) {
                    if (matcher.start() == actualMatchStart) {
                        result.append(testCode, lastEnd, matcher.start());
                        result.append(matcher.replaceFirst(replacement));
                        lastEnd = matcher.end();
                        replaced = true;
                        break;
                    }
                }
                if (replaced) {
                    result.append(testCode.substring(lastEnd));
                    currentWorkingTestCode = result.toString();
                } else {
                    // Fallback to simple replaceFirst if offset matching fails
                    currentWorkingTestCode = searchScope.replaceFirst(regexPattern, replacement);
                    currentWorkingTestCode = testCode.substring(0, scopeStartOffset) +
                                            currentWorkingTestCode +
                                            testCode.substring(scopeStartOffset + searchScope.length());
                }

                // Fire UI event for live fix display
                if (uiEventListener != null) {
                    Integer lineNumber = findLineNumber(testCode, matchedText);
                    uiEventListener.onFixApplied(matchedText, replacement, lineNumber);

                    // Also update the full test code display
                    if (currentTestClassName != null) {
                        uiEventListener.onTestCodeUpdated(currentTestClassName, currentWorkingTestCode);
                    }
                }

                String rangeInfo = startLine != null ? " (lines " + startLine + "-" + endLine + ")" : "";
                return "SUCCESS: Regex replacement applied" + rangeInfo + ". Matched: " +
                       matchedText.substring(0, Math.min(matchedText.length(), 100));

            } catch (Exception e) {
                LOG.warn("Error applying regex fix", e);
                return "ERROR: " + e.getMessage();
            }
        }

        @Tool("Add an import statement to the current test class. " +
              "Handles duplicates automatically and inserts in proper location. " +
              "Examples: 'java.util.List', 'static org.junit.Assert.*', 'com.example.MyClass'")
        public String addImport(String importStatement) {
            if (currentWorkingTestCode == null || currentTestClassName == null) {
                return "ERROR: No current test code. Use setNewTestCode first.";
            }
            notifyTool("addImport", importStatement);

            try {
                String testCode = currentWorkingTestCode;

                // Normalize the import statement
                String normalizedImport = importStatement.trim();

                // Remove "import" keyword if present
                if (normalizedImport.startsWith("import ")) {
                    normalizedImport = normalizedImport.substring(7).trim();
                }

                // Remove semicolon if present
                if (normalizedImport.endsWith(";")) {
                    normalizedImport = normalizedImport.substring(0, normalizedImport.length() - 1).trim();
                }

                // Build the full import statement
                String fullImport = "import " + normalizedImport + ";";

                // Check if import already exists
                if (testCode.contains(fullImport)) {
                    return "ALREADY_EXISTS: Import already present in test code.";
                }

                // Find the location to insert (after package, before class)
                java.util.regex.Pattern packagePattern = java.util.regex.Pattern.compile(
                    "^package\\s+[^;]+;",
                    java.util.regex.Pattern.MULTILINE
                );
                java.util.regex.Matcher matcher = packagePattern.matcher(testCode);

                if (!matcher.find()) {
                    return "ERROR: No package declaration found in test code.";
                }

                int insertPosition = matcher.end();

                // Find existing imports to insert after them
                java.util.regex.Pattern importPattern = java.util.regex.Pattern.compile(
                    "^import\\s+(?:static\\s+)?[^;]+;",
                    java.util.regex.Pattern.MULTILINE
                );
                matcher = importPattern.matcher(testCode);

                int lastImportEnd = insertPosition;
                while (matcher.find(lastImportEnd)) {
                    lastImportEnd = matcher.end();
                }

                // Insert the new import
                String before = testCode.substring(0, lastImportEnd);
                String after = testCode.substring(lastImportEnd);

                // Add newline if needed
                if (!before.endsWith("\n")) {
                    before += "\n";
                }

                currentWorkingTestCode = before + fullImport + "\n" + after;

                // Fire UI event for live display
                if (uiEventListener != null && currentTestClassName != null) {
                    uiEventListener.onTestCodeUpdated(currentTestClassName, currentWorkingTestCode);
                }

                return "SUCCESS: Import added - " + fullImport;

            } catch (Exception e) {
                LOG.warn("Error adding import: " + importStatement, e);
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
            return lookupMethodTool.lookupMethod(className, methodName);
        }

        @Tool("Look up class structure using fully qualified class name to get class signature, fields, methods, and inner classes from project or library classes")
        public String lookupClass(String className) {
            notifyTool("lookupClass", className);
            return lookupClassTool.lookupClass(className);
        }

        @Tool("Perform LLM-powered code review of the current test code. " +
              "Reviews for: logical mistakes, code cleanliness, test quality, proper assertions, edge cases, anti-patterns. " +
              "Returns structured review findings with severity levels. " +
              "Use this AFTER validation passes but BEFORE marking as done to ensure high quality tests.")
        public String reviewTestQuality() {
            if (currentWorkingTestCode == null || currentTestClassName == null) {
                return "ERROR: No current test code to review. Use setNewTestCode first.";
            }
            notifyTool("reviewTestQuality", currentTestClassName);

            try {
                // Build comprehensive review prompt
                String reviewPrompt = buildTestReviewPrompt(currentTestClassName, currentWorkingTestCode);

                // Use NaiveLLMService for the review (faster, separate from main agent flow)
                String reviewResult = naiveLlmService.query(reviewPrompt, ChatboxUtilities.EnumUsage.AGENT_TEST_MERGER);

                return reviewResult;

            } catch (Exception e) {
                LOG.warn("Test quality review failed", e);
                return "REVIEW_SKIPPED: " + e.getMessage();
            }
        }

        private String buildTestReviewPrompt(String testClassName, String testCode) {
            return """
                You are a senior Java developer reviewing a generated test class for quality issues.

                Review the following test code for:

                1. **LOGICAL MISTAKES**:
                   - Incorrect assertions (testing wrong values)
                   - Wrong expected vs actual in assertions
                   - Missing or redundant assertions
                   - Incorrect mock behavior setup
                   - Logic errors in test scenarios

                2. **CODE CLEANLINESS**:
                   - Unclear or misleading test names
                   - Duplicate code that should be extracted
                   - Magic numbers/strings without explanation
                   - Poor readability
                   - Missing Given-When-Then structure

                3. **TEST QUALITY**:
                   - Missing edge cases
                   - Missing error/exception testing
                   - Incomplete test coverage of method behavior
                   - Missing boundary conditions
                   - Over-mocking (should use real objects)

                4. **ANTI-PATTERNS**:
                   - Testing multiple scenarios in one test
                   - Fragile tests (dependent on execution order)
                   - Unclear test intent
                   - Testing implementation details instead of behavior
                   - Missing test isolation

                TEST CLASS: %s

                ```java
                %s
                ```

                Respond in this EXACT format:

                REVIEW_RESULT: [PASS | NEEDS_IMPROVEMENT]

                ISSUES_FOUND: [number]

                [For each issue found:]

                📋 Issue #[N]: [Brief title]
                Severity: [CRITICAL | MAJOR | MINOR]
                Category: [LOGICAL | CLEANLINESS | QUALITY | ANTI_PATTERN]

                Location: Line [X] or [method name]

                Problem:
                [Detailed explanation of what's wrong and why it's a problem]

                Recommendation:
                [Specific suggestion on how to fix it]

                Example Fix:
                ```java
                [Show the corrected code snippet if applicable]
                ```

                ---

                OVERALL_ASSESSMENT:
                [Summary of test quality and key improvements needed]

                If no issues found, respond:
                REVIEW_RESULT: PASS
                ISSUES_FOUND: 0
                OVERALL_ASSESSMENT: Test code is clean, logical, and follows best practices.
                """.formatted(testClassName, testCode);
        }

        @Tool("Find correct fully qualified names (import paths) for simple class names. " +
              "Accepts multiple classes at once for batch lookup (up to 10 classes). " +
              "Use this BEFORE suppressing 'Cannot resolve symbol' errors to verify if classes exist. " +
              "Examples: " +
              "- findImportForClass('RedisConfig') - single class" +
              "- findImportForClass('RedisConfig, UserService, ProductRepository') - multiple classes (comma-separated)")
        public String findImportForClass(String simpleClassNames) {
            // Parse comma-separated class names
            String[] classNames = simpleClassNames.split(",");

            // Trim whitespace
            for (int i = 0; i < classNames.length; i++) {
                classNames[i] = classNames[i].trim();
            }

            // Limit to 10 classes to prevent abuse
            if (classNames.length > 10) {
                return "ERROR: Too many classes requested (" + classNames.length + "). Maximum is 10 per batch.";
            }

            notifyTool("findImportForClass", classNames.length + " class(es): " + simpleClassNames);

            // Run in background thread with read action to avoid blocking EDT
            try {
                return com.intellij.openapi.application.ApplicationManager.getApplication()
                    .executeOnPooledThread(() ->
                        com.intellij.openapi.application.ReadAction.compute(() ->
                            findImportForClassesInternal(classNames)
                        )
                    ).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        private String findImportForClassesInternal(String[] classNames) {
            try {
                StringBuilder result = new StringBuilder();
                com.intellij.psi.search.PsiShortNamesCache cache =
                    com.intellij.psi.search.PsiShortNamesCache.getInstance(project);

                result.append("📋 BATCH LOOKUP RESULTS (").append(classNames.length).append(" class(es)):\n\n");

                for (String simpleClassName : classNames) {
                    result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    result.append("🔍 Searching for: ").append(simpleClassName).append("\n\n");

                    com.intellij.psi.PsiClass[] classes = cache.getClassesByName(
                        simpleClassName,
                        com.intellij.psi.search.GlobalSearchScope.allScope(project)
                    );

                    if (classes.length == 0) {
                        result.append("❌ CLASS_NOT_FOUND\n");
                        result.append("   No class named '").append(simpleClassName).append("' found.\n");
                        result.append("   → This is likely a missing dependency or typo.\n");
                        result.append("   → Consider suppressing this error.\n\n");
                        continue;
                    }

                    if (classes.length == 1) {
                        com.intellij.psi.PsiClass psiClass = classes[0];
                        String qualifiedName = psiClass.getQualifiedName();

                        // Check if it's from a library or project
                        String location = "project";
                        if (psiClass.getContainingFile() != null) {
                            String filePath = psiClass.getContainingFile().getVirtualFile().getPath();
                            if (filePath.contains(".jar") || filePath.contains(".class")) {
                                location = "library";
                            }
                        }

                        result.append("✅ FOUND\n");
                        result.append("   FQN: ").append(qualifiedName).append("\n");
                        result.append("   Location: ").append(location).append("\n");
                        result.append("   → Add import: import ").append(qualifiedName).append(";\n\n");
                        continue;
                    }

                    // Multiple matches
                    result.append("⚠️  MULTIPLE_MATCHES (").append(classes.length).append(" found)\n");
                    for (int i = 0; i < classes.length; i++) {
                        com.intellij.psi.PsiClass psiClass = classes[i];
                        String qualifiedName = psiClass.getQualifiedName();

                        result.append("   ").append((i + 1)).append(". ").append(qualifiedName);

                        // Add context about location
                        if (psiClass.getContainingFile() != null) {
                            String filePath = psiClass.getContainingFile().getVirtualFile().getPath();
                            if (filePath.contains(".jar")) {
                                result.append(" [library]");
                            } else {
                                result.append(" [project]");
                            }
                        }
                        result.append("\n");
                    }
                    result.append("   → Choose the correct one based on context.\n\n");
                }

                result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                result.append("Summary: ").append(classNames.length).append(" class(es) looked up\n");

                return result.toString();

            } catch (Exception e) {
                LOG.error("Error in batch class lookup", e);
                return "ERROR: " + e.getMessage();
            }
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

        /**
         * Extract a substring from code based on line range.
         * Line numbers are 1-based (matching validation output).
         *
         * @return LineRangeExtraction with the extracted text and offset information
         */
        private LineRangeExtraction extractLineRange(String code, int startLine, int endLine) {
            String[] lines = code.split("\n", -1);

            if (startLine < 1 || endLine < startLine || startLine > lines.length) {
                return null;
            }

            // Calculate start offset (sum of all line lengths before startLine)
            int startOffset = 0;
            for (int i = 0; i < startLine - 1 && i < lines.length; i++) {
                startOffset += lines[i].length() + 1; // +1 for newline
            }

            // Extract lines in range
            StringBuilder extracted = new StringBuilder();
            int actualEndLine = Math.min(endLine, lines.length);
            for (int i = startLine - 1; i < actualEndLine; i++) {
                extracted.append(lines[i]);
                if (i < actualEndLine - 1) {
                    extracted.append("\n");
                }
            }

            // Calculate end offset
            int endOffset = startOffset + extracted.length();

            return new LineRangeExtraction(extracted.toString(), startOffset, endOffset, startLine, actualEndLine);
        }

        /**
         * Data class for line range extraction results
         */
        private static class LineRangeExtraction {
            final String text;
            final int startOffset;
            final int endOffset;
            final int startLine;
            final int endLine;

            LineRangeExtraction(String text, int startOffset, int endOffset, int startLine, int endLine) {
                this.text = text;
                this.startOffset = startOffset;
                this.endOffset = endOffset;
                this.startLine = startLine;
                this.endLine = endLine;
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

        @Tool("Read file content. Use for investigation during merging/fixing if needed.")
        public String readFile(String filePath) {
            if (contextTools == null) {
                return "ERROR: Context tools not available";
            }
            notifyTool("readFile", filePath);
            return contextTools.readFile(filePath);
        }

        @Tool("Search code for patterns. Use if you need to explore code during merging.")
        public String searchCode(String query, String filePattern, String excludePattern,
                                Integer beforeLines, Integer afterLines, Boolean multiline) {
            if (contextTools == null) {
                return "ERROR: Context tools not available";
            }
            notifyTool("searchCode", query);
            return contextTools.searchCode(query, filePattern, excludePattern, beforeLines, afterLines, multiline);
        }
    }
}