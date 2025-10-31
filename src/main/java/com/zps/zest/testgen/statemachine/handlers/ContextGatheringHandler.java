package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.ContextAgent;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.tools.AnalyzeClassTool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.openai.internal.chat.ToolMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the context gathering phase of test generation.
 * Analyzes target files and collects relevant context information.
 */
public class ContextGatheringHandler extends AbstractStateHandler {

    private static final boolean DEBUG_HANDLER_EXECUTION = true;

    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    private ContextAgent contextAgent;
    
    public ContextGatheringHandler() {
        this(null, null);
    }
    
    public ContextGatheringHandler(Consumer<String> streamingCallback) {
        this(streamingCallback, null);
    }
    
    public ContextGatheringHandler(Consumer<String> streamingCallback, 
                                  com.zps.zest.testgen.ui.StreamingEventListener uiEventListener) {
        super(TestGenerationState.GATHERING_CONTEXT);
        this.streamingCallback = streamingCallback;
        this.uiEventListener = uiEventListener;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        if (DEBUG_HANDLER_EXECUTION) {
            System.out.println("[DEBUG_HANDLER] ContextGatheringHandler.executeState START, sessionId=" +
                stateMachine.getSessionId());
        }

        try {
            TestGenerationRequest request = stateMachine.getRequest();
            if (request == null) {
                if (DEBUG_HANDLER_EXECUTION) {
                    System.out.println("[DEBUG_HANDLER] ContextGatheringHandler FAILED: no request");
                }
                return StateResult.failure("No request found", false);
            }
            
            // Initialize context agent
            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);
            ContextAgent contextAgent = new ContextAgent(getProject(stateMachine), langChainService, naiveLlmService);

            // Store as field for direct access
            this.contextAgent = contextAgent;

            // Save BEFORE checkpoint - capture initial state before context gathering
            try {
                String promptDescription = "Generate tests for " + request.getTargetFile().getName() +
                    " (" + request.getTargetMethods().size() + " methods)";
                com.zps.zest.testgen.snapshot.AgentSnapshot beforeSnapshot = contextAgent.exportSnapshot(
                    stateMachine.getSessionId(),
                    "Before context gathering - about to analyze " + request.getTargetFile().getName(),
                    promptDescription
                );
                java.io.File snapshotFile = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.saveCheckpoint(
                    beforeSnapshot,
                    getProject(stateMachine),
                    com.zps.zest.testgen.snapshot.CheckpointTiming.BEFORE
                );
                LOG.info("Saved BEFORE checkpoint: " + snapshotFile.getName());
            } catch (Exception e) {
                LOG.warn("Failed to save BEFORE checkpoint (non-critical)", e);
            }

            // Pre-load user-provided context if available (must be done first to check for target file)
            preloadUserContext(stateMachine, contextAgent);

            // Analyze target class and store in session data for later use in context prompt
            analyzeAndStoreTargetClass(stateMachine);

            // PRE-COMPUTE deterministic operations before AI starts
            preComputeContext(stateMachine, contextAgent, request);

            // Set up UI event listener for real-time context tab updates
            if (uiEventListener != null) {
                contextAgent.setEventListener(uiEventListener);
                // Notify UI of phase start
                uiEventListener.onPhaseStarted(getHandledState());
            }
            
            // Set up progress callback for context updates
            Consumer<Map<String, Object>> contextUpdateCallback = contextData -> {
                if (contextData != null && contextAgent != null) {
                    // Use direct tool access instead of hardcoded map lookups
                    com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();
                    int analyzedClasses = contextTools.getAnalyzedClasses().size();
                    int notes = contextTools.getContextNotes().size();
                    int files = contextTools.getReadFiles().size();
                    
                    int totalItems = analyzedClasses + notes + files;
                    if (totalItems > 0) {
                        int progressPercent = Math.min(90, 20 + (totalItems * 5)); // Cap at 90% during gathering
                        logToolActivity(stateMachine, "ContextAgent", 
                            String.format("Context: %d classes, %d notes, %d files", analyzedClasses, notes, files));
                        
                        // Send streaming update if callback available
                        if (streamingCallback != null) {
                            streamingCallback.accept(String.format("📁 Context analysis: %d classes, %d notes, %d files analyzed\n",
                                analyzedClasses, notes, files));
                            triggerContextUIUpdates(contextAgent);
                        }

                        // Don't trigger UI updates here - they're sent individually by tools
                    }
                }
            };
            
            logToolActivity(stateMachine, "ContextAgent", "Starting context analysis");
            
            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🔍 Starting context analysis...\n");
            }
            
