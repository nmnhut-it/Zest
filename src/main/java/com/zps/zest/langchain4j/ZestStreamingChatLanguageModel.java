package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.ZestNotifications;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.util.EnvLoader;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalQueries;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streaming wrapper that uses LangChain4j's OpenAI streaming model for real-time chat responses.
 * This provides streaming chat capabilities with tool support through LangChain4j's agentic framework.
 */
public class ZestStreamingChatLanguageModel implements StreamingChatModel {

    private static final Logger LOG = Logger.getInstance(ZestStreamingChatLanguageModel.class);
    private final OpenAiStreamingChatModel delegateModel;
    private final NaiveLLMService naiveLlmService; // Keep for backward compatibility if needed
    private final ChatboxUtilities.EnumUsage usage;
    private final ConfigurationManager config;

    // Rate limiting configuration - now configurable via environment variables
    private static final long DEFAULT_MIN_DELAY_MS = 2000; // Default 2 seconds for streaming (less than sync)
    private static final long DEFAULT_REQUEST_DELAY_MS = 3000; // Default 3 second delay for streaming
    private static final int MAX_RETRY_ATTEMPTS = 3; // Maximum number of retry attempts
    private static final long MAX_BACKOFF_DELAY_MS = 60000; // Maximum backoff delay (60 seconds)

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

