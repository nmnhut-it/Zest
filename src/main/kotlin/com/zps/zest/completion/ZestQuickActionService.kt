package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.completion.MethodContext
import com.zps.zest.completion.prompt.ZestMethodPromptBuilder
import com.zps.zest.completion.parser.ZestMethodResponseParser
import com.zps.zest.completion.actions.ZestTriggerQuickAction
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.langchain4j.ZestLangChain4jService
import com.zps.zest.testgen.agents.ContextAgent
import com.zps.zest.testgen.model.TestGenerationRequest
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.ZestNotifications
import com.zps.zest.completion.ui.ZestCompletionStatusBarWidget
import com.zps.zest.completion.metrics.ZestQuickActionMetricsService
import com.zps.zest.completion.experience.ZestExperienceTracker
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service for managing method-level code rewrites with language-specific semantic diffing.
 * Enhanced with intelligent diff rendering and proper IntelliJ threading.
 * BREAKING CHANGES: Simplified flow to prevent LLM blocking
 */
@Service(Service.Level.PROJECT)
class ZestQuickActionService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestQuickActionService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Dependencies
    private val metricsService by lazy { ZestQuickActionMetricsService.getInstance(project) }
    private val llmService by lazy {
        try {
            System.out.println("[ZestMethodRewrite] Initializing LLMService...")
            val service = LLMService(project)
            System.out.println("[ZestMethodRewrite] LLMService initialized successfully")
            service
        } catch (e: Exception) {
            System.out.println("[ZestMethodRewrite] Failed to create LLMService: ${e.message}")
            e.printStackTrace()
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }
    
    // Context collection using sophisticated ContextAgent
    private val contextAgent by lazy {
        try {
            val langChainService = project.getService(ZestLangChain4jService::class.java)
            ContextAgent(project, langChainService, llmService)
        } catch (e: Exception) {
            logger.warn("Failed to initialize ContextAgent, falling back to minimal context", e)
            null
        }
    }

    // Rich context collection using ContextAgent tools for comprehensive analysis
    private suspend fun collectMethodContextWithAgent(
        editor: Editor, 
        offset: Int,
        smartDialog: ZestTriggerQuickAction.SmartRewriteDialog? = null
    ): MethodContext? {
        // First get basic method info with non-blocking PSI read
        val basicMethodInfo = readAction {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@readAction null
            val element = psiFile.findElementAt(offset) ?: return@readAction null
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@readAction null
            val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            
            Triple(
                method,
                containingClass,
                psiFile.virtualFile?.path ?: "unknown"
            )
        } ?: return null
        
        val (method, containingClass, filePath) = basicMethodInfo
        val methodText = method.text ?: ""
        val fileName = if (filePath.endsWith(".java")) "java" else "javascript"
        
        // Use ContextAgent tools directly for rich context collection
        val (relatedClasses, classContent) = contextAgent?.let { agent ->
            try {
                smartDialog?.updateProgress(1, "🔍 Analyzing class structure and dependencies...")
                
                // Use ContextAgent tools directly
                val tools = agent.contextTools
                tools.reset()
                
                // Analyze the containing class
                val classAnalysis = tools.analyzeClass(filePath)
                logger.debug("Class analysis result: ${classAnalysis.take(200)}...")
                
                // Get related files and classes  
                val relatedClassesMap = (tools.gatheredData["analyzedClasses"] as? Map<String, String>) ?: emptyMap()
                
                // Update dialog with actual context items
                val contextItems = listOf(
                    "Analyzed class: ${containingClass?.qualifiedName ?: "unknown"}",
                    "Related classes found: ${relatedClassesMap.size}",
                    "Class analysis: ${classAnalysis.lines().size} lines",
                    "Method: ${method.name} in ${containingClass?.name ?: "unknown"}"
                )
                smartDialog?.addContextDetails(contextItems, relatedClassesMap.size)
                
                Pair(relatedClassesMap, classAnalysis)
                
            } catch (e: Exception) {
                logger.warn("ContextAgent tools failed, using basic context", e)
                Pair(emptyMap<String, String>(), "")
            }
        } ?: Pair(emptyMap(), "")
        
        // Build enhanced MethodContext with rich data
        return MethodContext(
            methodName = method.name,
            methodStartOffset = method.textRange.startOffset,
            methodEndOffset = method.textRange.endOffset,
            methodContent = methodText,
            language = fileName,
            fileName = filePath,
            isCocos2dx = false,
            relatedClasses = relatedClasses,
            containingClass = containingClass?.qualifiedName,
            classContext = classContent,
            surroundingMethods = extractSurroundingMethods(containingClass)
        )
    }
    
    // Helper to extract surrounding methods from class PSI
    private fun extractSurroundingMethods(containingClass: PsiClass?): List<SurroundingMethod> {
        if (containingClass == null) return emptyList()
        
        return containingClass.methods.map { psiMethod ->
            SurroundingMethod(
                position = MethodPosition.BEFORE, // Could determine actual position relative to target method
                signature = "${psiMethod.name}(${psiMethod.parameterList.parameters.joinToString { it.type.presentableText }})"
            )
        }
    }
    
    // Build rich context display items for the dialog
    private fun buildContextDisplayItems(methodContext: MethodContext): List<String> {
        val items = mutableListOf<String>()
        
        // Method information
        items.add("🎯 **Target Method**: `${methodContext.methodSignature}` in ${methodContext.fileName}")
        
        // Class context
        if (methodContext.containingClass != null) {
            items.add("📦 **Containing Class**: ${methodContext.containingClass}")
        }
        
        // Class content preview
        if (methodContext.classContext.isNotEmpty()) {
            val preview = methodContext.classContext.lines().take(5).joinToString("\n")
            items.add("🏗️ **Class Structure**:\n```java\n$preview\n${if (methodContext.classContext.lines().size > 5) "... (${methodContext.classContext.lines().size - 5} more lines)" else ""}\n```")
        }
        
        // Related classes
        if (methodContext.relatedClasses.isNotEmpty()) {
            items.add("🔗 **Related Classes Found**: ${methodContext.relatedClasses.size}")
            methodContext.relatedClasses.keys.take(3).forEach { className ->
                items.add("   • $className")
            }
            if (methodContext.relatedClasses.size > 3) {
                items.add("   • ... ${methodContext.relatedClasses.size - 3} more")
            }
        }
        
        // Surrounding methods
        if (methodContext.surroundingMethods.isNotEmpty()) {
            items.add("🧩 **Context Methods**: ${methodContext.surroundingMethods.size} surrounding methods")
            methodContext.surroundingMethods.take(3).forEach { method ->
                items.add("   • `${method.signature}`")
            }
        }
        
        // Framework detection
        if (methodContext.isCocos2dx) {
            items.add("🎮 **Framework**: Cocos2d-x detected - using specialized syntax patterns")
        }
        
        // Fallback if no rich context collected
        if (items.size <= 2) {
            items.add("ℹ️ **Basic Context**: Method-level analysis only (ContextAgent unavailable)")
        }
        
        return items
    }
    
    private val promptBuilder = ZestMethodPromptBuilder(project)
    private val responseParser = ZestMethodResponseParser()
    private val methodDiffRenderer = ZestMethodDiffRenderer()
    private val experienceTracker by lazy { ZestExperienceTracker.getInstance(project) }

    // Request tracking to prevent multiple concurrent rewrites
    private val rewriteRequestId = AtomicInteger(0)
    private var activeRewriteId: Int? = null

    // State management
    private var currentRewriteJob: Job? = null
    private var currentMethodContext: MethodContext? = null
    private var currentRewrittenMethod: String? = null
    private var currentRewriteId: String? = null

    /**
     * Trigger method rewrite with pre-found method context and status callback for background processing
     * Method context is passed in (found on EDT before) to avoid EDT blocking
     * BREAKING CHANGE: No dialog support, uses background processing with status bar updates
     */
    fun rewriteCurrentMethodWithStatusCallback(
        editor: Editor,
        methodContext: MethodContext,
        customInstruction: String? = null,
        smartDialog: ZestTriggerQuickAction.SmartRewriteDialog? = null,
        statusCallback: ((String) -> Unit)? = null
    ) {
        System.out.println("[ZestMethodRewrite] rewriteCurrentMethodWithStatusCallback called:")
        System.out.println("  - methodContext: ${methodContext.methodName}")
        System.out.println("  - customInstruction: $customInstruction")

        // Cancel any existing rewrite immediately
        cancelCurrentRewrite()

        val requestId = rewriteRequestId.incrementAndGet()
        activeRewriteId = requestId

        System.out.println("[ZestMethodRewrite] Starting background method rewrite with requestId=$requestId")
        logger.info("Starting background method rewrite for method ${methodContext.methodName}, requestId=$requestId")

        // Store context immediately
        currentMethodContext = methodContext
        
        // Generate unique rewrite ID for tracking
        val rewriteId = "rewrite_${UUID.randomUUID()}"
        currentRewriteId = rewriteId

        // Track rewrite request
        metricsService.trackRewriteRequested(
            rewriteId = rewriteId,
            methodName = methodContext.methodName,
            language = methodContext.language,
            fileType = getFileType(methodContext.fileName),
            actualModel = "local-model-mini", // Will be updated when we get actual model
            customInstruction = customInstruction,
            contextInfo = mapOf(
                "is_cocos2dx" to methodContext.isCocos2dx,
                "has_related_classes" to methodContext.relatedClasses.isNotEmpty(),
                "method_length" to methodContext.methodContent.length
            )
        )

        // Update status and start LLM call immediately
        val methodInfo = if (methodContext.isCocos2dx) {
            "🎮 Analyzing Cocos2d-x method '${methodContext.methodName}'"
        } else {
            "📝 Analyzing method '${methodContext.methodName}'"
        }
        statusCallback?.invoke(methodInfo)

        // Start method rewrite process immediately in background
        System.out.println("[ZestMethodRewrite] Starting background performMethodRewrite job...")
        currentRewriteJob = scope.launch(Dispatchers.IO) {
            performMethodRewriteWithCallback(editor, methodContext, customInstruction, requestId, rewriteId, smartDialog, statusCallback)
        }
    }

    /**
     * Trigger method rewrite at cursor position (legacy method - kept for compatibility)
     */
    fun rewriteCurrentMethod(editor: Editor, offset: Int, customInstruction: String? = null) {
        scope.launch(Dispatchers.IO) {
            val requestId = rewriteRequestId.incrementAndGet()

            // Cancel any existing rewrite
            cancelCurrentRewrite()
            activeRewriteId = requestId

            try {
                // Collect comprehensive method context using ContextAgent
                val methodContext = collectMethodContextWithAgent(editor, offset, null)
                if (methodContext == null) {
                    withContext(Dispatchers.Main) {
                        ZestNotifications.showWarning(
                            project,
                            "No Method Found",
                            "Could not identify a method at the current cursor position."
                        )
                    }
                    return@launch
                }

                // Check if this request is still active
                if (activeRewriteId != requestId) {
                    return@launch
                }

                currentMethodContext = methodContext

                // Show loading notification on EDT
                ApplicationManager.getApplication().invokeLater {
                    val title = if (methodContext.isCocos2dx) "🎮 Cocos2d-x Method Rewrite" else "Method Rewrite"
                    val subtitle = if (methodContext.relatedClasses.isNotEmpty()) {
                        "Analyzing '${methodContext.methodName}' with ${methodContext.relatedClasses.size} related classes..."
                    } else {
                        "Analyzing and rewriting method '${methodContext.methodName}'..."
                    }
                    ZestNotifications.showInfo(project, title, subtitle)
                }

                // Start method rewrite process
                val rewriteId = "rewrite_${UUID.randomUUID()}"
                currentRewriteId = rewriteId
                
                // Track rewrite request
                metricsService.trackRewriteRequested(
                    rewriteId = rewriteId,
                    methodName = methodContext.methodName,
                    language = methodContext.language,
                    fileType = getFileType(methodContext.fileName),
                    actualModel = "local-model-mini",
                    customInstruction = customInstruction,
                    contextInfo = mapOf(
                        "is_cocos2dx" to methodContext.isCocos2dx,
                        "method_length" to methodContext.methodContent.length
                    )
                )
                
                currentRewriteJob = scope.launch(Dispatchers.IO) {
                    performMethodRewriteWithCallback(
                        editor,
                        methodContext,
                        customInstruction,
                        requestId,
                        rewriteId,
                        null, // No smart dialog for legacy method
                    ) { status ->
                        // Convert status updates to log messages for legacy compatibility
                        System.out.println("[ZestMethodRewrite] Status: $status")
                    }
                }

            } catch (e: Exception) {
                logger.error("Failed to trigger method rewrite", e)
                ApplicationManager.getApplication().invokeLater {
                    ZestNotifications.showError(
                        project,
                        "Method Rewrite Error",
                        "Failed to start method rewrite: ${e.message}"
                    )
                }
                if (activeRewriteId == requestId) {
                    activeRewriteId = null
                }
            }
        }
    }

    /**
     * Perform the method rewrite operation with status callback for background processing
     * BREAKING CHANGE: Removed dialog support, focuses on status bar updates
     */
    private suspend fun performMethodRewriteWithCallback(
        editor: Editor,
        methodContext: MethodContext,
        customInstruction: String?,
        requestId: Int,
        rewriteId: String?,  // Unique ID for metrics tracking
        smartDialog: ZestTriggerQuickAction.SmartRewriteDialog? = null,
        statusCallback: ((String) -> Unit)?
    ) {
        System.out.println("[ZestMethodRewrite] performMethodRewriteWithCallback started for request $requestId")

        try {
            // Quick validation
            if (activeRewriteId != requestId) {
                System.out.println("[ZestMethodRewrite] Request $requestId is outdated")
                return
            }

            // Analyze method context
            val analyzeContextMsg = "Analyzing method context..."
            statusCallback?.invoke("🔍 $analyzeContextMsg")
            smartDialog?.updateProgress(1, analyzeContextMsg) // STAGE_RETRIEVING_CONTEXT = 1
            
            // Show comprehensive context information collected by ContextAgent
            val contextItems = buildContextDisplayItems(methodContext)
            smartDialog?.addContextDetails(contextItems, methodContext.relatedClasses.size)
            
            // Build AI prompt
            val buildingPromptMsg = "Building AI prompt with context..."
            statusCallback?.invoke("🧠 $buildingPromptMsg")
            smartDialog?.updateProgress(2, buildingPromptMsg) // STAGE_BUILDING_PROMPT = 2
            
            val prompt = if (customInstruction != null) {
                promptBuilder.buildCustomMethodPrompt(methodContext, customInstruction)
            } else {
                promptBuilder.buildMethodRewritePrompt(methodContext)
            }
            
            // Track rewrite request
            val actualModel = llmService.getConfigStatus().model ?: "local-model-mini"
            
            // Add prompt details to dialog
            smartDialog?.addPromptDetails(
                prompt = prompt,
                tokenCount = prompt.length / 3, // Rough token estimate
                modelName = actualModel
            )
            rewriteId?.let {
                metricsService.trackRewriteRequested(
                    rewriteId = it,
                    methodName = methodContext.methodName,
                    language = methodContext.language,
                    fileType = getFileType(methodContext.fileName),
                    actualModel = actualModel,
                    contextInfo = mapOf(
                        "has_custom_instruction" to (customInstruction != null),
                        "is_cocos2dx" to methodContext.isCocos2dx,
                        "related_classes_count" to methodContext.relatedClasses.size
                    )
                )
            }
            
            // Call LLM service immediately
            System.out.println("[ZestMethodRewrite] Calling LLM service...")
            val queryingMsg = "Querying AI model..."
            statusCallback?.invoke("🤖 $queryingMsg")
            smartDialog?.updateProgress(3, queryingMsg) // STAGE_QUERYING_LLM = 3

            val startTime = System.currentTimeMillis()

            val response = withTimeoutOrNull(METHOD_REWRITE_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .useLiteCodeModel()
                    .withMaxTokens(METHOD_REWRITE_MAX_TOKENS)
                    .withTemperature(0.3)
                    .withStopSequences(getMethodRewriteStopSequences())

                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.QUICK_ACTION_LOGGING)
            }

            val responseTime = System.currentTimeMillis() - startTime

            if (response == null) {
                System.out.println("[ZestMethodRewrite] LLM request timed out")
                statusCallback?.invoke("⏰ Request timed out - please try again")
                rewriteId?.let {
                    metricsService.trackRewriteCancelled(it, "timeout")
                }
                throw Exception("LLM request timed out after ${METHOD_REWRITE_TIMEOUT_MS}ms")
            }

            System.out.println("[ZestMethodRewrite] LLM response received in ${responseTime}ms")
            
            // Add response details to dialog
            smartDialog?.addResponseDetails(
                response = response,
                responseTime = responseTime
            )
            
            // Track response received
            rewriteId?.let {
                metricsService.trackRewriteResponse(
                    rewriteId = it,
                    responseTime = responseTime,
                    rewrittenContent = response,
                    success = true
                )
            }

            // Check if request is still active
            if (activeRewriteId != requestId) {
                System.out.println("[ZestMethodRewrite] Request $requestId no longer active after LLM response")
                return
            }

            val parsingMsg = "Parsing AI response..."
            statusCallback?.invoke("⚙️ $parsingMsg")
            smartDialog?.updateProgress(4, parsingMsg) // STAGE_PARSING_RESPONSE = 4

            // Parse response
            val parseResult = responseParser.parseMethodRewriteResponse(
                response = response,
                originalMethod = methodContext.methodContent,
                methodName = methodContext.methodName,
                language = methodContext.language
            )

            if (!parseResult.isValid) {
                statusCallback?.invoke("❌ Generated code is invalid: ${parseResult.issues.firstOrNull()}")
                rewriteId?.let {
                    metricsService.trackRewriteCancelled(it, "invalid_response")
                }
                throw Exception("Generated method is invalid: ${parseResult.issues.joinToString(", ")}")
            }

            // Store the rewritten method
            currentRewrittenMethod = parseResult.rewrittenMethod
            
            // Add analysis details to dialog
            val originalLines = methodContext.methodContent.lines().size
            val newLines = parseResult.rewrittenMethod.lines().size
            val changesDescription = "Lines: $originalLines → $newLines (${if (newLines > originalLines) "+" else ""}${newLines - originalLines})"
            
            smartDialog?.addAnalysisDetails(
                originalLines = originalLines,
                newLines = newLines,
                changes = changesDescription
            )

            // Final check
            if (activeRewriteId != requestId) {
                System.out.println("[ZestMethodRewrite] Request $requestId no longer active before showing diff")
                return
            }

            // Track rewrite viewed (simplified without diff stats)
            rewriteId?.let {
                metricsService.trackRewriteViewed(
                    rewriteId = it,
                    diffChanges = 1, // Simple placeholder since we're not calculating diff stats
                    confidence = parseResult.confidence
                )
            }
            
            // Show diff - this will update status bar to "Review Ready"
            val completeMsg = "Processing complete! Review changes and press TAB to accept, ESC to reject"
            statusCallback?.invoke("✅ $completeMsg")
            smartDialog?.completeProcessing() // Show completion and close dialog

            ApplicationManager.getApplication().invokeLater {
                showLanguageAwareDiff(
                    editor = editor,
                    methodContext = methodContext,
                    rewrittenMethod = parseResult.rewrittenMethod,
                    rewriteId = rewriteId
                )
            }

            System.out.println("[ZestMethodRewrite] Method rewrite completed successfully")
            logger.info("Method rewrite completed successfully")

        } catch (e: CancellationException) {
            System.out.println("[ZestMethodRewrite] Method rewrite was cancelled")
            statusCallback?.invoke("❌ Rewrite cancelled")
            smartDialog?.updateProgress(6, "❌ Process cancelled")
            rewriteId?.let {
                metricsService.trackRewriteCancelled(it, "user_cancelled")
            }
            throw e
        } catch (e: Exception) {
            System.out.println("[ZestMethodRewrite] Method rewrite failed: ${e.message}")
            e.printStackTrace()
            logger.error("Method rewrite failed", e)

            statusCallback?.invoke("❌ Rewrite failed: ${e.message}")
            smartDialog?.updateProgress(6, "❌ Error: ${e.message}")
            
            rewriteId?.let {
                metricsService.trackRewriteCancelled(it, "error: ${e.message}")
            }

            ApplicationManager.getApplication().invokeLater {
                methodDiffRenderer.hide()
            }
        } finally {
            // Clear activeRewriteId when the rewrite is complete
            if (activeRewriteId == requestId) {
                System.out.println("[ZestMethodRewrite] Clearing activeRewriteId after completion")
                activeRewriteId = null
            }
        }
    }




    /**
     * Show diff using IntelliJ's built-in diff dialog
     */
    private fun showLanguageAwareDiff(
        editor: Editor,
        methodContext: MethodContext,
        rewrittenMethod: String,
        rewriteId: String? = null
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()

        // Start the diff renderer - this will directly show the IntelliJ diff dialog
        methodDiffRenderer.startMethodRewrite(
            editor = editor,
            methodContext = methodContext,
            originalContent = methodContext.methodContent,
            rewrittenContent = rewrittenMethod,
            onAccept = { acceptMethodRewriteInternal(editor, rewriteId) },
            onReject = { cancelCurrentRewrite(rewriteId) }
        )
    }


    /**
     * Accept the method rewrite
     */
    private fun acceptMethodRewriteInternal(editor: Editor, rewriteId: String? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val methodContext = currentMethodContext ?: return
        val rewrittenMethod = currentRewrittenMethod ?: return

        try {
            // Notify status bar that we're applying changes
            val statusBarWidget = getStatusBarWidget()
            statusBarWidget?.updateMethodRewriteState(
                ZestCompletionStatusBarWidget.MethodRewriteState.APPLYING,
                "Applying method changes..."
            )


            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                
                // Always replace the entire method to preserve LLM's complete output
                replaceFullMethod(document, methodContext, rewrittenMethod)
            }

            // Track acceptance
            rewriteId?.let {
                metricsService.trackRewriteAccepted(
                    rewriteId = it,
                    acceptedContent = rewrittenMethod,
                    userAction = "tab"
                )
                
                // Track for experience learning
                experienceTracker.trackRewriteAcceptance(
                    sessionId = it,
                    methodName = methodContext.methodName,
                    originalCode = methodContext.methodContent,
                    aiSuggestion = rewrittenMethod,
                    editor = editor
                )
            }
            
            // Notify completion
            statusBarWidget?.updateMethodRewriteState(
                ZestCompletionStatusBarWidget.MethodRewriteState.COMPLETED,
                "Method '${methodContext.methodName}' rewritten successfully"
            )

        } catch (e: Exception) {
            logger.error("Failed to apply method rewrite", e)
            ZestNotifications.showError(project, "Apply Failed", "Failed to apply rewrite: ${e.message}")

            // Clear status on error
            getStatusBarWidget()?.clearMethodRewriteState()
        } finally {
            cleanup()
        }
    }

    /**
     * Replace the full method (fallback when we can't preserve declaration)
     */
    private fun replaceFullMethod(
        document: com.intellij.openapi.editor.Document,
        methodContext: MethodContext,
        rewrittenMethod: String
    ) {
        document.replaceString(
            methodContext.methodStartOffset,
            methodContext.methodEndOffset,
            rewrittenMethod
        )

        val newEndOffset = methodContext.methodStartOffset + rewrittenMethod.length

        // Reformat the code
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (psiFile != null) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
            try {
                CodeStyleManager.getInstance(project)
                    .reformatRange(psiFile, methodContext.methodStartOffset, newEndOffset)
            } catch (e: Exception) {
                logger.warn("Failed to reformat: ${e.message}")
            }
        }
    }


    /**
     * Get the status bar widget for updates
     */
    private fun getStatusBarWidget(): ZestCompletionStatusBarWidget? {
        return try {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            statusBar?.getWidget(ZestCompletionStatusBarWidget.WIDGET_ID) as? ZestCompletionStatusBarWidget
        } catch (e: Exception) {
            logger.debug("Could not get status bar widget", e)
            null
        }
    }

    /**
     * Cancel the current method rewrite operation
     */
    fun cancelCurrentRewrite() {
        cancelCurrentRewrite(null)
    }
    
    /**
     * Cancel the current method rewrite operation with specific rewrite ID
     */
    fun cancelCurrentRewrite(rewriteId: String?) {
        // Track rejection if rewrite ID is provided
        val idToTrack = rewriteId ?: currentRewriteId
        idToTrack?.let {
            metricsService.trackRewriteRejected(it, "esc_pressed")
        }
        currentRewriteJob?.cancel()
        ApplicationManager.getApplication().invokeLater {
            methodDiffRenderer.hide()
            // Clear status bar state
            getStatusBarWidget()?.clearMethodRewriteState()
        }
        currentRewriteJob = null
        currentMethodContext = null
        currentRewrittenMethod = null
        currentRewriteId = null
    }

    /**
     * Check if rewrite is in progress
     */
    fun isRewriteInProgress(): Boolean {
        return currentRewriteJob?.isActive == true || methodDiffRenderer.isActive()
    }

    /**
     * Accept method rewrite (public method)
     */
    fun acceptMethodRewrite(editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            acceptMethodRewriteInternal(editor, currentRewriteId)
        }
    }

    /**
     * Get stop sequences for method rewrite
     */
    private fun getMethodRewriteStopSequences(): List<String> {
        return listOf(
            "</method>", "</code>", "<|endoftext|>", "<|end|>",
            "# End of method", "```", "---", "Explanation:",
            "Note:", "Summary:", "class ", "interface ", "enum "
        )
    }

    /**
     * Clean up resources
     */
    private fun cleanup() {
        currentRewriteJob = null
        currentMethodContext = null
        currentRewrittenMethod = null
        currentRewriteId = null
    }

    override fun dispose() {
        logger.info("Disposing ZestMethodRewriteService")
        activeRewriteId = null
        scope.cancel()
        ApplicationManager.getApplication().invokeLater {
            methodDiffRenderer.hide()
            getStatusBarWidget()?.clearMethodRewriteState()
        }
        cleanup()
    }

    private fun getFileType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "")) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts" -> "typescript"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "header"
            "py" -> "python"
            else -> "unknown"
        }
    }


    companion object {
        private const val METHOD_REWRITE_TIMEOUT_MS = 200000L // 20 seconds
        private const val METHOD_REWRITE_MAX_TOKENS = 1500
    }
}