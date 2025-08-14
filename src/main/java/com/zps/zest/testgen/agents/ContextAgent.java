package com.zps.zest.testgen.agents;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
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
        
        // Build the assistant with proper configuration for multiple tool calls
        // Note: LangChain4j 0.35.0 doesn't support maxToolExecutions, but the AI will
        // naturally continue calling tools until it determines it has enough context
        // or hits our manual limit in the tools themselves
        this.assistant = AiServices.builder(ContextGatheringAssistant.class)
            .chatLanguageModel(model)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(15)) // Increased for more context
            .tools(contextTools)
            .build();
    }
    
    /**
     * Interface for the AI assistant that can use tools
     */
    interface ContextGatheringAssistant {
        @dev.langchain4j.service.SystemMessage(
            "You are a context gathering assistant for test generation. " +
            "You work in an iterative loop pattern - call tools multiple times to gather comprehensive information. " +
            
            "LOOP STRATEGY: " +
            "1. Start by reading the target file to understand its structure " +
            "2. Search for related test files to understand testing patterns " +
            "3. Find dependencies and related classes that the target uses " +
            "4. Look for similar code or functionality for testing inspiration " +
            "5. Continue exploring until you have sufficient context for test generation " +
            
            "DECISION CRITERIA - Stop when you have: " +
            "- Read the target file completely " +
            "- Found existing test patterns in the codebase " +
            "- Identified key dependencies and their usage " +
            "- Located related classes that provide testing examples " +
            "- Reached the tool call limit (15 calls) " +
            
            "Each tool call should build upon previous findings. Be methodical and thorough."
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
        private int toolCallCount = 0; // Track number of tool calls
        private static final int MAX_TOOL_CALLS = 15; // Maximum allowed tool calls
        private Set<String> readFiles = new HashSet<>(); // Track files that have been read
        private Set<String> exploredPatterns = new HashSet<>(); // Track what patterns we've explored
        
        public ContextGatheringTools(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
            this.project = project;
            this.toolRegistry = toolRegistry;
            this.readFileTool = new ReadFileTool(project);
        }
        
        public void resetToolCallCount() {
            this.toolCallCount = 0;
            this.readFiles.clear();
            this.exploredPatterns.clear();
        }
        
        public void setSessionId(String sessionId) {
            this.currentSessionId = sessionId;
        }
        
        /**
         * Provides progress status to help the AI agent make better loop decisions
         */
        private String getProgressStatus() {
            int remainingCalls = MAX_TOOL_CALLS - toolCallCount;
            return String.format(
                "LOOP PROGRESS: Tool calls used: %d/%d | Files read: %d | Patterns explored: %s | Remaining calls: %d",
                toolCallCount, MAX_TOOL_CALLS, readFiles.size(), 
                exploredPatterns.isEmpty() ? "none" : String.join(", ", exploredPatterns),
                remainingCalls
            );
        }
        
        @Tool("Read the complete contents of a file. Use this to read source code, test files, or any file in the project.")
        public String readFile(String filePath) {
            toolCallCount++;
            if (toolCallCount > MAX_TOOL_CALLS) {
                return "Error: Maximum tool call limit (" + MAX_TOOL_CALLS + ") reached. Please summarize findings.";
            }
            
            // Check if already read
            if (readFiles.contains(filePath)) {
                return "Note: File " + filePath + " was already read. " + getProgressStatus();
            }
            
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
                    readFiles.add(filePath);
                    String content = result.getContent();
                    
                    // Track what patterns we found
                    if (filePath.contains("Test") || filePath.contains("test")) {
                        exploredPatterns.add("test_patterns");
                    }
                    if (content.contains("@Test") || content.contains("@BeforeEach")) {
                        exploredPatterns.add("junit_annotations");
                    }
                    
                    return content + "\n\n" + getProgressStatus();
                } else {
                    return "Error reading file: " + result.getContent() + "\n\n" + getProgressStatus();
                }
            } catch (Exception e) {
                return "Failed to read file: " + e.getMessage();
            }
        }
        
        @Tool("List all files in a directory. Useful for exploring project structure and finding related files.")
        public String listFiles(String directoryPath) {
            toolCallCount++;
            if (toolCallCount > MAX_TOOL_CALLS) {
                return "Error: Maximum tool call limit (" + MAX_TOOL_CALLS + ") reached. Please summarize findings.";
            }
            
            exploredPatterns.add("directory_structure");
            
            try {
                CodeExplorationTool listTool = toolRegistry.getTool("list_files");
                if (listTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("directoryPath", directoryPath);
                    params.addProperty("recursive", false);
                    
                    CodeExplorationTool.ToolResult result = listTool.execute(params);
                    if (result.isSuccess()) {
                        return result.getContent() + "\n\n" + getProgressStatus();
                    }
                }
                return "Could not list files in " + directoryPath + "\n\n" + getProgressStatus();
            } catch (Exception e) {
                return "Error listing files: " + e.getMessage();
            }
        }
        
        @Tool("Find files by name pattern. Use this to locate test files, dependencies, or specific classes.")
        public String findFiles(String fileName) {
            toolCallCount++;
            if (toolCallCount > MAX_TOOL_CALLS) {
                return "Error: Maximum tool call limit (" + MAX_TOOL_CALLS + ") reached. Please summarize findings.";
            }
            
            if (fileName.contains("Test") || fileName.contains("test")) {
                exploredPatterns.add("test_discovery");
            }
            exploredPatterns.add("file_discovery");
            
            try {
                CodeExplorationTool findTool = toolRegistry.getTool("find_file");
                if (findTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("fileName", fileName);
                    
                    CodeExplorationTool.ToolResult result = findTool.execute(params);
                    if (result.isSuccess()) {
                        return result.getContent() + "\n\n" + getProgressStatus();
                    }
                }
                return "Could not find files matching: " + fileName + "\n\n" + getProgressStatus();
            } catch (Exception e) {
                return "Error finding files: " + e.getMessage();
            }
        }
        
        @Tool("Search for code patterns or concepts using semantic search. Use this to find similar code, test patterns, or related functionality.")
        public String searchCode(String query) {
            toolCallCount++;
            if (toolCallCount > MAX_TOOL_CALLS) {
                return "Error: Maximum tool call limit (" + MAX_TOOL_CALLS + ") reached. Please summarize findings.";
            }
            
            exploredPatterns.add("semantic_search_" + query.toLowerCase().replaceAll("\\s+", "_"));
            
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
                        return result.getContent() + "\n\n" + getProgressStatus();
                    }
                }
                return "No results found for: " + query + "\n\n" + getProgressStatus();
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
                
                // Set session ID for tool tracking and reset counter
                contextTools.setSessionId(sessionId);
                contextTools.resetToolCallCount();
                
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
                
                // Use advice-based approach
                Map<String, String> advice = AgentAdviceConfig.ContextGatheringAdvice.getContextAdvice();
                Map<String, Object> contextMap = new HashMap<>();
                contextMap.put("target_file", request.getTargetFile().getVirtualFile().getPath());
                contextMap.put("target_info", targetInfo);
                
                String baseTask = "Gather context for generating tests" + targetInfo;
                String task = AgentAdviceConfig.buildDynamicPrompt(baseTask, advice, contextMap);
                
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
            
            // CRITICAL: Extract the target class and method implementations
            String targetClassCode = null;
            String targetMethodCode = null;
            
            // First, try to get the target file content directly
            String targetFilePath = request.getTargetFile().getVirtualFile().getPath();
            for (Map.Entry<String, String> entry : fileContents.entrySet()) {
                if (entry.getKey().endsWith(request.getTargetFile().getName()) || 
                    targetFilePath.endsWith(entry.getKey())) {
                    targetClassCode = entry.getValue();
                    break;
                }
            }
            
            // If not found in gathered files, read it directly
            if (targetClassCode == null || targetClassCode.isEmpty()) {
                try {
                    ReadFileTool readFileTool = new ReadFileTool(project);
                    targetClassCode = readFileTool.execute(
                        createFilePathParams(targetFilePath)
                    ).getContent();
                } catch (Exception e) {
                    LOG.warn("Could not read target file directly: " + e.getMessage());
                }
            }
            
            // Extract the specific method implementation if we have a test plan
            if (testPlan != null && targetClassCode != null) {
                targetMethodCode = extractMethodImplementation(
                    targetClassCode, 
                    testPlan.getTargetMethod()
                );
            }
            
            // Convert file contents to ContextItems for better structure
            List<ZestLangChain4jService.ContextItem> contextItems = new ArrayList<>();
            
            // Add target class as primary context item
            if (targetClassCode != null) {
                contextItems.add(new ZestLangChain4jService.ContextItem(
                    "target-" + targetFilePath.hashCode(), // id
                    targetFilePath, // title
                    targetClassCode, // content
                    targetFilePath, // filePath
                    null, // lineNumber
                    1.0 // Maximum relevance
                ));
            }
            
            // Add other gathered files as context items
            for (Map.Entry<String, String> entry : fileContents.entrySet()) {
                if (!entry.getKey().equals(targetFilePath)) {
                    contextItems.add(new ZestLangChain4jService.ContextItem(
                        "context-" + entry.getKey().hashCode(), // id
                        entry.getKey(), // title
                        entry.getValue(), // content
                        entry.getKey(), // filePath
                        null, // lineNumber
                        0.8 // High relevance for gathered files
                    ));
                }
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
            
            notifyStream("✅ Context building complete!\n");
            notifyStream("  - Target class code: " + (targetClassCode != null ? targetClassCode.length() + " chars" : "not found") + "\n");
            notifyStream("  - Target method code: " + (targetMethodCode != null ? targetMethodCode.length() + " chars" : "not extracted") + "\n");
            notifyStream("  - Context items: " + contextItems.size() + "\n");
            notifyStream("  - Test patterns: " + testPatterns.size() + "\n");
            
            // Use the new constructor with target code
            return new TestContext(
                contextItems, 
                relatedFiles, 
                dependencies, 
                testPatterns, 
                framework, 
                metadata,
                targetClassCode,
                targetMethodCode
            );
            
        } catch (Exception e) {
            LOG.error("[ContextAgent] Failed to build test context from gathering", e);
            // Return minimal context
            return new TestContext(
                new ArrayList<>(),
                List.of(request.getTargetFile().getName()),
                Map.of(),
                new ArrayList<>(),
                "JUnit 5",
                Map.of("error", e.getMessage()),
                null,
                null
            );
        }
    }
    
    private JsonObject createFilePathParams(String filePath) {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", filePath);
        return params;
    }
    
    private String extractMethodImplementation(String classCode, String methodName) {
        // Simple method extraction - finds method by name and extracts its body
        // This is a simplified version - a real implementation would use PSI
        try {
            String[] lines = classCode.split("\n");
            StringBuilder methodCode = new StringBuilder();
            boolean inMethod = false;
            int braceCount = 0;
            
            for (String line : lines) {
                if (!inMethod && line.contains(methodName) && 
                    (line.contains("public") || line.contains("private") || 
                     line.contains("protected") || !line.contains("="))) {
                    // Found method declaration
                    inMethod = true;
                    methodCode.append(line).append("\n");
                    if (line.contains("{")) {
                        braceCount++;
                    }
                } else if (inMethod) {
                    methodCode.append(line).append("\n");
                    // Count braces to find method end
                    for (char c : line.toCharArray()) {
                        if (c == '{') braceCount++;
                        else if (c == '}') {
                            braceCount--;
                            if (braceCount == 0) {
                                return methodCode.toString();
                            }
                        }
                    }
                }
            }
            
            return methodCode.length() > 0 ? methodCode.toString() : null;
        } catch (Exception e) {
            LOG.warn("Failed to extract method implementation: " + e.getMessage());
            return null;
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

}