            // All context data is now available through contextAgent.getContextTools()
            // No need for session data - data passes through contextAgent directly

            // Execute context gathering with error handling
            CompletableFuture<Void> contextFuture = contextAgent.gatherContext(
                request,
                contextUpdateCallback
            );
            
            // Wait for context gathering to complete
            contextFuture.join();

            // Final UI update with all gathered context
            if (uiEventListener != null) {
                triggerContextUIUpdates(contextAgent);
            }

            // Context data available via getContextAgent().getContextTools()

            int totalItems = contextAgent.getContextTools().getAnalyzedClasses().size() +
                            contextAgent.getContextTools().getContextNotes().size() +
                            contextAgent.getContextTools().getReadFiles().size();
            String summary = String.format("Context gathered: %d items analyzed", totalItems);
            LOG.info(summary);

            // Save AFTER checkpoint for debugging and prompt experimentation
            try {
                String promptDescription = "Generate tests for " + request.getTargetFile().getName() +
                    " (" + request.getTargetMethods().size() + " methods)";
                com.zps.zest.testgen.snapshot.AgentSnapshot snapshot = contextAgent.exportSnapshot(
                    stateMachine.getSessionId(),
                    "After context gathering - " + totalItems + " items analyzed",
                    promptDescription,
                    request  // Pass request to save metadata
                );
                java.io.File snapshotFile = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.saveCheckpoint(
                    snapshot,
                    getProject(stateMachine),
                    com.zps.zest.testgen.snapshot.CheckpointTiming.AFTER
                );
                LOG.info("Saved AFTER checkpoint: " + snapshotFile.getName());
            } catch (Exception e) {
                LOG.warn("Failed to save AFTER checkpoint (non-critical)", e);
            }

