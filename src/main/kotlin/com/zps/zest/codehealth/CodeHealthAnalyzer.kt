package com.zps.zest.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLoopStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.langchain4j.ZestLangChain4jService
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Analysis engine that examines modified methods for issues and impact.
 * Uses LLM for intelligent analysis and existing Zest tools for context.
 */
@Service(Service.Level.PROJECT)
class CodeHealthAnalyzer(private val project: Project) {

    companion object {
        private const val LLM_DELAY_MS = 20000L // Delay between LLM calls
        const val MAX_METHODS_PER_ANALYSIS = 20 // Hard limit to prevent excessive LLM calls
        private const val MAX_LLM_RETRIES = 1 // Allow 3 attempts total for actual retries
        private const val LLM_TIMEOUT_MS = 30000L // 30 second timeout per LLM call
        
        // Feature flags
        var SKIP_VERIFICATION = true // Skip verification step for faster analysis
        
        fun getInstance(project: Project): CodeHealthAnalyzer =
            project.getService(CodeHealthAnalyzer::class.java)
    }

    private val contextCollector = ZestLeanContextCollector(project)
    private val llmService: LLMService = project.service()
    private val langChainService: ZestLangChain4jService = project.service()
    private val analysisQueue = SimpleAnalysisQueue(delayMs = 50L)
    private val results = ConcurrentHashMap<String, MethodHealthResult>()
    private val gson = Gson()
    
    // Rate limiter for LLM calls
    private val llmRateLimiter = RateLimiter(LLM_DELAY_MS)
    
    // JS/TS analyzer
    private val jsTsAnalyzer = JsTsHealthAnalyzer(project)
    private val analyzedJsTsFiles = ConcurrentHashMap.newKeySet<String>() // Track analyzed JS/TS files

    /**
     * Analyze specific files for code health issues
     */
    fun analyzeFiles(filePaths: List<String>): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing ${filePaths.size} files directly")
        val allResults = mutableListOf<MethodHealthResult>()
        
        // Define supported code file extensions
        val codeExtensions = setOf(
            "java", "kt", "js", "ts", "jsx", "tsx", "py", "cpp", "c", "h", 
            "cs", "go", "rb", "php", "swift", "rs", "scala"
        )
        
        ReadAction.run<RuntimeException> {
            for (filePath in filePaths) {
                // Skip non-code files
                val extension = filePath.substringAfterLast('.').toLowerCase()
                if (!codeExtensions.contains(extension)) {
                    println("[CodeHealthAnalyzer] Skipping non-code file: $filePath")
                    continue
                }
                
                val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(filePath)
                val psiFile = virtualFile?.let { 
                    com.intellij.psi.PsiManager.getInstance(project).findFile(it)
                }
                if (psiFile == null) {
                    println("[CodeHealthAnalyzer] File not found: $filePath")
                    continue
                }
                
                // For Java files, analyze all methods
                if (filePath.endsWith(".java")) {
                    val methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
                    println("[CodeHealthAnalyzer] Found ${methods.size} methods in $filePath")
                    
                    for (method in methods) {
                        val fqn = method.containingClass?.qualifiedName + "." + method.name
                        val mockMethod = ProjectChangesTracker.ModifiedMethod(
                            fqn = fqn,
                            modificationCount = 1,
                            lastModified = System.currentTimeMillis()
                        )
                        
                        // Analyze synchronously for immediate feedback
                        val result = analyzeMethodSync(mockMethod)
                        if (result.issues.isNotEmpty()) {
                            allResults.add(result)
                        }
                    }
                } 
                // For JS/TS files, analyze as regions
                else if (filePath.endsWith(".js") || filePath.endsWith(".ts") || 
                         filePath.endsWith(".jsx") || filePath.endsWith(".tsx")) {
                    val regionFqn = "$filePath:1"
                    val mockMethod = ProjectChangesTracker.ModifiedMethod(
                        fqn = regionFqn,
                        modificationCount = 1,
                        lastModified = System.currentTimeMillis()
                    )
                    
                    val result = analyzeJsTsRegionSync(mockMethod)
                    if (result.issues.isNotEmpty()) {
                        allResults.add(result)
                    }
                }
            }
        }
        
