package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.langchain4j.util.StreamingLLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Base agent with streaming support for real-time response visualization.
 */
public abstract class StreamingBaseAgent extends BaseAgent {
    
    private static final boolean DEBUG_STREAMING = true; // Debug flag
    protected final StreamingLLMService streamingService;
    protected Consumer<String> streamingConsumer;
    
    protected StreamingBaseAgent(@NotNull Project project,
                                @NotNull ZestLangChain4jService langChainService,
                                @NotNull LLMService llmService,
                                @NotNull String agentName) {
        super(project, langChainService, llmService, agentName);
        this.streamingService = project.getService(StreamingLLMService.class);
        if (DEBUG_STREAMING) {
            System.out.println("[DEBUG-STREAMING-" + agentName + "] Constructor called");
            System.out.println("[DEBUG-STREAMING-" + agentName + "] streamingService initialized: " + (streamingService != null));
        }
    }
    
    /**
     * Set the consumer for streaming text chunks.
     */
    public void setStreamingConsumer(@Nullable Consumer<String> consumer) {
        this.streamingConsumer = consumer;
        if (DEBUG_STREAMING) {
            System.out.println("[DEBUG-STREAMING-" + agentName + "] Consumer set, is null? " + (consumer == null));
        }
    }

    /**
     * Notify streaming consumer.
     */
    protected void notifyStream(@NotNull String text) {
        if (DEBUG_STREAMING) {
            System.out.println("[DEBUG-STREAMING-" + agentName + "] notifyStream called");
            System.out.println("[DEBUG-STREAMING-" + agentName + "] Consumer is null? " + (streamingConsumer == null));
            System.out.println("[DEBUG-STREAMING-" + agentName + "] Text length: " + text.length());
            if (text.length() <= 100) {
                System.out.println("[DEBUG-STREAMING-" + agentName + "] Text: " + text.replace("\n", "\\n"));
            } else {
                System.out.println("[DEBUG-STREAMING-" + agentName + "] Text (first 100 chars): " + 
                    text.substring(0, 100).replace("\n", "\\n") + "...");
            }
        }
        if (streamingConsumer != null) {
            streamingConsumer.accept(text);
            if (DEBUG_STREAMING) {
                System.out.println("[DEBUG-STREAMING-" + agentName + "] Text sent to consumer");
            }
        } else {
            if (DEBUG_STREAMING) {
                System.out.println("[DEBUG-STREAMING-" + agentName + "] WARNING: No consumer to notify!");
            }
        }
    }

    /**
     * Query LLM with streaming (for backward compatibility).
     */
    @Override
    protected String queryLLM(@NotNull String prompt, int maxTokens) {
        if (DEBUG_STREAMING) {
            System.out.println("[DEBUG-STREAMING-" + agentName + "] queryLLM called");
            System.out.println("[DEBUG-STREAMING-" + agentName + "] streamingService is null? " + (streamingService == null));
            System.out.println("[DEBUG-STREAMING-" + agentName + "] streamingConsumer is null? " + (streamingConsumer == null));
        }
        
        // Notify that we're making an LLM query (truncate prompt for debug)
        notifyStream("\n📤 Sending request to LLM...\n");
        // Truncate prompt preview to avoid excessive logging
        String promptPreview = prompt.length() > 100 ? 
            prompt.substring(0, 100) + "...[truncated]" : prompt;
        notifyStream("Prompt preview: " + promptPreview + "\n\n");
        notifyStream("📥 LLM Response:\n");
        notifyStream("=" .repeat(80) + "\n");
        
        StringBuilder result = new StringBuilder();
        
        try {
            // First try streaming if available
            if (streamingService != null && streamingConsumer != null) {
                if (DEBUG_STREAMING) {
                    System.out.println("[DEBUG-STREAMING-" + agentName + "] Using STREAMING service!");
                }
                CompletableFuture<String> future = streamingService.streamQuery(
                    prompt,
                    "local-model",
                    chunk -> {
                        result.append(chunk);
                        notifyStream(chunk);
                    }
                );
                
                String response = future.join();
                notifyStream("\n" + "=" .repeat(80) + "\n");
                notifyStream("✅ LLM Response complete\n\n");
                return response;
            } else {
                if (DEBUG_STREAMING) {
                    System.out.println("[DEBUG-STREAMING-" + agentName + "] WARNING: Falling back to NON-STREAMING service!");
                    if (streamingService == null) {
                        System.out.println("[DEBUG-STREAMING-" + agentName + "] Reason: streamingService is null");
                    }
                    if (streamingConsumer == null) {
                        System.out.println("[DEBUG-STREAMING-" + agentName + "] Reason: streamingConsumer is null");
                    }
                }
                // Fallback to regular LLM but still capture response
                String response = super.queryLLM(prompt, maxTokens);
                result.append(response);
                
                // Stream the response even if we got it all at once
                notifyStream(response);
                notifyStream("\n" + "=" .repeat(80) + "\n");
                notifyStream("✅ LLM Response complete (non-streaming)\n\n");
                
                return response;
            }
            
        } catch (Exception e) {
            LOG.error("[" + agentName + "] LLM query failed", e);
            notifyStream("\n❌ LLM query failed: " + e.getMessage() + "\n");
            // Fallback to regular LLM
            return super.queryLLM(prompt, maxTokens);
        }
    }
}