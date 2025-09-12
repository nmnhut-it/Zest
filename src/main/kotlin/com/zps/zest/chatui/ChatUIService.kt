package com.zps.zest.chatui

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.langchain4j.ZestChatLanguageModel
import com.zps.zest.langchain4j.ZestStreamingChatLanguageModel
import com.zps.zest.langchain4j.util.LLMService
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.TokenStream
import com.zps.zest.testgen.tools.ReadFileTool
import com.zps.zest.explanation.tools.RipgrepCodeTool
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.application.ApplicationManager
import com.zps.zest.ConfigurationManager
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Service for managing simple chat UI interactions.
 * Uses ZestChatLanguageModel and MessageWindowChatMemory for proper LangChain4j integration.
 */
@Service(Service.Level.PROJECT)
class ChatUIService(private val project: Project) : Disposable {
    
    companion object {
        private val LOG = Logger.getInstance(ChatUIService::class.java)
        
        // ZingPlay endpoints in priority order
        private val ZINGPLAY_ENDPOINTS = listOf(
            "https://chat.zingplay.com/api/chat/completions",
            "https://talk.zingplay.com/api/chat/completions"
        )
    }
    
    // Context tracking
    private var currentUsage: ChatboxUtilities.EnumUsage = ChatboxUtilities.EnumUsage.CHAT_CODE_REVIEW
    private var selectedModel: String = "local-model"
    
    // Models caching
    private var cachedModels: List<String>? = null
    private var lastModelsRefresh: LocalDateTime? = null
    private val modelsRefreshIntervalMinutes = 30L
    
    // LangChain4j components
    private val llmService: LLMService by lazy { project.getService(LLMService::class.java) }
    private var chatModel: ZestChatLanguageModel = createChatModel(currentUsage)
    private var streamingChatModel: ZestStreamingChatLanguageModel = createStreamingChatModel(currentUsage)
    private val chatMemory: MessageWindowChatMemory = MessageWindowChatMemory.withMaxMessages(500)
    
    // Tools for file operations
    private val readFiles = mutableMapOf<String, String>()
    private val searchResults = mutableListOf<String>()
    private val readFileTool = ReadFileTool(project, readFiles)
    private val searchTool = RipgrepCodeTool(project, mutableSetOf(), searchResults)
    private var toolEnabledAssistant: ChatAssistant? = null
    private var streamingToolEnabledAssistant: StreamingChatAssistant? = null
    
    // Dialog management
    private var currentDialog: JCEFChatDialog? = null

