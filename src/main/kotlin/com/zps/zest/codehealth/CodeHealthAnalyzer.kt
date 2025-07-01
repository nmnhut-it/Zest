package com.zps.zest.codehealth

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Analysis engine that examines modified methods for issues and impact.
 * Uses LLM for intelligent analysis and existing Zest tools for context.
 */
@Service(Service.Level.PROJECT)
class CodeHealthAnalyzer(private val project: Project) {

    companion object {
        private const val MAX_CONCURRENT_ANALYSES = 2
        private const val LLM_DELAY_MS = 1000L // Delay between LLM calls to avoid spikes
        private const val CHUNK_SIZE = 5 // Process methods in chunks
        
        fun getInstance(project: Project): CodeHealthAnalyzer =
            project.getService(CodeHealthAnalyzer::class.java)
    }

    private val contextCollector = ZestLeanContextCollector(project)
    private val llmService: LLMService = project.service()
    private val analysisQueue = SimpleAnalysisQueue(delayMs = 50L)
    private val results = ConcurrentHashMap<String, MethodHealthResult>()

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
        results.clear()
        val totalMethods = methods.size
        val completed = AtomicInteger(0)
        val latch = CountDownLatch(totalMethods)
        
        // Process methods in chunks to avoid overwhelming the system
        methods.chunked(CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
            analysisQueue.submit {
                chunk.forEach { method ->
                    if (indicator?.isCanceled == true) {
                        latch.countDown()
                        return@forEach
                    }
                    
                    // Update progress
                    indicator?.let {
                        it.fraction = completed.get().toDouble() / totalMethods
                        it.text = "Analyzing method ${completed.get() + 1} of $totalMethods: ${method.fqn}"
                    }
                    
                    // Analyze method asynchronously
                    analyzeMethodAsync(method) { result ->
                        results[method.fqn] = result
                        completed.incrementAndGet()
                        latch.countDown()
                    }
                }
                
                // Delay between chunks
                Thread.sleep(LLM_DELAY_MS)
            }
        }
        
        // Wait for all analyses to complete
        try {
            latch.await(5, TimeUnit.MINUTES) // Timeout after 5 minutes
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        return results.values.toList()
            .sortedByDescending { it.healthScore * it.modificationCount }
    }

