package com.zps.zest.codehealth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.langchain4j.ZestLangChain4jService
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class CodeHealthAnalyzer(private val project: Project) {

    companion object {
        private const val LLM_DELAY_MS = 500_000L
        const val MAX_METHODS_PER_ANALYSIS = 20
        private const val MAX_LLM_RETRIES = 1
        private const val LLM_TIMEOUT_MS = 300_000L

        // Feature flags
        var SKIP_VERIFICATION = true

        fun getInstance(project: Project): CodeHealthAnalyzer =
            project.getService(CodeHealthAnalyzer::class.java)

        private val CODE_EXTENSIONS = setOf(
            "java", "kt", "js", "ts", "jsx", "tsx", "py", "cpp", "c", "h",
            "cs", "go", "rb", "php", "swift", "rs", "scala"
        )

        // Common prompt components
        private val CATEGORIES = "Resource|Security|Concurrency|Logic|Performance|API-Usage|Error-Handling|Other"
        
        private val COMMON_RULES = """
            - KHÔNG được echo lại code trong JSON.
            - Dùng line số 1-based tương ứng với method body bạn thấy trong context.
            - Mỗi diagnostic có range (relativeTo="method") và ưu tiên có anchors.before/after (cụm ngắn).
            - Chỉ report issues severity ≥ 3, tập trung vào lỗi thực sự (crash, security, leak, performance nghiêm trọng).
            - Tối đa 1 issue per method - chỉ report vấn đề NGHIÊM TRỌNG NHẤT.
            - Tối đa 5 issues per file - ưu tiên top 5 critical nhất.
        """.trimIndent()
        
        private val DIAGNOSTIC_SCHEMA = """
            {
              "category": "$CATEGORIES",  // REQUIRED: Exactly one of: Resource|Security|Concurrency|Logic|Performance|API-Usage|Error-Handling|Other
              "severity": 3,  // REQUIRED: Integer 3-5 only (3=warning, 4=major, 5=critical/blocker)
              "title": "Brief issue name",  // REQUIRED: Max 50 chars, describe WHAT is wrong (e.g. "Null pointer risk", "SQL injection vulnerability")
              "message": "Clear description",  // REQUIRED: Max 200 chars, explain the specific problem found in THIS code
              "impact": "Potential consequences",  // REQUIRED: Max 150 chars, what bad things can happen if not fixed
              "suggestedFix": "Actionable solution",  // REQUIRED: Max 200 chars, specific steps to resolve the issue
              "confidence": 0.85,  // REQUIRED: Float 0.7-1.0 (0.7=possible, 0.85=likely, 1.0=certain)
              "range": {  // REQUIRED: Exact location of issue
                "relativeTo": "method",  // Always "method" for method-level analysis
                "start": {"line": 10, "col": 15},  // Line/col where issue starts (1-based)
                "end": {"line": 10, "col": 45}  // Line/col where issue ends (1-based)
              },
              "anchors": {  // OPTIONAL but recommended: Code fragments near the issue for precise location
                "before": "code_before",  // Max 40 chars: Unique code snippet BEFORE the issue (e.g. "if (obj == null)")
                "after": "code_after"  // Max 40 chars: Unique code snippet AFTER the issue (e.g. "obj.method()")
              }
            }
        """.trimIndent()

        // Detection prompt with line numbering (no echo)
        private val PROMPT_DETECTION_LINENUM_TEMPLATE = """
            Phân tích TỪNG method được cung cấp dưới đây. Với MỖI method, tìm TỐI ĐA 1 issue nghiêm trọng nhất (severity ≥ 3).
            QUAN TRỌNG: Phân tích TẤT CẢ methods được yêu cầu, không giới hạn số lượng methods.
            Method: {{FQN}}
            CODE (đã đánh số dòng):
            {{NUMBERED_CONTEXT}}
            QUY TẮC:
            - KHÔNG được trả lại code. KHÔNG echo nội dung code.
            - Chỉ trả về CHÍNH XÁC JSON theo schema bên dưới.
            - Line/column dùng chỉ số 1-based, tính theo block code đã đánh số dòng.
            - Với mỗi issue, luôn có range (relativeTo="method"), và nên kèm anchors.before/anchors.after là 1-2 cụm ngắn (<= 40 ký tự) mô tả bối cảnh gần vị trí lỗi.
            - Tối đa 1 issue per method - chỉ report vấn đề NGHIÊM TRỌNG NHẤT.
            - Bỏ qua style/naming/formatting/optimizations nhỏ.
            TRẢ VỀ CHỈ JSON:
            {
              "summary": "1 câu ngắn",
              "healthScore": 85,
              "diagnostics": [
                $DIAGNOSTIC_SCHEMA
              ]
            }
        """.trimIndent()

        // Verification prompt (kept simple; still takes truncated code)
        private val PROMPT_VERIFICATION_TEMPLATE = """
            Xác minh xem các issues này là VẤN ĐỀ THỰC SỰ hay FALSE POSITIVES. Hãy hoài nghi.
            Method: {{FQN}}
            Code:
            ```java
            {{CODE}}
            ```
            Issues cần verify:
            {{ISSUES}}
            Trả về CHỈ valid JSON:
            {
                "verifications": [
                    {
                        "issueIndex": 0,
                        "verified": true,
                        "verificationReason": "Tại sao thực sự hay false positive",
                        "adjustedSeverity": 4,
                        "adjustedConfidence": 0.9
                    }
                ]
            }
        """.trimIndent()

        // Whole-file prompt -> diagnostics per method (no code echo)
        private val PROMPT_WHOLE_FILE_TEMPLATE = """
            Phân tích TẤT CẢ methods trong Java file này. Với mỗi method, tìm tối đa 1 issue nghiêm trọng nhất. Tổng cộng tối đa 5 issues cho toàn file.
            QUAN TRỌNG: Phân tích TOÀN BỘ danh sách methods được yêu cầu, không bỏ qua bất kỳ method nào.
            File: {{CLASS}}
            Modified methods: {{METHODS}}
            CODE:
            {{CONTEXT}}
            QUY TẮC:
            $COMMON_RULES
            JSON TRẢ VỀ:
            {
              "methods": [
                {
                  "fqn": "full.class.Name.methodName",
                  "summary": "ngắn gọn",
                  "healthScore": 85,
                  "diagnostics": [
                    $DIAGNOSTIC_SCHEMA
                  ]
                }
              ]
            }
        """.trimIndent()

        // Partial-file prompt -> diagnostics per method in chunk
        private val PROMPT_PARTIAL_FILE_TEMPLATE = """
            Phân tích phần này của Java file, chỉ review methods HOÀN CHỈNH hiển thị trong chunk.
            File: {{CLASS}}
            Chunk: Lines {{START}}-{{END}}
            Modified methods in full file: {{METHODS}}
            CODE CHUNK:
            {{CONTEXT}}
            QUY TẮC:
            - KHÔNG echo code.
            - Chỉ report diagnostics cho methods hoàn chỉnh trong chunk.
            $COMMON_RULES
            JSON TRẢ VỀ:
            {
              "methods": [
                {
                  "fqn": "full.class.Name.methodName",
                  "summary": "ngắn gọn",
                  "healthScore": 85,
                  "diagnostics": [
                    $DIAGNOSTIC_SCHEMA
                  ]
                }
              ]
            }
        """.trimIndent()

        // Method-group prompt -> diagnostics per method
        private val PROMPT_METHOD_GROUP_TEMPLATE = """
            Phân tích TẤT CẢ Java methods trong danh sách. Với mỗi method, tìm tối đa 1 diagnostic nghiêm trọng nhất.
            QUAN TRỌNG: Phân tích TOÀN BỘ danh sách methods được yêu cầu, không bỏ qua method nào.
            Class: {{CLASS}}
            Methods: {{METHODS}}
            CODE:
            {{CONTEXT}}
            QUY TẮC:
            $COMMON_RULES
            JSON TRẢ VỀ:
            {
              "methods": [
                {
                  "fqn": "full.class.Name.methodName",
                  "summary": "ngắn gọn",
                  "healthScore": 85,
                  "diagnostics": [
                    $DIAGNOSTIC_SCHEMA
                  ]
                }
              ]
            }
        """.trimIndent()

        // JS/TS prompt -> diagnostics per region
        private val PROMPT_JS_TS_TEMPLATE = """
            Phân tích các JavaScript/TypeScript code regions này để tìm vấn đề tiềm tàng.
            File: {{CLASS}}
            Language: {{LANG}}
            Số regions được phân tích: {{COUNT}}
            CODE REGIONS:
            {{CONTEXT}}
            QUAN TRỌNG: bạn đang xem PARTIAL views (±20 dòng).
            QUY TẮC:
            - KHÔNG flag missing imports hoặc undefined variables có thể tồn tại ở nơi khác.
            - Tập trung vào các vấn đề thấy rõ trong fragment.
            - KHÔNG echo lại code trong JSON.
            - Trả về diagnostics với range relativeTo="region" (1-based theo block region bạn thấy).
            - Severity ≥ 3, tối đa 1 issue per region - chỉ report vấn đề NGHIÊM TRỌNG NHẤT.
            JSON TRẢ VỀ:
            {
              "regions": [
                {
                  "regionId": "filename.js:lineNumber",
                  "summary": "ngắn",
                  "healthScore": 85,
                  "diagnostics": [
                    {
                      "category": "$CATEGORIES",
                      "severity": 3,
                      "title": "Tiêu đề",
                      "message": "Mô tả",
                      "impact": "Hậu quả",
                      "suggestedFix": "Cách sửa",
                      "confidence": 0.9,
                      "range": { "relativeTo": "region", "start": {"line": 5,"col":1}, "end": {"line": 5,"col": 120} },
                      "anchors": { "before": "cụm-ngắn", "after": "cụm-ngắn" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private val naiveLlmService: NaiveLLMService = project.service()
    private val langChainService: ZestLangChain4jService = project.service()
    private val jsonHelper = JsonParsingHelper()

     private val results = ConcurrentHashMap<String, MethodHealthResult>()
    private val gson = Gson()
    private val llmRateLimiter = RateLimiter(LLM_DELAY_MS)

    private val jsTsAnalyzer = JsTsHealthAnalyzer(project)
    private val analyzedJsTsFiles = ConcurrentHashMap.newKeySet<String>()

    // Positioning for diagnostics
    data class Position(val line: Int, val col: Int)
    data class Range(val relativeTo: String = "method", val start: Position, val end: Position)
    data class Anchors(val before: String? = null, val after: String? = null)

    data class HealthIssue(
        val issueCategory: String,
        val severity: Int,
        val title: String,
        val description: String,
        val impact: String,
        val suggestedFix: String,
        val lineNumbers: List<Int> = emptyList(),
        val confidence: Double = 1.0,
        val verified: Boolean = false,
        val verificationReason: String? = null,
        var falsePositive: Boolean = false,
        val codeSnippet: String? = null,
        val callerSnippets: List<CallerSnippet> = emptyList(),
        val range: Range? = null,
        val anchors: Anchors? = null
    )

    data class CallerSnippet(
        val callerFqn: String,
        val callerFile: String,
        val lineNumber: Int,
        val snippet: String,
        val context: String
    )

    data class MethodHealthResult(
        val fqn: String,
        val issues: List<HealthIssue>,
        val impactedCallers: List<String>,
        val healthScore: Int,
        val modificationCount: Int,
        val codeContext: String = "",
        val summary: String = "",
        val actualModel: String = "local-model-mini",
        val annotatedCode: String,
        val originalCode: String
    )

    fun analyzeFiles(filePaths: List<String>): List<MethodHealthResult> {
        val output = mutableListOf<MethodHealthResult>()
        ReadAction.run<RuntimeException> { filePaths.forEach { analyzePath(it, output) } }
        return output
    }

    private fun analyzePath(filePath: String, out: MutableList<MethodHealthResult>) {
        if (!isCodeFile(filePath)) return
        val psiFile = toPsiFile(filePath) ?: return
        if (filePath.endsWith(".java")) analyzeJavaFile(psiFile, filePath, out)
        else analyzeJsTsFile(filePath, out)
    }

    private fun isCodeFile(filePath: String): Boolean {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        if (!CODE_EXTENSIONS.contains(ext)) {
            println("[CodeHealthAnalyzer] Skipping non-code file: $filePath")
            return false
        }
        return true
    }

    private fun toPsiFile(filePath: String): PsiFile? {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
        val psi = vf?.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it) }
        if (psi == null) println("[CodeHealthAnalyzer] File not found: $filePath")
        return psi
    }

    private fun analyzeJavaFile(psiFile: PsiFile, filePath: String, out: MutableList<MethodHealthResult>) {
        val methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
        println("[CodeHealthAnalyzer] Found ${methods.size} methods in $filePath")
        methods.forEach { addMethodAnalysisResult(it, out) }
    }

    private fun addMethodAnalysisResult(method: PsiMethod, out: MutableList<MethodHealthResult>) {
        val fqn = method.containingClass?.qualifiedName + "." + method.name
        val mod = ProjectChangesTracker.ModifiedMethod(fqn, 1, System.currentTimeMillis())
        val result = analyzeMethodSync(mod)
        if (result.issues.isNotEmpty()) out.add(result)
    }

    private fun analyzeJsTsFile(filePath: String, out: MutableList<MethodHealthResult>) {
        val regionFqn = "$filePath:1"
        val mod = ProjectChangesTracker.ModifiedMethod(regionFqn, 1, System.currentTimeMillis())
        val result = analyzeJsTsRegionSync(mod)
        if (result.issues.isNotEmpty()) out.add(result)
    }

    private fun analyzeMethodSync(method: ProjectChangesTracker.ModifiedMethod): MethodHealthResult {
        val future = CompletableFuture<MethodHealthResult>()
        return try {
            analyzeMethodAsync(method) { future.complete(it) }
            future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error analyzing method ${method.fqn}: ${e.message}")
            errorResult(method, "Error analyzing method: ${e.message}")
        }
    }

    private fun analyzeJsTsRegionSync(method: ProjectChangesTracker.ModifiedMethod): MethodHealthResult {
        val future = CompletableFuture<MethodHealthResult>()
        return try {
            analyzeJsTsRegionAsync(method) { future.complete(it) }
            future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error analyzing JS/TS region ${method.fqn}: ${e.message}")
            errorResult(method, "Error analyzing region: ${e.message}")
        }
    }

    private fun errorResult(method: ProjectChangesTracker.ModifiedMethod, summary: String) = MethodHealthResult(
        fqn = method.fqn,
        issues = emptyList(),
        impactedCallers = emptyList(),
        healthScore = 100,
        modificationCount = method.modificationCount,
        codeContext = "",
        summary = summary,
        actualModel = "local-model-mini",
        annotatedCode = "",
        originalCode = ""
    )

    fun analyzeAllMethodsAsync(
        methods: List<ProjectChangesTracker.ModifiedMethod>,
        indicator: ProgressIndicator? = null
    ): List<MethodHealthResult> {
        val limited = methods.take(MAX_METHODS_PER_ANALYSIS)
        if (limited.size < methods.size) println("[CodeHealthAnalyzer] Limited from ${methods.size} to ${limited.size}")
        println("[CodeHealthAnalyzer] Starting analysis of ${limited.size} methods")
        resetState()

        val latch = CountDownLatch(limited.size)
        val completed = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)

        limited.forEachIndexed { index, method ->
            if (cancelled.get() || indicator?.isCanceled == true) return@forEachIndexed latch.countDown()
            updateProgressQueued(indicator, index + 1, limited.size, method)
            analyzeMethodAsync(method) { onMethodComplete(it, latch, completed, limited.size, indicator, cancelled) }
        }

        waitForAll(latch, limited.size)
        return sortedResults()
    }

    private fun resetState() {
        results.clear()
        analyzedJsTsFiles.clear()
    }

    private fun updateProgressQueued(
        indicator: ProgressIndicator?, current: Int, total: Int, method: ProjectChangesTracker.ModifiedMethod
    ) {
        indicator?.fraction = current.toDouble() / total
        indicator?.text = "Analyzing method $current of $total: ${method.fqn}"
        indicator?.text2 = "Modified ${method.modificationCount} times"
        println("[CodeHealthAnalyzer] Queuing analysis for $current/$total: ${method.fqn}")
    }

    private fun onMethodComplete(
        result: MethodHealthResult,
        latch: CountDownLatch,
        completed: AtomicInteger,
        total: Int,
        indicator: ProgressIndicator?,
        cancelled: AtomicBoolean
    ) {
        val issueCount = result.issues.size
        val verifiedCount = result.issues.count { it.verified && !it.falsePositive }
        println("[CodeHealthAnalyzer] Complete: ${result.fqn} -> $issueCount issues, $verifiedCount verified")

        // ALWAYS store result, even if empty or partial
        results[result.fqn] = ensureResultHasData(result)

        val done = completed.incrementAndGet()
        indicator?.fraction = done.toDouble() / total
        indicator?.text = "Completed $done of $total methods"
        if (indicator?.isCanceled == true) cancelled.set(true)
        latch.countDown()

        println("[CodeHealthAnalyzer] Progress: $done/$total completed, ${latch.count} remaining")
    }

    private fun ensureResultHasData(result: MethodHealthResult): MethodHealthResult {
        // If completely empty, add a synthetic issue to ensure visibility
        if (result.issues.isEmpty() && result.summary.isBlank()) {
            val syntheticIssue = HealthIssue(
                issueCategory = "Other",
                severity = 1,
                title = "Analysis pending",
                description = "Method tracked but not yet fully analyzed",
                impact = "Manual review recommended",
                suggestedFix = "Re-run analysis or review manually",
                confidence = 0.3,
                verified = false
            )
            return result.copy(
                issues = listOf(syntheticIssue),
                summary = "Analysis incomplete"
            )
        }
        return result
    }

    private fun waitForAll(latch: CountDownLatch, total: Int) {
        println("[CodeHealthAnalyzer] Waiting for all analyses to complete...")
        val finished = latch.await(10, TimeUnit.MINUTES)
        if (finished) return
        println("[CodeHealthAnalyzer] WARNING: Timeout after 10 minutes. Latch: ${latch.count}")
        notifyPartialResults(total)
    }

    private fun notifyPartialResults(total: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val count = results.size
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Health")
                .createNotification(
                    "⏱️ Zest Guardian: Partial Results",
                    "⚡ Got $count of $total results. Still valuable insights!",
                    NotificationType.WARNING
                )
                .notify(project)
        }
    }

    private fun sortedResults(): List<MethodHealthResult> =
        results.values.toList().sortedByDescending { it.healthScore * it.modificationCount }

    private fun analyzeMethodAsync(
        method: ProjectChangesTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        if (method.fqn.isBlank()) return onComplete(createFallbackResult("", "", emptyList(), method.modificationCount))
        if (isJsTsRegion(method)) return analyzeJsTsRegionAsync(method, onComplete)
        if (results.containsKey(method.fqn)) return onComplete(results[method.fqn]!!)
        ApplicationManager.getApplication().executeOnPooledThread { runMethodAnalysis(method, onComplete) }
    }

    private fun isJsTsRegion(method: ProjectChangesTracker.ModifiedMethod) =
        method.fqn.contains(".js:") || method.fqn.contains(".ts:")

    private fun runMethodAnalysis(
        method: ProjectChangesTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        try {
            val context = fetchContext(method.fqn)
            if (context.isBlank() || context.contains("Method not found")) {
                return onComplete(errorResult(method, "Method not found or empty context"))
            }

            val callers = fetchCallers(method.fqn)
            val callerSnippets = fetchCallerSnippets(method.fqn).take(5)

            val detection = detectIssuesWithLLM(method, context, callers, callerSnippets)
            val final = finalizeDetection(detection)
            onComplete(final)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            println("[CodeHealthAnalyzer] ERROR analyzing ${method.fqn}: ${e.message}")
            e.printStackTrace()
            onComplete(errorResult(method, "Unexpected error: ${e.message}"))
        }
    }

    private fun fetchContext(fqn: String): String =
        ReadAction.nonBlocking<String> { getMethodContext(fqn) }.inSmartMode(project).executeSynchronously()

    private fun fetchCallers(fqn: String): List<String> =
        ReadAction.nonBlocking<List<String>> { findCallers(fqn) }.inSmartMode(project).executeSynchronously()

    private fun fetchCallerSnippets(fqn: String): List<CallerSnippet> =
        ReadAction.nonBlocking<List<CallerSnippet>> { findCallersWithSnippets(fqn) }.inSmartMode(project).executeSynchronously()

    private fun finalizeDetection(detectionResult: MethodHealthResult): MethodHealthResult {
        if (detectionResult.issues.isEmpty() || !SKIP_VERIFICATION) return detectionResultOrVerified(detectionResult)
        println("[CodeHealthAnalyzer] Skipping verification (SKIP_VERIFICATION=true)")
        return detectionResult.copy(issues = detectionResult.issues.map {
            it.copy(verified = true, verificationReason = "Verification skipped")
        })
    }

    private fun detectionResultOrVerified(detectionResult: MethodHealthResult): MethodHealthResult {
        if (detectionResult.issues.isEmpty()) return detectionResult
        println("[CodeHealthAnalyzer] Step 5: Verifying issues for ${detectionResult.fqn}")
        return verifyIssuesWithAgent(detectionResult)
    }

    private fun analyzeJsTsRegionAsync(
        method: ProjectChangesTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        if (results.containsKey(method.fqn)) return onComplete(results[method.fqn]!!)
        val region = toModifiedRegion(method) ?: return onComplete(
            createFallbackResult(method.fqn, "", emptyList(), method.modificationCount)
        )

        if (!markJsTsFile(region.filePath)) return onComplete(
            createFallbackResult(method.fqn, "", emptyList(), method.modificationCount)
        )

        jsTsAnalyzer.analyzeRegion(region) { result ->
            results[method.fqn] = result
            onComplete(result)
        }
    }

    private fun toModifiedRegion(method: ProjectChangesTracker.ModifiedMethod): ModifiedRegion? {
        val idx = method.fqn.lastIndexOf(":")
        if (idx <= 0) {
            println("[CodeHealthAnalyzer] Invalid JS/TS region format: ${method.fqn}")
            return null
        }
        val filePath = method.fqn.substring(0, idx)
        val centerLine = method.fqn.substring(idx + 1).toIntOrNull() ?: 0
        val language = if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) "ts" else "js"
        return ModifiedRegion(
            filePath = filePath,
            centerLine = centerLine,
            startLine = (centerLine - 20).coerceAtLeast(0),
            endLine = centerLine + 20,
            language = language,
            framework = null,
            modificationCount = method.modificationCount,
            lastModified = method.lastModified
        )
    }

    private fun markJsTsFile(filePath: String): Boolean {
        if (analyzedJsTsFiles.contains(filePath)) {
            println("[CodeHealthAnalyzer] File $filePath already analyzed, skipping")
            return false
        }
        analyzedJsTsFiles.add(filePath)
        return true
    }

    private fun callLLMWithRetry(
        params: NaiveLLMService.LLMQueryParams,
        usage: com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage,
        description: String
    ): String? {
        var last: Exception? = null
        for (attempt in 1..MAX_LLM_RETRIES) {
            val response = tryLLMCall(params, usage, description, attempt)
            if (response != null) return response
            last = Exception("LLM returned null response")
            if (attempt < MAX_LLM_RETRIES) backoff(attempt)
        }
        println("[CodeHealthAnalyzer] All LLM attempts failed for $description: ${last?.message}")
        return null
    }

    private fun tryLLMCall(
        params: NaiveLLMService.LLMQueryParams,
        usage: com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage,
        description: String,
        attempt: Int
    ): String? {
        return try {
            println("[CodeHealthAnalyzer] LLM attempt $attempt for $description")
            llmRateLimiter.acquire()
            val future = CompletableFuture.supplyAsync { naiveLlmService.queryWithParams(params, usage) }
            val response = getWithTimeout(future)
            if (response == null) llmRateLimiter.recordError() else llmRateLimiter.recordSuccess()
            response
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] LLM attempt $attempt failed for $description: ${e.message}")
            llmRateLimiter.recordError()
            null
        }
    }

    private fun getWithTimeout(future: CompletableFuture<String?>): String? {
        return try {
            future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw Exception("LLM timeout after ${LLM_TIMEOUT_MS}ms")
        }
    }

    private fun backoff(attempt: Int) {
        val backoffMs = (1000L * Math.pow(2.0, attempt.toDouble())).toLong()
        println("[CodeHealthAnalyzer] Waiting ${backoffMs}ms before retry...")
        Thread.sleep(backoffMs)
    }

    private fun detectIssuesWithLLM(
        method: ProjectChangesTracker.ModifiedMethod,
        context: String,
        callers: List<String>,
        callerSnippets: List<CallerSnippet>
    ): MethodHealthResult {
        val prompt = buildDetectionPrompt(method.fqn, context)
        val params = NaiveLLMService.LLMQueryParams(prompt).useLiteCodeModel().withMaxTokens(4096).withTemperature(0.3)
        val model = params.getModel()
        val resp = callLLMWithRetry(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH, "detection for ${method.fqn}")
        return if (resp != null) {
            parseDetectionResponse(method.fqn, context, callers, callerSnippets, method.modificationCount, resp, model)
        } else {
            MethodHealthResult(
                fqn = method.fqn,
                issues = emptyList(),
                impactedCallers = callers,
                healthScore = 85,
                modificationCount = method.modificationCount,
                codeContext = context,
                summary = "Analysis failed",
                actualModel = model,
                annotatedCode = "",
                originalCode = ""
            )
        }
    }

    private fun withLineNumbers(code: String): String =
        code.lineSequence().mapIndexed { idx, line -> "L${idx + 1}| $line" }.joinToString("\n")

    private fun buildDetectionPrompt(fqn: String, context: String): String {
        val numbered = withLineNumbers(context)
        return PROMPT_DETECTION_LINENUM_TEMPLATE
            .replace("{{FQN}}", fqn)
            .replace("{{NUMBERED_CONTEXT}}", numbered)
    }

    private fun parseDiagnostic(obj: JsonObject): HealthIssue? {
        val severity = obj.optInt("severity", 3)
        if (severity < 3) return null
        val category = obj.optString("category", "Unknown")
        val title = obj.optString("title", "Unknown Issue")
        val message = obj.optString("message", "")
        val impact = obj.optString("impact", "")
        val suggestedFix = obj.optString("suggestedFix", "")
        val confidence = obj.optDouble("confidence", 0.8)
        val snippet = obj.optStringOrNull("snippet")

        val rangeObj = obj.getAsJsonObject("range")
        val range = if (rangeObj != null) {
            val rel = rangeObj.optString("relativeTo", "method")
            val startObj = rangeObj.getAsJsonObject("start")
            val endObj = rangeObj.getAsJsonObject("end")
            val start = Position(startObj.optInt("line", 1), startObj.optInt("col", 1))
            val end = Position(endObj.optInt("line", start.line), endObj.optInt("col", 999))
            Range(rel, start, end)
        } else null

        val anchorsObj = obj.getAsJsonObject("anchors")
        val anchors = if (anchorsObj != null) {
            Anchors(
                anchorsObj.optStringOrNull("before"),
                anchorsObj.optStringOrNull("after")
            )
        } else null

        return HealthIssue(
            issueCategory = category,
            severity = severity,
            title = title,
            description = message,
            impact = impact,
            suggestedFix = suggestedFix,
            confidence = confidence,
            codeSnippet = snippet,
            callerSnippets = emptyList(),
            range = range,
            anchors = anchors
        )
    }

    private fun extractJsonFromResponse(response: String): JsonObject? {
        val json = jsonHelper.extractJson(response)
        if (json == null) {
            showMalformedJsonDialog(response, "Could not extract valid JSON")
        }
        return json
    }

    private fun showMalformedJsonDialog(raw: String, reason: String) {
        // Non-blocking notification only
        println("[CodeHealthAnalyzer] Malformed JSON: $reason")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Health")
            .createNotification(
                "Partial Analysis",
                "Some results may be incomplete due to parsing issues",
                NotificationType.WARNING
            )
            .notify(project)
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
        val (json, fallbackIssues) = tryParseResponse(llmResponse, fqn, callerSnippets)
        val issues = extractIssuesFromJson(json, callerSnippets, fallbackIssues)
        val summary = json?.optString("summary", "Analysis completed") ?: "Partial analysis"
        val healthScore = json?.optInt("healthScore", 85) ?: calculateHealthFromIssues(issues)

        return buildHealthResult(
            fqn, context, callers, issues,
            healthScore, modificationCount, summary, actualModel
        )
    }

    private fun tryParseResponse(
        response: String,
        fqn: String,
        callerSnippets: List<CallerSnippet>
    ): Pair<JsonObject?, List<HealthIssue>> {
        val json = jsonHelper.extractJson(response)
        if (json != null) return Pair(json, emptyList())

        // Try to extract individual diagnostics
        val diagnostics = jsonHelper.extractDiagnostics(response)
        if (diagnostics.isNotEmpty()) {
            val issues = diagnostics.mapNotNull { parseDiagnostic(it) }
                .map { it.copy(callerSnippets = callerSnippets) }
            return Pair(null, issues)
        }

        // Create fallback diagnostic
        val fallback = parseDiagnostic(jsonHelper.createFallbackDiagnostic(response, fqn))
        return Pair(null, listOfNotNull(fallback))
    }

    private fun extractIssuesFromJson(
        json: JsonObject?,
        callerSnippets: List<CallerSnippet>,
        fallback: List<HealthIssue>
    ): List<HealthIssue> {
        if (json == null) return fallback
        val issues = mutableListOf<HealthIssue>()
        if (json.has("diagnostics")) {
            json.getAsJsonArray("diagnostics").forEach { el ->
                parseDiagnostic(el.asJsonObject)?.let {
                    issues.add(it.copy(callerSnippets = callerSnippets))
                }
            }
        }
        return if (issues.isNotEmpty()) issues else fallback
    }

    private fun buildHealthResult(
        fqn: String,
        context: String,
        callers: List<String>,
        issues: List<HealthIssue>,
        healthScore: Int,
        modificationCount: Int,
        summary: String,
        actualModel: String
    ): MethodHealthResult {
        val annotated = buildAnnotatedFromDiagnostics(context, issues)
        logCodeFieldDebug(fqn, context, annotated)
        return MethodHealthResult(
            fqn = fqn,
            issues = issues,
            impactedCallers = callers,
            healthScore = healthScore,
            modificationCount = modificationCount,
            codeContext = context,
            summary = summary,
            actualModel = actualModel,
            annotatedCode = annotated,
            originalCode = context
        )
    }

    private fun calculateHealthFromIssues(issues: List<HealthIssue>): Int {
        var score = 100
        issues.forEach { score -= (it.severity * 5) }
        return score.coerceIn(0, 100)
    }

    private fun logCodeFieldDebug(fqn: String, original: String, annotated: String) {
        println("[CodeHealthAnalyzer] LLM Response Debug for $fqn:")
        println("  originalCode provided locally: ${original.isNotEmpty()}, length: ${original.length}")
        println("  annotatedCode generated locally: ${annotated.isNotEmpty()}, length: ${annotated.length}")
    }

    private fun verifyIssuesWithAgent(result: MethodHealthResult): MethodHealthResult {
        println("[CodeHealthAnalyzer] Verifying ${result.issues.size} issues for ${result.fqn}")
        val prompt = buildVerificationPrompt(result)
        val params = NaiveLLMService.LLMQueryParams(prompt).useLiteCodeModel().withMaxTokens(2048).withTemperature(0.1)
        val resp = callLLMWithRetry(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH, "verification for ${result.fqn}")
        return if (resp != null) applyVerificationResponse(result, resp) else result
    }

    private fun buildVerificationPrompt(result: MethodHealthResult): String {
        val issuesText = result.issues.take(5).mapIndexed { i, issue ->
            "$i. [${issue.issueCategory}] ${issue.title} - ${issue.description}"
        }.joinToString("\n")
        val code = result.codeContext.take(1000) + if (result.codeContext.length > 1000) "..." else ""
        return PROMPT_VERIFICATION_TEMPLATE
            .replace("{{FQN}}", result.fqn)
            .replace("{{CODE}}", code)
            .replace("{{ISSUES}}", issuesText)
    }

    private fun applyVerificationResponse(result: MethodHealthResult, llmResponse: String): MethodHealthResult {
        val json = extractJsonFromResponse(llmResponse) ?: return result
        val verifiedIssues = result.issues.toMutableList()
        val arr = json.getAsJsonArray("verifications") ?: return result
        arr.forEach { applyVerificationObject(it.asJsonObject, verifiedIssues) }
        val realIssues = verifiedIssues.filter { it.verified && !it.falsePositive }
        val newScore = calculateHealthScore(realIssues, result.modificationCount, result.impactedCallers.size)
        println("[CodeHealthAnalyzer] Verified ${realIssues.size}/${verifiedIssues.size} issues as real for ${result.fqn}")
        return result.copy(issues = verifiedIssues, healthScore = newScore)
    }

    private fun applyVerificationObject(obj: JsonObject, issues: MutableList<HealthIssue>) {
        val index = obj.optInt("issueIndex", -1)
        if (index !in issues.indices) return
        val verified = obj.optBoolean("verified", true)
        val reason = obj.optString("verificationReason", "No reason provided")
        val adjustedSeverity = if (obj.has("adjustedSeverity")) obj.optInt("adjustedSeverity", issues[index].severity) else null
        val adjustedConfidence = if (obj.has("adjustedConfidence")) obj.optDouble("adjustedConfidence", issues[index].confidence) else null
        val current = issues[index]
        issues[index] = current.copy(
            verified = verified,
            verificationReason = reason,
            falsePositive = !verified,
            severity = adjustedSeverity ?: current.severity,
            confidence = adjustedConfidence ?: current.confidence
        )
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
            annotatedCode = "",
            originalCode = ""
        )
    }

    private fun getMethodContext(fqn: String): String {
        if (project.isDisposed) return ""
        val parts = fqn.split(".")
        if (parts.size < 2) return ""
        val cls = parts.dropLast(1).joinToString(".")
        val methodName = parts.last()
        val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(cls, GlobalSearchScope.projectScope(project))
        if (psiClass?.containingFile?.name?.endsWith(".java") != true) return "// Not a Java file: $fqn"
        val basic = psiClass.methods.find { it.name == methodName }?.let { basicContext(it, psiClass) } ?: "// Method not found: $fqn"
        return enhanceContextWithRetrieval(fqn, basic)
    }

    private fun basicContext(psiMethod: PsiMethod, psiClass: PsiClass): String {
        return buildString {
            appendLine("// File: ${psiMethod.containingFile?.virtualFile?.path}")
            appendLine("// Class: ${psiClass.qualifiedName}")
            appendLine()
            append(psiMethod.text)
        }
    }

    private fun enhanceContextWithRetrieval(fqn: String, basicContext: String): String {
        return try {
            val parts = fqn.split(".")
            val methodName = parts.last()
            val className = parts.dropLast(1).last()
            val query = "method $methodName in class $className related code patterns dependencies"
            val retrieval = langChainService.retrieveContext(query, 3, 0.6).get(5, TimeUnit.SECONDS)
            if (!retrieval.isSuccess || retrieval.items.isEmpty()) return basicContext
            buildString {
                append(basicContext)
                appendLine()
                appendLine("// === Related Code Context (via RAG) ===")
                retrieval.items.forEach { addRagItem(it.title, it.score, it.content) }
            }
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] Error enhancing context with retrieval: ${e.message}")
            basicContext
        }
    }

    private fun StringBuilder.addRagItem(title: String, score: Double, content: String) {
        appendLine("// From: $title (score: ${String.format("%.2f", score)})")
        appendLine("/*")
        appendLine(content.take(300) + if (content.length > 300) "..." else "")
        appendLine("*/")
        appendLine()
    }

    private fun findCallers(fqn: String): List<String> {
        if (project.isDisposed) return emptyList()
        return try {
            val parts = fqn.split(".")
            if (parts.size < 2) return emptyList()
            val className = parts.dropLast(1).joinToString(".")
            val methodName = parts.last()
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.projectScope(project))
            val psiMethod = psiClass?.methods?.find { it.name == methodName } ?: return emptyList()
            MethodReferencesSearch.search(psiMethod).findAll().mapNotNull { referenceToFqn(it.element) }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun referenceToFqn(element: PsiElement): String? {
        val m = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        return "${m.containingClass?.qualifiedName}.${m.name}"
    }

    private fun findCallersWithSnippets(fqn: String): List<CallerSnippet> {
        if (project.isDisposed) return emptyList()
        return try {
            val target = resolvePsiMethod(fqn) ?: return emptyList()
            MethodReferencesSearch.search(target).findAll().mapNotNull { ref ->
                val element = ref.element
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@mapNotNull null
                buildCallerSnippet(method, element)
            }.filterNotNull().take(10)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolvePsiMethod(fqn: String): PsiMethod? {
        val parts = fqn.split(".")
        if (parts.size < 2) return null
        val className = parts.dropLast(1).joinToString(".")
        val methodName = parts.last()
        val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.projectScope(project))
        return psiClass?.methods?.find { it.name == methodName }
    }

    private fun buildCallerSnippet(method: PsiMethod, callElement: PsiElement): CallerSnippet? {
        val file = method.containingFile?.virtualFile?.path ?: ""
        val document = PsiDocumentManager.getInstance(project).getDocument(method.containingFile!!) ?: return null
        val lineNumber = document.getLineNumber(callElement.textOffset)
        val snippet = extractSnippet(document, lineNumber)
        val context = analyzeCallingContext(method, callElement)
        return CallerSnippet(
            callerFqn = "${method.containingClass?.qualifiedName}.${method.name}",
            callerFile = file,
            lineNumber = lineNumber + 1,
            snippet = snippet,
            context = context
        )
    }

    private fun extractSnippet(document: com.intellij.openapi.editor.Document, lineNumber: Int): String {
        val start = (lineNumber - 3).coerceAtLeast(0)
        val end = (lineNumber + 3).coerceAtMost(document.lineCount - 1)
        val builder = StringBuilder()
        for (i in start..end) {
            val line = document.text.substring(document.getLineStartOffset(i), document.getLineEndOffset(i))
            builder.appendLine(if (i == lineNumber) ">>> $line  // <<< METHOD CALL HERE" else "    $line")
        }
        return builder.toString()
    }

    private fun analyzeCallingContext(
        callerMethod: PsiMethod,
        callElement: PsiElement
    ): String {
        if (PsiTreeUtil.getParentOfType(callElement, PsiLoopStatement::class.java) != null)
            return "Called inside a loop - potential performance impact"
        if (PsiTreeUtil.getParentOfType(callElement, PsiIfStatement::class.java) != null)
            return "Called conditionally"
        if (PsiTreeUtil.getParentOfType(callElement, PsiTryStatement::class.java) != null)
            return "Called within error handling block"
        if (PsiTreeUtil.getParentOfType(callElement, PsiField::class.java) != null)
            return "Called during field initialization"
        return "Standard method call"
    }

    private fun calculateHealthScore(
        issues: List<HealthIssue>,
        modificationCount: Int,
        callerCount: Int
    ): Int {
        var score = 100
        issues.forEach { score -= (it.severity * 5 * it.confidence).toInt() }
        score -= (modificationCount * 2)
        score -= when {
            callerCount > 10 -> 10
            callerCount > 5 -> 5
            else -> 0
        }
        return score.coerceIn(0, 100)
    }

    fun analyzeReviewUnitsAsync(
        reviewUnits: List<ReviewOptimizer.ReviewUnit>,
        optimizer: ReviewOptimizer,
        progressCallback: ((String) -> Unit)? = null
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Starting analysis of ${reviewUnits.size} review units")
        resetState()

        val latch = CountDownLatch(reviewUnits.size)
        val completed = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)

        reviewUnits.forEachIndexed { idx, unit ->
            if (cancelled.get()) return@forEachIndexed latch.countDown()
            progressCallback?.invoke("Analyzing unit ${idx + 1} of ${reviewUnits.size}: ${unit.getDescription()}")
            analyzeReviewUnitAsync(unit, optimizer) { onUnitComplete(unit, it, latch, completed, reviewUnits.size, progressCallback) }
        }

        waitUnits(latch)
        return sortedResults()
    }

    private fun onUnitComplete(
        unit: ReviewOptimizer.ReviewUnit,
        unitResults: List<MethodHealthResult>,
        latch: CountDownLatch,
        completed: AtomicInteger,
        total: Int,
        progressCallback: ((String) -> Unit)?
    ) {
        println("[CodeHealthAnalyzer] Unit analysis complete: ${unit.getDescription()}, ${unitResults.size} results")
        unitResults.forEach { results[it.fqn] = it }
        val done = completed.incrementAndGet()
        progressCallback?.invoke("Completed $done of $total units")
        latch.countDown()
    }

    private fun waitUnits(latch: CountDownLatch) {
        try {
            val completed = latch.await(10, TimeUnit.MINUTES)
            if (!completed) println("[CodeHealthAnalyzer] WARNING: Analysis timed out after 10 minutes")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun analyzeReviewUnitAsync(
        unit: ReviewOptimizer.ReviewUnit,
        optimizer: ReviewOptimizer,
        onComplete: (List<MethodHealthResult>) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val ctx = optimizer.prepareReviewContext(unit)
                onComplete(analyzeUnit(unit, ctx))
            } catch (e: Exception) {
                println("[CodeHealthAnalyzer] ERROR analyzing unit ${unit.getDescription()}: ${e.message}")
                e.printStackTrace()
                onComplete(emptyList())
            }
        }
    }

    private fun analyzeUnit(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        return when (unit.type) {
            ReviewOptimizer.ReviewUnit.ReviewType.WHOLE_FILE -> analyzeWholeFile(unit, context)
            ReviewOptimizer.ReviewUnit.ReviewType.PARTIAL_FILE -> analyzePartialFile(unit, context)
            ReviewOptimizer.ReviewUnit.ReviewType.METHOD_GROUP -> analyzeMethodGroup(unit, context)
            ReviewOptimizer.ReviewUnit.ReviewType.JS_TS_REGION -> analyzeJsTsRegions(unit, context)
        }
    }

    private fun analyzeWholeFile(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing whole file: ${unit.className}")
        val prompt = PROMPT_WHOLE_FILE_TEMPLATE
            .replace("{{CLASS}}", unit.className)
            .replace("{{METHODS}}", unit.methods.joinToString(", "))
            .replace("{{CONTEXT}}", context.toPromptContext())
        return callLLMForFileAnalysis(unit, context, prompt)
    }

    private fun analyzePartialFile(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing partial file: ${unit.className} (lines ${context.startLine}-${context.endLine})")
        val prompt = PROMPT_PARTIAL_FILE_TEMPLATE
            .replace("{{CLASS}}", unit.className)
            .replace("{{START}}", context.startLine.toString())
            .replace("{{END}}", context.endLine.toString())
            .replace("{{METHODS}}", unit.methods.joinToString(", "))
            .replace("{{CONTEXT}}", context.toPromptContext())
        return callLLMForFileAnalysis(unit, context, prompt)
    }

    private fun analyzeMethodGroup(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing method group: ${unit.className} - ${unit.methods.size} methods")
        val prompt = PROMPT_METHOD_GROUP_TEMPLATE
            .replace("{{CLASS}}", unit.className)
            .replace("{{METHODS}}", unit.methods.joinToString(", "))
            .replace("{{CONTEXT}}", context.toPromptContext())
        return callLLMForFileAnalysis(unit, context, prompt)
    }

    private fun callLLMForFileAnalysis(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        prompt: String
    ): List<MethodHealthResult> {
        val params = NaiveLLMService.LLMQueryParams(prompt).useLiteCodeModel().withMaxTokens(4096)
        val model = params.getModel()
        println("[CodeHealthAnalyzer] Using model for file analysis: $model")
        val response = callLLMWithRetry(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH, "file analysis for ${unit.getDescription()}")
        return if (response != null) parseFileAnalysisResponse(unit, context, response, model) else emptyList()
    }

    private fun parseFileAnalysisResponse(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        response: String,
        actualModel: String
    ): List<MethodHealthResult> {
        val json = extractJsonFromResponse(response) ?: return emptyList()
        val methodsArray = json.getAsJsonArray("methods") ?: return emptyList()
        val out = mutableListOf<MethodHealthResult>()
        methodsArray.forEach { element ->
            parseMethodAnalysisObject(unit, context, element.asJsonObject, actualModel)?.let { out.add(it) }
        }
        return out
    }

    private fun parseMethodAnalysisObject(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        obj: JsonObject,
        actualModel: String
    ): MethodHealthResult? {
        val fqn = obj.optString("fqn", "")
        val summary = obj.optString("summary", "Analysis completed")
        val healthScore = obj.optInt("healthScore", 85)

        val issues = mutableListOf<HealthIssue>()
        if (obj.has("diagnostics")) {
            obj.getAsJsonArray("diagnostics").forEach { el ->
                parseDiagnostic(el.asJsonObject)?.let {
                    issues.add(it.copy(verified = true, verificationReason = "Verified through ${unit.type} analysis"))
                }
            }
        }

        val methodCtx = extractMethodContextFromUnit(unit, context, fqn)
        if (methodCtx.isBlank()) return null
        val annotated = buildAnnotatedFromDiagnostics(methodCtx, issues)
        logCodeFieldDebug(fqn, methodCtx, annotated)
        if (issues.isEmpty() && healthScore < 80) return null

        return MethodHealthResult(
            fqn = fqn,
            issues = issues,
            impactedCallers = emptyList(),
            healthScore = healthScore,
            modificationCount = 1,
            codeContext = methodCtx,
            summary = summary,
            actualModel = actualModel,
            annotatedCode = annotated,
            originalCode = methodCtx
        )
    }

    private fun extractMethodContextFromUnit(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        fqn: String
    ): String {
        val info = context.methods.find { "${unit.className}.${it.name}" == fqn }
        return if (info != null) "${info.signature}\n${info.body}" else context.fileContent ?: ""
    }

    private fun analyzeJsTsRegions(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext
    ): List<MethodHealthResult> {
        println("[CodeHealthAnalyzer] Analyzing JS/TS regions: ${unit.className}")
        println("[CodeHealthAnalyzer] Context has ${context.regionContexts.size} regions")
        if (context.regionContexts.isEmpty()) return emptyList()
        val prompt = PROMPT_JS_TS_TEMPLATE
            .replace("{{CLASS}}", unit.className)
            .replace("{{LANG}}", context.language ?: "js")
            .replace("{{COUNT}}", context.regionContexts.size.toString())
            .replace("{{CONTEXT}}", context.toPromptContext())
        return callLLMForJsTsAnalysis(unit, context, prompt)
    }

    private fun callLLMForJsTsAnalysis(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        prompt: String
    ): List<MethodHealthResult> {
        val params = NaiveLLMService.LLMQueryParams(prompt).useLiteCodeModel().withMaxTokens(4096)
        val model = params.getModel()
        println("[CodeHealthAnalyzer] Using model for JS/TS analysis: $model")
        val response = callLLMWithRetry(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH, "JS/TS analysis for ${unit.getDescription()}")
        return if (response != null) parseJsTsAnalysisResponse(unit, context, response, model) else emptyList()
    }

    private fun parseJsTsAnalysisResponse(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        response: String,
        actualModel: String
    ): List<MethodHealthResult> {
        val json = extractJsonFromResponse(response) ?: return emptyList()
        val regionsArray = json.getAsJsonArray("regions") ?: return emptyList()
        val out = mutableListOf<MethodHealthResult>()
        regionsArray.forEach { element ->
            parseRegionObject(unit, context, element.asJsonObject, actualModel)?.let { out.add(it) }
        }
        return out
    }

    private fun parseRegionObject(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        obj: JsonObject,
        actualModel: String
    ): MethodHealthResult? {
        val regionId = obj.optString("regionId", "")
        val summary = obj.optString("summary", "Analysis completed")
        val healthScore = obj.optInt("healthScore", 85)

        val issues = mutableListOf<HealthIssue>()
        if (obj.has("diagnostics")) {
            obj.getAsJsonArray("diagnostics").forEach { el ->
                parseDiagnostic(el.asJsonObject)?.let {
                    issues.add(it.copy(verified = true, verificationReason = "Verified through region analysis"))
                }
            }
        }

        val regionCtx = context.regionContexts.find { it.regionId == regionId }
        val regionCode = regionCtx?.content ?: ""
        val modificationCount = unit.methods.count { it == regionId }
        val annotated = buildAnnotatedFromDiagnostics(regionCode, issues)
        logCodeFieldDebug(regionId, regionCode, annotated)

        return MethodHealthResult(
            fqn = regionId,
            issues = issues,
            impactedCallers = emptyList(),
            healthScore = healthScore,
            modificationCount = modificationCount,
            codeContext = regionCode,
            summary = summary,
            actualModel = actualModel,
            annotatedCode = annotated,
            originalCode = regionCode
        )
    }

    private fun buildAnnotatedFromDiagnostics(code: String, issues: List<HealthIssue>): String {
        val lines = code.lines().toMutableList()

        fun sevEmoji(sev: Int) = when {
            sev >= 4 -> "🔴 CRITICAL"
            sev >= 3 -> "🟠 WARNING"
            else -> "🟡 SUGGESTION"
        }

        fun findLineByAnchors(anchors: Anchors?): Int? {
            if (anchors == null) return null
            val before = anchors.before?.takeIf { it.isNotBlank() }
            val after = anchors.after?.takeIf { it.isNotBlank() }
            if (before != null) {
                val idx = lines.indexOfFirst { it.contains(before) }
                if (idx >= 0) return idx + 1
            }
            if (after != null) {
                val idx = lines.indexOfFirst { it.contains(after) }
                if (idx >= 0) return (idx + 1).coerceAtLeast(1)
            }
            return null
        }

        issues.forEach { issue ->
            val targetLine = when {
                issue.range?.relativeTo != null &&
                        issue.range.relativeTo.lowercase() in setOf("method", "region") &&
                        issue.range.start.line in 1..lines.size -> issue.range.start.line
                else -> findLineByAnchors(issue.anchors)
            } ?: 1

            val i = (targetLine - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            val summary = if (issue.description.isNotBlank()) issue.description else issue.impact
            val tailComment = " // ${sevEmoji(issue.severity)}: ${issue.title}" + (if (summary.isNotBlank()) " - $summary" else "")
            if (lines.isEmpty()) lines.add("")
            if (!lines[i].contains(tailComment)) {
                lines[i] = lines[i] + tailComment
            }
        }

        return lines.joinToString("\n")
    }

    fun dispose() {
        llmRateLimiter.shutdown()
    }

    // === Helpers: safe JSON accessors to avoid NPEs (fixes getAsBoolean null) ===
    private fun JsonObject.optString(key: String, default: String): String =
        if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else default

    private fun JsonObject.optStringOrNull(key: String): String? =
        if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else null

    private fun JsonObject.optInt(key: String, default: Int): Int =
        if (this.has(key) && !this.get(key).isJsonNull) runCatching { this.get(key).asInt }.getOrElse { default } else default

    private fun JsonObject.optDouble(key: String, default: Double): Double =
        if (this.has(key) && !this.get(key).isJsonNull) runCatching { this.get(key).asDouble }.getOrElse { default } else default

    private fun JsonObject.optBoolean(key: String, default: Boolean): Boolean =
        if (this.has(key) && !this.get(key).isJsonNull) runCatching { this.get(key).asBoolean }.getOrElse { default } else default
}

class RateLimiter(private val delayMs: Long) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val semaphore = Semaphore(1)
    private val recentErrors = AtomicInteger(0)
    private val circuitBreakerThreshold = 5
    private var circuitOpenUntil = AtomicLong(0)

    fun acquire() {
        val now = System.currentTimeMillis()
        if (circuitOpenUntil.get() > now) {
            val waitTime = circuitOpenUntil.get() - now
            println("[RateLimiter] Circuit breaker open. Waiting ${waitTime}ms")
            Thread.sleep(waitTime)
        }
        semaphore.acquire()
        scheduler.schedule({ semaphore.release() }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun recordError() {
        val errors = recentErrors.incrementAndGet()
        if (errors >= circuitBreakerThreshold) {
            circuitOpenUntil.set(System.currentTimeMillis() + 30000)
            println("[RateLimiter] Circuit breaker opened due to $errors errors")
            recentErrors.set(0)
        }
    }

    fun recordSuccess() {
        recentErrors.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun shutdown() {
        scheduler.shutdown()
    }
}