    /**
     * Send a message to the AI with streaming response support and tool integration using AiServices
     * 
     * @param userMessage The message to send
     * @param onToken Callback for each streaming token
     * @param onComplete Callback when streaming is complete
     * @param onError Callback for errors
     * @param onToolCall Callback when a tool is called (toolName, toolArgs, toolCallId)
     * @param onToolResult Callback when a tool completes (toolCallId, result)
     */
    fun sendMessageStreaming(
        userMessage: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onToolCall: ((String, String, String) -> Unit)? = null,
        onToolResult: ((String, String) -> Unit)? = null
    ) {
        LOG.info("Sending streaming message to AI: ${userMessage.take(100)}...")
        
        try {
            // Initialize streaming tool-enabled assistant if not already done
            if (streamingToolEnabledAssistant == null) {
                streamingToolEnabledAssistant = createStreamingToolEnabledAssistant()
            }
            
            val responseBuilder = StringBuilder()
            
            // Token batching for better performance
            val tokenBatch = StringBuilder()
            var lastBatchTime = System.currentTimeMillis()
            val batchInterval = 200L
            
            val sendBatch = {
                if (tokenBatch.isNotEmpty()) {
                    val chunk = tokenBatch.toString()
                    tokenBatch.clear()
                    ApplicationManager.getApplication().invokeLater {
                        onToken(chunk)
                    }
                }
            }
            
            // Use AiServices streaming with TokenStream
            val tokenStream = streamingToolEnabledAssistant!!.chat(userMessage)
            
            // Store tool call IDs for UI updates
            val toolCallIds = mutableMapOf<String, String>()
            
            tokenStream
                .onPartialResponse { partialResponse ->
                    responseBuilder.append(partialResponse)
                    tokenBatch.append(partialResponse)
                    
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBatchTime >= batchInterval || tokenBatch.length > 50) {
                        sendBatch()
                        lastBatchTime = currentTime
                    }
                }
                .beforeToolExecution { beforeToolExecution ->
                    // Show tool call in UI
                    val toolRequest = beforeToolExecution.request()
                    val toolName = toolRequest.name()
                    val toolArgs = toolRequest.arguments()
                    val toolCallId = "tool-${toolRequest.id()}-${System.currentTimeMillis()}"
                    
                    // Store mapping for later updates
                    toolCallIds[toolRequest.id()] = toolCallId
                    
                    ApplicationManager.getApplication().invokeLater {
                        onToolCall?.invoke(toolName, toolArgs, toolCallId)
                    }
                    LOG.info("Tool execution starting: $toolName with args: $toolArgs")
                }
                .onToolExecuted { toolExecution ->
                    // Show tool result in UI
                    val toolRequest = toolExecution.request()
                    val toolResult = toolExecution.result()
                    val toolCallId = toolCallIds[toolRequest.id()] ?: "tool-${toolRequest.id()}"
                    
                    ApplicationManager.getApplication().invokeLater {
                        onToolResult?.invoke(toolCallId, toolResult)
                    }
                    LOG.info("Tool execution completed: ${toolRequest.name()} -> ${toolResult.take(100)}...")
                }
                .onCompleteResponse { response ->
                    // Send final batch if any
                    sendBatch()
                    
                    val fullResponse = responseBuilder.toString()
                    LOG.info("Received streaming AI response with tools: ${fullResponse.take(100)}...")
                    ApplicationManager.getApplication().invokeLater {
                        onComplete(fullResponse)
                    }
                }
                .onError { error ->
                    LOG.error("Streaming failed", error)
                    ApplicationManager.getApplication().invokeLater {
                        onError(error)
                    }
                }
                .start()
            
        } catch (e: Exception) {
            LOG.error("Failed to start streaming message", e)
            onError(e)
        }
    }
    
    
    /**
     * Create a tool-enabled assistant with file reading and search capabilities
     */
    private fun createToolEnabledAssistant(): ChatAssistant {
        return AiServices.builder(ChatAssistant::class.java)
            .chatModel(chatModel)
            .chatMemory(chatMemory)
            .tools(readFileTool, searchTool)
            .build()
    }
    
    /**
     * Create a streaming tool-enabled assistant with file reading and search capabilities
     */
    private fun createStreamingToolEnabledAssistant(): StreamingChatAssistant {
        return AiServices.builder(StreamingChatAssistant::class.java)
            .streamingChatModel(streamingChatModel)
            .chatMemory(chatMemory)
            .maxSequentialToolsInvocations(3)
            .tools(readFileTool, searchTool)
            .build()
    }
    
    /**
     * Get all messages in the current conversation
     */
    fun getMessages(): List<ChatMessage> {
        return try {
            chatMemory.messages()
        } catch (e: Exception) {
            LOG.warn("Failed to get messages from chat memory", e)
            emptyList()
        }
    }
    
    /**
     * Clear the current conversation
     */
    fun clearConversation() {
        LOG.info("Clearing chat conversation")
        chatMemory.clear()
    }
    
    /**
     * Open the chat dialog
     */
    fun openChat(): JCEFChatDialog {
        if (currentDialog == null || currentDialog!!.isDisposed) {
            LOG.info("Creating new JCEFChatDialog for project: ${project.name}")
            currentDialog = JCEFChatDialog(project)
            currentDialog!!.show()
        } else if (!currentDialog!!.isVisible) {
            LOG.info("Reusing existing JCEFChatDialog, making it visible")
            currentDialog!!.show()
        } else {
            // Bring existing dialog to front
            LOG.debug("Bringing existing JCEFChatDialog to front")
            currentDialog!!.toFront()
        }
        return currentDialog!!
    }
    
    /**
     * Open chat dialog with a pre-filled message
     */
    fun openChatWithMessage(message: String, autoSend: Boolean = true) {
        val dialog = openChat()
        dialog.openWithMessage(message, autoSend)
    }

    /**
     * Add a system message to the conversation (for context/instructions)
     */
    fun addSystemMessage(message: String) {
        LOG.info("Adding system message: ${message.take(100)}...")
        chatMemory.add(dev.langchain4j.data.message.SystemMessage.from(message))
    }
    
    /**
     * Prepare the chat with context for code review
     */
    fun prepareForCodeReview() {
        // Set context for code review
        setContext(ChatboxUtilities.EnumUsage.CHAT_CODE_REVIEW)
        
        if (getMessages().isEmpty()) {
            addSystemMessage("""
You are an expert code reviewer and software development assistant. Your role is to:

1. **Code Review**: Analyze code for bugs, performance issues, security vulnerabilities, and best practices
2. **Code Quality**: Suggest improvements for readability, maintainability, and architecture
3. **Best Practices**: Recommend industry-standard patterns and conventions
4. **Security**: Identify potential security issues and suggest fixes
5. **Performance**: Point out performance bottlenecks and optimization opportunities
6. **Test-ability**: Point out flaws that make the code hard to be unit-tested or integration-tested

## Tool Usage Limits

**IMPORTANT**: You are limited to a maximum of 3 tool calls per conversation. Use them wisely and strategically.

Only use tools when you need specific information from the codebase. Do not use tools for general advice or when the user's question can be answered without examining code.

Please provide:
- **Issues**: Specific problems with line numbers when possible
- **Recommendations**: Concrete suggestions with code examples
- **Priority**: Which issues to address first
- **Summary**: Brief overall assessment

Be thorough but concise. Focus on actionable feedback.

            """.trimIndent())
        }
    }
    
    /**
     * Prepare the chat with context for commit message generation
     */
    fun prepareForCommitMessage() {
        // Set context for commit message generation
        setContext(ChatboxUtilities.EnumUsage.CHAT_GIT_COMMIT_MESSAGE)
        
        if (getMessages().isEmpty()) {
            addSystemMessage("""
You are a Git commit message specialist. Your role is to create clear, conventional commit messages.

Follow these guidelines:
1. **Format**: Use conventional commits (feat:, fix:, docs:, style:, refactor:, test:, chore:)
2. **Summary**: Keep first line under 50 characters
3. **Description**: Explain what and why, not how
4. **Body**: Add details if needed, wrapped at 72 characters
5. **Breaking**: Note breaking changes with BREAKING CHANGE:

## Tool Usage for Commit Messages

Use tools sparingly for commit message context:

**When to Use Tools:**
✅ User mentions "review this commit" → `readFile()` on specific changed files
✅ Need to understand complex changes → `searchCode()` to see related patterns
✅ Large refactoring commits → `findFiles()` to understand scope

**When NOT to Use Tools:**
❌ Simple commits with clear context already provided
❌ User just wants message format help
❌ Commit diff is already sufficient
❌ Standard feature additions without complexity

**Tool Examples:**
• `readFile("src/main/java/UserService.java")` → When commit touches complex business logic
• `searchCode("UserService", "*.java", null)` → When refactoring affects multiple files
• `findFiles("**/test/**")` → When commit adds extensive test coverage

Examples:
- feat: add user authentication system
- fix: resolve null pointer exception in UserService
- refactor: extract common validation logic
Be concise but descriptive.
            """.trimIndent())
        }
    }
    
    /**
     * Set the context/usage for this chat session
     */
    fun setContext(usage: ChatboxUtilities.EnumUsage) {
        if (currentUsage != usage) {
            LOG.info("Switching chat context from $currentUsage to $usage")
            currentUsage = usage
            chatModel = createChatModel(usage)
            streamingChatModel = createStreamingChatModel(usage)
            // Reset assistants to use new models
            toolEnabledAssistant = null
            streamingToolEnabledAssistant = null
        }
    }
    
    /**
     * Create a chat model with the specified usage context and ZingPlay priority
     */
    private fun createChatModel(usage: ChatboxUtilities.EnumUsage): ZestChatLanguageModel {
        return ZestChatLanguageModel(createPrioritizedLLMService(), usage, selectedModel)
    }
    
    /**
     * Create a streaming chat model with the specified usage context
     */
    private fun createStreamingChatModel(usage: ChatboxUtilities.EnumUsage): ZestStreamingChatLanguageModel {
        return ZestStreamingChatLanguageModel(createPrioritizedLLMService(), usage, selectedModel)
    }
    
    /**
     * Create an LLMService that prioritizes ZingPlay endpoints over LiteLLM
     */
    private fun createPrioritizedLLMService(): LLMService {
        val configManager = com.zps.zest.ConfigurationManager.getInstance(project)
        val originalApiUrl = configManager.apiUrl
        
        // Check if we can prioritize ZingPlay endpoints
        val preferredApiUrl = getPreferredZingPlayEndpoint(originalApiUrl)
        
        if (preferredApiUrl != originalApiUrl) {
            LOG.info("Prioritizing ZingPlay endpoint: $preferredApiUrl over original: $originalApiUrl")
            // Temporarily override the API URL for chat dialog
            configManager.apiUrl = preferredApiUrl
        }
        
        return llmService
    }
    
    /**
     * Get the preferred ZingPlay endpoint, falling back to LiteLLM if necessary
     */
    private fun getPreferredZingPlayEndpoint(currentUrl: String): String {
        // If already using a ZingPlay endpoint, keep it
        if (ZINGPLAY_ENDPOINTS.any { currentUrl.startsWith(it.substringBefore("/api")) }) {
            return currentUrl
        }
        
        // Check if current URL is LiteLLM - if so, try to use ZingPlay instead
        if (currentUrl.contains("litellm")) {
            LOG.info("Current URL is LiteLLM, attempting to prioritize ZingPlay for chat dialog")
            
            // For chat dialog, prefer chat.zingplay.com first
            val preferredEndpoint = ZINGPLAY_ENDPOINTS.first()
            LOG.info("Using preferred ZingPlay endpoint for chat: $preferredEndpoint")
            return preferredEndpoint
        }
        
        // Keep the original URL if it's not LiteLLM
        return currentUrl
    }
    
    /**
     * Get available models from OpenWebUI API with caching
     */
    fun getAvailableModels(): List<String> {
        // Check if cached models are still valid
        if (cachedModels != null && lastModelsRefresh != null) {
            val minutesSinceRefresh = ChronoUnit.MINUTES.between(lastModelsRefresh, LocalDateTime.now())
            if (minutesSinceRefresh < modelsRefreshIntervalMinutes) {
                return cachedModels!!
            }
        }
        
        // Refresh models from API
        return refreshModelsFromAPI()
    }
    
    /**
     * Force refresh models from OpenWebUI API
     */
    fun refreshModelsFromAPI(): List<String> {
        try {
            val configManager = com.zps.zest.ConfigurationManager.getInstance(project)
            val baseUrl = getOpenWebUIBaseUrl(configManager.apiUrl)
            
            if (baseUrl != null) {
                val models = fetchModelsFromOpenWebUI(baseUrl)
                if (models.isNotEmpty()) {
                    cachedModels = models
                    lastModelsRefresh = LocalDateTime.now()
                    LOG.info("Successfully fetched ${models.size} models from OpenWebUI API")
                    return models
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to fetch models from OpenWebUI API: ${e.message}", e)
        }
        
        // Fallback to default models
        val defaultModels = getDefaultModels()
        cachedModels = defaultModels
        lastModelsRefresh = LocalDateTime.now()
        return defaultModels
    }
    
    /**
     * Get OpenWebUI base URL from API endpoint
     */
    private fun getOpenWebUIBaseUrl(apiUrl: String): String? {
        return when {
            apiUrl.contains("chat.zingplay.com") -> "https://chat.zingplay.com"
            apiUrl.contains("talk.zingplay.com") -> "https://talk.zingplay.com"
            apiUrl.contains("openwebui") -> {
                // Extract base URL from full endpoint
                try {
                    val url = URL(apiUrl)
                    "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}"
                } catch (e: Exception) {
                    LOG.warn("Failed to parse OpenWebUI URL: $apiUrl", e)
                    null
                }
            }
            else -> null
        }
    }
    
    /**
     * Fetch models from OpenWebUI /api/models endpoint
     */
    private fun fetchModelsFromOpenWebUI(baseUrl: String): List<String> {
        try {
            val url = URL("$baseUrl/api/models")
            val connection = url.openConnection() as HttpURLConnection
            
            // Add authentication if available
            val apiKey = getOpenWebUIApiKey()
            if (apiKey != null) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                return parseModelsResponse(response)
            } else {
                LOG.warn("OpenWebUI API returned status code: $responseCode")
                if (responseCode == 401) {
                    LOG.warn("Authentication failed - check OpenWebUI API key")
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error fetching models from OpenWebUI: ${e.message}", e)
        }
        
        return emptyList()
    }
    
    /**
     * Parse OpenWebUI models API response
     */
    private fun parseModelsResponse(jsonResponse: String): List<String> {
        try {
            val gson = Gson()
            val response = gson.fromJson(jsonResponse, OpenWebUIModelsResponse::class.java)
            
            return response.data?.map { model ->
                // Extract model name/id, preferring id over name
                model.id ?: model.name ?: "unknown"
            }?.filter { it != "unknown" } ?: emptyList()
            
        } catch (e: JsonSyntaxException) {
            LOG.warn("Failed to parse OpenWebUI models response: ${e.message}", e)
            
            // Try to extract models from alternative format
            try {
                val gson = Gson()
                val models = gson.fromJson(jsonResponse, Array<OpenWebUIModel>::class.java)
                return models.map { it.id ?: it.name ?: "unknown" }.filter { it != "unknown" }
            } catch (e2: Exception) {
                LOG.warn("Failed to parse models with alternative format: ${e2.message}")
            }
        } catch (e: Exception) {
            LOG.warn("Unexpected error parsing models response: ${e.message}", e)
        }
        
        return emptyList()
    }
    
    /**
     * Get OpenWebUI API key from configuration
     */
    private fun getOpenWebUIApiKey(): String? {
        return try {
            // Check for OpenWebUI-specific API key in various sources
            val apiKey = System.getProperty("openwebui.api.key") 
                ?: System.getenv("OPENWEBUI_API_KEY")
                ?: System.getenv("ZINGPLAY_API_KEY")  // ZingPlay specific
                ?: ConfigurationManager.getInstance(project).authToken;
            if (apiKey.isNullOrBlank()) {
                LOG.debug("No OpenWebUI API key found - requests will be made without authentication")
                null
            } else {
                LOG.debug("Found OpenWebUI API key for authentication")
                apiKey
            }
        } catch (e: Exception) {
            LOG.warn("Error getting OpenWebUI API key: ${e.message}")
            null
        }
    }
    
    /**
     * Get default models as fallback
     */
    private fun getDefaultModels(): List<String> {
        return listOf(
            "gpt-4o",
            "gpt-4o-mini", 
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "gemini-1.5-pro-002",
            "gemini-1.5-flash-002",
            "llama3.1",
            "qwen2.5:7b",
            "mistral-nemo:12b"
        )
    }
    
    // Data classes for OpenWebUI API responses
    private data class OpenWebUIModelsResponse(
        val data: List<OpenWebUIModel>?
    )
    
    private data class OpenWebUIModel(
        val id: String?,
        val name: String?,
        val `object`: String? = null,  // Use backticks for reserved keywords
        val owned_by: String? = null,
        val permission: List<Any>? = null
    )
    
    /**
     * Set the selected model
     */
    fun setSelectedModel(model: String) {
        if (selectedModel != model) {
            LOG.info("Switching model from $selectedModel to $model")
            selectedModel = model
            // Recreate both chat models with new settings
            chatModel = createChatModel(currentUsage)
            streamingChatModel = createStreamingChatModel(currentUsage)
            // Reset assistants to use new models
            toolEnabledAssistant = null
            streamingToolEnabledAssistant = null
        }
    }
    
    /**
     * Get current selected model
     */
    fun getSelectedModel(): String = selectedModel
    
    // Data class for chat statistics
    data class ChatStats(
        val totalMessages: Int,
        val userMessages: Int, 
        val aiMessages: Int,
        val hasActiveConversation: Boolean
    )
    
    /**
     * Dispose method to properly clean up JCEF components when service is disposed
     */
    override fun dispose() {
        LOG.info("Disposing ChatUIService for project: ${project.name}")
        try {
            // Properly dispose of the dialog and its JCEF components
            currentDialog?.let { dialog ->
                if (!dialog.isDisposed) {
                    LOG.info("Disposing JCEFChatDialog and its JCEF components")
                    dialog.close(0)
                    // The dialog itself will be disposed through IntelliJ's disposal mechanism
                }
            }
            currentDialog = null
            
            // Clear chat memory to free up resources
            chatMemory.clear()
            
            // Clear tool-enabled assistant to free up resources
            toolEnabledAssistant = null
            
            LOG.info("ChatUIService disposed successfully")
        } catch (e: Exception) {
            LOG.error("Error during ChatUIService disposal", e)
        }
    }
}

/**
 * LangChain4j service interfaces for tool-enabled chat
 */
interface ChatAssistant {
    fun chat(userMessage: String): String
}

interface StreamingChatAssistant {
    fun chat(userMessage: String): TokenStream
}