        // Apply the configured request delay for conservative rate limiting
        if (lastTime > 0) {
            long timeSinceLastRequest = currentTime - lastTime;
            long requiredDelay = Math.max(minDelayMs, requestDelayMs);

            if (timeSinceLastRequest < requiredDelay) {
                long delayMs = requiredDelay - timeSinceLastRequest;
                LOG.info("Rate limiting streaming: Delaying request by " + delayMs + "ms (total delay: " + requiredDelay + "ms)");
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
     */
    private <T> T executeWithPluginClassLoader(java.util.function.Supplier<T> action) {
        return action.get();
    }

    /**
     * Execute void operations with plugin classloader
     */
    private void executeWithPluginClassLoader(Runnable action) {
        action.run();
    }

    public ZestStreamingChatLanguageModel(NaiveLLMService naiveLlmService, ChatboxUtilities.EnumUsage usage) {
        this(naiveLlmService, usage, null);
    }

    public ZestStreamingChatLanguageModel(NaiveLLMService naiveLlmService, ChatboxUtilities.EnumUsage usage, String selectedModel) {
        if (selectedModel == null)
            selectedModel = "local-model";
        this.naiveLlmService = naiveLlmService;
        this.usage = usage;
        this.config = ConfigurationManager.getInstance(naiveLlmService.getProject());

        // Load configurable delays from environment variables (lower than sync model)
        this.minDelayMs = loadDelayFromEnv("OPENAI_STREAMING_MIN_DELAY_MS", DEFAULT_MIN_DELAY_MS);
        this.requestDelayMs = loadDelayFromEnv("OPENAI_STREAMING_REQUEST_DELAY_MS", DEFAULT_REQUEST_DELAY_MS);

        LOG.info("Streaming rate limiting configured - Min delay: " + minDelayMs + "ms, Request delay: " + requestDelayMs + "ms");

        this.delegateModel = createOpenAiStreamingModel(naiveLlmService.getProject(), selectedModel);

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

    private OpenAiStreamingChatModel createOpenAiStreamingModel(@NotNull Project project, String selectedModel) {
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
            LOG.info("Using OpenAI streaming configuration from .env file - Model: " + modelName +
                    (selectedModel != null ? " (selected from UI)" : " (from env)"));
        } else {
            // No env config - apply office hours logic
            apiKey = config.getAuthTokenNoPrompt(); // Use auth token as API key
            // Prioritize selectedModel from UI, fallback to local-model
            modelName = (selectedModel != null && !selectedModel.isEmpty()) ? selectedModel : "local-model";

            if (!isWithinOfficeHours()) {
                // Outside office hours: use chat/talk zingplay
                apiUrl = config.getApiUrl().replace("/v1/chat/completion", "");
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
                apiUrl = config.getApiUrl().replace("/v1/chat/completion", "");
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

        // Ensure the URL ends with /v1 for OpenAI compatibility
        if (!apiUrl.endsWith("/v1") && !apiUrl.contains("/v1/")) {
            if (!apiUrl.endsWith("/")) {
                apiUrl += "/";
            }
            apiUrl += "v1";
        }
        LOG.info("Creating OpenAI streaming model with URL: " + apiUrl + ", Model: " + modelName);

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
        metadata.put("service", "ZestStreamingChatLanguageModel");

        // Add custom_tool field for chat.zingplay/talk.zingplay requests
        if (finalApiUrl.contains("chat.zingplay") || finalApiUrl.contains("talk.zingplay")) {
            metadata.put("custom_tool", "Zest|" + usage.name());
        }

        // Use plugin classloader to avoid Jackson ServiceLoader conflicts
        return executeWithPluginClassLoader(() -> {
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(finalApiUrl)
                    .apiKey(finalApiKey)
                    .modelName(finalModelName)
                    .user(username != null && !username.isEmpty() ? username : null)
                    .logRequests(true)
                    .logResponses(true)
                    .parallelToolCalls(false)
                    .timeout(Duration.ofSeconds(1200));

            // Add metadata for chat.zingplay/talk.zingplay APIs
            if (finalApiUrl.contains("chat.zingplay") || finalApiUrl.contains("talk.zingplay")) {
                builder.metadata(metadata);
            }

            return builder.build();
        });
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        executeWithRetryBackoff(() -> {
            applyRateLimit();
            executeWithPluginClassLoader(() -> delegateModel.chat(chatRequest, handler));
        });
    }

    @Override
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        executeWithRetryBackoff(() -> {
            applyRateLimit();
            executeWithPluginClassLoader(() -> delegateModel.chat(messages, handler));
        });
    }

    @Override
    public List<ChatModelListener> listeners() {
        return executeWithPluginClassLoader(() -> delegateModel.listeners());
    }

    @Override
    public ModelProvider provider() {
        return executeWithPluginClassLoader(() -> delegateModel.provider());
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

        LOG.warn("Rate limit detected in streaming, applying exponential backoff: " + backoffDelay + "ms");
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
     * Execute an operation with exponential backoff retry logic for rate limit errors.
     * Will retry up to MAX_RETRY_ATTEMPTS times with exponential backoff.
     */
    private void executeWithRetryBackoff(Runnable operation) {
        Exception lastException = null;
        long backoffDelay = requestDelayMs;

        for (int attempt = 0; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                operation.run();
                return; // Success - exit
            } catch (Exception e) {
                lastException = e;

                // Only retry for rate limit errors
                if (!isRateLimitError(e)) {
                    throw new RuntimeException("Non-rate-limit error in streaming chat", e);
                }

                // Check if we've exhausted retries
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    LOG.error("Maximum retry attempts (" + MAX_RETRY_ATTEMPTS + ") exceeded for rate limit error");
                    // Show final error notification to user
                    ZestNotifications.showError(naiveLlmService.getProject(), "API Rate Limit - Max Retries Exceeded", e.getMessage());
                    throw new RuntimeException("Rate limit error after " + MAX_RETRY_ATTEMPTS + " retries", e);
                }

                // Calculate backoff delay
                long suggestedDelay = extractSuggestedDelay(e.getMessage());
                if (suggestedDelay > 0) {
                    backoffDelay = Math.min(suggestedDelay, MAX_BACKOFF_DELAY_MS);
                } else {
                    // Exponential backoff: double the delay each time
                    backoffDelay = Math.min(backoffDelay * 2, MAX_BACKOFF_DELAY_MS);
                }

                LOG.warn("Rate limit error on attempt " + (attempt + 1) + "/" + MAX_RETRY_ATTEMPTS +
                        ". Retrying after " + backoffDelay + "ms backoff. Error: " + e.getMessage());

                try {
                    TimeUnit.MILLISECONDS.sleep(backoffDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during rate limit backoff", ie);
                }
            }
        }

        // This should never be reached due to the throw in the loop, but just in case
        if (lastException != null) {
            throw new RuntimeException("Unexpected error in retry logic", lastException);
        }
    }
}