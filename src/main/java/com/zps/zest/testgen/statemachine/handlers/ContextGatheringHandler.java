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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the context gathering phase of test generation.
 * Analyzes target files and collects relevant context information.
 */
public class ContextGatheringHandler extends AbstractStateHandler {
    
    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    
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
        try {
            TestGenerationRequest request = (TestGenerationRequest) getSessionData(stateMachine, "request");
            if (request == null) {
                return StateResult.failure("No request found in session data", false);
            }
            
            // Initialize context agent
            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);
            ContextAgent contextAgent = new ContextAgent(getProject(stateMachine), langChainService, naiveLlmService);

            // Store the ContextAgent in session data for UI access
            stateMachine.setSessionData("contextAgent", contextAgent);

            // Pre-load user-provided context if available (must be done first to check for target file)
            preloadUserContext(stateMachine, contextAgent);

            // Analyze the target class and add to chat memory (will skip if already in user context)
            analyzeTargetClass(stateMachine, contextAgent);

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
            
            // Execute context gathering with error handling
            CompletableFuture<Void> contextFuture = contextAgent.gatherContext(
                request,
                    // No test plan at this stage
                    contextUpdateCallback
            );
            
            // Wait for context gathering to complete
            contextFuture.join();

            // Final UI update with all gathered context
            if (uiEventListener != null) {
                triggerContextUIUpdates(contextAgent);
            }

            // Store contextTools in session data instead of TestContext
            setSessionData(stateMachine, "contextTools", contextAgent.getContextTools());
            setSessionData(stateMachine, "workflowPhase", "context");
            
            int totalItems = contextAgent.getContextTools().getAnalyzedClasses().size() + 
                            contextAgent.getContextTools().getContextNotes().size() + 
                            contextAgent.getContextTools().getReadFiles().size();
            String summary = String.format("Context gathered: %d items analyzed", totalItems);
            LOG.info(summary);
            
            return StateResult.success(null, summary, TestGenerationState.PLANNING_TESTS);
            
        } catch (Exception e) {
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
     * Analyze the target class and add to chat memory for the context agent.
     */
    private void analyzeTargetClass(@NotNull TestGenerationStateMachine stateMachine,
                                   @NotNull ContextAgent contextAgent) {
        try {
            TestGenerationRequest request = (TestGenerationRequest) getSessionData(stateMachine, "request");
            if (request == null) return;

            String targetFilePath = request.getTargetFile().getVirtualFile().getPath();

            // Check if this file is already in analyzedClasses (user might have provided it)
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();
            if (contextTools.getAnalyzedClasses().containsKey(targetFilePath)) {
                LOG.info("Target class already analyzed, skipping duplicate analysis");
                return;
            }

            LOG.info("Analyzing target class: " + targetFilePath);

            // Create AnalyzeClassTool and analyze the target class
            AnalyzeClassTool analyzeClassTool = new AnalyzeClassTool(
                getProject(stateMachine),
                contextTools.getAnalyzedClasses()
            );
            String analysis = analyzeClassTool.analyzeClass(targetFilePath);

            // Add to chat memory so the agent sees it in conversation
            contextAgent.getChatMemory().add(UserMessage.from(
                "Target class analysis:\n" + analysis
            ));

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
     * Pre-load user-provided context into the context agent's tools.
     */
    private void preloadUserContext(@NotNull TestGenerationStateMachine stateMachine,
                                   @NotNull ContextAgent contextAgent) {
        try {
            // Get user-provided files from the request
            TestGenerationRequest request = (TestGenerationRequest) getSessionData(stateMachine, "request");
            if (request == null) return;

            List<String> userFiles = request.getUserProvidedFiles();
            String userCode = request.getUserProvidedCode();
            String targetFilePath = request.getTargetFile().getVirtualFile().getPath();

            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextAgent.getContextTools();

            // Pre-load user-provided files
            if (userFiles != null && !userFiles.isEmpty()) {
                LOG.info("Pre-loading " + userFiles.size() + " user-provided files");

                for (String filePath : userFiles) {
                    try {
                        String content = contextTools.readFile(filePath);
                        if (content != null) {
                            // Check if this is the target file and analyze it properly
                            if (filePath.equals(targetFilePath)) {
                                LOG.info("User provided target file - analyzing with AnalyzeClassTool");

                                // Use AnalyzeClassTool for proper analysis
                                AnalyzeClassTool analyzeClassTool = new AnalyzeClassTool(
                                    getProject(stateMachine),
                                    contextTools.getAnalyzedClasses()
                                );
                                String analysis = analyzeClassTool.analyzeClass(targetFilePath);

                                // Add analyzed version to chat memory
                                contextAgent.getChatMemory().add(UserMessage.from(
                                    "Target class analysis (user-provided):\n" + analysis
                                ));
                            } else {
                                // Regular file, just add content
                                contextAgent.getChatMemory().add(UserMessage.from(
                                    "File: " + filePath + "\n" + content
                                ));
                            }

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
            }

            // Pre-load user code snippets
            if (userCode != null && !userCode.trim().isEmpty()) {
                contextTools.takeNote("User context: " + userCode);
                contextAgent.getChatMemory().add(UserMessage.from("User code:\n" + userCode));

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
}