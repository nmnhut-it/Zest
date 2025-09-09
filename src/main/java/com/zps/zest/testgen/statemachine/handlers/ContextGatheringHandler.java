package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.ContextAgent;
import com.zps.zest.testgen.model.TestContext;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

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
            LLMService llmService = getProject(stateMachine).getService(LLMService.class);
            ContextAgent contextAgent = new ContextAgent(getProject(stateMachine), langChainService, llmService);
            
            // Store the ContextAgent in session data for UI access
            stateMachine.setSessionData("contextAgent", contextAgent);
            
            // Set up UI event listener for real-time context tab updates
            if (uiEventListener != null) {
                contextAgent.setEventListener(uiEventListener);
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
                        
                        // Trigger UI updates for each analyzed file
                        if (uiEventListener != null) {
                            triggerContextUIUpdates(contextAgent);
                        }
                    }
                }
            };
            
            logToolActivity(stateMachine, "ContextAgent", "Starting context analysis");
            
            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🔍 Starting context analysis...\n");
            }
            
            // Execute context gathering with error handling
            CompletableFuture<TestContext> contextFuture = contextAgent.gatherContext(
                request,
                null, // No test plan at this stage
                stateMachine.getSessionId(),
                contextUpdateCallback
            );
            
            // Wait for context gathering to complete
            TestContext context = contextFuture.join();
            
            if (context == null) {
                return StateResult.failure("Context gathering returned null result", true);
            }
            
            // Store context in session data
            setSessionData(stateMachine, "context", context);
            setSessionData(stateMachine, "workflowPhase", "context");
            
            String summary = String.format("Context gathered: %d items analyzed", context.getContextItemCount());
            LOG.info(summary);
            
            return StateResult.success(context, summary, TestGenerationState.PLANNING_TESTS);
            
        } catch (Exception e) {
            LOG.error("Context gathering failed", e);
            
            // Check if this is a recoverable error
            boolean recoverable = isRecoverableError(e);
            
            return StateResult.failure(e, recoverable, 
                "Failed to gather context: " + e.getMessage());
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
                    String fileName = entry.getKey();
                    String analysis = entry.getValue();
                    
                    // Create context display data
                    com.zps.zest.testgen.ui.model.ContextDisplayData displayData = 
                        new com.zps.zest.testgen.ui.model.ContextDisplayData(
                            fileName, // filePath
                            fileName, // fileName  
                            com.zps.zest.testgen.ui.model.ContextDisplayData.AnalysisStatus.COMPLETED,
                            "File analyzed: " + fileName, // summary
                            analysis, // fullAnalysis
                            java.util.Collections.emptyList(), // classes
                            java.util.Collections.emptyList(), // methods
                            java.util.Collections.emptyList(), // dependencies
                            System.currentTimeMillis() // timestamp
                        );
                    
                    // Trigger UI update
                    uiEventListener.onFileAnalyzed(displayData);
                }
                
                // Get context notes and trigger updates
                java.util.List<String> contextNotes = contextTools.getContextNotes();
                for (String note : contextNotes) {
                    com.zps.zest.testgen.ui.model.ContextDisplayData displayData = 
                        new com.zps.zest.testgen.ui.model.ContextDisplayData(
                            "Note", // filePath
                            "Note", // fileName
                            com.zps.zest.testgen.ui.model.ContextDisplayData.AnalysisStatus.COMPLETED,
                            note, // summary
                            note, // fullAnalysis
                            java.util.Collections.emptyList(), // classes
                            java.util.Collections.emptyList(), // methods
                            java.util.Collections.emptyList(), // dependencies
                            System.currentTimeMillis() // timestamp
                        );
                    
                    uiEventListener.onFileAnalyzed(displayData);
                }
            }
        }
            
        } catch (Exception e) {
            LOG.warn("Error triggering context UI updates", e);
        }
    }
}