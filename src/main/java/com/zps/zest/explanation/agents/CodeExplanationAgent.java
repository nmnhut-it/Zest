package com.zps.zest.explanation.agents;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.explanation.tools.*;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.agents.StreamingBaseAgent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Agent for explaining code and its interactions with other parts of the codebase.
 * Uses language-agnostic tools including LLM-based keyword extraction and grep search.
 */
public class CodeExplanationAgent extends StreamingBaseAgent {
    private final CodeExplorationToolRegistry toolRegistry;
    private final CodeExplanationTools explanationTools;
    private final CodeExplanationAssistant assistant;
    private final MessageWindowChatMemory chatMemory;

    public CodeExplanationAgent(@NotNull Project project,
                               @NotNull ZestLangChain4jService langChainService,
                               @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "CodeExplanationAgent");
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.explanationTools = new CodeExplanationTools(project, toolRegistry, this::sendToUI, this);

        // Build the agent with streaming support
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(50);
        this.assistant = AgenticServices
                .agentBuilder(CodeExplanationAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(8) // Allow multiple tool calls for thorough analysis
                .chatMemory(chatMemory)
                .tools(explanationTools)
                .build();
    }

    /**
     * Assistant interface for code explanation with language-agnostic approach
     */
    public interface CodeExplanationAssistant {
        @dev.langchain4j.service.SystemMessage("""
You are a code explanation expert that helps developers understand HOW code works step-by-step. Your goal is to explain the execution flow and mechanics of the code.

WORKFLOW - You MUST complete this entire sequence before providing your final explanation:

1. EXTRACT KEYWORDS:
   - Call extractKeywords() with the provided code to identify important terms
   - Wait for the result before proceeding

2. SEARCH FOR USAGE:
   - Call searchCode() for each important keyword found (at least 3-5 searches)
   - Use patterns like: searchCode("MethodName", "*.java", "")
   - Wait for each search to complete before the next one

3. READ RELATED FILES:
   - If you find interesting files in search results, call readFile() to examine them
   - Focus on files that help understand how the original code is used

4. RECORD EXECUTION STEPS:
   - Call takeNote() for each step of how the code works internally
   - Record at least 3 detailed notes about execution flow
   - Focus on: "Step X: [what happens mechanically]"

5. PROVIDE FINAL EXPLANATION:
   - Only after completing ALL tool calls, provide your comprehensive explanation
   - Structure it as a step-by-step walkthrough of code execution

EXECUTION FOCUS - For takeNote(), record details like:
- "Step 1: Method validates input parameter X by checking if it's null or empty"
- "Step 2: Creates connection object using Y configuration and sets timeout to Z"
- "Step 3: Loops through collection A, for each item calling method B with parameter C"
- "Step 4: Transforms result from format X to format Y using mapper Z"
- "Error handling: If connection fails, method throws XException and logs error"

SEARCH STRATEGY:
- Search for class names to find where code is instantiated
- Search for method names to find all callers
- Search for key variables to understand data flow
- Search for return types to see how results are used

Complete ALL tool usage before giving your final explanation. Do not provide the explanation until you have gathered all the data through tools.
""")
        @dev.langchain4j.agentic.Agent
        String explainCode(String request);
    }

