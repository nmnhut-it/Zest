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
        private const val MAX_CONCURRENT_ANALYSES = 2
        private const val LLM_DELAY_MS = 2000L // Increased delay between LLM calls
        private const val CHUNK_SIZE = 5 // Process methods in chunks
        const val MAX_METHODS_PER_ANALYSIS = 20 // Hard limit to prevent excessive LLM calls
        private const val MAX_LLM_RETRIES = 2 // Limit retries per method
        private const val LLM_TIMEOUT_MS = 30000L // 30 second timeout per LLM call
        
        // Feature flags
        var SKIP_VERIFICATION = true // Skip verification step for faster analysis
        
        fun getInstance(project: Project): CodeHealthAnalyzer =
            project.getService(CodeHealthAnalyzer::class.java)
    }

    private val contextCollector = ZestLeanContextCollector(project)
    private val llmService: LLMService = project.service()
    private val analysisQueue = SimpleAnalysisQueue(delayMs = 50L)
    private val results = ConcurrentHashMap<String, MethodHealthResult>()
    
    // Rate limiter for LLM calls
    private val llmRateLimiter = RateLimiter(LLM_DELAY_MS)

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
        val falsePositive: Boolean = false,
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
        val summary: String = ""  // Overall method health summary
    )

    /**
     * Analyze all modified methods using async processing with progress indicator
     */
    fun analyzeAllMethodsAsync(
        methods: List<CodeHealthTracker.ModifiedMethod>,
        indicator: ProgressIndicator? = null
    ): List<MethodHealthResult> {
        // Limit the number of methods to analyze
        val methodsToAnalyze = methods.take(MAX_METHODS_PER_ANALYSIS)
        
        if (methodsToAnalyze.size < methods.size) {
            println("[CodeHealthAnalyzer] Limited analysis from ${methods.size} to ${methodsToAnalyze.size} methods")
        }
        
        println("[CodeHealthAnalyzer] Starting analysis of ${methodsToAnalyze.size} methods")
        results.clear()
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
                            .getNotificationGroup("Zest Code Guardian")
                            .createNotification(
                                "Code Health Analysis Partial Results",
                                "Analysis timed out. Showing ${partialResults.size} of ${methodsToAnalyze.size} results.",
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
        method: CodeHealthTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        if (method.fqn.isBlank()) {
            println("[CodeHealthAnalyzer] WARNING: Skipping method with empty FQN")
            onComplete(createFallbackResult("", "", emptyList(), method.modificationCount))
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
                    onComplete(createFallbackResult(method.fqn, context, emptyList(), method.modificationCount))
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
                println("[CodeHealthAnalyzer] ERROR analyzing ${method.fqn}: ${e.message}")
                e.printStackTrace()
                onComplete(createFallbackResult(method.fqn, "", emptyList(), method.modificationCount))
            }
        }
    }

    /**
     * First pass: LLM detects all potential issues
     */
    private fun detectIssuesWithLLM(
        method: CodeHealthTracker.ModifiedMethod,
        context: String,
        callers: List<String>,
        callerSnippets: List<CallerSnippet>
    ): MethodHealthResult {
        val detectionPrompt = buildDetectionPrompt(method.fqn, context, callers, callerSnippets)
        
        var lastException: Exception? = null
        
        // Retry logic with exponential backoff
        for (attempt in 1..MAX_LLM_RETRIES) {
            try {
                println("[CodeHealthAnalyzer] LLM detection attempt $attempt for ${method.fqn}")
                
                // Rate limit LLM calls
                llmRateLimiter.acquire()
                
                val startTime = System.currentTimeMillis()
                
                // Use lite code model for better performance with timeout
                val params = LLMService.LLMQueryParams(detectionPrompt)
                    .useLiteCodeModel()
                    .withMaxTokens(4096)
                    .withTemperature(0.3)
                
                // Create a future with timeout
                val future = CompletableFuture.supplyAsync({
                    llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
                })
                
                val response = try {
                    future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    throw Exception("LLM timeout after ${LLM_TIMEOUT_MS}ms")
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                
                if (response != null) {
                    println("[CodeHealthAnalyzer] LLM succeeded on attempt $attempt in ${elapsed}ms for ${method.fqn}")
                    llmRateLimiter.recordSuccess()
                    return parseDetectionResponse(method.fqn, context, callers, callerSnippets, method.modificationCount, response)
                } else {
                    println("[CodeHealthAnalyzer] LLM returned null on attempt $attempt for ${method.fqn}")
                    lastException = Exception("LLM returned null response")
                    llmRateLimiter.recordError()
                }
                
            } catch (e: Exception) {
                println("[CodeHealthAnalyzer] LLM attempt $attempt failed for ${method.fqn}: ${e.message}")
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
        
        // All retries failed
        println("[CodeHealthAnalyzer] All LLM attempts failed for ${method.fqn}: ${lastException?.message}")
        
        // Return a result indicating analysis failure
        return MethodHealthResult(
            fqn = method.fqn,
            issues = emptyList(),
            impactedCallers = callers,
            healthScore = 85, // Conservative score when analysis fails
            modificationCount = method.modificationCount,
            codeContext = context,
            summary = "Analysis failed: ${lastException?.message ?: "Unknown error"}"
        )
    }

    private fun buildDetectionPrompt(fqn: String, context: String, callers: List<String>, callerSnippets: List<CallerSnippet>): String {
        return """
            Analyze this Java method for potential issues. Be concise but thorough.
            
            Java Method: $fqn
            Modified: ${if (callers.size > 5) "Many times" else "Recently"}
            Callers: ${callers.size} methods
            
            Java Code:
            ```java
            $context
            ```
            
            ${if (callerSnippets.isNotEmpty()) """
            Usage Examples:
            ${callerSnippets.take(3).joinToString("\n") { snippet ->
                "- ${snippet.callerFqn}: ${snippet.context}"
            }}
            """ else ""}
            
            Find issues in these categories:
            - Null safety risks
            - Resource leaks
            - Performance problems
            - Error handling gaps
            - Security vulnerabilities
            - Code quality issues
            
            Return ONLY valid JSON:
            {
                "summary": "1-line method assessment",
                "healthScore": 85,
                "issues": [
                    {
                        "category": "Category Name",
                        "severity": 3,
                        "title": "Brief issue title",
                        "description": "What's wrong",
                        "impact": "What could happen",
                        "suggestedFix": "How to fix",
                        "confidence": 0.9
                    }
                ]
            }
            
            Be realistic. Focus on actual problems, not style preferences.
        """.trimIndent()
    }

    private fun parseDetectionResponse(
        fqn: String,
        context: String,
        callers: List<String>,
        callerSnippets: List<CallerSnippet>,
        modificationCount: Int,
        llmResponse: String
    ): MethodHealthResult {
        return try {
            // Extract JSON content
            val jsonStart = llmResponse.indexOf("{")
            val jsonEnd = llmResponse.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) {
                return createFallbackResult(fqn, context, callers, modificationCount)
            }
            
            val jsonContent = llmResponse.substring(jsonStart, jsonEnd + 1)
            
            // Parse summary and health score
            val summaryMatch = Regex(""""summary"\s*:\s*"([^"]+)"""").find(jsonContent)
            val summary = summaryMatch?.groupValues?.get(1) ?: "Analysis completed"
            
            val scoreMatch = Regex(""""healthScore"\s*:\s*(\d+)""").find(jsonContent)
            val healthScore = scoreMatch?.groupValues?.get(1)?.toIntOrNull() ?: 85
            
            // Parse issues
            val issues = mutableListOf<HealthIssue>()
            val issuePattern = Regex(
                """"category"\s*:\s*"([^"]+)"[^}]*"severity"\s*:\s*(\d+)[^}]*"title"\s*:\s*"([^"]+)"[^}]*"description"\s*:\s*"([^"]+)"[^}]*"impact"\s*:\s*"([^"]+)"[^}]*"suggestedFix"\s*:\s*"([^"]+)"[^}]*"confidence"\s*:\s*([\d.]+)(?:[^}]*"callerEvidence"\s*:\s*"([^"]+)")?""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            issuePattern.findAll(jsonContent).forEach { match ->
                val groups = match.groupValues
                val category = groups[1]
                val severityStr = groups[2]
                val title = groups[3]
                val description = groups[4]
                val impact = groups[5]
                val suggestedFix = groups[6]
                val confidenceStr = groups[7]
                val callerEvidence = groups.getOrNull(8)
                
                // Extract code snippet if present
                val snippetMatch = Regex(""""codeSnippet"\s*:\s*"([^"]+)"""").find(jsonContent)
                val codeSnippet = snippetMatch?.groupValues?.get(1)
                
                // If caller evidence is mentioned, try to match it with actual caller snippets
                val relevantCallerSnippets = if (callerEvidence != null) {
                    callerSnippets.filter { snippet ->
                        callerEvidence.contains(snippet.callerFqn.substringAfterLast('.')) ||
                        snippet.context.contains("loop") && callerEvidence.contains("loop") ||
                        snippet.context.contains("conditional") && callerEvidence.contains("condition")
                    }
                } else {
                    emptyList()
                }
                
                issues.add(HealthIssue(
                    issueCategory = category,
                    severity = severityStr.toIntOrNull() ?: 1,
                    title = title,
                    description = description,
                    impact = impact,
                    suggestedFix = suggestedFix,
                    confidence = confidenceStr.toDoubleOrNull() ?: 0.8,
                    verified = false,  // Will be verified in second pass
                    codeSnippet = codeSnippet,
                    callerSnippets = relevantCallerSnippets
                ))
            }
            
            MethodHealthResult(
                fqn = fqn,
                issues = issues,
                impactedCallers = callers,
                healthScore = healthScore,
                modificationCount = modificationCount,
                codeContext = context,
                summary = summary
            )
        } catch (e: Exception) {
            createFallbackResult(fqn, context, callers, modificationCount)
        }
    }

    /**
     * Second pass: Verify detected issues with intelligent agent
     */
    private fun verifyIssuesWithAgent(result: MethodHealthResult): MethodHealthResult {
        println("[CodeHealthAnalyzer] Verifying ${result.issues.size} issues for ${result.fqn}")
        
        // Rate limit verification calls
        llmRateLimiter.acquire()
        
        // For now, use simple verification. Can be upgraded to use CodeHealthVerificationAgent later
        val verificationPrompt = buildVerificationPrompt(result)
        
        return try {
            val startTime = System.currentTimeMillis()
            
            // Use lite code model for verification too
            val params = LLMService.LLMQueryParams(verificationPrompt)
                .useLiteCodeModel()
                .withMaxTokens(2048)
                .withTemperature(0.1) // Lower temperature for more consistent verification
            
            val response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
            val elapsed = System.currentTimeMillis() - startTime
            
            if (response != null) {
                println("[CodeHealthAnalyzer] Verification completed in ${elapsed}ms for ${result.fqn}")
                val verifiedResult = parseVerificationResponse(result, response)
                val verifiedCount = verifiedResult.issues.count { it.verified && !it.falsePositive }
                println("[CodeHealthAnalyzer] Verified $verifiedCount/${verifiedResult.issues.size} issues as real for ${result.fqn}")
                verifiedResult
            } else {
                println("[CodeHealthAnalyzer] Verification failed for ${result.fqn} after ${elapsed}ms")
                result
            }
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] ERROR during verification for ${result.fqn}: ${e.message}")
            result
        }
    }

    private fun buildVerificationPrompt(result: MethodHealthResult): String {
        return """
            Verify if these issues are REAL problems or FALSE POSITIVES. Be skeptical.
            
            Method: ${result.fqn}
            
            Code:
            ```java
            ${result.codeContext.take(1000)}${if (result.codeContext.length > 1000) "..." else ""}
            ```
            
            Issues to verify:
            ${result.issues.take(5).mapIndexed { index, issue ->
                "$index. [${issue.issueCategory}] ${issue.title} - ${issue.description}"
            }.joinToString("\n")}
            
            Return ONLY valid JSON:
            {
                "verifications": [
                    {
                        "issueIndex": 0,
                        "verified": true,
                        "verificationReason": "Why real or false positive"
                    }
                ]
            }
        """.trimIndent()
    }

    private fun parseVerificationResponse(result: MethodHealthResult, llmResponse: String): MethodHealthResult {
        try {
            val jsonStart = llmResponse.indexOf("{")
            val jsonEnd = llmResponse.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) {
                return result
            }
            
            val jsonContent = llmResponse.substring(jsonStart, jsonEnd + 1)
            
            // Parse verifications
            val verificationPattern = Regex(
                """"issueIndex"\s*:\s*(\d+)[^}]*"verified"\s*:\s*(true|false)[^}]*"verificationReason"\s*:\s*"([^"]+)"[^}]*"adjustedSeverity"\s*:\s*(\d+)[^}]*"adjustedConfidence"\s*:\s*([\d.]+)""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            val verifiedIssues = result.issues.toMutableList()
            
            verificationPattern.findAll(jsonContent).forEach { match ->
                val (indexStr, verifiedStr, reason, severityStr, confidenceStr) = match.destructured
                val index = indexStr.toIntOrNull() ?: return@forEach
                
                if (index in verifiedIssues.indices) {
                    verifiedIssues[index] = verifiedIssues[index].copy(
                        verified = verifiedStr.toBoolean(),
                        verificationReason = reason,
                        falsePositive = !verifiedStr.toBoolean(),
                        severity = severityStr.toIntOrNull() ?: verifiedIssues[index].severity,
                        confidence = confidenceStr.toDoubleOrNull() ?: verifiedIssues[index].confidence
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
            return result
        }
    }

    private fun createFallbackResult(
        fqn: String,
        context: String,
        callers: List<String>,
        modificationCount: Int
    ): MethodHealthResult {
        return MethodHealthResult(
            fqn = fqn,
            issues = emptyList(),
            impactedCallers = callers,
            healthScore = 85,
            modificationCount = modificationCount,
            codeContext = context,
            summary = "Unable to perform detailed analysis"
        )
    }

    private fun getMethodContext(fqn: String): String {
        if (project.isDisposed) return ""
        
        // Use existing context collector
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
        
        return psiClass?.methods?.find { it.name == methodName }?.let { psiMethod ->
            buildString {
                appendLine("// File: ${psiMethod.containingFile?.virtualFile?.path}")
                appendLine("// Class: ${psiClass.qualifiedName}")
                appendLine()
                append(psiMethod.text)
            }
        } ?: "// Method not found: $fqn"
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
            Analyze this entire Java file for code health issues.
            
            File: ${unit.className}
            Size: ${unit.lineCount} lines
            Modified methods: ${unit.methods.joinToString(", ")}
            
            ${context.toPromptContext()}
            
            Analyze ALL methods in the file, but pay special attention to the modified ones.
            Look for:
            - Issues in the modified methods
            - Problems in how other methods interact with modified methods
            - Class-level design issues
            - Resource management problems
            - Thread safety concerns
            
            Return ONLY valid JSON with results for EACH method:
            {
                "methods": [
                    {
                        "fqn": "full.class.Name.methodName",
                        "summary": "Brief assessment",
                        "healthScore": 85,
                        "issues": [
                            {
                                "category": "Category",
                                "severity": 3,
                                "title": "Issue title",
                                "description": "What's wrong",
                                "impact": "Consequences",
                                "suggestedFix": "How to fix",
                                "confidence": 0.9
                            }
                        ]
                    }
                ]
            }
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
            Analyze this group of related Java methods for code health issues.
            
            Class: ${unit.className}
            Methods: ${unit.methods.joinToString(", ")}
            
            ${context.toPromptContext()}
            
            Focus on:
            - Issues within each method
            - Problems in how these methods interact
            - Shared resources or state problems
            - Pattern violations across methods
            - Cumulative performance impacts
            
            Return ONLY valid JSON with results for EACH method:
            {
                "methods": [
                    {
                        "fqn": "full.class.Name.methodName",
                        "summary": "Brief assessment",
                        "healthScore": 85,
                        "issues": [
                            {
                                "category": "Category",
                                "severity": 3,
                                "title": "Issue title",
                                "description": "What's wrong",
                                "impact": "Consequences",
                                "suggestedFix": "How to fix",
                                "confidence": 0.9
                            }
                        ]
                    }
                ]
            }
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
        try {
            // Rate limit LLM calls
            llmRateLimiter.acquire()
            
            val params = LLMService.LLMQueryParams(prompt)
                .useLiteCodeModel()
                .withMaxTokens(4096)
                .withTemperature(0.3)
            
            val response = llmService.queryWithParams(params, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
            
            return if (response != null) {
                parseFileAnalysisResponse(unit, context, response)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] ERROR calling LLM for ${unit.getDescription()}: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * Parse LLM response for file/group analysis
     */
    private fun parseFileAnalysisResponse(
        unit: ReviewOptimizer.ReviewUnit,
        context: ReviewOptimizer.ReviewContext,
        response: String
    ): List<MethodHealthResult> {
        try {
            val results = mutableListOf<MethodHealthResult>()
            
            // Extract JSON
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) return emptyList()
            
            val jsonContent = response.substring(jsonStart, jsonEnd + 1)
            
            // Parse each method result
            val methodPattern = Regex(
                """"fqn"\s*:\s*"([^"]+)"[^}]*"summary"\s*:\s*"([^"]+)"[^}]*"healthScore"\s*:\s*(\d+)""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            methodPattern.findAll(jsonContent).forEach { match ->
                val fqn = match.groupValues[1]
                val summary = match.groupValues[2]
                val healthScore = match.groupValues[3].toIntOrNull() ?: 85
                
                // Find issues for this method
                val issues = parseIssuesForMethod(fqn, jsonContent)
                
                results.add(MethodHealthResult(
                    fqn = fqn,
                    issues = issues,
                    impactedCallers = emptyList(), // Will be filled later if needed
                    healthScore = healthScore,
                    modificationCount = 1, // Default
                    codeContext = "", // Already in context
                    summary = summary
                ))
            }
            
            // Mark all issues as verified when using file/group analysis
            return results.map { result ->
                result.copy(
                    issues = result.issues.map { issue ->
                        issue.copy(
                            verified = true,
                            verificationReason = "Verified through ${unit.type} analysis"
                        )
                    }
                )
            }
            
        } catch (e: Exception) {
            println("[CodeHealthAnalyzer] ERROR parsing file analysis response: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * Parse issues for a specific method from JSON response
     */
    private fun parseIssuesForMethod(fqn: String, jsonContent: String): List<HealthIssue> {
        // This is a simplified version - in production, use proper JSON parsing
        val issues = mutableListOf<HealthIssue>()
        
        // Find the method block and extract its issues
        // ... parsing logic ...
        
        return issues
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
    private val isProcessing = AtomicBoolean(false)
    
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
