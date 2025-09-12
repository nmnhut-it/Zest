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
    
    // Rate limiting configuration - now configurable via environment variables
    private static final long DEFAULT_MIN_DELAY_MS = 3000; // Default 3 seconds between requests (more conservative)
    private static final long DEFAULT_REQUEST_DELAY_MS = 5000; // Default 5 second delay for rate limiting
    private static final long TPM_LIMIT = 150000; // Tokens per minute limit (adjust based on your tier)
    
    // Configurable delays
    private final long minDelayMs;
    private final long requestDelayMs;
    
    // Track last request time for rate limiting
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    
    /**
     * Apply rate limiting with configurable delays to prevent hitting API limits
     */
    private void applyRateLimit() {
        long currentTime = System.currentTimeMillis();
        long lastTime = lastRequestTime.get();
        
        // Always apply the configured request delay for conservative rate limiting
        if (lastTime > 0) {
            long timeSinceLastRequest = currentTime - lastTime;
            long requiredDelay = Math.max(minDelayMs, requestDelayMs);
            
            if (timeSinceLastRequest < requiredDelay) {
                long delayMs = requiredDelay - timeSinceLastRequest;
                LOG.info("Rate limiting: Delaying request by " + delayMs + "ms (total delay: " + requiredDelay + "ms)");
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Rate limiting delay was interrupted", e);
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
        this(llmService, usage, null);
    }
    
    public ZestChatLanguageModel(LLMService llmService, ChatboxUtilities.EnumUsage usage, String selectedModel) {
        if (selectedModel == null)
            selectedModel = "local-model";
        this.llmService = llmService;
        this.usage = usage;
        this.config = ConfigurationManager.getInstance(llmService.getProject());
        
        // Load configurable delays from environment variables
        this.minDelayMs = loadDelayFromEnv("OPENAI_MIN_DELAY_MS", DEFAULT_MIN_DELAY_MS);
        this.requestDelayMs = loadDelayFromEnv("OPENAI_REQUEST_DELAY_MS", DEFAULT_REQUEST_DELAY_MS);
        
        LOG.info("Rate limiting configured - Min delay: " + minDelayMs + "ms, Request delay: " + requestDelayMs + "ms");
        
        this.delegateModel = createOpenAiModel(llmService.getProject(), selectedModel);
        
        // Trigger username fetch
        LLMService.fetchAndStoreUsername(llmService.getProject());
    }
    
    /**
     * Load delay configuration from environment variables with fallback to defaults
     */
    private long loadDelayFromEnv(String envVarName, long defaultValue) {
        try {
            String envValue = EnvLoader.getEnv(envVarName);
            if (envValue != null && !envValue.isEmpty()) {
                long delay = Long.parseLong(envValue);
                if (delay >= 0) {
                    LOG.info("Using " + envVarName + " = " + delay + "ms");
                    return delay;
                }
            }
        } catch (NumberFormatException e) {
            LOG.warn("Invalid " + envVarName + " value, using default: " + defaultValue + "ms");
        }
        return defaultValue;
    }

    private OpenAiChatModel createOpenAiModel(LLMService llmService) {
        // Get configuration from the LLMService's project
        Project project = llmService.getProject();
        return createOpenAiModel(project, null);
    }

    private OpenAiChatModel createOpenAiModel(@NotNull Project project, String selectedModel) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);

        // Load environment variables from .env file
        if (project.getBasePath() != null) {
            EnvLoader.loadEnv(project.getBasePath());
        }

        // Check for OpenAI configuration in .env file first
        String envApiKey = EnvLoader.getEnv("OPENAI_API_KEY");
        String envModel = EnvLoader.getEnv("OPENAI_MODEL", "gpt-4o-mini");
        String envBaseUrl = EnvLoader.getEnv("OPENAI_BASE_URL", "https://api.openai.com/v1");

        String apiUrl, apiKey, modelName;
        
        if (envApiKey != null && !envApiKey.isEmpty()) {
            // Use OpenAI configuration from .env
            apiUrl = envBaseUrl;
            apiKey = envApiKey;
            // Prioritize selectedModel from UI, fallback to env model
            modelName = (selectedModel != null && !selectedModel.isEmpty()) ? selectedModel : envModel;
            LOG.info("Using OpenAI configuration from .env file - Model: " + modelName + 
                    (selectedModel != null ? " (selected from UI)" : " (from env)"));
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
            // Prioritize selectedModel from UI, fallback to local-model
            modelName = (selectedModel != null && !selectedModel.isEmpty()) ? selectedModel : "local-model";
            LOG.info("Using fallback configuration - Model: " + modelName + 
                    (selectedModel != null ? " (selected from UI)" : " (fallback)"));
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
                .parallelToolCalls(false)
                .user(username != null && !username.isEmpty() ? username : null)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(1200));
                
            // Only add metadata for models that support it without store
            // GPT-4.1 and GPT-4o-mini require 'store' to be enabled for metadata
            if (!finalModelName.equals("gpt-4.1") && !finalModelName.equals("gpt-4o-mini")) {
                builder.metadata(metadata);
            }
            
            return builder.build();
        });
    }


    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        applyRateLimit();
        
        try {
            return executeWithPluginClassLoader(() -> delegateModel.chat(chatRequest));
        } catch (Exception e) {
            // Check if this is a rate limit error and apply exponential backoff
            if (isRateLimitError(e)) {
                handleRateLimitError(e);
                // Retry once after exponential backoff
                applyRateLimit(); // Apply additional delay
                return executeWithPluginClassLoader(() -> delegateModel.chat(chatRequest));
            }
            throw e;
        }
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
    
    /**
     * Check if the exception indicates a rate limit error
     */
    private boolean isRateLimitError(Exception e) {
        if (e.getMessage() == null) {
            return false;
        }
        
        String message = e.getMessage().toLowerCase();
        return message.contains("rate limit") || 
               message.contains("too many requests") ||
               message.contains("quota exceeded") ||
               message.contains("429") ||
               message.contains("tpm");
    }
    
    /**
     * Handle rate limit errors with exponential backoff
     */
    private void handleRateLimitError(Exception e) {
        // Extract suggested wait time from error message if available
        long suggestedDelay = extractSuggestedDelay(e.getMessage());
        
        // Use exponential backoff: either suggested delay or double the request delay
        long backoffDelay = suggestedDelay > 0 ? suggestedDelay : requestDelayMs * 2;
        
        LOG.warn("Rate limit detected, applying exponential backoff: " + backoffDelay + "ms");
        LOG.warn("Rate limit error: " + e.getMessage());
        
        try {
            TimeUnit.MILLISECONDS.sleep(backoffDelay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Rate limit backoff was interrupted", ie);
        }
    }
    
    /**
     * Extract suggested delay from rate limit error message
     * Example: "Please try again in 32.747s" -> returns 33000ms
     */
    private long extractSuggestedDelay(String errorMessage) {
        if (errorMessage == null) {
            return 0;
        }
        
        try {
            // Look for "try again in X seconds" or "try again in X.Xs"
            if (errorMessage.contains("try again in")) {
                String[] parts = errorMessage.split("try again in");
                if (parts.length > 1) {
                    String delayPart = parts[1].trim();
                    // Extract number before 's' (seconds)
                    int sIndex = delayPart.indexOf('s');
                    if (sIndex > 0) {
                        String numberStr = delayPart.substring(0, sIndex).replaceAll("[^0-9.]", "");
                        double seconds = Double.parseDouble(numberStr);
                        return (long) Math.ceil(seconds * 1000); // Convert to ms and round up
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not parse suggested delay from error message", e);
        }
        
        return 0;
    }
}