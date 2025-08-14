package com.zps.zest.testgen.agents;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.ZestChatLanguageModel;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.testgen.model.*;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Simple context gathering agent that continues conversation until it decides to stop
 */
public class ContextAgent extends StreamingBaseAgent {
    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextGatheringTools contextTools;
    private final ZestChatLanguageModel model;
    private final ContextGatheringAssistant assistant;
    
    public ContextAgent(@NotNull Project project,
                       @NotNull ZestLangChain4jService langChainService,
                       @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "ContextAgent");
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.contextTools = new ContextGatheringTools(project, toolRegistry);
        this.model = new ZestChatLanguageModel(llmService);
        
        // Build the conversational agent
        this.assistant = AgenticServices
            .agentBuilder(ContextGatheringAssistant.class)
            .chatModel(model)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(30))
            .tools(contextTools)
            .build();
    }
    
    /**
     * Simple conversational interface for context gathering
     */
    public interface ContextGatheringAssistant {
        @dev.langchain4j.service.SystemMessage("""
            You are a context gathering assistant for test generation.
            You have tools to analyze code, list files, and search for patterns.
            
            Your goal is to gather comprehensive context by:
            1. Analyzing the target class/file using analyzeClass
            2. Finding and analyzing related test files
            3. Searching for test patterns and examples
            4. Understanding dependencies and relationships
            
            Continue exploring and gathering context until you have enough information
            for comprehensive test generation. When you're done, explicitly say:
            "CONTEXT_GATHERING_COMPLETE"
            
            Be conversational and explain what you're doing as you gather context.
            """)
        @dev.langchain4j.agentic.Agent
        String gatherContext(String request);
    }
    
    /**
     * Gather context through conversation until completion
     */
    @NotNull
    public CompletableFuture<TestContext> gatherContext(@NotNull TestGenerationRequest request,
                                                        @Nullable TestPlan testPlan,
                                                        @Nullable String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                LOG.info("[ContextAgent] Starting conversational context gathering");
                notifyStream("\n🔍 Starting context gathering conversation...\n");
                
                contextTools.reset();
                contextTools.setSessionId(sessionId);
                
                // Build initial request
                StringBuilder initialRequest = new StringBuilder();
                initialRequest.append("Gather comprehensive context for test generation.\n");
                initialRequest.append("Target file: ").append(request.getTargetFile().getVirtualFile().getPath()).append("\n");
                
                if (testPlan != null) {
                    initialRequest.append("Target class: ").append(testPlan.getTargetClass()).append("\n");
                    if (testPlan.getTargetMethod() != null) {
                        initialRequest.append("Target method: ").append(testPlan.getTargetMethod()).append("\n");
                    }
                }
                
                if (request.hasSelection()) {
                    initialRequest.append("User has selected specific code to test.\n");
                }
                
                initialRequest.append("\nPlease analyze the target and gather all necessary context.");
                
                // Start conversation
                String response = "";
                String previousResponse = "";
                int iterations = 0;
                final int MAX_ITERATIONS = 20;
                
                // Initial request
                notifyStream("💬 Assistant: Starting context gathering...\n");
                response = assistant.gatherContext(initialRequest.toString());
                notifyStream(response + "\n\n");
                
                // Continue conversation until completion signal
                while (!response.contains("CONTEXT_GATHERING_COMPLETE") && iterations < MAX_ITERATIONS) {
                    iterations++;
                    
                    // Simple continuation prompt
                    String continuationPrompt = "Please continue gathering context. " +
                        "Remember to say 'CONTEXT_GATHERING_COMPLETE' when you have enough information.";
                    
                    previousResponse = response;
                    response =  assistant.gatherContext(continuationPrompt);
                    notifyStream("💬 Assistant: " + response + "\n\n");
                    
                    // Prevent infinite loops
                    if (response.equals(previousResponse)) {
                        notifyStream("⚠️ Agent seems stuck, forcing completion...\n");
                        break;
                    }
                }
                
                notifyStream("✅ Context gathering complete after " + iterations + " iterations\n");
                
                // Build TestContext from gathered data
                return buildTestContext(request, testPlan, contextTools.getGatheredData());
                
            } catch (Exception e) {
                LOG.error("[ContextAgent] Failed to gather context", e);
                throw new RuntimeException("Context gathering failed: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Build TestContext from gathered data
     */
    private TestContext buildTestContext(TestGenerationRequest request, 
                                       TestPlan testPlan,
                                       Map<String, Object> gatheredData) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("targetFile", request.getTargetFile().getName());
            metadata.put("gatheredData", gatheredData);
            
            // Extract analyzed classes
            Map<String, String> analyzedClasses = (Map<String, String>) gatheredData.get("analyzedClasses");
            
            // Find target class code
            String targetClassCode = null;
            String targetMethodCode = null;
            String targetPath = request.getTargetFile().getVirtualFile().getPath();
            
            for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
                if (entry.getKey().contains(targetPath)) {
                    targetClassCode = entry.getValue();
                    break;
                }
            }
            
            // Convert to context items
            List<ZestLangChain4jService.ContextItem> contextItems = new ArrayList<>();
            for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
                contextItems.add(new ZestLangChain4jService.ContextItem(
                    "class-" + entry.getKey().hashCode(),
                    entry.getKey(),
                    entry.getValue(),
                    entry.getKey(),
                    null,
                    entry.getKey().contains(targetPath) ? 1.0 : 0.8
                ));
            }
            
            // Simple pattern detection
            List<String> patterns = new ArrayList<>();
            String allContent = String.join("\n", analyzedClasses.values());
            if (allContent.contains("@Test")) patterns.add("JUnit tests");
            if (allContent.contains("@Mock")) patterns.add("Mockito");
            if (allContent.contains("assertEquals")) patterns.add("Assertions");
            
            return new TestContext(
                contextItems,
                new ArrayList<>(analyzedClasses.keySet()),
                new HashMap<>(),
                patterns,
                "JUnit 5",
                metadata,
                targetClassCode,
                targetMethodCode
            );
            
        } catch (Exception e) {
            LOG.error("Failed to build test context", e);
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
    
    /**
     * Simple tools wrapper
     */
    public static class ContextGatheringTools {
        private final Project project;
        private final CodeExplorationToolRegistry toolRegistry;
        private String sessionId;
        private final Map<String, String> analyzedClasses = new HashMap<>();
        
        public ContextGatheringTools(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
            this.project = project;
            this.toolRegistry = toolRegistry;
        }
        
        public void reset() {
            analyzedClasses.clear();
        }
        
        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
        
        public Map<String, Object> getGatheredData() {
            Map<String, Object> data = new HashMap<>();
            data.put("analyzedClasses", new HashMap<>(analyzedClasses));
            return data;
        }
        
        @Tool("Analyze a Java class to get its structure, dependencies, and relationships")
        public String analyzeClass(String filePath) {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                try {
                    VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
                    if (virtualFile == null) {
                        return "File not found: " + filePath;
                    }
                    
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                    if (!(psiFile instanceof PsiJavaFile)) {
                        return "Not a Java file: " + filePath;
                    }
                    
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    PsiClass[] classes = javaFile.getClasses();
                    if (classes.length == 0) {
                        return "No classes found in file";
                    }
                    
                    PsiClass targetClass = classes[0];
                    String contextInfo = ClassAnalyzer.collectClassContext(targetClass);
                    
                    // Store the analyzed class
                    analyzedClasses.put(filePath, contextInfo);
                    
                    return "Analyzed " + targetClass.getName() + ":\n" + contextInfo;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            });
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
                    return result.isSuccess() ? result.getContent() : "Error: " + result.getContent();
                }
                return "List tool not available";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
        
        @Tool("Find files by name pattern")
        public String findFiles(String pattern) {
            try {
                CodeExplorationTool findTool = toolRegistry.getTool("find_file");
                if (findTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("fileName", pattern);
                    
                    CodeExplorationTool.ToolResult result = findTool.execute(params);
                    return result.isSuccess() ? result.getContent() : "Error: " + result.getContent();
                }
                return "Find tool not available";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
        
        @Tool("Search for code patterns")
        public String searchCode(String query) {
            try {
                CodeExplorationTool searchTool = toolRegistry.getTool("retrieve_context");
                if (searchTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("query", query);
                    params.addProperty("max_results", 5);
                    
                    CodeExplorationTool.ToolResult result = searchTool.execute(params);
                    return result.isSuccess() ? result.getContent() : "Error: " + result.getContent();
                }
                return "Search tool not available";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
    }
    
    // Required overrides
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        return new AgentAction(AgentAction.ActionType.COMPLETE, "Context gathering completed", reasoning);
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        return action.getParameters();
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "conversational context gathering agent";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(AgentAction.ActionType.COMPLETE);
    }
}