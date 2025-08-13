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
            "STOP when you have gathered the essential context."
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
        
        public ContextGatheringTools(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
            this.project = project;
            this.toolRegistry = toolRegistry;
            this.readFileTool = new ReadFileTool(project);
        }
        
        @Tool("Read the contents of a file")
        public String readFile(String filePath) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("filePath", filePath);
                
                CodeExplorationTool.ToolResult result = readFileTool.execute(params);
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
            try {
                CodeExplorationTool retrievalTool = toolRegistry.getTool("retrieve_context");
                if (retrievalTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("query", query);
                    params.addProperty("max_results", 5);
                    params.addProperty("threshold", 0.6);
                    
                    CodeExplorationTool.ToolResult result = retrievalTool.execute(params);
                    if (result.isSuccess()) {
                        return result.getContent();
                    }
                }
                return "No results found for: " + query;
            } catch (Exception e) {
                return "Error searching code: " + e.getMessage();
            }
        }
    }
    
    // No longer need the adapter since we use ZestChatLanguageModel
    
    /**
     * Gather comprehensive context for test generation using LangChain4j tools
     */
    @NotNull
    public CompletableFuture<TestContext> gatherContext(@NotNull TestGenerationRequest request, @Nullable TestPlan testPlan) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[ContextAgent] Gathering context for: " + request.getTargetFile().getName());
                notifyStream("\n🔍 Gathering comprehensive context for test generation...\n");
                
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
                
                notifyStream("\n✅ Context gathering complete\n");
                
                // Build the final TestContext from what was gathered
                return buildTestContextFromGathering(request, testPlan, contextGatheringResult);
                
            } catch (Exception e) {
                LOG.error("[ContextAgent] Failed to gather context", e);
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
    
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        String lowerReasoning = reasoning.toLowerCase();
        
        if (lowerReasoning.contains("search") || lowerReasoning.contains("find") || lowerReasoning.contains("look for")) {
            return new AgentAction(AgentAction.ActionType.SEARCH, "Search for related code and patterns", reasoning);
        } else if (lowerReasoning.contains("analyze") || lowerReasoning.contains("examine") || lowerReasoning.contains("inspect")) {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze code dependencies and structure", reasoning);
        } else if (lowerReasoning.contains("gather") || lowerReasoning.contains("collect") || lowerReasoning.contains("retrieve")) {
            return new AgentAction(AgentAction.ActionType.GENERATE, "Gather context information", reasoning);
        } else if (lowerReasoning.contains("complete") || lowerReasoning.contains("done") || lowerReasoning.contains("finished")) {
            return new AgentAction(AgentAction.ActionType.COMPLETE, "Context gathering completed", reasoning);
        } else {
            return new AgentAction(AgentAction.ActionType.SEARCH, "Search for additional context", reasoning);
        }
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        switch (action.getType()) {
            case SEARCH:
                return performSearch(action.getParameters());
            case ANALYZE:
                return analyzeCodeStructure(action.getParameters());
            case GENERATE:
                return gatherAdditionalContext(action.getParameters());
            case COMPLETE:
                return action.getParameters();
            default:
                return "Unknown action: " + action.getType();
        }
    }
    
    private String performSearch(@NotNull String parameters) {
        try {
            // Use RAG to find related context
            String searchQuery = extractSearchTerms(parameters);
            
            ZestLangChain4jService.RetrievalResult result = langChainService
                .retrieveContext(searchQuery, 5, 0.6).join();
            
            if (result.isSuccess() && !result.getItems().isEmpty()) {
                StringBuilder context = new StringBuilder();
                context.append("Found ").append(result.getItems().size()).append(" relevant code pieces:\n\n");
                
                for (ZestLangChain4jService.ContextItem item : result.getItems()) {
                    context.append("File: ").append(item.getFilePath()).append("\n");
                    if (item.getLineNumber() != null) {
                        context.append("Line: ").append(item.getLineNumber()).append("\n");
                    }
                    context.append("Content:\n").append(item.getContent()).append("\n");
                    context.append("Relevance Score: ").append(String.format("%.2f", item.getScore())).append("\n\n");
                }
                
                return context.toString();
            } else {
                return "No relevant context found for: " + searchQuery;
            }
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Search failed", e);
            return "Search failed: " + e.getMessage();
        }
    }
    
    // These methods are no longer needed as LangChain4j handles tool calling automatically
    
    private String analyzeCodeStructure(@NotNull String parameters) {
        try {
            StringBuilder analysis = new StringBuilder();
            analysis.append("Code Structure Analysis:\n\n");
            
            // Search for existing test files
            List<VirtualFile> testFiles = findExistingTestFiles();
            if (!testFiles.isEmpty()) {
                analysis.append("Existing Test Files Found:\n");
                for (VirtualFile testFile : testFiles) {
                    analysis.append("- ").append(testFile.getName()).append(" (").append(testFile.getPath()).append(")\n");
                }
                analysis.append("\n");
            }
            
            // Analyze test patterns in existing files
            String testPatterns = analyzeExistingTestPatterns(testFiles);
            analysis.append(testPatterns);
            
            // Detect testing framework
            String frameworkInfo = detectTestingFramework();
            analysis.append("Testing Framework: ").append(frameworkInfo).append("\n\n");
            
            return analysis.toString();
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Code structure analysis failed", e);
            return "Code structure analysis failed: " + e.getMessage();
        }
    }
    
    private String gatherAdditionalContext(@NotNull String parameters) {
        StringBuilder context = new StringBuilder();
        
        try {
            // Gather project-specific context
            context.append("Project Context:\n");
            context.append("Project Name: ").append(project.getName()).append("\n");
            context.append("Base Path: ").append(project.getBasePath()).append("\n");
            
            // Check for common configuration files
            String configInfo = analyzeProjectConfiguration();
            context.append(configInfo);
            
            // Analyze dependencies from build files
            String dependencyInfo = analyzeDependencies();
            context.append(dependencyInfo);
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Additional context gathering failed", e);
            context.append("Additional context gathering failed: ").append(e.getMessage()).append("\n");
        }
        
        return context.toString();
    }
    
    private String extractSearchTerms(@NotNull String parameters) {
        // Use LLM to extract key search terms
        String prompt = "Extract 3 search terms:\n" +
                       parameters + "\n\n" +
                       "Output: term1 term2 term3\n\n" +
                       "Terms:";
        
        String response = queryLLM(prompt, 50); // Reduced from 200
        return response.isEmpty() ? "test methods patterns" : response;
    }
    
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
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a context gathering agent that uses RAG and code analysis to collect comprehensive information for test generation";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(
            AgentAction.ActionType.SEARCH,
            AgentAction.ActionType.ANALYZE,
            AgentAction.ActionType.GENERATE,
            AgentAction.ActionType.COMPLETE
        );
    }
    
    @NotNull
    @Override
    protected String buildActionPrompt(@NotNull AgentAction action) {
        switch (action.getType()) {
            case SEARCH:
                return "Find existing test files (names only):\n" +
                       action.getParameters() + "\n\n" +
                       "Output: file names. NO code.\n\n" +
                       "Files:";
                       
            case ANALYZE:
                return "Identify test framework:\n" +
                       action.getParameters() + "\n\n" +
                       "Output: JUnit5/JUnit4/TestNG. One word only.\n\n" +
                       "Framework:";
                       
            case GENERATE:
                return "List project info:\n" +
                       action.getParameters() + "\n\n" +
                       "Output: build system, test directory. NO code.\n\n" +
                       "Info:";
                       
            default:
                return action.getParameters();
        }
    }
}