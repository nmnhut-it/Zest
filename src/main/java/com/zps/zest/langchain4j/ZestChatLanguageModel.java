package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.util.EnvLoader;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper that uses LangChain4j's OpenAI model for better compatibility
 * with OpenAI-compatible APIs.
 * This provides better tool calling support and compatibility with various
 * OpenAI-compatible services like Ollama, LM Studio, etc.
 */
public class ZestChatLanguageModel implements ChatModel {
    
    private static final Logger LOG = Logger.getInstance(ZestChatLanguageModel.class);
    private final OpenAiChatModel delegateModel;
    private final LLMService llmService; // Keep for backward compatibility if needed
    private final ChatboxUtilities.EnumUsage usage;
    private final ConfigurationManager config;
//    private final TokenUsageTracker tokenTracker;
    
    // Rate limiting configuration
    private static final long MIN_DELAY_MS = 1000; // Minimum 1 second between requests
    private static final long TPM_LIMIT = 150000; // Tokens per minute limit (adjust based on your tier)
    
    // Track last request time for rate limiting
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    
    /**
     * Apply rate limiting based on actual token usage
     */
    private void applyRateLimit() {
        // Check if we need to delay based on actual token usage
        long estimatedDelay = 2000; ;// tokenTracker.estimateDelayForTokens(0, TPM_LIMIT);
        if (estimatedDelay > 0) {
            LOG.info("Rate limiting based on token usage: Delaying request by " + estimatedDelay + "ms");
            try {
                TimeUnit.MILLISECONDS.sleep(estimatedDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Rate limiting delay was interrupted", e);
            }
        } else {
            // Apply minimum delay between requests
            long currentTime = System.currentTimeMillis();
            long lastTime = lastRequestTime.get();
            
            if (lastTime > 0) {
                long timeSinceLastRequest = currentTime - lastTime;
                if (timeSinceLastRequest < MIN_DELAY_MS) {
                    long delayMs = MIN_DELAY_MS - timeSinceLastRequest;
                    try {
                        TimeUnit.MILLISECONDS.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn("Minimum delay was interrupted", e);
                    }
                }
            }
        }
        
        lastRequestTime.set(System.currentTimeMillis());
    }
    
    /**
     * Execute code with plugin classloader to avoid Jackson conflicts
     * Uses AccessController to handle security permissions
     */
    private <T> T executeWithPluginClassLoader(java.util.function.Supplier<T> action) {
        return action.get();
        // In plugin context, we might not have permission to change context classloader
        // Try with permissions, fall back to direct execution if not allowed
//        try {
//            return java.security.AccessController.doPrivileged(
//                (java.security.PrivilegedAction<T>) () -> {
//                    Thread currentThread = Thread.currentThread();
//                    ClassLoader originalClassLoader = currentThread.getContextClassLoader();
//                    ClassLoader pluginClassLoader = this.getClass().getClassLoader();
//
//                    try {
//                        currentThread.setContextClassLoader(pluginClassLoader);
//                        return action.get();
//                    } finally {
//                        currentThread.setContextClassLoader(originalClassLoader);
//                    }
//                }
//            );
//        } catch (SecurityException e) {
//            // If we don't have permission to change classloader, just execute directly
//            // The model creation might still work if Jackson is properly isolated
//            LOG.warn("Cannot change context classloader in plugin context, executing directly", e);
//            return action.get();
//        }
    }

    public ZestChatLanguageModel(LLMService llmService, ChatboxUtilities.EnumUsage usage) {
        this.llmService = llmService;
        this.usage = usage;
        this.config = ConfigurationManager.getInstance(llmService.getProject());
        this.delegateModel = createOpenAiModel(llmService.getProject());
        
        // Trigger username fetch
        LLMService.fetchAndStoreUsername(llmService.getProject());
    }

    private OpenAiChatModel createOpenAiModel(LLMService llmService) {
        // Get configuration from the LLMService's project
        Project project = llmService.getProject();
        return createOpenAiModel(project);
    }

    private OpenAiChatModel createOpenAiModel(@NotNull Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);

        // Load environment variables from .env file
        if (project.getBasePath() != null) {
            EnvLoader.loadEnv(project.getBasePath());
        }

        // Check for OpenAI configuration in .env file first
        String envApiKey = EnvLoader.getEnv("OPENAI_API_KEY");
        String envModel = EnvLoader.getEnv("OPENAI_MODEL", "gpt-4.1");
        String envBaseUrl = EnvLoader.getEnv("OPENAI_BASE_URL", "https://api.openai.com/v1");

        String apiUrl, apiKey, modelName;
        
        if (envApiKey != null && !envApiKey.isEmpty()) {
            // Use OpenAI configuration from .env
            apiUrl = envBaseUrl;
            apiKey = envApiKey;
            modelName = envModel;
            LOG.info("Using OpenAI configuration from .env file - Model: " + modelName);
        } else {
            // Fallback to existing configuration
            apiUrl = config.getApiUrl().replace("/v1/chat/completion","");
            if (apiUrl.contains("chat.zingplay"))
                apiUrl = "https://chat.zingplay.com/api";
            if (apiUrl.contains("talk.zingplay"))
                apiUrl = "https://talk.zingplay.com/api";
            if (apiUrl.contains("openwebui.zingplay"))
                apiUrl = "https://openwebui.zingplay.com/api";
            apiKey = config.getAuthTokenNoPrompt(); // Use auth token as API key
            modelName = "local-model"; // Use local model as fallback
        }

//        // Default values if not configured
//        if (apiUrl == null || apiUrl.isEmpty()) {
//            apiUrl = "http://localhost:11434"; // Default Ollama URL
//        }
//        if (apiKey == null || apiKey.isEmpty()) {
//            apiKey = "dummy-key"; // Many local services don't need a real key
//        }
//        if (modelName == null || modelName.isEmpty()) {
//            modelName = "llama3.2"; // Default model
//        }

        // Apply office hour policy for redirects
        if (isWithinOfficeHours()) {
            LOG.info("Office hours: redirecting to litellm for ZestChatLanguageModel");
            if (apiUrl.contains("chat.zingplay") || apiUrl.contains("openwebui.zingplay")) {
                apiUrl = "https://litellm.zingplay.com/v1";
                apiKey = "sk-0c1l7KCScBLmcYDN-Oszmg";
            }
            if (apiUrl.contains("talk.zingplay")) {
                apiUrl = "https://litellm-internal.zingplay.com/v1";
                apiKey = "sk-0c1l7KCScBLmcYDN-Oszmg";
            }
        } else {
            LOG.info("Outside office hours: using original configured URL for ZestChatLanguageModel");
            // Keep original apiUrl and apiKey from configuration
        }

//         Ensure the URL ends with /v1 for OpenAI compatibility
        if (!apiUrl.endsWith("/v1") && !apiUrl.contains("/v1/")) {
            if (!apiUrl.endsWith("/")) {
                apiUrl += "/";


            }
            apiUrl += "v1";
        }
        LOG.info("Creating OpenAI model with URL: " + apiUrl + ", Model: " + modelName);

        final String finalApiUrl = apiUrl;
        final String finalApiKey = apiKey;
        final String finalModelName = modelName;

        // Get username for tracking
        String username = config.getUsername();
        
        // Create metadata map with both user and usage
        java.util.Map<String, String> metadata = new java.util.HashMap<>();
        if (username != null && !username.isEmpty()) {
            metadata.put("user", username);
        }
        metadata.put("usage", usage.name());
        metadata.put("tool", "Zest");
        metadata.put("service", "ZestChatLanguageModel");

        // Use plugin classloader to avoid Jackson ServiceLoader conflicts
        return executeWithPluginClassLoader(() -> {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(finalApiUrl)
                .apiKey(finalApiKey)
                .modelName(finalModelName)
                .parallelToolCalls(true)
                .user(username != null && !username.isEmpty() ? username : null)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(120));
                
            // Only add metadata if NOT using GPT-4.1 (OpenAI doesn't allow metadata without store)
            if (!finalModelName.equals("gpt-4.1")) {
                builder.metadata(metadata);
            }
            
            return builder.build();
        });
    }


    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        applyRateLimit();
         return executeWithPluginClassLoader(() -> delegateModel.chat(chatRequest));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return executeWithPluginClassLoader(() -> delegateModel.defaultRequestParameters());
    }

    @Override
    public List<ChatModelListener> listeners() {
        return executeWithPluginClassLoader(() -> delegateModel.listeners());
    }

    @Override
    public ModelProvider provider() {
        return executeWithPluginClassLoader(() -> delegateModel.provider());
    }


    @Override
    public Set<Capability> supportedCapabilities() {
        return executeWithPluginClassLoader(() -> delegateModel.supportedCapabilities());
    }

    /**
     * Check if current time is within office hours (8:30 - 17:30)
     * Copied from LLMService for consistency
     */
    private boolean isWithinOfficeHours() {
        LocalTime now = LocalTime.now();
        LocalTime startTime = LocalTime.of(8, 30); // 8:30 AM
        LocalTime endTime = LocalTime.of(17, 30);  // 5:30 PM
        
        boolean withinHours = !now.isBefore(startTime) && !now.isAfter(endTime);
        LOG.debug("Current time: " + now + ", Office hours: " + startTime + " - " + endTime + ", Within hours: " + withinHours);
        
        return withinHours;
    }
}