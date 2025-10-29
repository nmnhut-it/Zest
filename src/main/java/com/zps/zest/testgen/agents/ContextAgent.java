package com.zps.zest.testgen.agents;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.testgen.analysis.UsageAnalyzer;
import com.zps.zest.testgen.analysis.UsageContext;
import com.zps.zest.testgen.tools.AnalyzeClassTool;
import com.zps.zest.testgen.tools.ListFilesTool;
import com.zps.zest.testgen.tools.TakeNoteTool;
import com.zps.zest.langchain4j.tools.impl.ReadFileTool;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.ui.model.ContextDisplayData;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI Agent responsible for gathering project context and understanding codebase.
 *
 * This agent acts as the "explorer" - it can navigate the project structure,
 * search for code patterns, analyze classes, and build a mental model of
 * the codebase architecture.
 *
 * Key capabilities:
 * - File system navigation (list files, find files)
 * - Code search with ripgrep (regex patterns, context lines)
 * - Class analysis (structure, dependencies, methods)
 * - Note-taking for important findings
 * - Build file analysis (pom.xml, build.gradle)
 *
 * The agent maintains internal state of explored classes and findings,
 * which can be accessed by the coordinator agent.
 */
public class ContextAgent extends StreamingBaseAgent {
    private static final Logger LOG = Logger.getInstance(ContextAgent.class);
    private static final int DEFAULT_MAX_TOOLS_PER_RESPONSE = 20;

    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextGatheringTools contextTools;
    private final ContextGatheringAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    private int maxToolsPerResponse = DEFAULT_MAX_TOOLS_PER_RESPONSE;