    /**
     * Analyze a single method asynchronously
     */
    private fun analyzeMethodAsync(
        method: CodeHealthTracker.ModifiedMethod,
        onComplete: (MethodHealthResult) -> Unit
    ) {
        analysisQueue.submit {
            // Step 1: Get method context
            val context = ReadAction.nonBlocking<String> {
                getMethodContext(method.fqn)
            }
            .inSmartMode(project)
            .executeSynchronously()
            
            // Step 2: Find callers asynchronously
            analysisQueue.submit {
                val callers = ReadAction.nonBlocking<List<String>> {
                    findCallers(method.fqn)
                }
                .inSmartMode(project)
                .executeSynchronously()
                
                // Step 3: Get limited caller snippets (max 5 to avoid performance issues)
                analysisQueue.submit {
                    val callerSnippets = ReadAction.nonBlocking<List<CallerSnippet>> {
                        findCallersWithSnippets(method.fqn).take(5)
                    }
                    .inSmartMode(project)
                    .executeSynchronously()
                    
                    // Step 4: Call LLM for detection
                    analysisQueue.submit {
                        val detectionResult = detectIssuesWithLLM(method, context, callers, callerSnippets)
                        
                        // Step 5: Verify issues if needed
                        if (detectionResult.issues.isNotEmpty()) {
                            analysisQueue.submit {
                                val verifiedResult = verifyIssuesWithAgent(detectionResult)
                                onComplete(verifiedResult)
                            }
                        } else {
                            onComplete(detectionResult)
                        }
                    }
                }
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
        
        return try {
            val response = llmService.query(detectionPrompt, "local-model-mini", com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
            if (response != null) {
                parseDetectionResponse(method.fqn, context, callers, callerSnippets, method.modificationCount, response)
            } else {
                // Return empty result if LLM fails
                MethodHealthResult(
                    fqn = method.fqn,
                    issues = emptyList(),
                    impactedCallers = callers,
                    healthScore = 100,
                    modificationCount = method.modificationCount,
                    codeContext = context,
                    summary = "Failed to analyze method"
                )
            }
        } catch (e: Exception) {
            MethodHealthResult(
                fqn = method.fqn,
                issues = emptyList(),
                impactedCallers = callers,
                healthScore = 100,
                modificationCount = method.modificationCount,
                codeContext = context,
                summary = "Analysis error: ${e.message}"
            )
        }
    }

    private fun buildDetectionPrompt(fqn: String, context: String, callers: List<String>, callerSnippets: List<CallerSnippet>): String {
        return """
            You are an expert code analyzer. Examine this Java method for ANY potential issues, no matter how minor.
            Be creative and thorough - think of edge cases, potential misuse, performance concerns, maintainability, etc.
            
            Method: $fqn
            Modified: Multiple times (frequently edited - higher risk)
            Called by ${callers.size} methods: ${callers.take(5).joinToString(", ")}${if (callers.size > 5) " and ${callers.size - 5} more" else ""}
            
            Code:
            ```java
            $context
            ```
            
            HOW THIS METHOD IS CALLED (Real usage examples):
            ${callerSnippets.joinToString("\n\n") { snippet ->
                """
                Caller: ${snippet.callerFqn}
                Context: ${snippet.context}
                Code:
                ```java
                ${snippet.snippet}
                ```
                """.trimIndent()
            }}
            
            Based on the actual usage patterns above, analyze for ALL possible issues including:
            - Null pointer risks (especially check how parameters are passed by callers)
            - Resource leaks (connections, streams, etc.)
            - Concurrency problems (race conditions, deadlocks)
            - Performance bottlenecks (especially if called in loops)
            - Security vulnerabilities
            - Error handling gaps
            - Code smells and anti-patterns
            - Maintainability concerns
            - Testing difficulties
            - Design principle violations
            - Magic numbers/hardcoded values
            - Naming issues
            - Documentation gaps
            - Potential future problems
            - Integration risks
            - Edge cases not handled
            - Issues specific to how the method is actually used
            
            Return JSON format:
            {
                "summary": "Brief overall assessment of method health",
                "healthScore": 85,  // 0-100, where 100 is perfect
                "issues": [
                    {
                        "category": "Null Safety",  // You decide the category
                        "severity": 3,  // 1-5, where 5 is critical
                        "title": "Unchecked null parameter",
                        "description": "Parameter 'user' is not validated for null before being dereferenced, and callers pass it directly from external sources",
                        "impact": "Will throw NullPointerException if null is passed, crashing the application",
                        "suggestedFix": "Add null check: if (user == null) throw new IllegalArgumentException(\"User cannot be null\");",
                        "lineNumbers": [15, 16],
                        "confidence": 0.95,  // 0.0-1.0, your confidence in this issue
                        "codeSnippet": "user.getName() // line 15",
                        "callerEvidence": "UserController passes request.getUser() directly without validation"
                    }
                ]
            }
            
            Be comprehensive but realistic. Pay special attention to how callers use this method.
            Consider the calling context (loops, conditionals, error handling) when assessing severity.
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
        // For now, use simple verification. Can be upgraded to use CodeHealthVerificationAgent later
        val verificationPrompt = buildVerificationPrompt(result)
        
        return try {
            val response = llmService.query(verificationPrompt, "local-model-mini", com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
            if (response != null) {
                parseVerificationResponse(result, response)
            } else {
                result
            }
        } catch (e: Exception) {
            result
        }
    }

    private fun buildVerificationPrompt(result: MethodHealthResult): String {
        return """
            You are a verification agent. Your job is to verify if detected issues are REAL problems or FALSE POSITIVES.
            Be skeptical - many "issues" might be intentional design choices or already handled properly.
            
            Method: ${result.fqn}
            Context: Method has been modified ${result.modificationCount} times
            Callers: ${result.impactedCallers.size} other methods depend on this
            
            Full Code:
            ```java
            ${result.codeContext}
            ```
            
            Detected Issues to Verify:
            ${result.issues.mapIndexed { index, issue ->
                """
                ${index + 1}. [${issue.issueCategory}] ${issue.title}
                   Severity: ${issue.severity}/5
                   Description: ${issue.description}
                   Impact: ${issue.impact}
                   Confidence: ${issue.confidence}
                   
                   ${if (issue.callerSnippets.isNotEmpty()) {
                       """
                       HOW IT'S CALLED:
                       ${issue.callerSnippets.joinToString("\n") { snippet ->
                           """
                           Caller: ${snippet.callerFqn} (${snippet.context})
                           ```java
                           ${snippet.snippet}
                           ```
                           """.trimIndent()
                       }}
                       """.trimIndent()
                   } else ""}
                """.trimIndent()
            }.joinToString("\n\n")}
            
            For each issue, determine:
            1. Is this a REAL issue that could cause problems in production?
            2. Is it a FALSE POSITIVE (handled elsewhere, intentional, or misconception)?
            
            Consider:
            - The full method context and its purpose
            - How callers use this method (see code snippets above)
            - Whether defensive programming already handles the issue
            - If the "issue" might be intentional design
            - Whether the impact is realistic given the actual usage
            
            Return JSON:
            {
                "verifications": [
                    {
                        "issueIndex": 0,  // 0-based index matching the issue order above
                        "verified": true,  // true if REAL issue, false if FALSE POSITIVE
                        "verificationReason": "The null check is indeed missing and callers pass unchecked parameters from user input",
                        "adjustedSeverity": 3,  // You can adjust severity based on context
                        "adjustedConfidence": 0.9  // Your confidence after verification
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

    fun dispose() {
        analysisQueue.shutdown()
    }
}

/**
 * Simple analysis queue for spreading work over time
 */
class SimpleAnalysisQueue(private val delayMs: Long = 100L) {
    private val executor = Executors.newSingleThreadExecutor()
    
    fun submit(task: () -> Unit) {
        executor.submit {
            try {
                task()
                Thread.sleep(delayMs) // Delay between tasks
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // Log error but continue processing
            }
        }
    }
    
    fun shutdown() {
        executor.shutdown()
    }
}
