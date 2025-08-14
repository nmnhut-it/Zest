package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Wrapper that uses LangChain4j's OpenAI model for better compatibility
 * with OpenAI-compatible APIs.
 * This provides better tool calling support and compatibility with various
 * OpenAI-compatible services like Ollama, LM Studio, etc.
 */
public class ZestChatLanguageModel implements ChatModel {
    
    private static final Logger LOG = Logger.getInstance(ZestChatLanguageModel.class);
    private final ChatModel delegateModel;
    private final LLMService llmService; // Keep for backward compatibility if needed
    
    /**
     * Execute code with plugin classloader to avoid Jackson conflicts
     * Uses AccessController to handle security permissions
     */
    private <T> T executeWithPluginClassLoader(java.util.function.Supplier<T> action) {
        // In plugin context, we might not have permission to change context classloader
        // Try with permissions, fall back to direct execution if not allowed
        try {
            return java.security.AccessController.doPrivileged(
                (java.security.PrivilegedAction<T>) () -> {
                    Thread currentThread = Thread.currentThread();
                    ClassLoader originalClassLoader = currentThread.getContextClassLoader();
                    ClassLoader pluginClassLoader = this.getClass().getClassLoader();

                    try {
                        currentThread.setContextClassLoader(pluginClassLoader);
                        return action.get();
                    } finally {
                        currentThread.setContextClassLoader(originalClassLoader);
                    }
                }
            );
        } catch (SecurityException e) {
            // If we don't have permission to change classloader, just execute directly
            // The model creation might still work if Jackson is properly isolated
            LOG.warn("Cannot change context classloader in plugin context, executing directly", e);
            return action.get();
        }
    }

    /**
     * Execute code with plugin classloader (void version)
     * Uses AccessController to handle security permissions
     */
    private void executeWithPluginClassLoader(Runnable action) {
        try {
            java.security.AccessController.doPrivileged(
                (java.security.PrivilegedAction<Void>) () -> {
                    Thread currentThread = Thread.currentThread();
                    ClassLoader originalClassLoader = currentThread.getContextClassLoader();
                    ClassLoader pluginClassLoader = this.getClass().getClassLoader();

                    try {
                        currentThread.setContextClassLoader(pluginClassLoader);
                        action.run();
                        return null;
                    } finally {
                        currentThread.setContextClassLoader(originalClassLoader);
                    }
                }
            );
        } catch (SecurityException e) {
            // If we don't have permission to change classloader, just execute directly
            LOG.warn("Cannot change context classloader in plugin context, executing directly", e);
            action.run();
        }
    }

    public ZestChatLanguageModel(LLMService llmService) {
        this.llmService = llmService;
        this.delegateModel = createOpenAiModel(llmService);
    }

    /**
     * Alternative constructor that accepts a project to get configuration
     */
    public ZestChatLanguageModel(@NotNull Project project) {
        this.llmService = project.getService(LLMService.class);
        this.delegateModel = createOpenAiModel(project);
    }

    private ChatModel createOpenAiModel(LLMService llmService) {
        // Get configuration from the LLMService's project
        Project project = llmService.getProject();
        return createOpenAiModel(project);
    }

    private ChatModel createOpenAiModel(@NotNull Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);

        // Get API configuration
        String apiUrl = config.getApiUrl().replace("/v1/chat/completion","");
        if (apiUrl.contains("chat.zingplay"))
            apiUrl = "https://chat.zingplay.com/api";
        if (apiUrl.contains("talk.zingplay"))
            apiUrl = "https://talk.zingplay.com/api";
        if (apiUrl.contains("openwebui.zingplay"))
            apiUrl = "https://openwebui.zingplay.com/api";
        String apiKey = config.getAuthTokenNoPrompt(); // Use auth token as API key
        String modelName = "local-model"; // Use code model setting

        // Default values if not configured
        if (apiUrl == null || apiUrl.isEmpty()) {
            apiUrl = "http://localhost:11434"; // Default Ollama URL
        }
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "dummy-key"; // Many local services don't need a real key
        }
        if (modelName == null || modelName.isEmpty()) {
            modelName = "llama3.2"; // Default model
        }

        // Ensure the URL ends with /v1 for OpenAI compatibility
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

        // Use plugin classloader to avoid Jackson ServiceLoader conflicts
        return executeWithPluginClassLoader(() ->
            OpenAiChatModel.builder()
                .baseUrl(finalApiUrl)
                .apiKey(finalApiKey)
                .modelName(finalModelName)
                .temperature(0.7)
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(120))
                .build()
        );
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return executeWithPluginClassLoader(() -> delegateModel.chat(chatRequest));
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        return executeWithPluginClassLoader(() -> delegateModel.doChat(chatRequest));
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
    public String chat(String userMessage) {
        return executeWithPluginClassLoader(() -> delegateModel.chat(userMessage));
    }

    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return executeWithPluginClassLoader(() -> delegateModel.chat(messages));
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return executeWithPluginClassLoader(() -> delegateModel.chat(messages));
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return executeWithPluginClassLoader(() -> delegateModel.supportedCapabilities());
    }
}