    /**
     * Explain the given code and its interactions
     */
    @NotNull
    public CompletableFuture<CodeExplanationResult> explainCode(@NotNull String filePath,
                                                               @NotNull String codeContent,
                                                               @NotNull String language,
                                                               @Nullable Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting code explanation for: " + filePath);
                
                // Notify UI that we're starting
                notifyStart();
                sendToUI("🔍 Analyzing code and finding related components...\n\n");

                // Reset tools for new session
                explanationTools.reset();
                explanationTools.setProgressCallback(progressCallback);

                // Build the explanation request
                String explanationRequest = buildExplanationRequest(filePath, codeContent, language);
                
                // Send the initial request to UI
                sendToUI("📋 Request: Explain code in " + filePath + "\n\n");
                sendToUI("🤖 Assistant Analysis:\n");
                sendToUI("-".repeat(50) + "\n");

                // Let LangChain4j handle the explanation with streaming
                String response;
                try {
                    response = assistant.explainCode(explanationRequest);
                    sendToUI(response);
                    sendToUI("\n" + "-".repeat(50) + "\n");
                    notifyComplete();
                } catch (Exception e) {
                    LOG.warn("Code explanation agent encountered an error", e);
                    sendToUI("\n⚠️ Explanation agent stopped: " + e.getMessage());
                    response = "Error during explanation: " + e.getMessage();
                }

                // Build result from gathered data
                CodeExplanationResult result = buildExplanationResult(filePath, codeContent, language, response, explanationTools.getGatheredData());
                
                LOG.debug("Code explanation complete. Components found: " + result.getRelatedComponents().size());
                
                return result;

            } catch (Exception e) {
                LOG.error("Failed to explain code", e);
                sendToUI("\n❌ Code explanation failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Code explanation failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Build the explanation request with code context
     */
    private String buildExplanationRequest(String filePath, String codeContent, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Explain the following code and its interactions with the rest of the codebase.\n\n");
        
        prompt.append("File: ").append(filePath).append("\n");
        prompt.append("Language: ").append(language).append("\n");
        prompt.append("Code length: ").append(codeContent.length()).append(" characters\n\n");
        
        prompt.append("Code to explain:\n");
        prompt.append("```").append(language.toLowerCase()).append("\n");
        prompt.append(codeContent);
        prompt.append("\n```\n\n");
        
        prompt.append("Please provide a comprehensive explanation covering:\n");
        prompt.append("1. What this code does and why it exists\n");
        prompt.append("2. How it works internally\n");
        prompt.append("3. Where and how it's used in the codebase\n");
        prompt.append("4. What dependencies it has\n");
        prompt.append("5. Its role in the overall architecture\n");
        
        return prompt.toString();
    }

    /**
     * Build the explanation result from gathered data
     */
    private CodeExplanationResult buildExplanationResult(String filePath, String codeContent, String language, 
                                                        String explanation, Map<String, Object> gatheredData) {
        try {
            // Extract data from gathered information with defensive programming
            System.out.println("[DEBUG] Building result from gathered data:");
            for (String key : gatheredData.keySet()) {
                Object value = gatheredData.get(key);
                System.out.println("  " + key + ": " + value.getClass().getSimpleName() + " = " + value);
            }
            
            List<String> keywords = ensureStringList(gatheredData.get("extractedKeywords"));
            List<String> relatedFiles = ensureStringList(gatheredData.get("relatedFiles"));
            List<String> usagePatterns = ensureStringList(gatheredData.get("usagePatterns"));
            List<String> notes = ensureStringList(gatheredData.get("explanationNotes"));
            
            @SuppressWarnings("unchecked")
            Map<String, String> readFiles = (Map<String, String>) gatheredData.getOrDefault("readFiles", new HashMap<>());

            return new CodeExplanationResult(
                    filePath,
                    language,
                    codeContent,
                    explanation,
                    keywords,
                    relatedFiles,
                    usagePatterns,
                    notes,
                    readFiles // This gets mapped to relatedComponents in the constructor
            );
            
        } catch (Exception e) {
            LOG.error("Failed to build explanation result", e);
            // Return a minimal result on error
            return new CodeExplanationResult(
                    filePath,
                    language,
                    codeContent,
                    explanation,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    List.of("Error building result: " + e.getMessage()),
                    new HashMap<>()
            );
        }
    }

    /**
     * Safely convert an object to a List<String>, handling various input types
     */
    private List<String> ensureStringList(Object obj) {
        System.out.println("[DEBUG] ensureStringList input: " + 
            (obj != null ? obj.getClass().getSimpleName() + " = " + obj : "null"));
            
        if (obj == null) {
            System.out.println("[DEBUG] Returning empty list for null input");
            return new ArrayList<>();
        }
        
        if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) obj;
            System.out.println("[DEBUG] Returning List<String> with " + list.size() + " items");
            return new ArrayList<>(list);
        } else if (obj instanceof String[]) {
            // If we get a String array directly, convert it
            String[] array = (String[]) obj;
            List<String> stringList = new ArrayList<>();
            for (String s : array) {
                stringList.add(s);
            }
            System.out.println("[DEBUG] Converted String[] to List<String> with " + stringList.size() + " items");
            return stringList;
        } else {
            // For any other type, create a single-item list
            List<String> stringList = new ArrayList<>();
            stringList.add(obj.toString());
            System.out.println("[DEBUG] Converted " + obj.getClass().getSimpleName() + " to single-item List<String>");
            return stringList;
        }
    }

    /**
     * Result object containing comprehensive code explanation data
     */
    public static class CodeExplanationResult {
        private final String filePath;
        private final String language;
        private final String codeContent;
        private final String explanation;
        private final List<String> extractedKeywords;
        private final List<String> relatedFiles;
        private final List<String> usagePatterns;
        private final List<String> notes;
        private final Map<String, String> relatedComponents;

        public CodeExplanationResult(String filePath, String language, String codeContent, String explanation,
                                   List<String> extractedKeywords, List<String> relatedFiles, 
                                   List<String> usagePatterns, List<String> notes, 
                                   Map<String, String> relatedComponents) {
            this.filePath = filePath;
            this.language = language;
            this.codeContent = codeContent;
            this.explanation = explanation;
            this.extractedKeywords = new ArrayList<>(extractedKeywords);
            this.relatedFiles = new ArrayList<>(relatedFiles);
            this.usagePatterns = new ArrayList<>(usagePatterns);
            this.notes = new ArrayList<>(notes);
            this.relatedComponents = new HashMap<>(relatedComponents);
        }

        // Getters
        public String getFilePath() { return filePath; }
        public String getLanguage() { return language; }
        public String getCodeContent() { return codeContent; }
        public String getExplanation() { return explanation; }
        public List<String> getExtractedKeywords() { return new ArrayList<>(extractedKeywords); }
        public List<String> getRelatedFiles() { return new ArrayList<>(relatedFiles); }
        public List<String> getUsagePatterns() { return new ArrayList<>(usagePatterns); }
        public List<String> getNotes() { return new ArrayList<>(notes); }
        public Map<String, String> getRelatedComponents() { return new HashMap<>(relatedComponents); }
    }
}