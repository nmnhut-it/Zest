package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enhanced processor for AI agent requests that supports:
 * - Conversation history integration
 * - Code context awareness
 * - Tool invocation with JSON-RPC style
 */
public class EnhancedAgentRequestProcessor {
    private static final Logger LOG = Logger.getInstance(EnhancedAgentRequestProcessor.class);

    private final Project project;
    private final AgentToolRegistry toolRegistry;
    public final ConfigurationManager configManager;
    private final PromptBuilderForAgent promptBuilderForAgent;
    private final ToolExecutor toolExecutor;

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
    public String processRequestWithTools(
            @NotNull String userRequest,
            @NotNull List<String> conversationHistory,
            @Nullable Editor editor) throws PipelineExecutionException {

        // Generate a request ID for tracking
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LOG.info("Processing enhanced request " + requestId + ": " + userRequest);

        try {
            // 1. Gather code context from the current editor
            Map<String, String> codeContext = ContextGathererForAgent.gatherCodeContext(project, editor);

            // 2. Construct the full prompt with JSON-RPC tool formatting
            String fullPrompt = promptBuilderForAgent.buildPrompt(userRequest, conversationHistory, codeContext);

            // 3. Call the LLM API
            String response = callLlmApi(fullPrompt);

            // 4. Process tool usage in the response if any
            response = toolExecutor.processToolInvocations(response);

            LOG.info("Request " + requestId + " processed successfully");
            return response;

        } catch (Exception e) {
            LOG.error("Error processing request " + requestId, e);
            throw new PipelineExecutionException("Error processing request: " + e.getMessage(), e);
        }
    }

    /**
     * Processes a follow-up to a previous request.
     *
     * @param followUpResponse The user's follow-up response
     * @param conversationHistory The conversation history
     * @param editor The current editor (can be null)
     * @return The assistant's response
     * @throws PipelineExecutionException If there's an error during processing
     */
    public String processFollowUp(
            @NotNull String followUpResponse,
            @NotNull List<String> conversationHistory,
            @Nullable Editor editor) throws PipelineExecutionException {

        // Tag the follow-up for special handling
        String taggedRequest = "<FOLLOW_UP>" + followUpResponse + "</FOLLOW_UP>";

        // Process normally using the tagged request
        return processRequestWithTools(taggedRequest, conversationHistory, editor);
    }

    /**
     * Calls the LLM API with the constructed prompt.
     */
    private String callLlmApi(@NotNull String prompt) throws PipelineExecutionException {
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
            LlmApiCallStage apiCallStage = new LlmApiCallStage();
            apiCallStage.process(context);

            return context.getApiResponse();
        } catch (Exception e) {
            LOG.error("Error calling LLM API", e);
            throw new PipelineExecutionException("Error calling LLM API: " + e.getMessage(), e);
        }
    }
}