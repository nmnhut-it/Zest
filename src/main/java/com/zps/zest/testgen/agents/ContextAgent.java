package com.zps.zest.testgen.agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.ZestChatLanguageModel;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.langchain4j.tools.impl.ReadFileTool;
import com.zps.zest.testgen.model.*;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ContextAgent extends StreamingBaseAgent {
    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextGatheringTools contextTools;
    private final ContextGatheringAssistant assistant;
    
    public ContextAgent(@NotNull Project project,
                       @NotNull ZestLangChain4jService langChainService,
                       @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "ContextAgent");
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.contextTools = new ContextGatheringTools(project, toolRegistry);
        
        // Create AI assistant with tools using LangChain4j pattern
        // Use the existing ZestChatLanguageModel which properly implements ChatLanguageModel
        ChatLanguageModel model = new ZestChatLanguageModel(llmService);
        this.assistant = AiServices.builder(ContextGatheringAssistant.class)
            .chatLanguageModel(model)  // Use chatLanguageModel instead of chatModel
            .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
            .tools(contextTools)
            // Note: maxToolExecutions may not be available in this version
            .build();
    }
    
    /**
     * Interface for the AI assistant that can use tools
     */
    interface ContextGatheringAssistant {
        @dev.langchain4j.service.SystemMessage(
            "You are a context gathering assistant for test generation. " +
            "Your job is to explore the codebase efficiently to gather relevant context. " +
            "BE STRATEGIC: Don't call tools endlessly. Usually 3-7 tool calls are sufficient. " +
            "After each tool call, evaluate if you have enough information. " +
            "Focus on: 1) Target file content, 2) Test examples, 3) Key dependencies. " +
            "STOP when you have gathered the essential context. " +
            "Output clean text without markdown formatting."
        )
        String gatherContext(String task);
    }
    
    /**
     * Tools class with @Tool annotated methods for LangChain4j
     */
    public static class ContextGatheringTools {
        private final Project project;
        private final CodeExplorationToolRegistry toolRegistry;
        private final ReadFileTool readFileTool;
        private String currentSessionId; // Track current session for recording
        
        public ContextGatheringTools(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
            this.project = project;
            this.toolRegistry = toolRegistry;
            this.readFileTool = new ReadFileTool(project);
        }
        
        public void setSessionId(String sessionId) {
            this.currentSessionId = sessionId;
        }
        
        @Tool("Read the contents of a file")
        public String readFile(String filePath) {
            long startTime = System.currentTimeMillis();
            try {
                JsonObject params = new JsonObject();
                params.addProperty("filePath", filePath);
                
                CodeExplorationTool.ToolResult result = readFileTool.execute(params);
                
                // Record tool call
                if (currentSessionId != null) {
                    recordToolCall("readFile", filePath, result.getContent(), 
                        System.currentTimeMillis() - startTime);
                }
                
                if (result.isSuccess()) {
                    return result.getContent();
                } else {
                    return "Error reading file: " + result.getContent();
                }
            } catch (Exception e) {
                return "Failed to read file: " + e.getMessage();
            }
        }
        
        @Tool("List files in a directory")
        public String listFiles(String directoryPath) {
            try {
                CodeExplorationTool listTool = toolRegistry.getTool("list_files");
                if (listTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("directoryPath", directoryPath);
                    params.addProperty("recursive", false);
                    
                    CodeExplorationTool.ToolResult result = listTool.execute(params);
                    if (result.isSuccess()) {
                        return result.getContent();
                    }
                }
                return "Could not list files in " + directoryPath;
            } catch (Exception e) {
                return "Error listing files: " + e.getMessage();
            }
        }
        
        @Tool("Find files by name pattern")
        public String findFiles(String fileName) {
            try {
                CodeExplorationTool findTool = toolRegistry.getTool("find_file");
                if (findTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("fileName", fileName);
                    
                    CodeExplorationTool.ToolResult result = findTool.execute(params);
                    if (result.isSuccess()) {
                        return result.getContent();
                    }
                }
                return "Could not find files matching: " + fileName;
            } catch (Exception e) {
                return "Error finding files: " + e.getMessage();
            }
        }
        
        @Tool("Search for code using semantic search")
        public String searchCode(String query) {
            long startTime = System.currentTimeMillis();
            try {
                CodeExplorationTool retrievalTool = toolRegistry.getTool("retrieve_context");
                if (retrievalTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("query", query);
                    params.addProperty("max_results", 5);
                    params.addProperty("threshold", 0.6);
                    
                    CodeExplorationTool.ToolResult result = retrievalTool.execute(params);
                    
                    // Record tool call
                    if (currentSessionId != null) {
                        recordToolCall("searchCode", query, result.getContent(), 
                            System.currentTimeMillis() - startTime);
                    }
                    
                    if (result.isSuccess()) {
                        return result.getContent();
                    }
                }
                return "No results found for: " + query;
            } catch (Exception e) {
                return "Error searching code: " + e.getMessage();
            }
        }
        
        private void recordToolCall(String toolName, String input, String output, long duration) {
            try {
                if (currentSessionId != null) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("tool", toolName);
                    
                    com.zps.zest.testgen.ui.AgentDebugDialog.Companion.recordAgentExecution(
                        currentSessionId, "ContextAgent", 
                        "TOOL_" + toolName.toUpperCase(),
                        input, 
                        output.length() > 500 ? output.substring(0, 500) + "..." : output,
                        duration, 
                        metadata
                    );
                }
            } catch (Exception e) {
                // Ignore errors in recording
            }
        }
    }
    
    // No longer need the adapter since we use ZestChatLanguageModel
    
    /**
     * Gather comprehensive context for test generation using LangChain4j tools
     */
    @NotNull
    public CompletableFuture<TestContext> gatherContext(@NotNull TestGenerationRequest request, @Nullable TestPlan testPlan) {
        return gatherContext(request, testPlan, null);
    }
    
    /**
     * Gather comprehensive context for test generation using LangChain4j tools with session tracking
     */
    @NotNull
    public CompletableFuture<TestContext> gatherContext(@NotNull TestGenerationRequest request, 
                                                        @Nullable TestPlan testPlan,
                                                        @Nullable String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                LOG.info("[ContextAgent] Gathering context for: " + request.getTargetFile().getName());
                notifyStream("\n🔍 Gathering comprehensive context for test generation...\n");
                
                // Set session ID for tool tracking
                contextTools.setSessionId(sessionId);
                
                // Record start
                if (sessionId != null) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("targetFile", request.getTargetFile().getName());
                    com.zps.zest.testgen.ui.AgentDebugDialog.Companion.recordAgentExecution(
                        sessionId, "ContextAgent", "START", 
                        "Target: " + request.getTargetFile().getName(), "", 0, metadata
                    );
                }
                
                String targetInfo = "";
                if (testPlan != null) {
                    targetInfo = " for class " + testPlan.getTargetClass() + " method " + testPlan.getTargetMethod();
                } else {
                    targetInfo = " for file " + request.getTargetFile().getName();
                }
                
                String task = "Gather context for generating tests" + targetInfo + ".\n\n" +
                             "IMPORTANT INSTRUCTIONS:\n" +
                             "1. Start by reading the target file: " + request.getTargetFile().getVirtualFile().getPath() + "\n" +
                             "2. Find and read 2-3 related test files to understand testing patterns\n" +
                             "3. Search for key dependencies or related code\n" +
                             "4. STOP after gathering essential information (maximum 5-7 tool calls)\n" +
                             "5. Return a summary of what you found\n\n" +
                             "DO NOT call tools endlessly. Be selective and strategic.\n" +
                             "After each tool call, evaluate if you have enough context.";
                
                // Use the LangChain4j AI assistant with tools
                String contextGatheringResult = assistant.gatherContext(task);
                
                // Clean any markdown formatting from the result
                contextGatheringResult = stripMarkdown(contextGatheringResult);
                
                notifyStream("\n✅ Context gathering complete\n");
                
                // Record completion
                if (sessionId != null) {
                    com.zps.zest.testgen.ui.AgentDebugDialog.Companion.recordAgentExecution(
                        sessionId, "ContextAgent", "COMPLETE", 
                        task, contextGatheringResult.length() > 500 ? 
                            contextGatheringResult.substring(0, 500) + "..." : contextGatheringResult,
                        System.currentTimeMillis() - startTime, new HashMap<>()
                    );
                }
                
                // Build the final TestContext from what was gathered
                return buildTestContextFromGathering(request, testPlan, contextGatheringResult);
                
            } catch (Exception e) {
                LOG.error("[ContextAgent] Failed to gather context", e);
                
                // Record error
                if (sessionId != null) {
                    com.zps.zest.testgen.ui.AgentDebugDialog.Companion.recordAgentExecution(
                        sessionId, "ContextAgent", "ERROR", 
                        request.getTargetFile().getName(), e.getMessage(), 
                        System.currentTimeMillis() - startTime, new HashMap<>()
                    );
                }
                
                throw new RuntimeException("Context gathering failed: " + e.getMessage());
            }
        });
    }
    
    private String buildInitialContext(@NotNull TestGenerationRequest request, @NotNull TestPlan testPlan) {
        StringBuilder context = new StringBuilder();
        
        // Add basic file information
        context.append("Target File: ").append(request.getTargetFile().getName()).append("\n");
        context.append("Target Class: ").append(testPlan.getTargetClass()).append("\n");
        context.append("Target Method: ").append(testPlan.getTargetMethod()).append("\n");
        context.append("Test Type: ").append(testPlan.getRecommendedTestType()).append("\n");
        context.append("Dependencies: ").append(String.join(", ", testPlan.getDependencies())).append("\n\n");
        
        // Add test scenarios summary
        context.append("Test Scenarios:\n");
        for (TestPlan.TestScenario scenario : testPlan.getTestScenarios()) {
            context.append("- ").append(scenario.getName()).append(" (").append(scenario.getType()).append(")\n");
        }
        context.append("\n");
        
        return context.toString();
    }
    
    // Simplified helper methods for internal use
    
    private List<VirtualFile> findExistingTestFiles() {
        try {
            // Wrap file index access in read action to avoid threading issues
            return ApplicationManager.getApplication().runReadAction((Computable<List<VirtualFile>>) () -> {
                try {
                    Collection<VirtualFile> testFiles = FilenameIndex.getAllFilesByExt(
                        project, "java", GlobalSearchScope.projectScope(project)
                    );
                    
                    return testFiles.stream()
                        .filter(file -> file.getName().contains("Test") || 
                                       file.getPath().contains("test") ||
                                       file.getPath().contains("/test/"))
                        .limit(10) // Limit to avoid too much processing
                        .toList();
                } catch (Exception e) {
                    LOG.warn("[ContextAgent] Failed to find test files in read action", e);
                    return new ArrayList<>();
                }
            });
                
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Failed to find existing test files", e);
            return new ArrayList<>();
        }
    }
    
    private String analyzeExistingTestPatterns(@NotNull List<VirtualFile> testFiles) {
        if (testFiles.isEmpty()) {
            return "No existing test patterns found.\n\n";
        }
        
        StringBuilder patterns = new StringBuilder();
        patterns.append("Existing Test Patterns:\n");
        
        try {
            PsiManager psiManager = PsiManager.getInstance(project);
            
            for (VirtualFile testFile : testFiles.subList(0, Math.min(3, testFiles.size()))) {
                PsiFile psiFile = psiManager.findFile(testFile);
                if (psiFile != null) {
                    // Get a sample of the test file structure
                    String structure = ClassAnalyzer.getTextOfPsiElement(psiFile);
                    if (structure.length() > 500) {
                        structure = structure.substring(0, 500) + "...";
                    }
                    patterns.append("From ").append(testFile.getName()).append(":\n");
                    patterns.append(structure).append("\n\n");
                }
            }
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Failed to analyze test patterns", e);
            patterns.append("Failed to analyze test patterns: ").append(e.getMessage()).append("\n");
        }
        
        return patterns.toString();
    }
    
    private String detectTestingFramework() {
        try {
            // Wrap file index access in read action to avoid threading issues
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                try {
                    // Search for common testing framework indicators
                    Collection<VirtualFile> javaFiles = FilenameIndex.getAllFilesByExt(
                        project, "java", GlobalSearchScope.projectScope(project)
                    );
                    
                    for (VirtualFile file : javaFiles) {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                        if (psiFile != null) {
                            String content = ClassAnalyzer.getTextOfPsiElement(psiFile);
                            
                            if (content.contains("@Test") && content.contains("org.junit.jupiter")) {
                                return "JUnit 5";
                            } else if (content.contains("@Test") && content.contains("org.junit.")) {
                                return "JUnit 4";
                            } else if (content.contains("@Test") && content.contains("testng")) {
                                return "TestNG";
                            }
                        }
                    }
                    
                    return "Unknown (will use JUnit 5 as default)";
                } catch (Exception e) {
                    LOG.warn("[ContextAgent] Framework detection failed in read action", e);
                    return "Detection failed (will use JUnit 5 as default)";
                }
            });
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Framework detection failed", e);
            return "Detection failed (will use JUnit 5 as default)";
        }
    }
    
    private String analyzeProjectConfiguration() {
        StringBuilder config = new StringBuilder();
        
        try {
            // Check for common build files
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                VirtualFile pomXml = baseDir.findChild("pom.xml");
                VirtualFile buildGradle = baseDir.findChild("build.gradle");
                VirtualFile buildGradleKts = baseDir.findChild("build.gradle.kts");
                
                if (pomXml != null) {
                    config.append("Build System: Maven\n");
                } else if (buildGradle != null || buildGradleKts != null) {
                    config.append("Build System: Gradle\n");
                } else {
                    config.append("Build System: Unknown\n");
                }
            }
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Configuration analysis failed", e);
            config.append("Configuration analysis failed\n");
        }
        
        return config.toString();
    }
    
    private String analyzeDependencies() {
        // This could be expanded to actually parse build files
        // For now, return basic information
        return "Dependencies: Will be determined from build configuration\n\n";
    }
    
    @NotNull
    private TestContext buildTestContextFromGathering(@NotNull TestGenerationRequest request,
                                                      @Nullable TestPlan testPlan,
                                                      @NotNull String gatheringResult) {
        try {
            // Parse the gathering result to extract what was found
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("targetFile", request.getTargetFile().getName());
            metadata.put("hasSelection", request.hasSelection());
            metadata.put("userDescription", request.getUserDescription());
            if (testPlan != null) {
                metadata.put("scenarioCount", testPlan.getScenarioCount());
            }
            metadata.put("gatheringResult", gatheringResult);
            
            // Extract file contents from gathering result
            Map<String, String> fileContents = extractFileContents(gatheringResult);
            if (!fileContents.isEmpty()) {
                metadata.put("gatheredFiles", fileContents);
            }
            
            // Extract patterns from gathering result
            List<String> testPatterns = extractTestPatterns(gatheringResult);
            
            // Build dependency map from test plan or gathering result
            Map<String, String> dependencies = new HashMap<>();
            if (testPlan != null) {
                for (String dep : testPlan.getDependencies()) {
                    dependencies.put(dep, gatheringResult.contains(dep) ? "found" : "referenced");
                }
            }
            
            // Extract related files from gathering
            List<String> relatedFiles = extractRelatedFiles(gatheringResult);
            if (!relatedFiles.contains(request.getTargetFile().getName())) {
                relatedFiles.add(0, request.getTargetFile().getName());
            }
            
            // Determine framework
            String framework = detectFramework(gatheringResult);
            
            // Extract any RAG context items if mentioned
            List<ZestLangChain4jService.ContextItem> contextItems = new ArrayList<>();
            if (gatheringResult.contains("relevant code pieces")) {
                // Parse RAG results if present
                contextItems = parseRAGResults(gatheringResult);
            }
            
            notifyStream("✅ Context building complete!\n");
            
            return new TestContext(contextItems, relatedFiles, dependencies, testPatterns, framework, metadata);
            
        } catch (Exception e) {
            LOG.error("[ContextAgent] Failed to build test context from gathering", e);
            // Return minimal context
            return new TestContext(
                new ArrayList<>(),
                List.of(request.getTargetFile().getName()),
                Map.of(),
                new ArrayList<>(),
                "JUnit 5",
                Map.of("error", e.getMessage())
            );
        }
    }
    
    private Map<String, String> extractFileContents(@NotNull String gatheringResult) {
        // Strip any markdown first
        gatheringResult = stripMarkdown(gatheringResult);
        
        Map<String, String> contents = new HashMap<>();
        String[] lines = gatheringResult.split("\n");
        
        String currentFile = null;
        StringBuilder currentContent = new StringBuilder();
        
        for (String line : lines) {
            if (line.startsWith("File content of ") && line.contains(":")) {
                // Save previous file if any
                if (currentFile != null && currentContent.length() > 0) {
                    contents.put(currentFile, currentContent.toString());
                }
                // Start new file
                currentFile = line.substring("File content of ".length(), line.indexOf(":")).trim();
                currentContent = new StringBuilder();
            } else if (currentFile != null && !line.startsWith("Failed to read") && !line.startsWith("Files in") && !line.startsWith("Found files")) {
                currentContent.append(line).append("\n");
            }
        }
        
        // Save last file if any
        if (currentFile != null && currentContent.length() > 0) {
            contents.put(currentFile, currentContent.toString());
        }
        
        return contents;
    }
    
    private List<String> extractTestPatterns(@NotNull String gatheringResult) {
        List<String> patterns = new ArrayList<>();
        
        if (gatheringResult.contains("@Test")) patterns.add("JUnit @Test annotation");
        if (gatheringResult.contains("@BeforeEach")) patterns.add("@BeforeEach setup");
        if (gatheringResult.contains("@Mock")) patterns.add("Mockito mocks");
        if (gatheringResult.contains("assertEquals")) patterns.add("JUnit assertions");
        if (gatheringResult.contains("assertThat")) patterns.add("AssertJ assertions");
        if (gatheringResult.contains("when(") && gatheringResult.contains("thenReturn")) patterns.add("Mockito stubbing");
        
        return patterns.stream().distinct().collect(Collectors.toList());
    }
    
    private List<String> extractRelatedFiles(@NotNull String gatheringResult) {
        List<String> files = new ArrayList<>();
        String[] lines = gatheringResult.split("\n");
        
        for (String line : lines) {
            if ((line.contains("File content of ") || line.contains("Files in ") || line.contains("Found files")) && line.contains(".java")) {
                // Extract filename from various formats
                String fileName = null;
                if (line.contains("File content of ")) {
                    fileName = line.substring("File content of ".length(), line.indexOf(":")).trim();
                } else if (line.startsWith("- ") && line.endsWith(".java")) {
                    fileName = line.substring(2).trim();
                }
                
                if (fileName != null && !files.contains(fileName)) {
                    files.add(fileName);
                }
            }
        }
        
        return files;
    }
    
    private String detectFramework(@NotNull String gatheringResult) {
        if (gatheringResult.contains("org.junit.jupiter") || gatheringResult.contains("JUnit 5")) {
            return "JUnit 5";
        } else if (gatheringResult.contains("org.junit.Test") || gatheringResult.contains("JUnit 4")) {
            return "JUnit 4";
        } else if (gatheringResult.contains("org.testng")) {
            return "TestNG";
        }
        return "JUnit 5"; // Default
    }
    
    private List<ZestLangChain4jService.ContextItem> parseRAGResults(@NotNull String gatheringResult) {
        List<ZestLangChain4jService.ContextItem> items = new ArrayList<>();
        // Simple parsing - in real implementation would be more sophisticated
        // For now, return empty as the actual content is in the metadata
        return items;
    }
    
    private List<ZestLangChain4jService.ContextItem> extractCodeContext(@NotNull String contextResult) {
        // In a real implementation, this would parse the context result
        // For now, return empty list - the actual context items come from RAG calls
        return new ArrayList<>();
    }
    
    private Map<String, String> extractDependencies(@NotNull String contextResult, @NotNull List<String> plannedDependencies) {
        Map<String, String> dependencies = new HashMap<>();
        
        // Add planned dependencies
        for (String dep : plannedDependencies) {
            dependencies.put(dep, "planned");
        }
        
        // Extract additional dependencies from context
        if (contextResult.contains("import ")) {
            String[] lines = contextResult.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("import ") && !line.contains("java.")) {
                    String importStmt = line.trim().substring(7);
                    if (importStmt.endsWith(";")) {
                        importStmt = importStmt.substring(0, importStmt.length() - 1);
                    }
                    dependencies.put(importStmt, "discovered");
                }
            }
        }
        
        return dependencies;
    }
    
    private String extractFrameworkInfo(@NotNull String contextResult) {
        if (contextResult.contains("JUnit 5") || contextResult.contains("jupiter")) {
            return "JUnit 5";
        } else if (contextResult.contains("JUnit 4") || contextResult.contains("org.junit.Test")) {
            return "JUnit 4";
        } else if (contextResult.contains("TestNG")) {
            return "TestNG";
        } else {
            return "JUnit 5"; // Default
        }
    }
    
    private Map<String, Object> buildMetadata(@NotNull TestGenerationRequest request, @NotNull TestPlan testPlan, @NotNull String contextResult) {
        Map<String, Object> metadata = new HashMap<>();
        
        metadata.put("targetFile", request.getTargetFile().getName());
        metadata.put("hasSelection", request.hasSelection());
        metadata.put("userDescription", request.getUserDescription());
        metadata.put("scenarioCount", testPlan.getScenarioCount());
        metadata.put("contextLength", contextResult.length());
        metadata.put("gatheringTimestamp", System.currentTimeMillis());
        
        return metadata;
    }
    
    /**
     * Strip markdown formatting from text
     */
    private String stripMarkdown(@NotNull String text) {
        // Remove code blocks
        text = text.replaceAll("```(?:java|kotlin|javascript|typescript)?\\s*\\n", "");
        text = text.replaceAll("\\n?```\\s*", "");
        
        // Remove backticks
        text = text.replaceAll("`([^`]+)`", "$1");
        
        // Remove bold/italic
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("__(.*?)__", "$1");
        
        return text.trim();
    }
    
    // Required overrides for StreamingBaseAgent (simplified since we use AiServices)
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        // Not used with AiServices pattern
        return new AgentAction(AgentAction.ActionType.COMPLETE, "Context gathering completed", reasoning);
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        // Not used with AiServices pattern
        return action.getParameters();
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a context gathering agent that uses tools to collect comprehensive information for test generation";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        // Simplified - not used with AiServices
        return Arrays.asList(AgentAction.ActionType.COMPLETE);
    }
    
    @NotNull
    @Override
    protected String buildActionPrompt(@NotNull AgentAction action) {
        // Not used with AiServices pattern
        return action.getParameters();
    }
}