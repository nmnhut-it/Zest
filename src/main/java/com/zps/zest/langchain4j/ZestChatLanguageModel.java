package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.ZestNotifications;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.util.EnvLoader;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private final NaiveLLMService naiveLlmService; // Keep for backward compatibility if needed
    private final ChatboxUtilities.EnumUsage usage;
    private final ConfigurationManager config;
    private final com.zps.zest.langchain4j.http.CancellableJdkHttpClient cancellableHttpClient;
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

    // Cancellation flag
    private volatile boolean cancelled = false;
    
    /**
     * Apply rate limiting with configurable delays to prevent hitting API limits
     */
    private void applyRateLimit() {
        // Check cancellation before rate limiting
        if (cancelled) {
            throw new java.util.concurrent.CancellationException("Operation cancelled");
        }

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
                    throw new java.util.concurrent.CancellationException("Operation interrupted during rate limiting");
                }
            }
        }

        // Check cancellation after rate limiting sleep
        if (cancelled) {
            throw new java.util.concurrent.CancellationException("Operation cancelled");
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

    public ZestChatLanguageModel(NaiveLLMService naiveLlmService, ChatboxUtilities.EnumUsage usage) {
        this(naiveLlmService, usage, null);
    }
    
    public ZestChatLanguageModel(NaiveLLMService naiveLlmService, ChatboxUtilities.EnumUsage usage, String selectedModel) {
        if (selectedModel == null)
            selectedModel = "local-model";
        this.naiveLlmService = naiveLlmService;
        this.usage = usage;
        this.config = ConfigurationManager.getInstance(naiveLlmService.getProject());

        // Create cancellable HTTP client first
        com.zps.zest.langchain4j.http.CancellableJdkHttpClientBuilder builder =
            com.zps.zest.langchain4j.http.CancellableJdkHttpClientBuilder.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(300));
        this.cancellableHttpClient = builder.getClient();

        // Load configurable delays from environment variables
        this.minDelayMs = loadDelayFromEnv("OPENAI_MIN_DELAY_MS", DEFAULT_MIN_DELAY_MS);
        this.requestDelayMs = loadDelayFromEnv("OPENAI_REQUEST_DELAY_MS", DEFAULT_REQUEST_DELAY_MS);

        LOG.info("Rate limiting configured - Min delay: " + minDelayMs + "ms, Request delay: " + requestDelayMs + "ms");

        this.delegateModel = createOpenAiModel(naiveLlmService.getProject(), selectedModel, builder);

        // Trigger username fetch
        NaiveLLMService.fetchAndStoreUsername(naiveLlmService.getProject());
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

    private OpenAiChatModel createOpenAiModel(NaiveLLMService naiveLlmService) {
        // Get configuration from the LLMService's project
        Project project = naiveLlmService.getProject();
        com.zps.zest.langchain4j.http.CancellableJdkHttpClientBuilder builder =
            com.zps.zest.langchain4j.http.CancellableJdkHttpClientBuilder.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(300));
        return createOpenAiModel(project, null, builder);
    }

    private OpenAiChatModel createOpenAiModel(@NotNull Project project, String selectedModel,
                                             com.zps.zest.langchain4j.http.CancellableJdkHttpClientBuilder httpClientBuilder) {
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
            // Use OpenAI configuration from .env - no office hours logic applied
            apiUrl = envBaseUrl;
            apiKey = envApiKey;
            // When env exists, prioritize env model, only use selectedModel if it's not the default "local-model"
            modelName = (selectedModel != null && !selectedModel.isEmpty() && !"local-model".equals(selectedModel)) ? selectedModel : envModel;
            LOG.info("Using OpenAI configuration from .env file - Model: " + modelName +
                    (selectedModel != null ? " (selected from UI)" : " (from env)"));
        } else {
            // No env config - apply office hours logic
            apiKey = config.getAuthTokenNoPrompt(); // Use auth token as API key
            // Prioritize selectedModel from UI, fallback to local-model
            modelName = (selectedModel != null && !selectedModel.isEmpty()) ? selectedModel : "local-model";

            if (!isWithinOfficeHours()) {
                // Outside office hours: use chat/talk zingplay
                apiUrl = config.getApiUrl().replace("/v1/chat/completion","");
                if (apiUrl.contains("chat.zingplay"))
                    apiUrl = "https://chat.zingplay.com/api";
                if (apiUrl.contains("talk.zingplay"))
                    apiUrl = "https://talk.zingplay.com/api";
                if (apiUrl.contains("openwebui.zingplay"))
                    apiUrl = "https://openwebui.zingplay.com/api";
                LOG.info("Outside office hours: using chat/talk zingplay - Model: " + modelName +
                        (selectedModel != null ? " (selected from UI)" : " (fallback)"));
            } else {
                // Office hours: use litellm
                apiUrl = config.getApiUrl().replace("/v1/chat/completion","");
                if (apiUrl.contains("chat.zingplay") || apiUrl.contains("openwebui.zingplay")) {
                    apiUrl = "https://litellm.zingplay.com/v1";
                    apiKey = "sk-0c1l7KCScBLmcYDN-Oszmg";
                }
                if (apiUrl.contains("talk.zingplay")) {
                    apiUrl = "https://litellm-internal.zingplay.com/v1";
                    apiKey = "sk-0c1l7KCScBLmcYDN-Oszmg";
                }
                LOG.info("Office hours: using litellm - Model: " + modelName +
                        (selectedModel != null ? " (selected from UI)" : " (fallback)"));
            }
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

        // Add custom_tool field for chat.zingplay/talk.zingplay requests
        if (finalApiUrl.contains("chat.zingplay") || finalApiUrl.contains("talk.zingplay")) {
            metadata.put("custom_tool", "Zest|" + usage.name());
        }

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
                .timeout(Duration.ofSeconds(1200))
                .httpClientBuilder(httpClientBuilder);

            // Add metadata for chat.zingplay/talk.zingplay APIs
            if (finalApiUrl.contains("chat.zingplay") || finalApiUrl.contains("talk.zingplay")) {
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
     * Check if current time is within office hours (8:30 - 17:30, Monday-Friday)
     * Copied from LLMService for consistency
     */
    private boolean isWithinOfficeHours() {
        LocalDateTime nowDateTime = LocalDateTime.now();
        DayOfWeek dayOfWeek = nowDateTime.getDayOfWeek();

        // Exclude weekends
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            LOG.debug("Weekend detected: " + dayOfWeek + ", not within office hours");
            return false;
        }

        LocalTime now = nowDateTime.toLocalTime();
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

        // Show notification to user
        ZestNotifications.showWarning(naiveLlmService.getProject(), "API Rate Limit", e.getMessage());

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

    /**
     * Cancel all active HTTP connections
     */
    public void cancelAll() {
        this.cancelled = true;
        if (cancellableHttpClient != null) {
            cancellableHttpClient.cancelAll();
            LOG.info("Cancelled all active requests");
        }
    }

    /**
     * Reset for new session
     */
    public void reset() {
        this.cancelled = false;
        if (cancellableHttpClient != null) {
            cancellableHttpClient.reset();
        }
    }
}