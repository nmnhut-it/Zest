package com.zps.zest;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.mcp.McpAgentHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced processor for AI agent requests that supports:
 * - Conversation history integration
 * - Code context awareness
 * - Tool invocation with JSON-RPC style
 * - Model Context Protocol (MCP) integration
 */
public class EnhancedAgentRequestProcessor {
    private static final Logger LOG = Logger.getInstance(EnhancedAgentRequestProcessor.class);

    private final Project project;
    private final AgentToolRegistry toolRegistry;
    private final ConfigurationManager configManager;
    private final PromptBuilderForAgent promptBuilderForAgent;
    private final ToolExecutor toolExecutor;
    private final McpAgentHandler mcpHandler;

    /**
     * Creates a new enhanced request processor.
     *
     * @param project The current project
     */
    public EnhancedAgentRequestProcessor(@NotNull Project project) {
        this.project = project;
        this.toolRegistry = new AgentToolRegistry(project);
        this.configManager = new ConfigurationManager(project);
        this.promptBuilderForAgent = new PromptBuilderForAgent(toolRegistry);
        this.toolExecutor = new ToolExecutor(toolRegistry);
        this.mcpHandler = new McpAgentHandler(project);
    }

    /**
     * Processes a user request with tools and conversation history.
     *
     * @param userRequest The user's request
     * @param conversationHistory The conversation history
     * @param editor The current editor (can be null)
     * @return The assistant's response
     * @throws PipelineExecutionException If there's an error during processing
     */
    public CompletableFuture<String> processRequestWithTools(
            @NotNull String userRequest,
            @NotNull List<String> conversationHistory,
            @Nullable Editor editor) throws PipelineExecutionException {

        // Generate a request ID for tracking
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LOG.info("Processing enhanced request " + requestId + ": " + userRequest);
        
        // Check if MCP is enabled
        if (configManager.isMcpEnabled()) {
            LOG.info("Using MCP for request " + requestId);
            return mcpHandler.processMessage(userRequest)
                    .exceptionally(ex -> {
                        LOG.error("MCP processing failed, falling back to standard method", ex);
                        try {
                            return processWithStandardMethod(userRequest, conversationHistory, editor).get();
                        } catch (Exception e) {
                            LOG.error("Standard method also failed", e);
                            throw new RuntimeException("Failed to process request", e);
                        }
                    });
        }
        
        // Standard processing method
        return processWithStandardMethod(userRequest, conversationHistory, editor);
    }
    
    /**
     * Process the request using the standard method (without MCP).
     */
    private CompletableFuture<String> processWithStandardMethod(
            @NotNull String userRequest,
            @NotNull List<String> conversationHistory,
            @Nullable Editor editor) throws PipelineExecutionException {
        try {
            // 1. Gather code context from the current editor
            Map<String, String> codeContext = ReadAction.compute(() -> {
                return ContextGathererForAgent.gatherCodeContext(project, editor);
            });

            // 2. Construct the full prompt with JSON-RPC tool formatting
            String fullPrompt = promptBuilderForAgent.buildPrompt(userRequest, conversationHistory, codeContext);
            System.out.println(fullPrompt);
            System.out.println("----------------------");

            // 3. Call the LLM API
            CompletableFuture<String> response = callLlmApi(fullPrompt)
                    .thenApply(s -> toolExecutor.processToolInvocations(s));
            response.thenAccept(trs -> System.out.println(trs));

            return response;
        } catch (Exception e) {
            LOG.error("Error processing request with standard method", e);
            throw new PipelineExecutionException("Error processing request: " + e.getMessage(), e);
        }
    }

    /**
     * Calls the LLM API with the constructed prompt.
     */
    private CompletableFuture<String> callLlmApi(@NotNull String prompt) throws PipelineExecutionException {
        try {
            // Create a context for the API call
            CodeContext context = new CodeContext();
            context.setProject(project);
            context.setConfig(configManager);
            if (!prompt.contains("Write test")) {
                context.useTestWrightModel(false);
            }

            context.setPrompt(prompt);

            // Call the LLM API using the existing implementation
            return CompletableFuture.supplyAsync(() -> {
                LlmApiCallStage apiCallStage = new LlmApiCallStage();

                try {
                    apiCallStage.process(context);
                } catch (PipelineExecutionException e) {
                    throw new RuntimeException(e);
                }

                return context.getApiResponse();
            });

        } catch (Exception e) {
            LOG.error("Error calling LLM API", e);
            throw new PipelineExecutionException("Error calling LLM API: " + e.getMessage(), e);
        }
    }
}