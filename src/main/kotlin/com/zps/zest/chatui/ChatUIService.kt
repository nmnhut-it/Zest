package com.zps.zest.chatui

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.langchain4j.ZestChatLanguageModel
import com.zps.zest.langchain4j.ZestStreamingChatLanguageModel
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.TokenStream
import com.zps.zest.testgen.tools.ReadFileTool
import com.zps.zest.testgen.tools.AnalyzeClassTool
import com.zps.zest.testgen.tools.ListFilesTool
import com.zps.zest.testgen.tools.LookupMethodTool
import com.zps.zest.testgen.tools.LookupClassTool
import com.zps.zest.explanation.tools.RipgrepCodeTool
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.application.ApplicationManager
import com.zps.zest.ConfigurationManager
import com.zps.zest.rules.ZestRulesLoader
import dev.langchain4j.service.V
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
    private val naiveLlmService: NaiveLLMService by lazy { project.getService(NaiveLLMService::class.java) }
    private var chatModel: ZestChatLanguageModel = createChatModel(currentUsage)
    private var streamingChatModel: ZestStreamingChatLanguageModel = createStreamingChatModel(currentUsage)
    private val chatMemory: MessageWindowChatMemory = MessageWindowChatMemory.withMaxMessages(500)
    
    // Tools for file operations
    private val readFiles = mutableMapOf<String, String>()
    private val searchResults = mutableListOf<String>()
    private val analyzedClasses = mutableSetOf<String>()
    private val readFileTool = ReadFileTool(project, readFiles)
    private val searchTool = RipgrepCodeTool(project, mutableSetOf(), searchResults)
    private val analyzeClassTool = AnalyzeClassTool(project, HashMap())
    private val listFilesTool = ListFilesTool(project)
    private val lookupMethodTool = LookupMethodTool(project)
    private val lookupClassTool = LookupClassTool(project)

    // Code modification tools need dialog reference - created lazily
    private fun getCodeModificationTools(): com.zps.zest.chatui.CodeModificationTools {
        return com.zps.zest.chatui.CodeModificationTools(project, currentDialog)
    }

    private var toolEnabledAssistant: ChatAssistant? = null
    private var streamingToolEnabledAssistant: StreamingChatAssistant? = null
    
    // Dialog management
    private var currentDialog: JCEFChatDialog? = null

    // Cache for project rules
    private var cachedProjectRules: String? = null
    private var lastRulesLoadTime: LocalDateTime? = null
    private val rulesRefreshIntervalMinutes = 30L

    /**
     * Build system prompt with project rules concatenated if they exist
     */
    private fun buildSystemPromptWithRules(basePrompt: String): String {
        // Check if we need to refresh the cached rules
        if (cachedProjectRules == null || lastRulesLoadTime == null ||
            ChronoUnit.MINUTES.between(lastRulesLoadTime, LocalDateTime.now()) >= rulesRefreshIntervalMinutes) {

            // Load project rules
            val rulesLoader = ZestRulesLoader(project)
            cachedProjectRules = rulesLoader.loadCustomRules()
            lastRulesLoadTime = LocalDateTime.now()

            if (cachedProjectRules != null) {
                LOG.info("Loaded project rules for chat context (${cachedProjectRules!!.length} characters)")
            } else {
                LOG.info("No project rules found")
            }
        }

        // If rules exist and not already in the prompt, concatenate them
        return if (!cachedProjectRules.isNullOrBlank() && !basePrompt.contains("## Project-Specific Rules")) {
            """
$basePrompt

## Project-Specific Rules

${cachedProjectRules}
            """.trimIndent()
        } else {
            basePrompt
        }
    }

    /**
     * Send a message to the AI with non-streaming response and tool integration using AiServices
     *
     * @param userMessage The message to send
     * @param onComplete Callback when response is complete
     * @param onError Callback for errors
     */
    fun sendMessage(
        userMessage: String,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        LOG.info("Sending non-streaming message to AI: ${userMessage.take(100)}...")

        try {
            // Initialize tool-enabled assistant if not already done
            if (toolEnabledAssistant == null) {
                toolEnabledAssistant = createToolEnabledAssistant()
            }

            // Execute on background thread to avoid blocking UI
            Thread {
                try {
                    val response = toolEnabledAssistant!!.chat(userMessage)
                    LOG.info("Received non-streaming AI response: ${response.take(100)}...")
                    ApplicationManager.getApplication().invokeLater {
                        onComplete(response)
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to get non-streaming response", e)
                    ApplicationManager.getApplication().invokeLater {
                        onError(e)
                    }
                }
            }.start()

        } catch (e: Exception) {
            LOG.error("Failed to start non-streaming message", e)
            onError(e)
        }
    }

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
        onIntermediateResponse: ((dev.langchain4j.model.chat.response.ChatResponse) -> Unit)? = null,
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
                .onIntermediateResponse { response ->
                    // AI finished streaming text, about to execute tools
                    sendBatch()  // Send any remaining tokens

                    ApplicationManager.getApplication().invokeLater {
                        onIntermediateResponse?.invoke(response)
                    }

                    LOG.info("Intermediate response: ${response.aiMessage().toolExecutionRequests().size} tools to execute")
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
            .tools(readFileTool, searchTool, analyzeClassTool, listFilesTool, lookupMethodTool, lookupClassTool, getCodeModificationTools())
            .build()
    }

    /**
     * Create a streaming tool-enabled assistant with file reading and search capabilities
     */
    private fun createStreamingToolEnabledAssistant(): StreamingChatAssistant {
        return AiServices.builder(StreamingChatAssistant::class.java)
            .streamingChatModel(streamingChatModel)
            .chatMemory(chatMemory)
            .maxSequentialToolsInvocations(10)
            .tools(readFileTool, searchTool, analyzeClassTool, listFilesTool, lookupMethodTool, lookupClassTool, getCodeModificationTools())
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
     * Get the chat memory object directly (for TestWriterAgent integration)
     */
    fun getChatMemory(): MessageWindowChatMemory {
        return chatMemory
    }
    
    /**
     * Clear the current conversation
     */
    fun clearConversation() {
        LOG.info("Clearing chat conversation")
        chatMemory.clear()
        // Reset assistants to rebuild with fresh memory
        toolEnabledAssistant = null
        streamingToolEnabledAssistant = null
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
            addSystemMessage(buildSystemPromptWithRules("""
You are an expert code reviewer following the ContextAgent philosophy: understand code thoroughly before reviewing.

## Core Review Principles

1. **Thorough Understanding First**: Use tools to explore the codebase and understand context before making judgments
2. **Code Reuse Focus**: Always check for existing implementations before suggesting new code
3. **Actionable Feedback**: Provide specific, line-numbered suggestions with code examples
4. **Concise Communication**: Less talk, more value - focus on critical issues

## Review Areas

1. **Correctness & Bugs**: Logic errors, edge cases, null safety, race conditions
2. **Performance**: Algorithm complexity, resource leaks, unnecessary operations, caching opportunities
3. **Security**: Input validation, injection risks, authentication, data exposure
4. **Testability**: Dependency injection issues, hard-to-mock patterns, missing test coverage
5. **Code Reuse**: Duplicate logic, reinvented wheels, missed abstraction opportunities
6. **Architecture**: SOLID violations, design pattern misuse, coupling issues

## Tool Usage Strategy (5 tools maximum)

**IMPORTANT**: You have 5 tool calls. Track usage: "Using tool 1/5", "4/5 remaining", etc.

### Available Tools

1. **readFile(filePath)** - Read complete file contents
2. **searchCode(query, filePattern, excludePattern, beforeLines, afterLines)** - Search with ripgrep
3. **findFiles(pattern)** - Find files matching a pattern
4. **analyzeClass(className)** - Get class structure and relationships
5. **listFiles(directoryPath, recursiveLevel)** - List directory contents

### Smart Search Patterns

You read the searching tool instructions and tips carefully  

### When to Use Tools

✅ **DO use tools for:**
- Finding existing implementations: `searchCode("similar_method", "*.java")`
- Checking test coverage: `findFiles("*Test.java")`
- Understanding dependencies: `searchCode("import.*ClassName", "*.java")`
- Finding usage patterns: `searchCode("methodName\\(", "*.java", null, 2, 2)`

❌ **DON'T use tools for:**
- General coding advice
- Obvious issues visible in provided code
- Style preferences without context

### Tool Examples (Actual Syntax)

1. **Check for existing implementations:**
   Use searchCode with pattern chaining: "validateInput|sanitizeData|checkPermission"

2. **Find related tests:**
   Use findFiles with wildcards: "*UserService*Test*.java"

3. **Understand class relationships:**
   Use analyzeClass with full name: "com.example.UserService"

4. **Check for similar patterns:**
   Use searchCode with regex: "synchronized.*lock|ReentrantLock|Semaphore"

5. **Explore project structure:**
   Use listFiles with depth: listFiles("src/main", 2)

## Response Style

- **Be concise**: Get to the point quickly
- **Use bullet points**: Organize findings clearly
- **Provide examples**: Show, don't just tell
- **Prioritize issues**: Critical → Major → Minor → Suggestions
- **Track tool budget**: Always mention remaining tool calls

## Output Format

### 🔴 Critical Issues
- [Line X]: Specific issue with code example fix

### 🟡 Major Issues
- [Line Y]: Issue description with improvement suggestion

### 🟢 Minor Issues
- [Line Z]: Enhancement opportunity

### 💡 Suggestions
- Consider using existing `ClassName.method()` instead of reimplementing

### Tool Usage: X/5 used

**Note**: All tool syntax examples are illustrative. Follow the actual tool signatures provided.

            """.trimIndent()))
        }
    }
    
    /**
     * Prepare the chat with context for method rewriting
     */
    fun prepareForMethodRewrite() {
        // Set context for method rewriting
        setContext(ChatboxUtilities.EnumUsage.CHAT_CODE_REVIEW) // Reuse code review context for now

        if (getMessages().isEmpty()) {
            addSystemMessage(buildSystemPromptWithRules("""
You are a code modification expert. Make focused, single edits with immediate user review.

## Tool Budget

**Exploration tools** (count toward budget): ~2-3 calls
- searchCode, readFile, findFiles, analyzeClass, listFiles, lookupMethod, lookupClass

**Code modification tools** (FREE - do NOT count):
- replaceCodeInFile, createNewFile

## Workflow

1. **Read file**: Use `readFile()` to see exact code with correct indentation
2. **Make ONE focused edit**: Use `replaceCodeInFile()` to change one thing at a time
3. **User reviews**: Diff dialog shows with TAB (accept) / ESC (reject)
4. **Repeat if needed**: Make next edit after user accepts/rejects
5. **Review your edit**: if you introduce duplicated logic or code, or produce syntax errors. 

## Code quality: 

1. Follow user preferences as you can infer from the code. 
2. Pay attention to existing code in the file and method
3. Follow best practices for programming.

## Critical Syntax Rules

**When writing searchPattern and replacement:**

⚠️ **INDENTATION**: Copy exact spaces/tabs from readFile output - off by one space = not found
⚠️ **BRACES**: Count carefully - every { needs matching }
⚠️ **BRACKETS**: Every ( [ { must have closing ) ] }
⚠️ **SEMICOLONS**: Don't forget ; at end of statements
⚠️ **QUOTES**: Match " and ' exactly - escape if needed: \\"
⚠️ **LINE BREAKS**: Use \\n for newlines in multiline patterns

**Best practice:** Copy searchPattern EXACTLY from readFile output - don't retype!

## Response Style

**DO:**
- Call tools directly - just use them
- Keep responses brief (1-2 sentences max)
- Make focused, single-purpose edits

**DON'T:**
- Say "Now I will call X tool..." - just call it
- Show code blocks - the diff shows everything
- Explain what tools do - just use them
- Try to make multiple complex changes in one edit

**Example:**
User: "Add logging to processData method"
→ [calls: readFile("Service.java")]
→ [calls: replaceCodeInFile with single focused change]
"✅ Added logging. Review with TAB/ESC."
            """.trimIndent()))
        }
    }

    /**
     * Prepare the chat with context for commit message generation
     */
    fun prepareForCommitMessage() {
        // Set context for commit message generation
        setContext(ChatboxUtilities.EnumUsage.CHAT_GIT_COMMIT_MESSAGE)
        
        if (getMessages().isEmpty()) {
            addSystemMessage(buildSystemPromptWithRules("""
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
- `readFile("src/main/java/UserService.java")` → When commit touches complex business logic
- `searchCode("UserService", "*.java", null)` → When refactoring affects multiple files
- `findFiles("**/test/**")` → When commit adds extensive test coverage

Examples:
- feat: add user authentication system
- fix: resolve null pointer exception in UserService
- refactor: extract common validation logic
Be concise but descriptive.
            """.trimIndent()))
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
    private fun createPrioritizedLLMService(): NaiveLLMService {
        val configManager = com.zps.zest.ConfigurationManager.getInstance(project)
        val originalApiUrl = configManager.apiUrl
        
        // Check if we can prioritize ZingPlay endpoints
        val preferredApiUrl = getPreferredZingPlayEndpoint(originalApiUrl)
        
        if (preferredApiUrl != originalApiUrl) {
            LOG.info("Prioritizing ZingPlay endpoint: $preferredApiUrl over original: $originalApiUrl")
            // Temporarily override the API URL for chat dialog
            configManager.apiUrl = preferredApiUrl
        }
        
        return naiveLlmService
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

            // Clear assistants to free up resources
            toolEnabledAssistant = null
            streamingToolEnabledAssistant = null

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
    @dev.langchain4j.service.UserMessage("{{it}}")
    fun chat(userMessage: String): String
}

interface StreamingChatAssistant {
    @dev.langchain4j.service.UserMessage("{{it}}")
    fun chat(userMessage: String): TokenStream
}