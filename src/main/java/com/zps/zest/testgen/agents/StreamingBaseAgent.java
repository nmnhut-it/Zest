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
    protected com.zps.zest.langchain4j.ZestStreamingChatLanguageModel streamingChatModel;

    // Consumer for UI updates (sends complete responses)
    public Consumer<String> streamingConsumer;
    // Event listener for structured UI updates
    protected StreamingEventListener eventListener;

    // Cancellation flag
    private volatile boolean cancelled = false;
    
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
        this.streamingChatModel = new com.zps.zest.langchain4j.ZestStreamingChatLanguageModel(naiveLlmService, usage);

        LOG.info("[" + agentName + "] Initialized with LangChain4j chat model and streaming model using " + usage.name());
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

        if (chatModel instanceof com.zps.zest.langchain4j.ZestChatLanguageModel) {
            ((com.zps.zest.langchain4j.ZestChatLanguageModel) chatModel).setEventListener(listener);
        }
    }
    
    /**
     * Get the ChatModel for use with AgenticServices.
     */
    protected ChatModel getChatModelWithStreaming() {
        return chatModel;
    }

    /**
     * Get the StreamingChatModel for use with AgenticServices streaming.
     */
    protected com.zps.zest.langchain4j.ZestStreamingChatLanguageModel getStreamingChatModel() {
        return streamingChatModel;
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
            // Check cancellation before making LLM call
            checkCancellation();

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
        } catch (java.util.concurrent.CancellationException e) {
            LOG.warn("[" + agentName + "] LLM query cancelled");
            throw e;
        } catch (Exception e) {
            LOG.error("[" + agentName + "] LLM query failed", e);
            return "";
        }
    }

    /**
     * Cancel this agent's operations
     */
    public void cancel() {
        LOG.info("[" + agentName + "] Cancelling agent operations");
        this.cancelled = true;

        // Cancel the underlying chat model if it's ZestChatLanguageModel
        if (chatModel instanceof com.zps.zest.langchain4j.ZestChatLanguageModel) {
            ((com.zps.zest.langchain4j.ZestChatLanguageModel) chatModel).cancelAll();
        }

        // Cancel streaming model
        if (streamingChatModel != null) {
            streamingChatModel.cancelAll();
        }
    }

    /**
     * Check if this agent has been cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Reset cancellation state for new session
     */
    public void reset() {
        this.cancelled = false;
        if (chatModel instanceof com.zps.zest.langchain4j.ZestChatLanguageModel) {
            ((com.zps.zest.langchain4j.ZestChatLanguageModel) chatModel).reset();
        }
        if (streamingChatModel != null) {
            streamingChatModel.reset();
        }
    }

    /**
     * Check cancellation and throw exception if cancelled
     */
    protected void checkCancellation() {
        if (cancelled) {
            throw new java.util.concurrent.CancellationException(agentName + " operation cancelled");
        }
    }
}