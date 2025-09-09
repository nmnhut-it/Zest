package com.zps.zest.testgen.statemachine.handlers;

import com.intellij.openapi.project.Project;
import com.zps.zest.testgen.agents.TestFixingAgent;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test fixing phase of test generation.
 * Analyzes and fixes compilation errors in generated test files.
 */
public class TestFixingHandler extends AbstractStateHandler {
    
    private final Project project;
    private final ZestLangChain4jService langChainService;
    private final LLMService llmService;
    private final Consumer<String> streamingCallback;
    
    public TestFixingHandler(@NotNull Project project,
                            @NotNull ZestLangChain4jService langChainService,
                            @NotNull LLMService llmService) {
        this(project, langChainService, llmService, null);
    }
    
    public TestFixingHandler(@NotNull Project project,
                            @NotNull ZestLangChain4jService langChainService,
                            @NotNull LLMService llmService,
                            Consumer<String> streamingCallback) {
        super(TestGenerationState.FIXING_TESTS);
        this.project = project;
        this.langChainService = langChainService;
        this.llmService = llmService;
        this.streamingCallback = streamingCallback;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            LOG.info("TestFixingHandler.executeState() called for session: " + stateMachine.getSessionId());
            
            // Validate required data
            if (!hasRequiredData(stateMachine, "mergedTestClass")) {
                LOG.error("Missing required data for test fixing");
                return StateResult.failure("Missing required data for test fixing", false);
            }
            
            MergedTestClass mergedTest = (MergedTestClass) getSessionData(stateMachine, "mergedTestClass");
            // TestFixingAgent has minimal context needs, no contextTools required
            
            logToolActivity(stateMachine, "TestFixing", "Starting compilation error fixing");
            
            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🔧 Starting test compilation error fixing...\n");
            }
            
            // Create TestFixingAgent
            TestFixingAgent fixingAgent = new TestFixingAgent(project, langChainService, llmService);
            
            // Set up streaming callback for the agent
            if (streamingCallback != null) {
                fixingAgent.setStreamingConsumer(streamingCallback);
            }
            
            // Get the test content and file name for in-memory fixing
            String fileName = mergedTest.getFileName();
            String fileContent = mergedTest.getFullContent();
            
            logToolActivity(stateMachine, "TestFixing", "Analyzing test content for compilation errors: " + fileName);
            
            // Execute fixing asynchronously with in-memory content
            CompletableFuture<String> fixingFuture = fixingAgent.fixTestContent(fileName, fileContent);
            
            // Wait for completion
            String fixingResult = fixingFuture.get();
            
            // Store the fixing result in session data
            setSessionData(stateMachine, "testFixingResult", fixingResult);
            
            logToolActivity(stateMachine, "TestFixing", "Compilation error fixing completed");
            
            return StateResult.success(fixingResult, "Test fixing completed successfully");
            
        } catch (Exception e) {
            LOG.error("TestFixingHandler failed with exception", e);
            logToolActivity(stateMachine, "TestFixing", "Error: " + e.getMessage());
            
            // Instead of failing, skip the fixing step to prevent infinite loops
            logToolActivity(stateMachine, "TestFixing", "Skipping test fixing due to error - proceeding to completion");
            return StateResult.success(null, "Test fixing skipped due to error: " + e.getMessage());
        }
    }
}