            if (DEBUG_HANDLER_EXECUTION) {
                System.out.println("[DEBUG_HANDLER] ContextGatheringHandler SUCCESS: " + summary +
                    ", nextState=PLANNING_TESTS, sessionId=" + stateMachine.getSessionId());
            }
            return StateResult.success(null, summary, TestGenerationState.PLANNING_TESTS);

        } catch (Exception e) {
            if (DEBUG_HANDLER_EXECUTION) {
                System.out.println("[DEBUG_HANDLER] ContextGatheringHandler EXCEPTION: " +
                    e.getMessage() + ", sessionId=" + stateMachine.getSessionId());
            }
            LOG.error("Context gathering failed", e);

            // Check if this is a recoverable error
            boolean recoverable = isRecoverableError(e);

            return StateResult.failure(e, recoverable,
                "Failed to gather context: " + e.getMessage());
        }
    }
    
    /**
     * Helper to trigger file analyzed event for UI.
     */
    private void triggerFileAnalyzedEvent(ContextAgent contextAgent, String filePath, String content) {
        if (uiEventListener == null) return;
        var displayData = contextAgent.getContextTools().createContextDisplayData(filePath, content);
        uiEventListener.onFileAnalyzed(displayData);
    }

    /**
     * Analyze the target class and store in session data for later inclusion in context prompt.
     * Also adds to ContextAgent tools for proper tracking and UI updates.
     */
    private void analyzeAndStoreTargetClass(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            TestGenerationRequest request = stateMachine.getRequest();
            ContextAgent contextAgent = this.contextAgent;
            if (request == null || contextAgent == null) return;

            String targetFilePath = request.getTargetFile().getVirtualFile().getPath();

            // Check if this file is already in analyzedClasses (user might have provided it)
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();
            if (contextTools.getAnalyzedClasses().containsKey(targetFilePath)) {
                LOG.info("Target class already analyzed, skipping duplicate analysis");
                return;
            }

            LOG.info("Analyzing target class: " + targetFilePath);

            // Analyze the target class using ContextAgent's tool (already properly initialized)
            String analysis = contextTools.analyzeClass(targetFilePath);

            // Get actual target methods from the request (PsiMethod objects)
            java.util.List<String> targetMethodNames = new java.util.ArrayList<>();
            for (com.intellij.psi.PsiMethod method : request.getTargetMethods()) {
                targetMethodNames.add(method.getName());
            }
            // Target methods available via request.getTargetMethods()

            if (streamingCallback != null) {
                streamingCallback.accept("📋 Analyzed target class: " + request.getTargetFile().getName() + "\n");
            }

            // Update UI if needed
            if (uiEventListener != null) {
                triggerFileAnalyzedEvent(contextAgent, targetFilePath, analysis);
            }

        } catch (Exception e) {
            LOG.warn("Failed to analyze target class, continuing with context gathering", e);
        }
    }

    /**
     * Extract method names from class analysis for context focus.
     */
    private java.util.List<String> extractMethodsFromAnalysis(@NotNull String analysis) {
        java.util.List<String> methods = new java.util.ArrayList<>();

        // Look for method signatures in the analysis
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

    /**
     * Pre-load user-provided context into the context agent's tools.
     */
    private void preloadUserContext(@NotNull TestGenerationStateMachine stateMachine,
                                   @NotNull ContextAgent contextAgent) {
        try {
            // Get user-provided files from the request
            TestGenerationRequest request = stateMachine.getRequest();
            if (request == null) return;

            List<String> userFiles = request.getUserProvidedFiles();
            String userCode = request.getUserProvidedCode();
            String targetFilePath = request.getTargetFile().getVirtualFile().getPath();

            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();

            // Pre-load user-provided files
            if (userFiles != null && !userFiles.isEmpty()) {
                LOG.info("Pre-loading " + userFiles.size() + " user-provided files");

                // Store user-provided files for inclusion in context prompt
                java.util.Map<String, String> userProvidedFilesMap = new java.util.HashMap<>();

                for (String filePath : userFiles) {
                    try {
                        String content = contextTools.readFile(filePath);
                        if (content != null) {
                            // Add to user-provided files map (except target file which gets special handling)
                            if (!filePath.equals(targetFilePath)) {
                                userProvidedFilesMap.put(filePath, content);
                            }

                            // Check if this is the target file and analyze it properly
                            if (filePath.equals(targetFilePath)) {
                                LOG.info("User provided target file - analyzing with AnalyzeClassTool");

                                // Analyze using ContextAgent's tool (already properly initialized)
                                String analysis = contextTools.analyzeClass(targetFilePath);

                                // Get actual target methods from the request (PsiMethod objects)
                                java.util.List<String> targetMethodNames = new java.util.ArrayList<>();
                                for (com.intellij.psi.PsiMethod method : request.getTargetMethods()) {
                                    targetMethodNames.add(method.getName());
                                }
                                // Target methods available via request.getTargetMethods()
                            }
                            // Note: User-provided files will be accessible via context tools

                            if (streamingCallback != null) {
                                streamingCallback.accept("📂 Loaded: " + filePath + "\n");
                            }

                            // Update UI
                            if (uiEventListener != null) {
                                triggerFileAnalyzedEvent(contextAgent, filePath, content);
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to pre-load user file: " + filePath, e);
                    }
                }

                // User-provided files are accessible via contextTools.getReadFiles()
            }

            // Pre-load user code snippets
            if (userCode != null && !userCode.trim().isEmpty()) {
                contextTools.takeNotes(Collections.singletonList("User context: \n" + userCode));
                // User code is accessible via contextTools.getContextNotes()

                if (streamingCallback != null) {
                    streamingCallback.accept("📝 Added code snippets\n");
                }

                if (uiEventListener != null) {
                    triggerFileAnalyzedEvent(contextAgent, "User Code", userCode);
                }
            }


        } catch (Exception e) {
            LOG.warn("Error pre-loading user context, continuing with normal context gathering", e);
        }
    }

    /**
     * Determine if an error during context gathering is recoverable
     */
    private boolean isRecoverableError(@NotNull Exception e) {
        // JSON parsing errors from LLM responses are usually recoverable
        if (e.getCause() instanceof com.google.gson.JsonSyntaxException) {
            return true;
        }
        
        // Network timeouts and connection issues are recoverable
        if (e.getMessage() != null && 
            (e.getMessage().contains("timeout") || 
             e.getMessage().contains("connection") ||
             e.getMessage().contains("network"))) {
            return true;
        }
        
        // LangChain4j-related errors are usually recoverable (retry with different prompt)
        if (e.getClass().getName().contains("langchain4j")) {
            return true;
        }
        
        // File access errors are usually not recoverable
        if (e instanceof java.io.IOException) {
            return false;
        }
        
        // Default to recoverable for most exceptions
        return true;
    }
    
    @Override
    public boolean isRetryable() {
        return true;
    }
    
    @Override
    public boolean isSkippable() {
        return true; // Context gathering can be skipped if user provides manual context
    }

    @Override
    public void cancel() {
        super.cancel();
        if (contextAgent != null) {
            contextAgent.cancel();
            LOG.info("Cancelled ContextAgent");
        }
    }

    /**
     * Get the context agent (direct access instead of session data)
     */
    @Nullable
    public ContextAgent getContextAgent() {
        return contextAgent;
    }

    /**
     * Set the context agent (for checkpoint restoration)
     */
    public void setContextAgent(@NotNull ContextAgent agent) {
        this.contextAgent = agent;
    }

    /**
     * Trigger UI updates for context gathering using direct tool access
     */
    private void triggerContextUIUpdates(ContextAgent contextAgent) {
        try {
            if (contextAgent != null) {
                com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();

                // Get analyzed classes and trigger file analyzed events
                Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
                for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
                    var displayData = contextTools.createContextDisplayData(entry.getKey(), entry.getValue());
                    uiEventListener.onFileAnalyzed(displayData);
                }

                // Get context notes and trigger updates
                java.util.List<String> contextNotes = contextTools.getContextNotes();
                for (String note : contextNotes) {
                    var displayData = contextTools.createContextDisplayData("Note", note);
                    uiEventListener.onFileAnalyzed(displayData);
                }
            }


        } catch (Exception e) {
            LOG.warn("Error triggering context UI updates", e);
        }
    }

    /**
     * Pre-compute deterministic operations before AI exploration starts.
     * This saves AI tool calls and provides richer initial context.
     */
    private void preComputeContext(@NotNull TestGenerationStateMachine stateMachine,
                                   @NotNull ContextAgent contextAgent,
                                   @NotNull TestGenerationRequest request) {
        try {
            LOG.info("Pre-computing context data (dependencies, usage analysis, framework)");

            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();

            // 1. Find and analyze project dependencies (was AI tool findProjectDependencies())
            if (streamingCallback != null) {
                streamingCallback.accept("📦 Analyzing project dependencies...\n");
            }

            String projectDeps = preComputeDependencies(contextTools);

            if (projectDeps != null && !projectDeps.isEmpty()) {
                contextTools.takeNotes(Collections.singletonList("[DEPENDENCY_ANALYSIS] " + projectDeps));
                LOG.info("Pre-computed project dependencies: " + projectDeps.length() + " characters");

                if (streamingCallback != null) {
                    streamingCallback.accept("✅ Project dependencies analyzed\n");
                }
            }

            // 2. Analyze target method usages (moved from CoordinatorAgent line 267)
            if (streamingCallback != null) {
                streamingCallback.accept("🔍 Analyzing target method usage patterns...\n");
            }

            contextTools.analyzeMethodUsages(request.getTargetMethods());

            LOG.info("Pre-computed usage analysis for " + request.getTargetMethods().size() + " target methods");

            if (streamingCallback != null) {
                streamingCallback.accept("✅ Usage analysis complete\n");
            }

            // 3. Detect framework (now has enough data: target class + dependencies)
            contextTools.detectFramework();
            String framework = contextTools.getFrameworkInfo();
            if (framework != null && !framework.equals("Unknown")) {
                LOG.info("Pre-detected framework: " + framework);

                if (streamingCallback != null) {
                    streamingCallback.accept("🔧 Detected framework: " + framework + "\n");
                }
            }

            LOG.info("Pre-computation complete - AI will have rich initial context");

        } catch (Exception e) {
            LOG.warn("Error during pre-computation, continuing with AI exploration", e);
            if (streamingCallback != null) {
                streamingCallback.accept("⚠️ Pre-computation partially failed: " + e.getMessage() + "\n");
            }
        }
    }

    /**
     * Pre-compute project dependencies by finding and reading build files.
     * This is deterministic file I/O that doesn't require AI.
     */
    private String preComputeDependencies(com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools) {
        try {
            // Use contextTools.findProjectDependencies() directly (it's the tool implementation)
            // Pass dummy params as it doesn't use them
            return contextTools.findProjectDependencies("");

        } catch (Exception e) {
            LOG.warn("Failed to pre-compute dependencies", e);
            return null;
        }
    }
}