        return allResults
    }
    
    /**
     * Synchronous method analysis for immediate feedback
     */
    private fun analyzeMethodSync(method: ProjectChangesTracker.ModifiedMethod): MethodHealthResult {
        return try {
            val future = CompletableFuture<MethodHealthResult>()
            analyzeMethodAsync(method) { result ->
                future.complete(result)
            }
            future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error analyzing method ${method.fqn}: ${e.message}")
            MethodHealthResult(
                fqn = method.fqn,
                issues = emptyList(),
                impactedCallers = emptyList(),
                healthScore = 100,
                modificationCount = method.modificationCount,
                summary = "Error analyzing method: ${e.message}",
                annotatedCode = "",
                originalCode = ""
            )
        }
    }
    
    /**
     * Synchronous JS/TS region analysis
     */
    private fun analyzeJsTsRegionSync(method: ProjectChangesTracker.ModifiedMethod): MethodHealthResult {
        return try {
            val future = CompletableFuture<MethodHealthResult>()
            analyzeJsTsRegionAsync(method) { result ->
                future.complete(result)
            }
            future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error analyzing JS/TS region ${method.fqn}: ${e.message}")
            MethodHealthResult(
                fqn = method.fqn,
                issues = emptyList(),
                impactedCallers = emptyList(),
                healthScore = 100,
                modificationCount = method.modificationCount,
                summary = "Error analyzing region: ${e.message}",
                annotatedCode = "",
                originalCode = ""
            )
        }
    }

    /**
     * Health issue data class - completely flexible, LLM decides everything
     */
    data class HealthIssue(
        val issueCategory: String,  // LLM decides: "Null Safety", "Performance", etc.
        val severity: Int,          // LLM decides: 1-5 scale
        val title: String,          // LLM decides: concise issue title
        val description: String,    // LLM decides: detailed description
        val impact: String,         // LLM decides: what could go wrong
        val suggestedFix: String,   // LLM decides: how to fix it
        val lineNumbers: List<Int> = emptyList(),
        val confidence: Double = 1.0,  // LLM confidence in this issue
        val verified: Boolean = false,
        val verificationReason: String? = null,
        var falsePositive: Boolean = false,
        val codeSnippet: String? = null,  // Relevant code snippet
        val callerSnippets: List<CallerSnippet> = emptyList()  // How this method is called
    )
    
    /**
     * Represents a code snippet showing how a method is called
     */
    data class CallerSnippet(
        val callerFqn: String,
        val callerFile: String,
        val lineNumber: Int,
        val snippet: String,
        val context: String  // Brief description of calling context
    )

    /**
     * Analysis result for a method
     */
    data class MethodHealthResult(
        val fqn: String,
        val issues: List<HealthIssue>,
        val impactedCallers: List<String>,
        val healthScore: Int,
        val modificationCount: Int,
        val codeContext: String = "",
        val summary: String = "",  // Overall method health summary
        val actualModel: String = "local-model-mini",  // Model used for analysis
        val annotatedCode: String = "",  // Code with inline LLM comments
        val originalCode: String = ""  // Original method code without annotations
    )

    /**
     * Analyze all modified methods using async processing with progress indicator
     */
    fun analyzeAllMethodsAsync(
        methods: List<ProjectChangesTracker.ModifiedMethod>,
        indicator: ProgressIndicator? = null
    ): List<MethodHealthResult> {
        // Limit the number of methods to analyze
        val methodsToAnalyze = methods.take(MAX_METHODS_PER_ANALYSIS)
        
        if (methodsToAnalyze.size < methods.size) {
            println("[CodeHealthAnalyzer] Limited analysis from ${methods.size} to ${methodsToAnalyze.size} methods")
        }
        
        println("[CodeHealthAnalyzer] Starting analysis of ${methodsToAnalyze.size} methods")
        results.clear()
        analyzedJsTsFiles.clear() // Clear the JS/TS file tracking
        val totalMethods = methodsToAnalyze.size
        val completed = AtomicInteger(0)
        val latch = CountDownLatch(totalMethods)
        val cancelled = AtomicBoolean(false)
        
        // Process methods
        methodsToAnalyze.forEach { method ->
            if (indicator?.isCanceled == true || cancelled.get()) {
                println("[CodeHealthAnalyzer] Analysis cancelled")
                latch.countDown()
                return@forEach
            }
            
            val currentCount = completed.get() + 1
            
            // Update progress
            indicator?.let {
                it.fraction = currentCount.toDouble() / totalMethods
                it.text = "Analyzing method $currentCount of $totalMethods: ${method.fqn}"
                it.text2 = "Modified ${method.modificationCount} times"
            }
            
            println("[CodeHealthAnalyzer] Queuing analysis for method $currentCount/$totalMethods: ${method.fqn}")
            
            // Analyze method asynchronously
            analyzeMethodAsync(method) { result ->
                if (cancelled.get()) {
                    latch.countDown()
                    return@analyzeMethodAsync
                }
                
                val issueCount = result.issues.size
                val verifiedCount = result.issues.count { it.verified && !it.falsePositive }
                println("[CodeHealthAnalyzer] Method analysis complete for ${method.fqn}: $issueCount issues found, $verifiedCount verified")
                
                results[method.fqn] = result
                val completedCount = completed.incrementAndGet()
                
                // Update progress after completion
                indicator?.let {
                    it.fraction = completedCount.toDouble() / totalMethods
                    it.text = "Completed $completedCount of $totalMethods methods"
                    
                    // Check cancellation
                    if (it.isCanceled) {
                        cancelled.set(true)
                    }
                }
                
                latch.countDown()
                println("[CodeHealthAnalyzer] Progress: $completedCount/$totalMethods completed, ${latch.count} remaining")
            }
        }
        
        // Wait for all analyses to complete with better timeout handling
        try {
            println("[CodeHealthAnalyzer] Waiting for all analyses to complete...")
            
            val completed = latch.await(10, TimeUnit.MINUTES) // Increased timeout to 10 minutes
            
            if (!completed) {
                cancelled.set(true)
                println("[CodeHealthAnalyzer] WARNING: Analysis timed out after 10 minutes. Latch count: ${latch.count}")
                
                // Return partial results
                val partialResults = results.values.toList()
                    .sortedByDescending { it.healthScore * it.modificationCount }
                
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Zest Code Health")
                            .createNotification(
                                "⏱️ Zest Guardian: Partial Results",
                                "⚡ Got ${partialResults.size} of ${methodsToAnalyze.size} results. Still valuable insights!",
                                NotificationType.WARNING
                            )
                            .notify(project)
                    }
                }
                
                return partialResults
            }
        } catch (e: InterruptedException) {
            println("[CodeHealthAnalyzer] Analysis interrupted")
            cancelled.set(true)
            Thread.currentThread().interrupt()
        }
        
        val resultsList = results.values.toList()
            .sortedByDescending { it.healthScore * it.modificationCount }
        
        println("[CodeHealthAnalyzer] Analysis complete. Returning ${resultsList.size} results")
        return resultsList
    }

    /**
     * Analyze a single method asynchronously
     */
    private fun analyzeMethodAsync(
        method: ProjectChangesTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        if (method.fqn.isBlank()) {
            println("[CodeHealthAnalyzer] WARNING: Skipping method with empty FQN")
            onComplete(createFallbackResult("", "", emptyList(), method.modificationCount, "local-model-mini"))
            return
        }
        
        // Check if this is a JS/TS region (format: filename.js:lineNumber)
        if (method.fqn.contains(".js:") || method.fqn.contains(".ts:")) {
            println("[CodeHealthAnalyzer] Detected JS/TS region: ${method.fqn}")
            analyzeJsTsRegionAsync(method, onComplete)
            return
        }
        
        // Check if already analyzed
        if (results.containsKey(method.fqn)) {
            println("[CodeHealthAnalyzer] Method ${method.fqn} already analyzed, skipping")
            onComplete(results[method.fqn]!!)
            return
        }
        
        val startTime = System.currentTimeMillis()
        println("[CodeHealthAnalyzer] Starting async analysis of ${method.fqn}")
        
        // Run the entire analysis in a single background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Step 1: Get method context
                println("[CodeHealthAnalyzer] Step 1: Getting context for ${method.fqn}")
                val context = ReadAction.nonBlocking<String> {
                    getMethodContext(method.fqn)
                }
                .inSmartMode(project)
                .executeSynchronously()
                
                if (context.isEmpty() || context.contains("Method not found")) {
                    println("[CodeHealthAnalyzer] WARNING: No context found for ${method.fqn}")
                    onComplete(createFallbackResult(method.fqn, context, emptyList(), method.modificationCount, "local-model-mini"))
                    return@executeOnPooledThread
                }
                
                // Step 2: Find callers
                println("[CodeHealthAnalyzer] Step 2: Finding callers for ${method.fqn}")
                val callers = ReadAction.nonBlocking<List<String>> {
                    findCallers(method.fqn)
                }
                .inSmartMode(project)
                .executeSynchronously()
                
                println("[CodeHealthAnalyzer] Found ${callers.size} callers for ${method.fqn}")
                
                // Step 3: Get limited caller snippets
                println("[CodeHealthAnalyzer] Step 3: Getting caller snippets for ${method.fqn}")
                val callerSnippets = ReadAction.nonBlocking<List<CallerSnippet>> {
                    findCallersWithSnippets(method.fqn).take(5)
                }
                .inSmartMode(project)
                .executeSynchronously()
                
                println("[CodeHealthAnalyzer] Got ${callerSnippets.size} caller snippets for ${method.fqn}")
                
                // Step 4: Call LLM for detection
                println("[CodeHealthAnalyzer] Step 4: LLM detection for ${method.fqn}")
                val detectionResult = detectIssuesWithLLM(method, context, callers, callerSnippets)
                
                println("[CodeHealthAnalyzer] LLM detected ${detectionResult.issues.size} issues for ${method.fqn}")
                
                // Step 5: Verify issues if needed (skip if flag is set)
                val finalResult = if (detectionResult.issues.isNotEmpty() && !SKIP_VERIFICATION) {
                    println("[CodeHealthAnalyzer] Step 5: Verifying issues for ${method.fqn}")
                    verifyIssuesWithAgent(detectionResult)
                } else {
                    if (SKIP_VERIFICATION && detectionResult.issues.isNotEmpty()) {
                        println("[CodeHealthAnalyzer] Skipping verification (SKIP_VERIFICATION=true)")
                        // Mark all issues as verified when skipping verification
                        detectionResult.copy(
                            issues = detectionResult.issues.map { issue ->
                                issue.copy(
                                    verified = true,
                                    verificationReason = "Verification skipped"
                                )
                            }
                        )
                    } else {
                        detectionResult
                    }
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                val verifiedCount = finalResult.issues.count { it.verified && !it.falsePositive }
                println("[CodeHealthAnalyzer] Completed ${method.fqn} in ${elapsed}ms. Verified $verifiedCount/${finalResult.issues.size} issues")
                
                onComplete(finalResult)
                
            } catch (e: Exception) {
                // Rethrow ProcessCanceledException
                if (e is com.intellij.openapi.progress.ProcessCanceledException) {
                    throw e
                }
                println("[CodeHealthAnalyzer] ERROR analyzing ${method.fqn}: ${e.message}")
                e.printStackTrace()
                onComplete(createFallbackResult(method.fqn, "", emptyList(), method.modificationCount, "local-model-mini"))
            }
        }
    }
    
    /**
     * Analyze a JS/TS region asynchronously
     */
    private fun analyzeJsTsRegionAsync(
        method: ProjectChangesTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        // Check if already analyzed
        if (results.containsKey(method.fqn)) {
            println("[CodeHealthAnalyzer] JS/TS region ${method.fqn} already analyzed, skipping")
            onComplete(results[method.fqn]!!)
            return
        }
        
        // Convert to ModifiedRegion - handle file paths with colons correctly
        val lastColonIndex = method.fqn.lastIndexOf(":")
        if (lastColonIndex <= 0) {
            println("[CodeHealthAnalyzer] Invalid JS/TS region format: ${method.fqn}")
            onComplete(createFallbackResult(method.fqn, "", emptyList(), method.modificationCount, "local-model-mini"))
            return
        }
        
        val filePath = method.fqn.substring(0, lastColonIndex)
        val centerLine = method.fqn.substring(lastColonIndex + 1).toIntOrNull() ?: 0
        val language = if (filePath.endsWith(".ts")) "ts" else "js"
        
        // Check if file already analyzed
        if (analyzedJsTsFiles.contains(filePath)) {
            println("[CodeHealthAnalyzer] File $filePath already analyzed, skipping")
            onComplete(createFallbackResult(method.fqn, "", emptyList(), method.modificationCount, "local-model-mini"))
            return
        }
        
        // Mark file as analyzed
        analyzedJsTsFiles.add(filePath)
        
        val region = ModifiedRegion(
            filePath = filePath,
            centerLine = centerLine,
            startLine = (centerLine - 20).coerceAtLeast(0),
            endLine = centerLine + 20,
            language = language,
            framework = null,
            modificationCount = method.modificationCount,
            lastModified = method.lastModified
        )
        
        // Use JS/TS analyzer
        jsTsAnalyzer.analyzeRegion(region) { result ->
            results[method.fqn] = result
            onComplete(result)
        }
    }

    /**
     * Unified LLM call with retry logic
     */
    private fun callLLMWithRetry(
        params: LLMService.LLMQueryParams,
        usage: com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage,
        description: String
    ): String? {
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_LLM_RETRIES) {
            try {
                println("[CodeHealthAnalyzer] LLM attempt $attempt for $description")
                
                // Rate limit LLM calls
                llmRateLimiter.acquire()
                
                val startTime = System.currentTimeMillis()
                
                // Create a future with timeout
                val future = CompletableFuture.supplyAsync({
                    llmService.queryWithParams(params, usage)
                })
                
                val response = try {
                    future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    throw Exception("LLM timeout after ${LLM_TIMEOUT_MS}ms")
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                
                if (response != null) {
                    println("[CodeHealthAnalyzer] LLM succeeded on attempt $attempt in ${elapsed}ms for $description")
                    llmRateLimiter.recordSuccess()
                    return response
                } else {
                    println("[CodeHealthAnalyzer] LLM returned null on attempt $attempt for $description")
                    lastException = Exception("LLM returned null response")
                    llmRateLimiter.recordError()
                }
                
            } catch (e: Exception) {
                println("[CodeHealthAnalyzer] LLM attempt $attempt failed for $description: ${e.message}")
                lastException = e
                llmRateLimiter.recordError()
                
                // Exponential backoff before retry
                if (attempt < MAX_LLM_RETRIES) {
                    val backoffMs = (1000L * Math.pow(2.0, attempt.toDouble())).toLong()
                    println("[CodeHealthAnalyzer] Waiting ${backoffMs}ms before retry...")
                    Thread.sleep(backoffMs)
                }
            }
        }
        
        println("[CodeHealthAnalyzer] All LLM attempts failed for $description: ${lastException?.message}")
        return null
    }

    /**
     * First pass: LLM detects all potential issues
     */
    private fun detectIssuesWithLLM(
        method: ProjectChangesTracker.ModifiedMethod,
        context: String,
        callers: List<String>,
        callerSnippets: List<CallerSnippet>
    ): MethodHealthResult {
        val detectionPrompt = buildDetectionPrompt(method.fqn, context, callers, callerSnippets)
        
        val params = LLMService.LLMQueryParams(detectionPrompt)
            .useLiteCodeModel()
            .withMaxTokens(4096)
            .withTemperature(0.3)
        
        val actualModel = params.getModel()
        
        return callLLMWithRetry(
            params = params,
            usage = com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH,
            description = "detection for ${method.fqn}"
        )?.let { response ->
            parseDetectionResponse(method.fqn, context, callers, callerSnippets, method.modificationCount, response, actualModel)
        } ?: MethodHealthResult(
            fqn = method.fqn,
            issues = emptyList(),
            impactedCallers = callers,
            healthScore = 85,
            modificationCount = method.modificationCount,
            codeContext = context,
            summary = "Analysis failed",
            actualModel = actualModel,
            annotatedCode = context,
            originalCode = context
        )
    }

    private fun buildDetectionPrompt(fqn: String, context: String, callers: List<String>, callerSnippets: List<CallerSnippet>): String {
        return """
            Phân tích method này và xác định tối đa 3 vấn đề tiềm tàng, sau đó sắp xếp theo mức độ nghiêm trọng.
            
            Method: $fqn
            
            ```java
            $context
            ```
            
            Hãy xem xét kỹ:
            - Điều gì có thể thực sự gây lỗi hoặc bugs?
            - Có lỗ hổng security nào không?
            - Điều gì có thể leak resources hoặc memory?
            - Vấn đề performance nào nghiêm trọng?
            
            QUAN TRỌNG: Bạn PHẢI bao gồm originalCode và annotatedCode trong response.
            
            Trả về CHỈ valid JSON với các issues được sắp xếp theo độ nghiêm trọng (critical nhất trước):
            {
                "summary": "Đánh giá 1 dòng",
                "healthScore": 85,
                "originalCode": "COPY CHÍNH XÁC toàn bộ code từ java block trên - không được thay đổi gì",
                "annotatedCode": "COPY TOÀN BỘ code từ java block trên NHƯNG thêm inline comments review với prefix // 🔴 CRITICAL:, // 🟠 WARNING:, // 🟡 SUGGESTION:",
                "issues": [
                    {
                        "category": "Category",
                        "severity": 4,
                        "title": "Tiêu đề ngắn",
                        "description": "Vấn đề gì",
                        "impact": "Hậu quả thực tế",
                        "suggestedFix": "Cách fix",
                        "confidence": 0.9,
                        "priority": 1,
                        "lineNumber": 15,
                        "problematicCode": "dòng code chính xác có vấn đề"
                    }
                ]
            }
            
            Ví dụ originalCode và annotatedCode:
            
            originalCode: (copy chính xác từ java block)
            "public void processData(String input) {\n    Connection conn = null;\n    try {\n        conn = getConnection();\n        // process logic here\n    } catch (Exception e) {\n        e.printStackTrace();\n    }\n}"
            
            annotatedCode: (cùng code nhưng có inline comments)
            "public void processData(String input) {\n    Connection conn = null;\n    try {\n        conn = getConnection();\n        // process logic here\n    } catch (Exception e) {\n        e.printStackTrace(); // 🔴 CRITICAL: Exception swallowed without proper handling\n    } // 🟠 WARNING: Connection not closed in finally block\n}"
            
            Đối với annotatedCode:
            - PHẢI bao gồm TOÀN BỘ code gốc
            - Thêm inline comments với severity indicators (🔴 CRITICAL, 🟠 WARNING, 🟡 SUGGESTION)
            - Đặt comments trực tiếp trên hoặc cuối các dòng có vấn đề
            - Giữ nguyên cấu trúc code gốc
            - Sử dụng comments mô tả nhưng ngắn gọn
            
            Tìm tối đa 3 issues nhưng SẮP XẾP theo tác động thực tế.
            Issue đầu tiên phải là CRITICAL NHẤT.
            Bỏ qua: naming, formatting, optimizations nhỏ, style preferences.
            Chỉ báo cáo issues với severity 3+ có thể gây vấn đề thực sự.
        """.trimIndent()
    }

    /**
     * Parse a single issue from JSON object
     */
    private fun parseIssue(issueObject: JsonObject, verified: Boolean = false): HealthIssue? {
        val severity = issueObject.get("severity")?.asInt ?: 3
        
        // Only process significant issues (severity >= 3)
        if (severity < 3) return null
        
        return HealthIssue(
            issueCategory = issueObject.get("category")?.asString ?: "Unknown",
            severity = severity,
            title = issueObject.get("title")?.asString ?: "Unknown Issue",
            description = issueObject.get("description")?.asString ?: "",
            impact = issueObject.get("impact")?.asString ?: "",
            suggestedFix = issueObject.get("suggestedFix")?.asString ?: "",
            confidence = issueObject.get("confidence")?.asDouble ?: 0.8,
            verified = verified,
            verificationReason = if (verified) "Pre-verified" else null,
            codeSnippet = issueObject.get("codeSnippet")?.asString,
            callerSnippets = emptyList()
        )
    }
    
    /**
     * Extract JSON object from LLM response
     */
    private fun extractJsonFromResponse(response: String): JsonObject? {
        val jsonStart = response.indexOf("{")
        val jsonEnd = response.lastIndexOf("}")
        if (jsonStart == -1 || jsonEnd == -1) {
            return null
        }
        
        return try {
            val jsonContent = response.substring(jsonStart, jsonEnd + 1)
            gson.fromJson(jsonContent, JsonObject::class.java)
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error parsing JSON: ${e.message}")
            null
        }
    }
    
    private fun parseDetectionResponse(
        fqn: String,
        context: String,
        callers: List<String>,
        callerSnippets: List<CallerSnippet>,
        modificationCount: Int,
        llmResponse: String,
        actualModel: String
    ): MethodHealthResult {
        return try {
            val jsonObject = extractJsonFromResponse(llmResponse)
                ?: return createFallbackResult(fqn, context, callers, modificationCount, actualModel)
            
            // Parse summary and health score
            val summary = jsonObject.get("summary")?.asString ?: "Analysis completed"
            val healthScore = jsonObject.get("healthScore")?.asInt ?: 85
            
            // Parse code fields - get raw values to see what LLM actually returns
            val originalCodeRaw = jsonObject.get("originalCode")?.asString
            val annotatedCodeRaw = jsonObject.get("annotatedCode")?.asString
            
            println("[CodeHealthAnalyzer] LLM Response Debug for $fqn:")
            println("  originalCode provided: ${originalCodeRaw != null}, length: ${originalCodeRaw?.length ?: 0}")
            println("  annotatedCode provided: ${annotatedCodeRaw != null}, length: ${annotatedCodeRaw?.length ?: 0}")
            
            val originalCode = originalCodeRaw ?: ""
            val annotatedCode = annotatedCodeRaw ?: ""
            
            // Parse issues - AI returns up to 3 ordered by criticality, but we only show the first (most critical) one
            val issues = mutableListOf<HealthIssue>()
            val issuesArray = jsonObject.getAsJsonArray("issues")
            
            if (issuesArray != null && issuesArray.size() > 0) {
                // Only process the first issue (most critical) as per product requirements
                val issueObject = issuesArray.get(0).asJsonObject
                parseIssue(issueObject)?.let { issue ->
                    issues.add(issue.copy(callerSnippets = callerSnippets))
                }
            }
            
            MethodHealthResult(
                fqn = fqn,
                issues = issues,
                impactedCallers = callers,
                healthScore = healthScore,
                modificationCount = modificationCount,
                codeContext = context,
                summary = summary,
                actualModel = actualModel,
                annotatedCode = annotatedCode,
                originalCode = originalCode
            )
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error parsing detection response: ${e.message}")
            e.printStackTrace()
            createFallbackResult(fqn, context, callers, modificationCount, actualModel)
        }
    }

    /**
     * Second pass: Verify detected issues with intelligent agent
     */
    private fun verifyIssuesWithAgent(result: MethodHealthResult): MethodHealthResult {
        println("[CodeHealthAnalyzer] Verifying ${result.issues.size} issues for ${result.fqn}")
        
        val verificationPrompt = buildVerificationPrompt(result)
        
        val params = LLMService.LLMQueryParams(verificationPrompt)
            .useLiteCodeModel()
            .withMaxTokens(2048)
            .withTemperature(0.1)
        
        val response = callLLMWithRetry(
            params = params,
            usage = com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH,
            description = "verification for ${result.fqn}"
        )
        
        return if (response != null) {
            val verifiedResult = parseVerificationResponse(result, response)
            val verifiedCount = verifiedResult.issues.count { it.verified && !it.falsePositive }
            println("[CodeHealthAnalyzer] Verified $verifiedCount/${verifiedResult.issues.size} issues as real for ${result.fqn}")
            verifiedResult
        } else {
            println("[CodeHealthAnalyzer] Verification failed for ${result.fqn}")
            result
        }
    }

    private fun buildVerificationPrompt(result: MethodHealthResult): String {
        return """
            Xác minh xem các issues này là VẤN ĐỀ THỰC SỰ hay FALSE POSITIVES. Hãy hoài nghi.
            
            Method: ${result.fqn}
            
            Code:
            ```java
            ${result.codeContext.take(1000)}${if (result.codeContext.length > 1000) "..." else ""}
            ```
            
            Issues cần verify:
            ${result.issues.take(5).mapIndexed { index, issue ->
                "$index. [${issue.issueCategory}] ${issue.title} - ${issue.description}"
            }.joinToString("\n")}
            
            Trả về CHỈ valid JSON:
            {
                "verifications": [
                    {
                        "issueIndex": 0,
                        "verified": true,
                        "verificationReason": "Tại sao thực sự hay false positive"
                    }
                ]
            }
        """.trimIndent()
    }

    private fun parseVerificationResponse(result: MethodHealthResult, llmResponse: String): MethodHealthResult {
        try {
            val jsonObject = extractJsonFromResponse(llmResponse)
                ?: return result
            
            // Parse verifications
            val verificationsArray = jsonObject.getAsJsonArray("verifications")
            val verifiedIssues = result.issues.toMutableList()
            
            verificationsArray?.forEach { element ->
                val verificationObject = element.asJsonObject
                
                val index = verificationObject.get("issueIndex")?.asInt ?: return@forEach
                val verified = verificationObject.get("verified")?.asBoolean ?: true
                val reason = verificationObject.get("verificationReason")?.asString ?: "No reason provided"
                val adjustedSeverity = verificationObject.get("adjustedSeverity")?.asInt
                val adjustedConfidence = verificationObject.get("adjustedConfidence")?.asDouble
                
                if (index in verifiedIssues.indices) {
                    verifiedIssues[index] = verifiedIssues[index].copy(
                        verified = verified,
                        verificationReason = reason,
                        falsePositive = !verified,
                        severity = adjustedSeverity ?: verifiedIssues[index].severity,
                        confidence = adjustedConfidence ?: verifiedIssues[index].confidence
                    )
                }
            }
            
            // Recalculate health score based on verified issues
            val verifiedRealIssues = verifiedIssues.filter { it.verified && !it.falsePositive }
            val newHealthScore = calculateHealthScore(verifiedRealIssues, result.modificationCount, result.impactedCallers.size)
            
            return result.copy(
                issues = verifiedIssues,
                healthScore = newHealthScore
            )
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error parsing verification response: ${e.message}")
            e.printStackTrace()
            return result
        }
    }

    private fun createFallbackResult(
        fqn: String,
        context: String,
        callers: List<String>,
        modificationCount: Int,
        actualModel: String = "local-model-mini"
    ): MethodHealthResult {
        return MethodHealthResult(
            fqn = fqn,
            issues = emptyList(),
            impactedCallers = callers,
            healthScore = 85,
            modificationCount = modificationCount,
            codeContext = context,
            summary = "Unable to perform detailed analysis",
            actualModel = actualModel,
            annotatedCode = context,
            originalCode = context
        )
    }

    private fun getMethodContext(fqn: String): String {
        if (project.isDisposed) return ""
        
        // Use existing context collector for basic context
        val parts = fqn.split(".")
        if (parts.size < 2) return ""
        
        val className = parts.dropLast(1).joinToString(".")
        val methodName = parts.last()
        
        // Try to find the method and get its context
        val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
        
        // Ensure it's a Java class
        if (psiClass?.containingFile?.name?.endsWith(".java") != true) {
            return "// Not a Java file: $fqn"
        }
        
        val basicContext = psiClass?.methods?.find { it.name == methodName }?.let { psiMethod ->
            buildString {
                appendLine("// File: ${psiMethod.containingFile?.virtualFile?.path}")
                appendLine("// Class: ${psiClass.qualifiedName}")
                appendLine()
                append(psiMethod.text)
            }
        } ?: "// Method not found: $fqn"
        
        // Enhance with LangChain retrieval for better context
        return enhanceContextWithRetrieval(fqn, basicContext)
    }
    
    /**
     * Enhance method context using LangChain retrieval to find related code
     */
    private fun enhanceContextWithRetrieval(fqn: String, basicContext: String): String {
        return try {
            // Create a query based on the method name and class
            val parts = fqn.split(".")
            val methodName = parts.last()
            val className = parts.dropLast(1).last()
            
            val query = "method $methodName in class $className related code patterns dependencies"
            
            // Use LangChain retrieval to find related context
            val retrievalResult = langChainService.retrieveContext(query, 3, 0.6).get(5, TimeUnit.SECONDS)
            
            if (retrievalResult.isSuccess && retrievalResult.items.isNotEmpty()) {
                buildString {
                    append(basicContext)
                    appendLine()
                    appendLine("// === Related Code Context (via RAG) ===")
                    retrievalResult.items.forEach { item ->
                        appendLine("// From: ${item.title} (score: ${String.format("%.2f", item.score)})")
                        appendLine("/*")
                        appendLine(item.content.take(300) + if (item.content.length > 300) "..." else "")
                        appendLine("*/")
                        appendLine()
                    }
                }
            } else {
                basicContext
            }
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error enhancing context with retrieval: ${e.message}")
            basicContext
        }
    }

    private fun findCallers(fqn: String): List<String> {
        if (project.isDisposed) return emptyList()
        
        return try {
            val parts = fqn.split(".")
            if (parts.size < 2) return emptyList()
            
            val className = parts.dropLast(1).joinToString(".")
            val methodName = parts.last()
            
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.projectScope(project))
            
            val psiMethod = psiClass?.methods?.find { it.name == methodName }
                ?: return emptyList()
            
            val references = MethodReferencesSearch.search(psiMethod).findAll()
            references.mapNotNull { ref ->
                val element = ref.element
                val containingMethod = PsiTreeUtil.getParentOfType(
                    element, 
                    PsiMethod::class.java
                )
                containingMethod?.let { method ->
                    "${method.containingClass?.qualifiedName}.${method.name}"
                }
            }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Find callers with code snippets showing how the method is called
     */
    private fun findCallersWithSnippets(fqn: String): List<CallerSnippet> {
        if (project.isDisposed) return emptyList()
        
        return try {
            val parts = fqn.split(".")
            if (parts.size < 2) return emptyList()
            
            val className = parts.dropLast(1).joinToString(".")
            val methodName = parts.last()
            
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.projectScope(project))
            
            val psiMethod = psiClass?.methods?.find { it.name == methodName }
                ?: return emptyList()
            
            val references = MethodReferencesSearch.search(psiMethod).findAll()
            references.mapNotNull { ref ->
                val element = ref.element
                val containingMethod = PsiTreeUtil.getParentOfType(
                    element, 
                    PsiMethod::class.java
                )
                
                containingMethod?.let { method ->
                    val callerFqn = "${method.containingClass?.qualifiedName}.${method.name}"
                    val file = method.containingFile?.virtualFile?.path ?: ""
                    
                    // Get the line containing the method call
                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(method.containingFile!!)
                    
                    if (document != null) {
                        val lineNumber = document.getLineNumber(element.textOffset)
                        
                        // Get surrounding lines for context (3 before, 3 after)
                        val startLine = (lineNumber - 3).coerceAtLeast(0)
                        val endLine = (lineNumber + 3).coerceAtMost(document.lineCount - 1)
                        
                        val snippet = buildString {
                            for (i in startLine..endLine) {
                                val lineStart = document.getLineStartOffset(i)
                                val lineEnd = document.getLineEndOffset(i)
                                val lineText = document.text.substring(lineStart, lineEnd)
                                
                                if (i == lineNumber) {
                                    appendLine(">>> $lineText  // <<< METHOD CALL HERE")
                                } else {
                                    appendLine("    $lineText")
                                }
                            }
                        }
                        
                        // Determine calling context
                        val context = analyzeCallingContext(method, element)
                        
                        CallerSnippet(
                            callerFqn = callerFqn,
                            callerFile = file,
                            lineNumber = lineNumber + 1, // 1-based
                            snippet = snippet,
                            context = context
                        )
                    } else {
                        null
                    }
                }
            }.filterNotNull().take(10) // Limit to 10 most relevant callers
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Analyze the context of how a method is being called
     */
    private fun analyzeCallingContext(
        callerMethod: PsiMethod,
        callElement: PsiElement
    ): String {
        // Check if it's in a loop
        val loop = PsiTreeUtil.getParentOfType(
            callElement,
            PsiLoopStatement::class.java
        )
        if (loop != null) {
            return "Called inside a loop - potential performance impact"
        }
        
        // Check if it's in a conditional
        val conditional = PsiTreeUtil.getParentOfType(
            callElement,
            PsiIfStatement::class.java
        )
        if (conditional != null) {
            return "Called conditionally"
        }
        
        // Check if it's in a try-catch
        val tryStatement = PsiTreeUtil.getParentOfType(
            callElement,
            PsiTryStatement::class.java
        )
        if (tryStatement != null) {
            return "Called within error handling block"
        }
        
        // Check if it's in initialization
        val field = PsiTreeUtil.getParentOfType(
            callElement,
            PsiField::class.java
        )
        if (field != null) {
            return "Called during field initialization"
        }
        
        return "Standard method call"
    }

    private fun calculateHealthScore(
        issues: List<HealthIssue>, 
        modificationCount: Int,
        callerCount: Int
    ): Int {
        // Base score starts at 100
        var score = 100
        
        // Deduct points based on issue severity and confidence
        issues.forEach { issue ->
            val deduction = (issue.severity * 5 * issue.confidence).toInt()
            score -= deduction
        }
        
        // Factor in modification frequency (more changes = more risk)
        score -= modificationCount * 2
        
        // Factor in impact (more callers = higher impact)
        if (callerCount > 10) score -= 10
        else if (callerCount > 5) score -= 5
        
        return score.coerceIn(0, 100)
    }

    /**
     * Analyze review units (groups of methods or whole files) using async processing with progress callback
     */
    fun analyzeReviewUnitsAsync(
        reviewUnits: List<ReviewOptimizer.ReviewUnit>,
        optimizer: ReviewOptimizer,
        progressCallback: ((String) -> Unit)? = null
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Starting analysis of ${reviewUnits.size} review units")
        results.clear()
        analyzedJsTsFiles.clear() // Clear the JS/TS file tracking
        val totalUnits = reviewUnits.size
        val completed = AtomicInteger(0)
        val latch = CountDownLatch(totalUnits)
        val cancelled = AtomicBoolean(false)
        
        // Process review units
        reviewUnits.forEach { unit ->
            if (cancelled.get()) {
                println("[CodeHealthAnalyzer] Analysis cancelled")
                latch.countDown()
                return@forEach
            }
            
            val currentCount = completed.get() + 1
            
            // Update progress via callback
            progressCallback?.invoke("Analyzing unit $currentCount of $totalUnits: ${unit.getDescription()}")
            
            println("[CodeHealthAnalyzer] Queuing analysis for unit: ${unit.getDescription()}")
            
            // Analyze unit asynchronously
            analyzeReviewUnitAsync(unit, optimizer) { results ->
                if (cancelled.get()) {
                    latch.countDown()
                    return@analyzeReviewUnitAsync
                }
                
                println("[CodeHealthAnalyzer] Unit analysis complete: ${unit.getDescription()}, ${results.size} results")
                
                // Store results
                results.forEach { result ->
                    this.results[result.fqn] = result
                }
                
                val completedCount = completed.incrementAndGet()
                
                // Update progress after completion
                progressCallback?.invoke("Completed $completedCount of $totalUnits units")
                
                latch.countDown()
            }
        }
        
        // Wait for all analyses to complete
        try {
            val completed = latch.await(10, TimeUnit.MINUTES) // Increased to 10 minutes
            if (!completed) {
                cancelled.set(true)
                println("[CodeHealthAnalyzer] WARNING: Analysis timed out after 10 minutes")
            }
        } catch (e: InterruptedException) {
            cancelled.set(true)
            Thread.currentThread().interrupt()
        }
        
        return results.values.toList()
            .sortedByDescending { it.healthScore * it.modificationCount }
    }
    
    /**
     * Analyze a single review unit asynchronously
     */
    private fun analyzeReviewUnitAsync(
        unit: ReviewOptimizer.ReviewUnit,
        optimizer: ReviewOptimizer,
        onComplete: (List<MethodHealthResult>) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Get review context
                val context = ReadAction.nonBlocking<ReviewOptimizer.ReviewContext> {
                    optimizer.prepareReviewContext(unit)
                }
                .inSmartMode(project)
                .executeSynchronously()
                
                // Analyze based on unit type
                val results = when (unit.type) {
                    ReviewOptimizer.ReviewUnit.ReviewType.WHOLE_FILE -> {
                        analyzeWholeFile(unit, context)
                    }
                    ReviewOptimizer.ReviewUnit.ReviewType.METHOD_GROUP -> {
                        analyzeMethodGroup(unit, context)
                    }
                    ReviewOptimizer.ReviewUnit.ReviewType.JS_TS_REGION -> {
                        analyzeJsTsRegions(unit, context)
                    }
                }
                
                onComplete(results)
                
            } catch (e: Exception) {
                println("[CodeHealthAnalyzer] ERROR analyzing unit ${unit.getDescription()}: ${e.message}")
                e.printStackTrace()
                onComplete(emptyList())
            }
        }
    }
    
    /**
     * Analyze an entire file
     */
    private fun analyzeWholeFile(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing whole file: ${unit.className}")
        
        val prompt = """
            Phân tích Java file này và tìm tối đa 3 issues mỗi method, sắp xếp theo độ nghiêm trọng.
            
            File: ${unit.className}
            Modified methods: ${unit.methods.joinToString(", ")}
            
            ${context.toPromptContext()}
            
            Đối với mỗi method:
            1. Tìm tối đa 3 vấn đề tiềm tàng
            2. Sắp xếp theo tác động thực tế (critical nhất trước)
            3. Chỉ bao gồm issues có thể gây vấn đề thực sự
            4. PHẢI bao gồm originalCode và annotatedCode cho mỗi method
            
            Tập trung vào issues có thể:
            - Gây crashes hoặc mất dữ liệu
            - Tạo lỗ hổng security
            - Leak resources hoặc memory
            - Ảnh hưởng nghiêm trọng đến performance
            
            Trả về CHỈ valid JSON (đặt critical issue đầu tiên cho mỗi method):
            {
                "methods": [
                    {
                        "fqn": "full.class.Name.methodName",
                        "summary": "Đánh giá ngắn",
                        "healthScore": 85,
                        "originalCode": "COPY CHÍNH XÁC toàn bộ method code từ context - không được thay đổi gì",
                        "annotatedCode": "COPY TOÀN BỘ method code từ context NHƯNG thêm inline comments review với prefix // 🔴 CRITICAL:, // 🟠 WARNING:, // 🟡 SUGGESTION:",
                        "issues": [
                            {
                                "category": "Category",
                                "severity": 4,
                                "title": "Tiêu đề issue",
                                "description": "Vấn đề gì",
                                "impact": "Hậu quả thực tế",
                                "suggestedFix": "Cách fix",
                                "confidence": 0.9,
                                "priority": 1
                            }
                        ]
                    }
                ]
            }
            
            Đối với originalCode và annotatedCode:
            - originalCode: Copy chính xác method code từ context
            - annotatedCode: Cùng method code nhưng thêm inline review comments
            - PHẢI bao gồm cả hai fields này cho mỗi method
            
            Bỏ qua methods không có critical issues.
            Bỏ qua style, naming, optimizations nhỏ.
            Sắp xếp issues theo độ nghiêm trọng trong mỗi method.
        """.trimIndent()
        
        return callLLMForFileAnalysis(unit, context, prompt)
    }
    
    /**
     * Analyze a group of methods from the same class
     */
    private fun analyzeMethodGroup(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing method group: ${unit.className} - ${unit.methods.size} methods")
        
        val prompt = """
            Phân tích các Java methods này và tìm tối đa 3 issues mỗi method, sắp xếp theo độ nghiêm trọng.
            
            Class: ${unit.className}
            Methods: ${unit.methods.joinToString(", ")}
            
            ${context.toPromptContext()}
            
            Đối với mỗi method:
            1. Xác định tối đa 3 critical issues
            2. Sắp xếp theo tác động thực tế (critical nhất trước)
            3. Issue đầu tiên phải là CẦN KHUYẾN CÁO NHẤT để fix
            4. PHẢI bao gồm originalCode và annotatedCode cho mỗi method
            
            Issues phải là những thứ có thể:
            - Gây crashes, data corruption, hoặc security breaches
            - Leak resources hoặc tạo vấn đề memory
            - Ảnh hưởng nghiêm trọng đến performance hoặc stability
            
            Trả về CHỈ valid JSON (critical issue đầu tiên mỗi method):
            {
                "methods": [
                    {
                        "fqn": "full.class.Name.methodName",
                        "summary": "Đánh giá ngắn",
                        "healthScore": 85,
                        "originalCode": "COPY CHÍNH XÁC toàn bộ method code từ context - không được thay đổi gì",
                        "annotatedCode": "COPY TOÀN BỘ method code từ context NHƯNG thêm inline comments review với prefix // 🔴 CRITICAL:, // 🟠 WARNING:, // 🟡 SUGGESTION:",
                        "issues": [
                            {
                                "category": "Category",
                                "severity": 4,
                                "title": "Tiêu đề issue",
                                "description": "Vấn đề gì",
                                "impact": "Hậu quả thực tế",
                                "suggestedFix": "Cách fix",
                                "confidence": 0.9,
                                "priority": 1
                            }
                        ]
                    }
                ]
            }
            
            Đối với originalCode và annotatedCode:
            - originalCode: Copy chính xác method code từ context
            - annotatedCode: Cùng method code nhưng thêm inline review comments
            - PHẢI bao gồm cả hai fields này cho mỗi method
            
            Bỏ qua trivial issues. Chỉ report severity 3+ issues.
            Đặt MOST CRITICAL issue đầu tiên trong array.
        """.trimIndent()
        
        return callLLMForFileAnalysis(unit, context, prompt)
    }
    
    /**
     * Call LLM for file/group analysis
     */
    private fun callLLMForFileAnalysis(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        prompt: String
    ): List<MethodHealthResult> {
        val params = LLMService.LLMQueryParams(prompt)
            .useLiteCodeModel()
            .withMaxTokens(4096)
        
        val actualModel = params.getModel()
        println("[CodeHealthAnalyzer] Using model for file analysis: $actualModel")
        
        val response = callLLMWithRetry(
            params = params,
            usage = com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH,
            description = "file analysis for ${unit.getDescription()}"
        )
        
        return if (response != null) {
            parseFileAnalysisResponse(unit, context, response, actualModel)
        } else {
            emptyList()
        }
    }
    
    /**
     * Parse LLM response for file/group analysis
     */
    private fun parseFileAnalysisResponse(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        response: String,
        actualModel: String
    ): List<MethodHealthResult> {
        try {
            val results = mutableListOf<MethodHealthResult>()
            
            val jsonObject = extractJsonFromResponse(response)
                ?: return emptyList()
            
            val methodsArray = jsonObject.getAsJsonArray("methods")
            
            methodsArray?.forEach { element ->
                val methodObject = element.asJsonObject
                
                val fqn = methodObject.get("fqn")?.asString ?: return@forEach
                val summary = methodObject.get("summary")?.asString ?: "Analysis completed"
                val healthScore = methodObject.get("healthScore")?.asInt ?: 85
                
                // Parse issues for this method - AI returns up to 3, we take only the first (most critical)
                val issues = mutableListOf<HealthIssue>()
                val issuesArray = methodObject.getAsJsonArray("issues")
                
                if (issuesArray != null && issuesArray.size() > 0) {
                    // Only process the first issue (most critical) as per product requirements
                    val issueObject = issuesArray.get(0).asJsonObject
                    parseIssue(issueObject, verified = true)?.let { issue ->
                        issues.add(issue.copy(
                            verificationReason = "Verified through ${unit.type} analysis"
                        ))
                    }
                }
                
                // Parse code fields from LLM response
                val originalCodeRaw = methodObject.get("originalCode")?.asString
                val annotatedCodeRaw = methodObject.get("annotatedCode")?.asString
                
                println("[CodeHealthAnalyzer] LLM File Analysis Response Debug for $fqn:")
                println("  originalCode provided: ${originalCodeRaw != null}, length: ${originalCodeRaw?.length ?: 0}")
                println("  annotatedCode provided: ${annotatedCodeRaw != null}, length: ${annotatedCodeRaw?.length ?: 0}")
                
                // Only add result if there are critical issues or it's a healthy method
                if (issues.isNotEmpty() || healthScore >= 80) {
                    // Extract method context from unit if available
                    val methodInfo = context.methods.find { "${unit.className}.${it.name}" == fqn }
                    val methodContext = if (methodInfo != null) {
                        "${methodInfo.signature}\n${methodInfo.body}"
                    } else {
                        context.fileContent ?: ""
                    }
                    
                    val originalCode = originalCodeRaw ?: ""
                    val annotatedCode = annotatedCodeRaw ?: ""
                    
                    results.add(MethodHealthResult(
                        fqn = fqn,
                        issues = issues,
                        impactedCallers = emptyList(), // Could be filled later if needed
                        healthScore = healthScore,
                        modificationCount = 1, // Default
                        codeContext = methodContext,
                        summary = summary,
                        actualModel = actualModel,
                        annotatedCode = annotatedCode,
                        originalCode = originalCode
                    ))
                }
            }
            
            return results
            
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] ERROR parsing file analysis response: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    
    /**
     * Analyze JS/TS regions
     */
    private fun analyzeJsTsRegions(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing JS/TS regions: ${unit.className}")
        println("[CodeHealthAnalyzer] Context has ${context.regionContexts.size} regions")
        
        // Validate that we have regions to analyze
        if (context.regionContexts.isEmpty()) {
            println("[CodeHealthAnalyzer] WARNING: No region contexts found for ${unit.className}")
            return emptyList()
        }
        
        val prompt = """
            Phân tích các JavaScript/TypeScript code regions này để tìm vấn đề tiềm tàng.
            
            File: ${unit.className}
            Language: ${context.language}
            Số regions được phân tích: ${context.regionContexts.size}
            
            ${context.toPromptContext()}
            
            QUAN TRỌNG: Bạn đang xem PARTIAL views của file (±20 dòng xung quanh thay đổi).
            - KHÔNG flag missing imports hoặc undefined variables có thể tồn tại ở nơi khác
            - KHÔNG giả định architectural issues bạn không thể thấy đầy đủ
            - Tập trung vào issues thấy rõ trong các code fragments này
            - THẬN TRọNG - chỉ flag issues bạn tự tin
            - PHẢI bao gồm originalCode và annotatedCode cho mỗi region
            
            Phân tích để tìm:
            - Lỗi syntax rõ ràng hoặc bugs thấy được trong fragments
            - Lỗi logic rõ ràng
            - Vấn đề performance trong code thấy được
            - Mối lo ngại security (eval, innerHTML, etc.)
            - Vấn đề framework-specific nếu có
            
            Trả về CHỈ valid JSON với kết quả cho MỖI region:
            {
                "regions": [
                    {
                        "regionId": "filename.js:lineNumber",
                        "summary": "Đánh giá ngắn",
                        "healthScore": 85,
                        "originalCode": "COPY CHÍNH XÁC toàn bộ code từ region context - không được thay đổi gì",
                        "annotatedCode": "COPY TOÀN BỘ code từ region context NHƯNG thêm inline comments review với prefix // 🔴 CRITICAL:, // 🟠 WARNING:, // 🟡 SUGGESTION:",
                        "issues": [
                            {
                                "category": "Category",
                                "severity": 3,
                                "title": "Tiêu đề issue",
                                "description": "Vấn đề gì trong fragment này",
                                "impact": "Hậu quả",
                                "suggestedFix": "Cách fix",
                                "confidence": 0.9
                            }
                        ]
                    }
                ]
            }
            
            Đối với originalCode và annotatedCode:
            - originalCode: Copy chính xác code từ region context
            - annotatedCode: Cùng code nhưng thêm inline review comments
            - PHẢI bao gồm cả hai fields này cho mỗi region
        """.trimIndent()
        
        return callLLMForJsTsAnalysis(unit, context, prompt)
    }
    
    /**
     * Call LLM for JS/TS analysis
     */
    private fun callLLMForJsTsAnalysis(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        prompt: String
    ): List<MethodHealthResult> {
        val params = LLMService.LLMQueryParams(prompt)
            .useLiteCodeModel()
            .withMaxTokens(4096)
        
        val actualModel = params.getModel()
        println("[CodeHealthAnalyzer] Using model for JS/TS analysis: $actualModel")
        
        val response = callLLMWithRetry(
            params = params,
            usage = com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH,
            description = "JS/TS analysis for ${unit.getDescription()}"
        )
        
        return if (response != null) {
            parseJsTsAnalysisResponse(unit, context, response, actualModel)
        } else {
            emptyList()
        }
    }
    
    /**
     * Parse LLM response for JS/TS analysis
     */
    private fun parseJsTsAnalysisResponse(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        response: String,
        actualModel: String
    ): List<MethodHealthResult> {
        try {
            val results = mutableListOf<MethodHealthResult>()
            
            val jsonObject = extractJsonFromResponse(response)
                ?: return emptyList()
                
            val regionsArray = jsonObject.getAsJsonArray("regions")
            
            regionsArray?.forEach { element ->
                val regionObject = element.asJsonObject
                
                val regionId = regionObject.get("regionId")?.asString ?: return@forEach
                val summary = regionObject.get("summary")?.asString ?: "Analysis completed"
                val healthScore = regionObject.get("healthScore")?.asInt ?: 85
                
                // Parse issues for this region
                val issues = mutableListOf<HealthIssue>()
                val issuesArray = regionObject.getAsJsonArray("issues")
                
                issuesArray?.forEach { issueElement ->
                    val issueObject = issueElement.asJsonObject
                    parseIssue(issueObject, verified = true)?.let { issue ->
                        issues.add(issue.copy(
                            verificationReason = "Verified through region analysis"
                        ))
                    }
                }
                
                // Parse code fields from LLM response
                val originalCodeRaw = regionObject.get("originalCode")?.asString
                val annotatedCodeRaw = regionObject.get("annotatedCode")?.asString
                
                println("[CodeHealthAnalyzer] LLM JS/TS Response Debug for $regionId:")
                println("  originalCode provided: ${originalCodeRaw != null}, length: ${originalCodeRaw?.length ?: 0}")
                println("  annotatedCode provided: ${annotatedCodeRaw != null}, length: ${annotatedCodeRaw?.length ?: 0}")
                
                // Find the region context as fallback
                val regionContext = context.regionContexts.find { it.regionId == regionId }
                val modificationCount = unit.methods.count { it == regionId }
                val regionCode = regionContext?.content ?: ""
                
                val originalCode = originalCodeRaw ?: ""
                val annotatedCode = annotatedCodeRaw ?: ""
                
                results.add(MethodHealthResult(
                    fqn = regionId,
                    issues = issues,
                    impactedCallers = emptyList(), // JS/TS doesn't track callers
                    healthScore = healthScore,
                    modificationCount = modificationCount,
                    codeContext = regionCode,
                    summary = summary,
                    actualModel = actualModel,
                    annotatedCode = annotatedCode,
                    originalCode = originalCode
                ))
            }
            
            return results
            
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] ERROR parsing JS/TS analysis response: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    fun dispose() {
        analysisQueue.shutdown()
        llmRateLimiter.shutdown()
    }
}

/**
 * Simple analysis queue for spreading work over time
 */
class SimpleAnalysisQueue(private val delayMs: Long = 100L) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val taskCount = AtomicInteger(0)
    
    fun submit(task: () -> Unit) {
        val taskId = taskCount.incrementAndGet()
        println("[SimpleAnalysisQueue] Submitting task #$taskId")
        
        scheduler.execute {
            try {
                println("[SimpleAnalysisQueue] Executing task #$taskId")
                task()
                println("[SimpleAnalysisQueue] Completed task #$taskId")
                
                // Add delay before next task
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (e: Exception) {
                println("[SimpleAnalysisQueue] ERROR in task #$taskId: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    fun shutdown() {
        println("[SimpleAnalysisQueue] Shutting down queue")
        scheduler.shutdown()
    }
}

/**
 * Rate limiter for controlling LLM calls with circuit breaker pattern
 */
class RateLimiter(private val delayMs: Long) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val semaphore = Semaphore(1)
    private val recentErrors = AtomicInteger(0)
    private val circuitBreakerThreshold = 5
    private var circuitOpenUntil = AtomicLong(0)
    
    fun acquire() {
        // Check circuit breaker
        val now = System.currentTimeMillis()
        if (circuitOpenUntil.get() > now) {
            val waitTime = circuitOpenUntil.get() - now
            println("[RateLimiter] Circuit breaker open. Waiting ${waitTime}ms")
            Thread.sleep(waitTime)
        }
        
        semaphore.acquire()
        scheduler.schedule({
            semaphore.release()
        }, delayMs, TimeUnit.MILLISECONDS)
    }
    
    fun recordError() {
        val errors = recentErrors.incrementAndGet()
        if (errors >= circuitBreakerThreshold) {
            // Open circuit breaker for 30 seconds
            circuitOpenUntil.set(System.currentTimeMillis() + 30000)
            println("[RateLimiter] Circuit breaker opened due to $errors errors")
            recentErrors.set(0)
        }
    }
    
    fun recordSuccess() {
        recentErrors.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }
    
    fun shutdown() {
        scheduler.shutdown()
    }
}