    public ContextAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull NaiveLLMService naiveLlmService) {
        this(project, langChainService, naiveLlmService, DEFAULT_MAX_TOOLS_PER_RESPONSE);
    }

    public ContextAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull NaiveLLMService naiveLlmService,
                        int maxToolsPerResponse) {
        super(project, langChainService, naiveLlmService, "ContextAgent");
        this.maxToolsPerResponse = maxToolsPerResponse;
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.contextTools = new ContextGatheringTools(project, toolRegistry, this::sendToUI, this);

        // Build the agent with streaming support
        // LangChain4j will handle the conversation and tool orchestration
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(100);

        // Load system prompt from markdown file
        String systemPrompt = loadSystemPrompt(project);

        // Add system message to chat memory
        this.chatMemory.add(dev.langchain4j.data.message.SystemMessage.from(systemPrompt));

        this.assistant = AgenticServices
                .agentBuilder(ContextGatheringAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(maxToolsPerResponse*2) // Use the same limit as told to AI
                .chatMemory(chatMemory)
                .tools(contextTools)
                .build();
    }

    /**
     * Compatibility constructor for older code (Kotlin) - delegates to main constructor.
     */
    public ContextAgent(Project project, Object langChainService, Object llmService) {
        this(project, (ZestLangChain4jService) langChainService, (NaiveLLMService) llmService);
    }

    /**
     * Load system prompt from plugin resources (packaged in JAR).
     */
    private static String loadSystemPrompt(Project project) {
        try {
            // Load from plugin resources (works in both dev and deployed JAR)
            java.io.InputStream resourceStream = ContextAgent.class.getClassLoader()
                .getResourceAsStream("prompts/context-agent.md");

            if (resourceStream != null) {
                String content = new String(resourceStream.readAllBytes(),
                                           java.nio.charset.StandardCharsets.UTF_8);
                resourceStream.close();
                LOG.info("Loaded context-agent prompt from resources (" + content.length() + " chars)");
                return content;
            } else {
                LOG.warn("Resource prompts/context-agent.md not found in plugin JAR, using fallback");
            }
        } catch (Exception e) {
            LOG.warn("Failed to load prompt from resources, using fallback", e);
        }

        // Fallback to embedded minimal prompt
        return """
            # Context Gathering Agent

            You gather context for test generation.

            **GOAL**: Understand target methods - gather usage patterns, error handling, integration context.

            ## Workflow
            1. Call `findProjectDependencies()` first
            2. Call `createExplorationPlan()` and build plan with `addPlanItems([...])`
            3. Execute plan systematically
            4. Call `markContextCollectionDone()` when complete

            Track progress after each tool call. Call `getPlanStatus()` to check remaining items.
            """;
    }

    /**
     * Streamlined interface - LangChain4j handles the conversation loop internally
     */
    public interface ContextGatheringAssistant {
        @dev.langchain4j.agentic.Agent
        String gatherContext(String request);
    }

    /**
     * Gather context for test generation using the AI assistant.
     * Returns a completed future since the AI handles everything.
     */
    public CompletableFuture<Void> gatherContext(TestGenerationRequest request,
                                                 Consumer<Map<String, Object>> updateCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting context gathering with LangChain4j orchestration");

                // Build the context request FIRST (includes pre-computed data from ContextGatheringHandler)
                String contextRequest = buildContextRequest(request);

                // Then reset tools for new session (preserves pre-computed data in the prompt already built)
                // Note: Pre-computed usage analysis, framework, and dependencies are already in contextRequest
                contextTools.reset();
                contextTools.setContextUpdateCallback(updateCallback);

                // Send initial request to UI
                sendToUI("🔍 Gathering context for test generation...\n\n");
                sendToUI("📋 Request: " + contextRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Keep gathering context until explicitly marked as done
                int maxIterations = 7; // Allow deeper investigation with planning and validation
                int iteration = 0;

                while (!contextTools.isContextCollectionDone() && iteration < maxIterations) {
                    // Check cancellation at start of each iteration
                    checkCancellation();

                    iteration++;

                    try {
                        // Inject pre-hook: Remind AI of current task and checklist
                        String preHook = contextTools.explorationPlanningTool.getPreToolHook();
                        if (!preHook.isEmpty()) {
                            sendToUI("\n📍 **Status Check**\n" + preHook + "\n\n");
                        }

                        // For first iteration, use full context request; for subsequent iterations, be explicit
                        String promptToUse;
                        if (iteration == 1) {
                            promptToUse = contextRequest;
                        } else {
                            // Build explicit continuation prompt with remaining plan items
                            StringBuilder continuation = new StringBuilder();
                            continuation.append("Continue with your exploration plan.\n\n");

                            // Get incomplete plan items
                            java.util.List<String> incompletePlanItems = contextTools.explorationPlanningTool.getIncompletePlanItems();
                            if (!incompletePlanItems.isEmpty()) {
                                continuation.append("**Remaining Plan Items:**\n");
                                for (String item : incompletePlanItems) {
                                    continuation.append("- ").append(item).append("\n");
                                }
                                continuation.append("\n");
                            }

                            continuation.append("**Your Task:**\n");
                            continuation.append("1. Review the incomplete items above\n");
                            continuation.append("2. For each item: execute the investigation, take notes with findings\n");
                            continuation.append("3. Use completePlanItems() to mark multiple items done at once (saves tokens)\n");
                            continuation.append("4. If you already completed items but forgot to mark them done, call completePlanItems() NOW with all findings\n");
                            continuation.append("5. When ALL plan items are complete, call markContextCollectionDone()\n");

                            promptToUse = continuation.toString();
                        }

                        // Append pre-hook to prompt if available
                        if (!preHook.isEmpty() && iteration > 1) {
                            promptToUse = promptToUse + "\n\n" + preHook;
                        }

                        // Check cancellation before making assistant call
                        checkCancellation();

                        // Let LangChain4j handle the conversation with streaming
                        String response = assistant.gatherContext(promptToUse);
                        sendToUI(response);
                        sendToUI("\n" + "-".repeat(40) + "\n");

                        // Inject post-hook: Show progress and what's next
                        String postHook = contextTools.explorationPlanningTool.getPostToolHook();
                        if (!postHook.isEmpty()) {
                            sendToUI("\n" + postHook + "\n");
                        }

                        // Check if context collection is now done
                        if (contextTools.isContextCollectionDone()) {
                            sendToUI("✅ Context collection marked as complete by assistant.\n");
                            break;
                        }

                        // If not done and not at max iterations, show continuation message
                        if (!contextTools.isContextCollectionDone() && iteration < maxIterations) {
                            sendToUI("\n🔄 Continuing context gathering (iteration " + (iteration + 1) + ")...\n");
                        }

                    } catch (java.util.concurrent.CancellationException e) {
                        LOG.info("Context gathering cancelled by user");
                        sendToUI("\n🚫 Context gathering cancelled by user.\n");
                        throw e; // Re-throw to propagate cancellation
                    } catch (Exception e) {
                        LOG.warn("Context agent encountered an error but continuing", e);
                        sendToUI("\n⚠️ Context agent stopped: " + e.getMessage());
                        sendToUI("\nContinuing with available context...\n");
                        break; // Exit loop on error
                    }
                }

                if (iteration >= maxIterations && !contextTools.isContextCollectionDone()) {
                    LOG.warn("Context gathering reached maximum iterations without completion");
                    sendToUI("\n⚠️ Context gathering reached maximum iterations. Proceeding with gathered context.\n");
                }

                return null;

            } catch (Exception e) {
                LOG.error("Failed to gather context", e);
                sendToUI("\n❌ Context gathering failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Context gathering failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Build the initial context request with complete target class information.
     * Follows LLM best practices: clear hierarchy, pre-computed data first, task instructions last.
     */
    private String buildContextRequest(TestGenerationRequest request) {
        StringBuilder prompt = new StringBuilder();

        // SECTION 1: Task Configuration
        appendTaskConfiguration(prompt);

        // SECTION 2: Pre-Computed Analysis (CRITICAL - what we already know)
        appendPreComputedAnalysis(prompt, request);

        // SECTION 3: Target Code Information
        appendTargetInformation(prompt, request);

        // SECTION 4: User-Provided Context
        appendUserContext(prompt, request);

        // SECTION 5: Existing Context Notes
        appendExistingFindings(prompt);

        // SECTION 6: Task Instructions
        appendTaskInstructions(prompt, request);

        return prompt.toString();
    }

    /**
     * Section 1: Task configuration and constraints
     */
    private void appendTaskConfiguration(StringBuilder prompt) {
        prompt.append("## Task Configuration\n\n");
        prompt.append("**Tool Budget**: ").append(maxToolsPerResponse).append(" calls per response\n\n");
        prompt.append("---\n\n");
    }

    /**
     * Section 2: Pre-computed analysis - the most valuable context
     */
    private void appendPreComputedAnalysis(StringBuilder prompt, TestGenerationRequest request) {
        prompt.append("## Pre-Computed Analysis\n\n");
        prompt.append("*The following context has been pre-analyzed. Build on this foundation, don't rediscover it.*\n\n");

        // Method usage analysis (MOST CRITICAL)
        Map<String, com.zps.zest.testgen.analysis.UsageContext> methodUsages = contextTools.getMethodUsages();
        if (!methodUsages.isEmpty()) {
            prompt.append("### Method Usage Patterns\n\n");
//            prompt.append("This is an exhaustive list of static references. If usages of method under test are of uttermost");
//            prompt.append("importance, try searching for implicit call. Otherwise, use this list only.");
            for (Map.Entry<String, com.zps.zest.testgen.analysis.UsageContext> entry : methodUsages.entrySet()) {
                com.zps.zest.testgen.analysis.UsageContext usageContext = entry.getValue();
                prompt.append(usageContext.formatForLLM());
                prompt.append("\n");
            }
        }

        // Framework detection
        String framework = contextTools.getFrameworkInfo();
        if (framework != null && !framework.isEmpty() && !framework.equals("Unknown")) {
            prompt.append("### Detected Testing Framework\n\n");
            prompt.append("**Framework**: ").append(framework).append("\n\n");
        }

        // Project dependencies (structured)
        String dependencies = contextTools.getProjectDependencies();
        if (dependencies != null && !dependencies.isEmpty()) {
            prompt.append("### Project Dependencies\n\n");
            prompt.append(dependencies);
            prompt.append("\n");
        }

        prompt.append("---\n\n");
    }

    /**
     * Section 3: Target code information
     */
    private void appendTargetInformation(StringBuilder prompt, TestGenerationRequest request) {
        prompt.append("## Target Code\n\n");

        String targetFilePath = request.getTargetFile().getVirtualFile().getPath();

        // Target class analysis
        String targetClassAnalysis = getOrAnalyzeTargetClass(targetFilePath);
        if (targetClassAnalysis != null && !targetClassAnalysis.trim().isEmpty()) {
            prompt.append("### Target Class Analysis\n\n");
            prompt.append("```java\n");
            prompt.append(targetClassAnalysis);
            prompt.append("\n```\n\n");
            prompt.append("*Note: Target class already analyzed - do not re-analyze.*\n\n");
        }

        // Target methods
        if (!request.getTargetMethods().isEmpty()) {
            prompt.append("### Target Methods to Test\n\n");
            java.util.List<String> methodNames = new java.util.ArrayList<>();
            for (com.intellij.psi.PsiMethod method : request.getTargetMethods()) {
                methodNames.add(method.getName());
            }
            prompt.append("Focus on: **").append(String.join(", ", methodNames)).append("**\n\n");
        }

        prompt.append("**Target file**: `").append(targetFilePath).append("`\n");
        if (request.hasSelection()) {
            prompt.append("*User selected specific code to test.*\n");
        }

        prompt.append("\n---\n\n");
    }

    /**
     * Section 4: User-provided context (files, code snippets)
     */
    private void appendUserContext(StringBuilder prompt, TestGenerationRequest request) {
        String targetFilePath = request.getTargetFile().getVirtualFile().getPath();
        boolean hasUserFiles = request.getUserProvidedFiles() != null && !request.getUserProvidedFiles().isEmpty();
        boolean hasUserContext = request.hasUserProvidedContext();

        if (hasUserFiles || hasUserContext) {
            prompt.append("## User-Provided Context\n\n");

            if (hasUserContext) {
                prompt.append("*User provided additional context in chat history - prioritize it.*\n\n");
            }

            if (hasUserFiles) {
                appendUserProvidedFiles(prompt, request.getUserProvidedFiles(), targetFilePath);
            }

            prompt.append("---\n\n");
        }
    }

    /**
     * Section 5: Existing context notes and findings
     */
    private void appendExistingFindings(StringBuilder prompt) {
        java.util.List<String> contextNotes = contextTools.getContextNotes();
        if (!contextNotes.isEmpty()) {
            prompt.append("## Existing Context Notes\n\n");
            appendContextNotes(prompt, contextNotes);
            prompt.append("---\n\n");
        }
    }

    /**
     * Section 6: Task instructions - what the AI should do
     */
    private void appendTaskInstructions(StringBuilder prompt, TestGenerationRequest request) {
        prompt.append("## Your Task\n\n");
        prompt.append("**Objective**: Gather additional context needed for comprehensive test generation.\n\n");
        prompt.append("**Approach**:\n");
        prompt.append("1. Review the pre-computed analysis above\n");
        prompt.append("2. Identify gaps in understanding (referenced files, edge cases, integration patterns)\n");
        prompt.append("3. Build an exploration plan focusing on what's NOT yet known\n");
        prompt.append("4. Execute systematically and take structured notes\n\n");
        prompt.append("Focus on understanding how target methods are used in real scenarios. ");
        prompt.append("Build on the pre-computed analysis - don't rediscover what's already known.\n\n");

        // Final task restatement with specific target methods
        prompt.append("---\n\n");
        prompt.append("**Remember your specific goal**: ");

        // Include target method names
        if (!request.getTargetMethods().isEmpty()) {
            java.util.List<String> methodNames = new java.util.ArrayList<>();
            for (com.intellij.psi.PsiMethod method : request.getTargetMethods()) {
                methodNames.add(method.getName());
            }
            prompt.append("Gather context for methods: **").append(String.join(", ", methodNames)).append("**. ");
        }

        prompt.append("Focus on **gaps in the pre-computed analysis** above - find what's NOT yet known. ");
        prompt.append("Call `markContextCollectionDone()` when you have sufficient context to test these methods without assumptions.\n");
    }

    private String getOrAnalyzeTargetClass(String targetFilePath) {
        String analysis = contextTools.getAnalyzedClass(targetFilePath);
        if (analysis == null) {
            contextTools.analyzeClass(targetFilePath);
            analysis = contextTools.getAnalyzedClass(targetFilePath);
        }
        return analysis;
    }

    private void appendUserProvidedFiles(StringBuilder prompt, @NotNull List<String> userProvidedFiles, String targetFilePath) {
        if (userProvidedFiles.isEmpty()) {
            return;
        }

        prompt.append("### User-Provided Files\n\n");
        for (String filePath : userProvidedFiles) {
            if (filePath.equals(targetFilePath)) {
                continue; // Skip target file as it's already analyzed
            }
            prompt.append("**File**: `").append(extractFileName(filePath)).append("`\n\n");
            prompt.append("```\n");
            contextTools.readFile(filePath);
            prompt.append(contextTools.readFiles.get(filePath));
            prompt.append("\n```\n\n");
        }
        prompt.append("*Note: Files above provided by user - do not re-read.*\n\n");
    }

    private void appendContextNotes(StringBuilder prompt, java.util.List<String> contextNotes) {
        if (contextNotes.isEmpty()) {
            return;
        }

        for (String note : contextNotes) {
            prompt.append("- ").append(note).append("\n");
        }
        prompt.append("\n");
    }

    private String extractFileName(String filePath) {
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    /**
     * Get context tools for direct access (used by other components).
     */
    public ContextGatheringTools getContextTools() {
        return contextTools;
    }

    /**
     * Get the chat memory for this agent.
     */
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }

    /**
     * Get the maximum number of tools allowed per response.
     */
    public int getMaxToolsPerResponse() {
        return maxToolsPerResponse;
    }

    /**
     * Set the maximum number of tools allowed per response.
     */
    public void setMaxToolsPerResponse(int maxToolsPerResponse) {
        this.maxToolsPerResponse = maxToolsPerResponse;
    }

    /**
     * Set event listener (backward compatibility).
     */
    public void setEventListener(Object listener) {
        // No-op for backward compatibility
    }

    /**
     * Send context file analysis to UI.
     */
    protected void sendContextFileAnalyzed(ContextDisplayData data) {
        // Send to UI through streaming callback if available
        sendToUI("📄 Analyzed: " + data.getFileName() + "\n");
    }

    /**
     * Context gathering tools - actual implementation of all tool methods.
     * This is a static inner class that contains all the @Tool annotated methods
     * that the AI assistant can call.
     */
    public static class ContextGatheringTools {
        private final Project project;
        private final CodeExplorationToolRegistry toolRegistry;
        private String sessionId;
        private Consumer<String> toolNotifier;
        private Consumer<Map<String, Object>> contextUpdateCallback;
        private ContextAgent contextAgent; // Reference to parent agent

        // Shared data storage - thread-safe collections for concurrent access
        private final Map<String, String> analyzedClasses = new ConcurrentHashMap<>();
        private final Map<String, String> pathToFQN = new ConcurrentHashMap<>(); // Maps file path -> FQN
        private final List<String> contextNotes = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, String> readFiles = new ConcurrentHashMap<>();
        private final Map<String, String> buildFiles = new ConcurrentHashMap<>();  // Separate storage for build files
        private final Map<String, UsageContext> methodUsages = new ConcurrentHashMap<>(); // NEW: Usage context per method
        private String frameworkInfo = "";
        private String projectDependencies = "";  // Structured dependency information from build files
        private volatile boolean contextCollectionDone = false;

        // Caller tracking for validation
        private final java.util.Set<String> discoveredCallers = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private final java.util.Set<String> investigatedCallers = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private final java.util.Set<String> referencedFiles = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        // Individual tool instances
        private final AnalyzeClassTool analyzeClassTool;
        private final ListFilesTool listFilesTool;
        private final RipgrepCodeTool ripgrepCodeTool;
        private final TakeNoteTool takeNoteTool;
        private final ReadFileTool readFileTool;
        private final com.zps.zest.testgen.tools.LookupMethodTool lookupMethodTool;
        private final com.zps.zest.testgen.tools.LookupClassTool lookupClassTool;
        private final UsageAnalyzer usageAnalyzer; // NEW: Analyzer for method usage patterns
        private final com.zps.zest.testgen.tools.AnalyzeMethodUsageTool analyzeMethodUsageTool; // NEW: Expose usage analysis
        final com.zps.zest.testgen.tools.ExplorationPlanningTool explorationPlanningTool; // NEW: Planning workflow (package-private for hook access)

        public ContextGatheringTools(@NotNull Project project,
                                    @NotNull CodeExplorationToolRegistry toolRegistry,
                                    @Nullable Consumer<String> toolNotifier,
                                    @Nullable ContextAgent contextAgent) {
            this.project = project;
            this.toolRegistry = toolRegistry;
            this.toolNotifier = toolNotifier;
            this.contextAgent = contextAgent;

            // Initialize tools with shared data
            this.analyzeClassTool = new AnalyzeClassTool(project, analyzedClasses, pathToFQN);
            this.listFilesTool = new ListFilesTool(project);
            this.takeNoteTool = new TakeNoteTool(contextNotes);
            this.ripgrepCodeTool = new RipgrepCodeTool(project, new HashSet<>(), new ArrayList<>());
            this.readFileTool = new ReadFileTool(project);
            this.lookupMethodTool = new com.zps.zest.testgen.tools.LookupMethodTool(project);
            this.lookupClassTool = new com.zps.zest.testgen.tools.LookupClassTool(project);
            this.usageAnalyzer = new UsageAnalyzer(project);
            this.analyzeMethodUsageTool = new com.zps.zest.testgen.tools.AnalyzeMethodUsageTool(project);
            this.explorationPlanningTool = new com.zps.zest.testgen.tools.ExplorationPlanningTool();
        }

        public void reset() {
            analyzedClasses.clear();
            pathToFQN.clear();
            contextNotes.clear();
            readFiles.clear();
            buildFiles.clear();
            methodUsages.clear();
            discoveredCallers.clear();
            investigatedCallers.clear();
            referencedFiles.clear();
            frameworkInfo = "";
            projectDependencies = "";
            contextCollectionDone = false;
            explorationPlanningTool.reset();
        }

        public boolean isContextCollectionDone() {
            return contextCollectionDone;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public void setContextUpdateCallback(Consumer<Map<String, Object>> callback) {
            this.contextUpdateCallback = callback;
        }

        public Map<String, Object> getGatheredData() {
            Map<String, Object> data = new HashMap<>();
            data.put("analyzedClasses", new HashMap<>(analyzedClasses));
            data.put("contextNotes", new ArrayList<>(contextNotes));
            data.put("readFiles", new HashMap<>(readFiles));
            return data;
        }

        // Getters for backward compatibility
        public List<String> getContextNotes() {
            return new ArrayList<>(contextNotes);
        }

        public Map<String, String> getAnalyzedClasses() {
            return new HashMap<>(analyzedClasses);
        }

        /**
         * Get analyzed class by file path or FQN.
         * Tries FQN first, then checks if it's a path and converts to FQN.
         */
        @Nullable
        public String getAnalyzedClass(String pathOrFQN) {
            // Try direct lookup first (could be FQN)
            String result = analyzedClasses.get(pathOrFQN);
            if (result != null) {
                return result;
            }

            // Check if it's a file path that we have a mapping for
            String fqn = pathToFQN.get(pathOrFQN);
            if (fqn != null) {
                return analyzedClasses.get(fqn);
            }

            return null;
        }

        public Map<String, String> getReadFiles() {
            return new HashMap<>(readFiles);
        }

        public Map<String, String> getBuildFiles() {
            return new HashMap<>(buildFiles);
        }

        public String getFrameworkInfo() {
            detectFramework();
            return frameworkInfo;
        }

        public String getProjectDependencies() {
            return projectDependencies;
        }

        /**
         * Get usage context for methods.
         * Returns map of method signature -> usage context.
         */
        @NotNull
        public Map<String, UsageContext> getMethodUsages() {
            return new HashMap<>(methodUsages);
        }

        /**
         * Analyze usage patterns for target methods.
         * This should be called after initial context gathering is complete.
         *
         * @param targetMethods The methods to analyze usage for
         */
        public void analyzeMethodUsages(@NotNull List<PsiMethod> targetMethods) {
            LOG.info("Analyzing usage patterns for " + targetMethods.size() + " target methods");

            for (PsiMethod method : targetMethods) {
                try {
                    ReadAction.run(() -> {
                        String methodKey = getMethodKey(method);
                        UsageContext usageContext = usageAnalyzer.analyzeMethod(method);
                        methodUsages.put(methodKey, usageContext);

                        LOG.info("Usage analysis for " + methodKey + ": " +
                                usageContext.getTotalUsages() + " call sites, " +
                                usageContext.getDiscoveredEdgeCases().size() + " edge cases");
                    });
                } catch (Exception e) {
                    LOG.warn("Failed to analyze usage for method: " + e.getMessage(), e);
                }
            }
        }

        /**
         * Get or analyze usage context for a specific method.
         */
        @Nullable
        public UsageContext getUsageContext(@NotNull PsiMethod method) {
            return ReadAction.compute(() -> {
                String methodKey = getMethodKey(method);
                UsageContext existing = methodUsages.get(methodKey);

                if (existing == null) {
                    try {
                        existing = usageAnalyzer.analyzeMethod(method);
                        methodUsages.put(methodKey, existing);
                    } catch (Exception e) {
                        LOG.warn("Failed to get usage context for method: " + e.getMessage());
                    }
                }

                return existing;
            });
        }

        /**
         * Generate a unique key for a method.
         */
        @NotNull
        private String getMethodKey(@NotNull PsiMethod method) {
            String className = method.getContainingClass() != null ?
                    method.getContainingClass().getQualifiedName() : "Unknown";
            return className + "#" + method.getName();
        }

        /**
         * Detect testing framework from analyzed classes and build files
         */
        public void detectFramework() {
            String allContent = String.join("\n", analyzedClasses.values()) +
                               String.join("\n", readFiles.values());

            if (allContent.contains("org.junit.jupiter") || allContent.contains("@Test") && allContent.contains("jupiter")) {
                frameworkInfo = "JUnit 5";
            } else if (allContent.contains("org.junit.Test") || allContent.contains("junit:junit")) {
                frameworkInfo = "JUnit 4";
            } else if (allContent.contains("testng") || allContent.contains("org.testng")) {
                frameworkInfo = "TestNG";
            } else if (allContent.contains("spring-boot-starter-test") || allContent.contains("@SpringBootTest")) {
                frameworkInfo = "Spring Boot Test";
            } else {
                frameworkInfo = "JUnit 5"; // Default fallback
            }
        }

        private void notifyTool(String toolName, String params) {
            if (toolNotifier != null) {
                SwingUtilities.invokeLater(() ->
                    toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params)));
            }
        }

        private void notifyContextUpdate() {
            if (contextUpdateCallback != null) {
                SwingUtilities.invokeLater(() ->
                    contextUpdateCallback.accept(getGatheredData()));
            }
        }

        @Tool("""
            Analyze a Java class to extract complete structure, methods, fields, and dependencies.
            ⚠️ EXPENSIVE: This performs deep analysis - use sparingly for key classes only.

            INPUT FORMATS (tries in order):
            1. Fully qualified name: "com.example.service.UserService" (best)
            2. File path: "/src/main/java/com/example/UserService.java"
            3. Simple name: "UserService" (may find wrong class if multiple exist)

            RETURNS: Complete class source code, structure, javadoc, and all dependencies.

            TIPS: Use findFiles() or searchCode() first if unsure of exact class name.
            """)
        public String analyzeClass(String filePathOrClassName) {
            notifyTool("analyzeClass", filePathOrClassName);
            explorationPlanningTool.recordToolUse();
            String result = analyzeClassTool.analyzeClass(filePathOrClassName);

            // Create and send ContextDisplayData object directly to UI
            if (contextAgent != null) {
                ContextDisplayData contextData = createContextDisplayData(filePathOrClassName, result);
                contextAgent.sendContextFileAnalyzed(contextData);
            }

            notifyContextUpdate();
            return result;
        }

        @Tool("""
            List files and subdirectories in a directory with controlled recursion depth.

            Parameters:
            - directoryPath: The directory to list (e.g., "config/", "src/main/resources")
            - recursiveLevel: How deep to explore:
              * 0 = current directory only (non-recursive) - use for large dirs like src/
              * 1 = current + immediate subdirectories - use for most exploration
              * 2 = two levels deep - use for config/templates structures
              * 3+ = deeper exploration - use only when absolutely necessary

            Examples:
            - listFiles("config/", 0) → lists only files directly in config/
            - listFiles("config/", 2) → explores config/ and 2 levels deep
            - listFiles("src/main/resources", 1) → resources/ and immediate subdirs
            - listFiles("scripts/", 1) → scripts/ and immediate subdirs
            """)
        public String listFiles(String directoryPath, Integer recursiveLevel) {
            try {
                // Validate parameters
                if (directoryPath == null || directoryPath.trim().isEmpty()) {
                    return "Error: directoryPath cannot be null or empty";
                }

                // Default recursiveLevel to 1 if null
                if (recursiveLevel == null) {
                    recursiveLevel = 1;
                }

                // Validate recursiveLevel range
                if (recursiveLevel < 0 || recursiveLevel > 10) {
                    return "Error: recursiveLevel must be between 0 and 10";
                }

                notifyTool("listFiles", directoryPath + " (level=" + recursiveLevel + ")");
                explorationPlanningTool.recordToolUse();
                return listFilesTool.listFiles(directoryPath, recursiveLevel);
            } catch (Exception e) {
                LOG.error("Error in listFiles tool", e);
                return "Error listing files in " + directoryPath + ": " + e.getMessage();
            }
        }

        @Tool("""
            Find files by NAME/PATH matching glob patterns. Searches file names, NOT contents.

            Use comma-separated patterns for efficiency:
            - "pom.xml,build.gradle,*.properties,*.yml" → Build/config files
            - "*Test.java,*Tests.java,*IT.java" → Test files
            - "*Service.java,*Repository.java,*Controller.java" → Source files
            """)
        public String findFiles(String pattern) {
            notifyTool("findFiles", pattern);
            explorationPlanningTool.recordToolUse();
            return ripgrepCodeTool.findFiles(pattern);
        }

        @Tool("""
            Code search with regex patterns and context lines.

            KEY: query=REGEX (use | for OR), filePattern=GLOB (use comma for multiple)
            ⚠️ Escape special chars: "method\\(" not "method(", "Class\\." not "Class."

            Common Patterns:
            • Find calls: "methodName\\(" or "(save|update|delete)\\("
            • Find instantiations: "new ClassName\\(" or "ClassName\\."
            • Find usages with args: "getUserById\\([^)]*\\)"
            • Find test methods: "@Test.*void.*test"
            • Find assertions: "assert(Equals|True|NotNull)\\("

            Parameters:
            - query: Regex pattern
            - filePattern: File glob (e.g., "*.java,*.kt")
            - excludePattern: Exclude glob (e.g., "test,generated")
            - beforeLines/afterLines: Context lines (0-10)
            - multiline: Cross-line patterns (slower, default: false)

            Examples:
            - searchCode("TODO|FIXME", "*.java", null, 0, 0, false)
            - searchCode("new UserService\\(", "*.java", "test", 2, 5, false)
            """)
        public String searchCode(String query, String filePattern, String excludePattern,
                                Integer beforeLines, Integer afterLines, Boolean multiline) {
            try {
                // Validate required parameters
                if (query == null || query.trim().isEmpty()) {
                    return "Error: query cannot be null or empty";
                }

                // Default integer parameters if null
                if (beforeLines == null) beforeLines = 0;
                if (afterLines == null) afterLines = 0;

                // Validate ranges
                if (beforeLines < 0 || beforeLines > 10) {
                    return "Error: beforeLines must be between 0 and 10";
                }
                if (afterLines < 0 || afterLines > 10) {
                    return "Error: afterLines must be between 0 and 10";
                }

                notifyTool("searchCode", String.format("query=%s, filePattern=%s, excludePattern=%s, before=%d, after=%d, multiline=%s",
                                                       query, filePattern, excludePattern, beforeLines, afterLines, multiline));
                explorationPlanningTool.recordToolUse();
                return ripgrepCodeTool.searchCode(query, filePattern, excludePattern, beforeLines, afterLines, multiline);
            } catch (Exception e) {
                LOG.error("Error in searchCode tool", e);
                return "Error searching code with query '" + query + "': " + e.getMessage();
            }
        }

        @Tool("""
            Record project-specific findings for test generation.
            Capture ACTUAL patterns from THIS codebase with specific examples.

            Categories: [DEPENDENCY] libraries/tools, [TEST] test patterns, [ERROR] error handling,
            [PATTERN] code structure, [NAMING] conventions, [CONFIG] configuration,
            [VALIDATION] input validation, [BUSINESS] domain rules, [INTEGRATION] component interaction.

            Focus on 'this project does X' not 'best practice is X'.

            Parameters:
            - notes: List of notes to record

            Example:
            takeNotes([
              "[DEPENDENCY] Uses JUnit 5, Mockito, AssertJ",
              "[UserService.java:45] [USAGE] depends on external API",
              "[OrderProcessor.java:123] [INTEGRATION] uses async processing",
              "[AuthFilter.java:67] [ERROR] JWT tokens with 1hr expiry"
            ])
            """)
        public String takeNotes(List<String> notes) {
            notifyTool("takeNotes", notes.size() + " notes");
            explorationPlanningTool.recordToolUse();
            String result = takeNoteTool.takeNotes(notes);
            notifyContextUpdate();
            return result;
        }

        @Tool("Read the complete content of any file in the project")
        public String readFile(String filePath) {
            notifyTool("readFile", filePath);
            explorationPlanningTool.recordToolUse();

            // Use ReadFileTool with JsonObject parameter
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.addProperty("filePath", filePath);

            var toolResult = readFileTool.execute(params);
            String result = toolResult.isSuccess() ? toolResult.getContent() : toolResult.getContent();

            // Ensure result is never null or blank for LangChain4j
            if (result == null || result.trim().isEmpty()) {
                result = "File not found or empty: " + filePath;
            }

            // Store in readFiles map
            readFiles.put(filePath, result);

            // Also send file read as context update
            if (contextAgent != null && !result.startsWith("File not found")) {
                ContextDisplayData contextData = createContextDisplayData(filePath, result);
                contextAgent.sendContextFileAnalyzed(contextData);
            }

            notifyContextUpdate();
            return result;
        }

        /**
         * Read a build file without storing it in readFiles map.
         * This prevents duplication - build files should only be in buildFiles map.
         */
        private String readBuildFile(String filePath) {
            var params = new com.google.gson.JsonObject();
            params.addProperty("filePath", filePath);

            var toolResult = readFileTool.execute(params);
            String result = toolResult.isSuccess() ? toolResult.getContent() : toolResult.getContent();

            // Ensure result is never null or blank
            if (result == null || result.trim().isEmpty()) {
                result = "File not found or empty: " + filePath;
            }

            // Store in buildFiles map instead of readFiles
            buildFiles.put(filePath, result);

            return result;
        }

        @Tool("Mark context collection as complete when you have gathered sufficient context to test the code without assumptions. " +
              "Call this tool when you are confident that you have all necessary information.")
        public String markContextCollectionDone() {
            notifyTool("markContextCollectionDone", "");

            // VALIDATION 1: Check if plan exists and is complete
            if (!explorationPlanningTool.allPlanItemsComplete()) {
                java.util.List<String> incomplete = explorationPlanningTool.getIncompletePlanItems();
                if (!incomplete.isEmpty()) {
                    return "❌ Cannot mark done - Exploration plan incomplete.\n\n" +
                           "Remaining items:\n" + String.join("\n", incomplete) + "\n\n" +
                           "**What to do:**\n" +
                           "1. Review each remaining item above\n" +
                           "2. If you ALREADY completed items but forgot to mark them done, use completePlanItems() NOW (batch operation saves tokens)\n" +
                           "3. If items are NOT yet done, execute investigations, take notes, then use completePlanItems() to mark multiple at once\n" +
                           "4. Only after ALL items are marked complete can you call markContextCollectionDone()";
                }
            }

            // VALIDATION 2: Check caller investigation depth
            // If callers were discovered but not investigated, that's incomplete
            int discovered = discoveredCallers.size();
            int investigated = investigatedCallers.size();
            if (discovered > 0 && investigated < discovered) {
                return String.format("❌ Cannot mark done - Caller investigation incomplete.\n\n" +
                                   "Found %d caller references but only fully investigated %d.\n" +
                                   "You must investigate ALL discovered callers to understand usage patterns.\n\n" +
                                   "Use readFile() with targeted reading or searchCode() with adequate context.",
                                   discovered, investigated);
            }

            // VALIDATION 3: Check referenced files
            // If files were referenced (like SQL schemas) but not read, that's incomplete
            java.util.List<String> unresolvedRefs = getUnresolvedFileReferences();
            if (!unresolvedRefs.isEmpty()) {
                return "❌ Cannot mark done - Referenced files not read.\n\n" +
                       "The following files were referenced but not investigated:\n" +
                       String.join("\n", unresolvedRefs) + "\n\n" +
                       "Read these files to understand the complete context.";
            }

            // VALIDATION 4: Check minimum depth of investigation
            // Allow completion without notes if plan is empty (pre-computed data sufficient)
            int totalNotes = contextNotes.size();
            int totalFiles = readFiles.size() + analyzedClasses.size();
            boolean hasExplorationPlan = !explorationPlanningTool.getIncompletePlanItems().isEmpty() ||
                                        explorationPlanningTool.getToolsUsed() > 1; // More than just createPlan

            if (totalNotes < 1 && hasExplorationPlan) {
                return "❌ Cannot mark done - Investigation too shallow.\n\n" +
                       "Only " + totalNotes + " notes taken. This suggests insufficient exploration.\n" +
                       "If pre-computed data is sufficient, create an empty plan.\n" +
                       "Otherwise, use take note tools to take structured notes about:\n" +
                       "- [USAGE] patterns discovered\n" +
                       "- [ERROR] handling approaches\n" +
                       "- [SCHEMA] constraints and structure\n" +
                       "- [INTEGRATION] component interactions";
            }
            // All validations passed - mark as done
            contextCollectionDone = true;

            int totalItems = analyzedClasses.size() + contextNotes.size() + readFiles.size();
            String summary = String.format("""
                ✅ Context collection completed successfully!

                Summary:
                - Classes analyzed: %d
                - Notes taken: %d
                - Files read: %d
                - Callers investigated: %d
                - Tool budget used: %.0f%%

                Context is ready for test generation.
                """,
                analyzedClasses.size(),
                contextNotes.size(),
                readFiles.size(),
                investigatedCallers.size(),
                explorationPlanningTool.getBudgetUsedPercent());

            notifyContextUpdate();
            return summary;
        }

        /**
         * Get list of file references that haven't been resolved.
         * Checks for common resource file patterns referenced in code.
         */
        private java.util.List<String> getUnresolvedFileReferences() {
            java.util.List<String> unresolved = new ArrayList<>();

            // Common external resource file extensions to check
            String[] resourceExtensions = {
                ".sql",        // Database schemas/migrations
                ".yml", ".yaml", // Config files
                ".properties", // Config files
                ".xml",        // Config/schema files
                ".json",       // Config/data files
                ".graphql",    // GraphQL schemas
                ".proto",      // Protobuf definitions
                ".avsc",       // Avro schemas
                ".xsd"         // XML schemas
            };

            // Check analyzed classes for file references
            for (String analyzedClass : analyzedClasses.values()) {
                for (String ext : resourceExtensions) {
                    if (analyzedClass.contains(ext)) {
                        // Skip false positives (like SQLException, JsonProperty, etc.)
                        if (isFalsePositive(analyzedClass, ext)) {
                            continue;
                        }

                        // Extract file references using quoted string pattern
                        String[] lines = analyzedClass.split("\n");
                        for (String line : lines) {
                            if (line.contains(ext) && line.contains("\"")) {
                                extractAndCheckFileReference(line, ext, unresolved);
                            }
                        }
                    }
                }
            }

            return unresolved;
        }

        /**
         * Check if this is a false positive (class name, exception, etc. not a file reference).
         */
        private boolean isFalsePositive(String content, String extension) {
            switch (extension) {
                case ".sql":
                    return content.contains("SQLException") || content.contains("SQLIntegrityConstraint");
                case ".json":
                    return content.contains("JsonProperty") || content.contains("JsonNode");
                case ".xml":
                    return content.contains("XMLHttpRequest") || content.contains("XMLParser");
                default:
                    return false;
            }
        }

        /**
         * Extract file reference from a line and check if it was read.
         */
        private void extractAndCheckFileReference(String line, String extension, java.util.List<String> unresolved) {
            int extIndex = line.indexOf(extension);
            if (extIndex <= 0) return;

            // Look for quoted string containing the file reference
            int quoteStart = line.lastIndexOf('"', extIndex);
            int quoteEnd = line.indexOf('"', extIndex);

            if (quoteStart >= 0 && quoteEnd > extIndex) {
                String fileRef = line.substring(quoteStart + 1, quoteEnd);

                // Only consider if it looks like a file path (contains / or just a filename)
                if (fileRef.contains("/") || fileRef.contains(extension)) {
                    referencedFiles.add(fileRef);

                    // Check if we actually read this file
                    final String finalFileRef = fileRef;
                    boolean wasRead = readFiles.keySet().stream()
                            .anyMatch(k -> k.contains(finalFileRef) || k.endsWith(finalFileRef));

                    if (!wasRead && !unresolved.contains(fileRef)) {
                        unresolved.add(fileRef);
                    }
                }
            }
        }

        @Tool("""
            Look up method signatures by fully qualified class name and method name.
            Works with JARs/JDK (ripgrep only searches source files).

            Examples:
            - lookupMethod("java.util.List", "add")
            - lookupMethod("org.junit.jupiter.api.Assertions", "assertEquals")
            """)
        public String lookupMethod(String className, String methodName) {
            notifyTool("lookupMethod", className + "." + methodName);
            explorationPlanningTool.recordToolUse();
            return lookupMethodTool.lookupMethod(className, methodName);
        }

        @Tool("""
            Look up class structure by fully qualified class name.
            Works with JARs/JDK (ripgrep only searches source files).

            Examples:
            - lookupClass("java.util.ArrayList")
            - lookupClass("org.junit.jupiter.api.Test")
            """)
        public String lookupClass(String className) {
            notifyTool("lookupClass", className);
            explorationPlanningTool.recordToolUse();
            return lookupClassTool.lookupClass(className);
        }

        @Tool("Find and analyze project build files (pom.xml, build.gradle, *.iml) to understand available test frameworks and dependencies. " +
              "This should be called at the start to understand what testing libraries are available in the project.")
        public String findProjectDependencies(String dummyParams) {
            notifyTool("findProjectDependencies", "Searching for build files");
            explorationPlanningTool.recordToolUse();

            StringBuilder dependencyInfo = new StringBuilder();
            dependencyInfo.append("**PROJECT DEPENDENCIES**\n");
            dependencyInfo.append("```\n");

            // Find build files using existing findFiles tool
            String buildFilePatterns = "pom.xml,build.gradle,build.gradle.kts,settings.gradle,*.iml,build.xml,.classpath";
            String buildFilesFound = findFiles(buildFilePatterns);

            if (buildFilesFound.contains("No files found")) {
                dependencyInfo.append("No build files found. Cannot determine project dependencies.");
                dependencyInfo.append("\n```\n");
                projectDependencies = dependencyInfo.toString();
                return projectDependencies;
            }

            // Parse the file list - look for lines with "Full path:" to get absolute paths
            String[] lines = buildFilesFound.split("\n");
            List<String> buildFiles = new ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Full path:")) {
                    // Extract the full path for reading
                    String fullPath = line.substring("Full path:".length()).trim();
                    buildFiles.add(fullPath);
                }
            }

            // Read all build files and include their FULL content
            StringBuilder allDependencyContent = new StringBuilder();

            for (String buildFile : buildFiles) {
                String fileName = buildFile.substring(Math.max(buildFile.lastIndexOf('/'), buildFile.lastIndexOf('\\')) + 1);
                dependencyInfo.append("Found: ").append(fileName).append(" at ").append(buildFile).append("\n");

                // Read the ENTIRE file content using readBuildFile (stores in buildFiles map, not readFiles)
                String fileContent = readBuildFile(buildFile);

                // Add the full content with clear markers
                allDependencyContent.append("\n**").append(fileName).append("**\n");
                allDependencyContent.append("File: ").append(buildFile).append("\n");
                allDependencyContent.append(fileContent);
                allDependencyContent.append("\n---\n");
            }

            // Check for JAR files in lib folders
            StringBuilder jarFilesList = new StringBuilder();
            String[] libFolders = {"lib", "libs"};

            for (String libFolder : libFolders) {
                String libContents = listFiles(libFolder, 1);
                if (!libContents.contains("Error") && !libContents.contains("not found")) {
                    jarFilesList.append("\n**JAR files in ").append(libFolder).append(" folder**\n");
                    String[] linesJar = libContents.split("\n");
                    for (String line : linesJar) {
                        if (line.endsWith(".jar")) {
                            String jarName = line.substring(Math.max(line.lastIndexOf("/"), line.lastIndexOf("\\")) + 1);
                            jarFilesList.append(jarName).append("\n");
                        }
                    }
                }
            }

            if (jarFilesList.length() > 0) {
                allDependencyContent.append(jarFilesList.toString());
            }

            // Create a comprehensive note with ALL dependency information for the AI to interpret
            if (allDependencyContent.length() > 0) {
                dependencyInfo.append(allDependencyContent.toString());
                dependencyInfo.append("```\n\n");
                dependencyInfo.append("All dependency information from build files and lib folders has been recorded.\n");
                dependencyInfo.append("The AI will interpret these dependencies to understand available frameworks and libraries.\n");

                String comprehensiveNote = "[PROJECT_DEPENDENCIES] Complete dependency information from build files:\n" +
                                          allDependencyContent.toString();
                takeNotes(java.util.Collections.singletonList(comprehensiveNote));
            } else {
                dependencyInfo.append("⚠️ No dependency information found in build files\n");
                dependencyInfo.append("```\n");
                takeNotes(java.util.Collections.singletonList("[WARNING] No build files found - cannot determine project dependencies"));
            }

            projectDependencies = dependencyInfo.toString();
            notifyContextUpdate();
            return projectDependencies;
        }

        @Tool("""
            Analyze real-world usage patterns for a method to understand how it's actually used.
            This tool finds all call sites and provides comprehensive context about usage patterns,
            error handling, edge cases, and integration patterns.

            Essential for understanding:
            - How callers use the method
            - What error conditions are expected
            - What test scenarios matter
            - Real-world edge cases

            Parameters:
            - className: Fully qualified class name (e.g., "com.example.ProfileStorageService")
            - methodName: Method name (e.g., "getProfile")
            - maxCallSites: Optional limit (default 20)

            Example: analyzeMethodUsage("com.example.ProfileStorageService", "getProfile", 20)
            """)
        public String analyzeMethodUsage(String className, String methodName, Integer maxCallSites) {
            notifyTool("analyzeMethodUsage", className + "." + methodName);
            explorationPlanningTool.recordToolUse();
            String result = analyzeMethodUsageTool.analyzeMethodUsage(className, methodName, maxCallSites);
            notifyContextUpdate();
            return result;
        }

        @Tool("""
            Create a structured exploration plan before gathering context.
            Call this FIRST (after findProjectDependencies) to organize your investigation.

            Parameters:
            - targetInfo: What you're investigating (e.g., "getProfile and saveProfile methods")
            - toolBudget: Tool calls available this session (from initial request)
            """)
        public String createExplorationPlan(String targetInfo, Integer toolBudget) {
            notifyTool("createExplorationPlan", targetInfo);
            return explorationPlanningTool.createExplorationPlan(targetInfo, toolBudget);
        }

        @Tool("""
            Add an item to your exploration plan.

            Parameters:
            - category: [DEPENDENCY, USAGE, SCHEMA, TESTS, ERROR, INTEGRATION, VALIDATION, OTHER]
            - description: What to investigate
            - toolsNeeded: Estimated tools needed (optional)
            """)
        public String addPlanItem(String category, String description, Integer toolsNeeded) {
            return explorationPlanningTool.addPlanItem(category, description, toolsNeeded);
        }

        @Tool("""
            Mark a plan item complete and record findings.

            Parameters:
            - itemId: Plan item ID (from addPlanItem)
            - findings: What you discovered
            """)
        public String completePlanItem(int itemId, String findings) {
            return explorationPlanningTool.completePlanItem(itemId, findings);
        }

        @Tool("""
            Add multiple items to the exploration plan in one call (batch operation).

            SAVES TOOL CALLS: Use this instead of calling addPlanItem() multiple times.

            Parameters:
            - items: List of plan items, each with category, description, and optional toolsNeeded

            Example:
            addPlanItems([
              {category: "DEPENDENCY", description: "Find test frameworks", toolsNeeded: 1},
              {category: "USAGE", description: "Analyze getProfile usage", toolsNeeded: 3},
              {category: "SCHEMA", description: "Read user_profiles.sql", toolsNeeded: 1}
            ])

            Return type: String confirmation with item IDs
            """)
        public String addPlanItems(List<com.zps.zest.testgen.tools.ExplorationPlanningTool.PlanItemInput> items) {
            notifyTool("addPlanItems", items.size() + " items");
            return explorationPlanningTool.addPlanItems(items);
        }

        @Tool("""
            Mark multiple plan items as complete in one call (batch operation).

            SAVES TOOL CALLS: Use this instead of calling completePlanItem() multiple times.

            Parameters:
            - completions: List of completions, each with itemId and findings

            Example:
            completePlanItems([
              {itemId: 1, findings: "Found JUnit 5, Mockito, AssertJ"},
              {itemId: 2, findings: "getProfile called from 5 locations"},
              {itemId: 3, findings: "Schema has user_id primary key"}
            ])

            Return type: String summary of progress
            """)
        public String completePlanItems(List<com.zps.zest.testgen.tools.ExplorationPlanningTool.PlanItemCompletion> completions) {
            notifyTool("completePlanItems", completions.size() + " items");
            return explorationPlanningTool.completePlanItems(completions);
        }

        @Tool("""
            Check plan progress - what's done, what's pending, budget remaining.
            """)
        public String getPlanStatus() {
            return explorationPlanningTool.getPlanStatus();
        }


        public ContextDisplayData createContextDisplayData(String filePath, String analysisResult) {
            // Extract file name
            String fileName = filePath;
            int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                fileName = filePath.substring(lastSlash + 1);
            }

            // Extract summary (first paragraph or first 200 chars)
            String summary = analysisResult;
            if (summary.length() > 200) {
                int firstNewline = summary.indexOf('\n');
                if (firstNewline > 0 && firstNewline < 200) {
                    summary = summary.substring(0, firstNewline);
                } else {
                    summary = summary.substring(0, 200) + "...";
                }
            }

            // Extract classes and methods from analysis
            List<String> classes = extractClasses(analysisResult);
            List<String> methods = extractMethods(analysisResult);

            return new ContextDisplayData(
                filePath,
                fileName,
                ContextDisplayData.AnalysisStatus.COMPLETED,
                summary,
                analysisResult, // full analysis
                classes,
                methods,
                Collections.emptyList(), // dependencies
                System.currentTimeMillis()
            );
        }

        private List<String> extractClasses(String analysis) {
            List<String> classes = new ArrayList<>();
            // Look for class declarations
            String[] lines = analysis.split("\n");
            for (String line : lines) {
                if (line.contains("class ") && (line.contains("public") || line.contains("private"))) {
                    int classIndex = line.indexOf("class ") + 6;
                    int endIndex = line.indexOf(' ', classIndex);
                    if (endIndex == -1) endIndex = line.indexOf('{', classIndex);
                    if (endIndex > classIndex) {
                        String className = line.substring(classIndex, endIndex).trim();
                        if (!className.isEmpty()) {
                            classes.add(className);
                        }
                    }
                }
            }
            return classes;
        }

        private List<String> extractMethods(String analysis) {
            List<String> methods = new ArrayList<>();
            // Look for method signatures
            String[] lines = analysis.split("\n");
            for (String line : lines) {
                if ((line.contains("public ") || line.contains("private ") || line.contains("protected "))
                    && line.contains("(") && line.contains(")") && !line.contains("class ")) {
                    // Try to extract method name
                    int parenIndex = line.indexOf('(');
                    if (parenIndex > 0) {
                        int spaceBeforeParen = line.lastIndexOf(' ', parenIndex);
                        if (spaceBeforeParen >= 0 && spaceBeforeParen < parenIndex) {
                            String methodName = line.substring(spaceBeforeParen + 1, parenIndex).trim();
                            if (!methodName.isEmpty() && !methodName.contains(" ")) {
                                methods.add(methodName);
                            }
                        }
                    }
                }
            }
            return methods;
        }
    }
}