package com.zps.zest.testgen.agents;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestChatLanguageModel;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.ui.StreamingEventListener;
import com.zps.zest.testgen.ui.model.ContextDisplayData;
import com.zps.zest.testgen.ui.model.TestPlanDisplayData;
import com.zps.zest.testgen.ui.model.GeneratedTestDisplayData;
import dev.langchain4j.model.chat.ChatModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.util.function.Consumer;

/**
 * Base agent with LangChain4j support.
 * Uses ZestChatLanguageModel which implements ChatModel.
 */
public abstract class StreamingBaseAgent {
    
    protected static final Logger LOG = Logger.getInstance(StreamingBaseAgent.class);
    
    protected final Project project;
    protected final ZestLangChain4jService langChainService;
    protected final NaiveLLMService naiveLlmService;
    protected final String agentName;
    protected ChatModel chatModel;
    
    // Consumer for UI updates (sends complete responses)
    protected Consumer<String> streamingConsumer;
    // Event listener for structured UI updates
    protected StreamingEventListener eventListener;
    
    protected StreamingBaseAgent(@NotNull Project project,
                                @NotNull ZestLangChain4jService langChainService,
                                @NotNull NaiveLLMService naiveLlmService,
                                @NotNull String agentName) {
        this.project = project;
        this.langChainService = langChainService;
        this.naiveLlmService = naiveLlmService;
        this.agentName = agentName;
        
        // Create chat model using ZestChatLanguageModel with agent-specific usage
        com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage usage = determineUsageForAgent(agentName);
        this.chatModel = new ZestChatLanguageModel(naiveLlmService, usage);
        
        LOG.info("[" + agentName + "] Initialized with LangChain4j chat model using " + usage.name());
    }
    
    /**
     * Determine the appropriate EnumUsage based on agent name for tracking
     */
    private com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage determineUsageForAgent(String agentName) {
        String lowerName = agentName.toLowerCase();
        
        if (lowerName.contains("testwriter") || lowerName.equals("test writer agent")) {
            return com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.AGENT_TEST_WRITER;
        } else if (lowerName.contains("context") && lowerName.contains("agent")) {
            return com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.AGENT_CONTEXT_ANALYZER;
        } else if (lowerName.contains("coordinator")) {
            return com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.AGENT_COORDINATOR;
        } else if (lowerName.contains("merger") || lowerName.contains("merge")) {
            return com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.AGENT_TEST_MERGER;
        } else if (lowerName.contains("test")) {
            return com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.AGENT_TEST_WRITER;
        } else {
            // Fallback for unknown agent types
            return com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.LANGCHAIN_TEST_GENERATION;
        }
    }
    
    /**
     * Set the consumer for UI updates.
     * This will receive agent output and status messages.
     */
    public void setStreamingConsumer(@Nullable Consumer<String> consumer) {
        this.streamingConsumer = consumer;
        LOG.info("[" + agentName + "] UI consumer set: " + (consumer != null));
    }
    
    /**
     * Set the event listener for structured UI updates.
     * This will receive structured data objects for UI components.
     */
    public void setEventListener(@Nullable StreamingEventListener listener) {
        this.eventListener = listener;
        LOG.info("[" + agentName + "] Event listener set: " + (listener != null));
    }
    
    /**
     * Get the ChatModel for use with AgenticServices.
     */
    protected ChatModel getChatModelWithStreaming() {
        return chatModel;
    }
    
    /**
     * Send output to the UI consumer if available.
     */
    protected void sendToUI(String message) {
        if (streamingConsumer != null) {
            streamingConsumer.accept(message);
        }
    }
    
    /**
     * Notify about agent starting.
     */
    protected void notifyStart() {
        sendToUI(createAgentHeader());
    }
    
    /**
     * Notify about agent completion.
     */
    protected void notifyComplete() {
        sendToUI(createAgentFooter());
    }
    
    /**
     * Notify about tool calls with detailed logging and special formatting.
     */
    protected void notifyToolCall(@NotNull String toolName) {
        notifyToolCall(toolName, null, null);
    }
    
    /**
     * Notify about tool calls with parameters and results.
     */
    protected void notifyToolCall(@NotNull String toolName, @Nullable String parameters, @Nullable String result) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n🔧 Tool Call: ").append(toolName);
        
        if (parameters != null && !parameters.isEmpty()) {
            logMessage.append("\n   Parameters: ").append(parameters);
        }
        
        if (result != null && !result.isEmpty()) {
            String truncatedResult = result.length() > 200 ? result.substring(0, 200) + "..." : result;
            logMessage.append("\n   Result: ").append(truncatedResult);
        }
        
        logMessage.append("\n");
        sendToUI(logMessage.toString());
        
        // Also log to IntelliJ's log
        LOG.info("[" + agentName + "] Tool call: " + toolName + 
                (parameters != null ? " with params: " + parameters : ""));
    }
    
    /**
     * Log tool call execution with timing information.
     */
    protected void logToolExecution(@NotNull String toolName, long durationMs, boolean success) {
        String status = success ? "✅" : "❌";
        String message = String.format("\n%s Tool %s completed in %dms\n", status, toolName, durationMs);
        sendToUI(message);
        LOG.info("[" + agentName + "] Tool " + toolName + " executed in " + durationMs + "ms, success: " + success);
    }
    
    /**
     * Create a formatted agent header for output.
     */
    protected String createAgentHeader() {
        return "\n" + "=".repeat(60) + "\n" +
               "🤖 " + agentName + " Starting\n" +
               "=".repeat(60) + "\n\n";
    }
    
    /**
     * Create a formatted agent footer for output.
     */
    protected String createAgentFooter() {
        return "\n" + "=".repeat(60) + "\n" +
               "✅ " + agentName + " Complete\n" +
               "=".repeat(60) + "\n\n";
    }
    
    /**
     * Send context file analysis to UI.
     */
    protected void sendContextFileAnalyzed(@NotNull ContextDisplayData data) {
        if (eventListener != null) {
            SwingUtilities.invokeLater(() -> eventListener.onFileAnalyzed(data));
        }
    }
    
    /**
     * Send test plan update to UI.
     */
    protected void sendTestPlanUpdate(@NotNull TestPlanDisplayData data) {
        if (eventListener != null) {
            SwingUtilities.invokeLater(() -> eventListener.onTestPlanUpdated(data));
        }
    }
    
    /**
     * Send generated test to UI.
     */
    protected void sendTestGenerated(@NotNull GeneratedTestDisplayData data) {
        if (eventListener != null) {
            SwingUtilities.invokeLater(() -> eventListener.onTestGenerated(data));
        }
    }
    
    /**
     * Send status change to UI.
     */
    protected void sendStatusChange(@NotNull String status) {
        if (eventListener != null) {
            SwingUtilities.invokeLater(() -> eventListener.onStatusChanged(status));
        }
    }

    /**
     * Helper method for direct LLM queries (non-agentic).
     * Useful for simple prompt-response interactions.
     */
    protected String queryLLM(String prompt, int maxTokens) {
        try {
            var messages = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
            messages.add(dev.langchain4j.data.message.UserMessage.from(prompt));
            
            var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages)
                .build();
            
            var response = chatModel.chat(request);
            
            if (response != null && response.aiMessage() != null) {
                String content = response.aiMessage().text();
                // Send to UI
                sendToUI(content);
                return content;
            }
            
            return "";
        } catch (Exception e) {
            LOG.error("[" + agentName + "] LLM query failed", e);
            return "";
        }
    }
}