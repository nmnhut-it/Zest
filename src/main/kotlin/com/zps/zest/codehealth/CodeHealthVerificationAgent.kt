package com.zps.zest.codehealth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.zps.zest.langchain4j.agent.CodeExplorationReport
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent
import com.zps.zest.langchain4j.util.LLMService
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Verification agent that uses autonomous code exploration to verify detected issues.
 * This agent explores the codebase to understand context and determine if issues are real or false positives.
 */
@Service(Service.Level.PROJECT)
class CodeHealthVerificationAgent(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): CodeHealthVerificationAgent =
            project.getService(CodeHealthVerificationAgent::class.java)
    }
    
    private val llmService: LLMService = project.service()
    private val autonomousAgent = ImprovedToolCallingAutonomousAgent(project)
    private val verificationExecutor = Executors.newFixedThreadPool(2)
    
    /**
     * Verify a batch of issues using autonomous exploration
     */
    fun verifyIssuesWithExploration(
        methodResult: CodeHealthAnalyzer.MethodHealthResult
    ): CompletableFuture<CodeHealthAnalyzer.MethodHealthResult> {
        
        if (methodResult.issues.isEmpty()) {
            return CompletableFuture.completedFuture(methodResult)
        }
        
        // Run verification in background
        return CompletableFuture.supplyAsync({
            try {
                // For performance, we'll use simple verification for now
                // The full autonomous exploration can be enabled for critical methods only
                verifyWithSimpleLLM(methodResult)
            } catch (e: Exception) {
                // Fallback: return issues as verified if verification fails
                methodResult.copy(
                    issues = methodResult.issues.map { 
                        it.copy(
                            verified = true,
                            verificationReason = "Verification skipped: ${e.message}"
                        )
                    }
                )
            }
        }, verificationExecutor)
    }
    
    /**
     * Simple LLM verification without full exploration (faster)
     */
    private fun verifyWithSimpleLLM(
        methodResult: CodeHealthAnalyzer.MethodHealthResult
    ): CodeHealthAnalyzer.MethodHealthResult {
        
        val verificationPrompt = buildSimpleVerificationPrompt(methodResult)
        
        return try {
            val response = llmService.query(
                verificationPrompt,
                "local-model-mini",
                com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH
            )
            
            if (response != null) {
                parseVerificationResponse(methodResult, response)
            } else {
                // Default to verified if LLM fails
                methodResult.copy(
                    issues = methodResult.issues.map { 
                        it.copy(
                            verified = true,
                            verificationReason = "Verification service unavailable"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            methodResult.copy(
                issues = methodResult.issues.map { 
                    it.copy(
                        verified = true,
                        verificationReason = "Verification error: ${e.message}"
                    )
                }
            )
        }
    }
    
    /**
     * Full exploration-based verification (slower but more accurate)
     * Only use this for critical methods or when explicitly requested
     */
    fun verifyWithFullExploration(
        methodResult: CodeHealthAnalyzer.MethodHealthResult
    ): CompletableFuture<CodeHealthAnalyzer.MethodHealthResult> {
        
        if (methodResult.issues.isEmpty()) {
            return CompletableFuture.completedFuture(methodResult)
        }
        
        // Build exploration query
        val explorationQuery = buildExplorationQuery(methodResult)
        
        // Use autonomous agent to explore context
        return autonomousAgent.exploreAndGenerateReportAsync(explorationQuery, null)
            .thenApply { explorationReport ->
                // Use exploration results to verify issues
                verifyWithExplorationContext(methodResult, explorationReport)
            }
            .exceptionally { throwable ->
                // Fallback to simple verification if exploration fails
                verifyWithSimpleLLM(methodResult)
            }
    }
    
    private fun buildExplorationQuery(methodResult: CodeHealthAnalyzer.MethodHealthResult): String {
        val issueDescriptions = methodResult.issues.take(5).joinToString("\n") { issue ->
            "- [${issue.issueCategory}] ${issue.title}: ${issue.description}"
        }
        
        return """
            Investigate the method ${methodResult.fqn} and its context to verify potential code issues.
            
            The method has been modified ${methodResult.modificationCount} times and is called by ${methodResult.impactedCallers.size} other methods.
            
            Potential issues detected:
            $issueDescriptions
            
            I need to understand:
            1. The purpose and responsibility of this method
            2. How it's used by its callers
            3. What error handling or defensive programming is already in place
            4. Whether these issues are real problems or intentional design choices
            5. The broader context of the class and related components
        """.trimIndent()
    }
    
    private fun buildSimpleVerificationPrompt(methodResult: CodeHealthAnalyzer.MethodHealthResult): String {
        return """
            You are a verification agent. Quickly verify if these detected issues are REAL problems or FALSE POSITIVES.
            
            Method: ${methodResult.fqn}
            Modified: ${methodResult.modificationCount} times
            
            Code Context:
            ```java
            ${methodResult.codeContext}
            ```
            
            Issues to verify:
            ${methodResult.issues.mapIndexed { index, issue ->
                """
                ${index}. [${issue.issueCategory}] ${issue.title}
                   Severity: ${issue.severity}/5
                   Description: ${issue.description}
                """.trimIndent()
            }.joinToString("\n\n")}
            
            For each issue, determine if it's REAL (true) or FALSE POSITIVE (false).
            
            Return JSON:
            {
                "verifications": [
                    {
                        "issueIndex": 0,
                        "verified": true,
                        "verificationReason": "Reason for decision"
                    }
                ]
            }
        """.trimIndent()
    }
    
    private fun verifyWithExplorationContext(
        methodResult: CodeHealthAnalyzer.MethodHealthResult,
        explorationReport: CodeExplorationReport
    ): CodeHealthAnalyzer.MethodHealthResult {
        
        // Build comprehensive verification prompt with exploration context
        val verificationPrompt = buildComprehensiveVerificationPrompt(methodResult, explorationReport)
        
        return try {
            val response = llmService.query(
                verificationPrompt,
                "local-model",
                com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH
            )
            
            if (response != null) {
                parseEnhancedVerificationResponse(methodResult, response)
            } else {
                // Default to verified if LLM fails
                methodResult.copy(
                    issues = methodResult.issues.map { 
                        it.copy(
                            verified = true,
                            verificationReason = "Verification service unavailable"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            methodResult.copy(
                issues = methodResult.issues.map { 
                    it.copy(
                        verified = true,
                        verificationReason = "Verification error: ${e.message}"
                    )
                }
            )
        }
    }
    
    private fun buildComprehensiveVerificationPrompt(
        methodResult: CodeHealthAnalyzer.MethodHealthResult,
        explorationReport: CodeExplorationReport
    ): String {
        return """
            You are an expert code verification agent with deep understanding of the codebase.
            
            Based on comprehensive code exploration, verify if the detected issues are REAL problems or FALSE POSITIVES.
            
            METHOD UNDER ANALYSIS: ${methodResult.fqn}
            
            EXPLORATION FINDINGS:
            ${explorationReport.explorationSummary ?: "No summary available"}
            
            DISCOVERED ELEMENTS:
            ${explorationReport.discoveredElements?.joinToString("\n") { "- $it" } ?: "None"}
            
            CODE PIECES FOUND:
            ${explorationReport.codePieces?.take(5)?.joinToString("\n") { piece ->
                "- ${piece.type}: ${piece.className ?: piece.filePath} (${piece.content?.length ?: 0} chars)"
            } ?: "None"}
            
            DETECTED ISSUES TO VERIFY:
            ${methodResult.issues.mapIndexed { index, issue ->
                """
                ${index + 1}. [${issue.issueCategory}] ${issue.title}
                   Severity: ${issue.severity}/5
                   Description: ${issue.description}
                   Impact: ${issue.impact}
                   Initial Confidence: ${issue.confidence}
                   
                   ${if (issue.callerSnippets.isNotEmpty()) {
                       """
                       ACTUAL USAGE EXAMPLES:
                       ${issue.callerSnippets.joinToString("\n") { snippet ->
                           """
                           - Called by: ${snippet.callerFqn}
                             Context: ${snippet.context}
                             Code snippet shows: ${snippet.snippet.lines().find { it.contains(">>>") } ?: "method call"}
                           """.trimIndent()
                       }}
                       """.trimIndent()
                   } else ""}
                """.trimIndent()
            }.joinToString("\n\n")}
            
            For each issue, determine based on the exploration context AND actual usage:
            1. Is this a REAL issue that could cause problems?
            2. Is it a FALSE POSITIVE (handled elsewhere, intentional, or misunderstood)?
            3. Consider how the method is actually used in the codebase (see usage examples)
            4. Look for defensive programming that might already handle the issue
            5. Consider if the "issue" aligns with the project's design patterns
            6. Pay special attention to calling contexts (loops, error handling, etc.)
            
            Return JSON:
            {
                "verifications": [
                    {
                        "issueIndex": 0,
                        "verified": true,  // true = REAL issue, false = FALSE POSITIVE
                        "verificationReason": "Based on exploration and usage analysis, this parameter comes from user input without validation in any caller",
                        "adjustedSeverity": 4,  // Adjust based on actual risk
                        "adjustedConfidence": 0.95,  // Your confidence after deep analysis
                        "contextualEvidence": "UserController.handleRequest() passes raw request.getParameter() directly as shown in usage example"
                    }
                ]
            }
        """.trimIndent()
    }
    
    private fun parseVerificationResponse(
        result: CodeHealthAnalyzer.MethodHealthResult,
        llmResponse: String
    ): CodeHealthAnalyzer.MethodHealthResult {
        try {
            val jsonStart = llmResponse.indexOf("{")
            val jsonEnd = llmResponse.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) {
                return result
            }
            
            val jsonContent = llmResponse.substring(jsonStart, jsonEnd + 1)
            
            // Parse verifications
            val verificationPattern = Regex(
                """"issueIndex"\s*:\s*(\d+)[^}]*"verified"\s*:\s*(true|false)[^}]*"verificationReason"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            val verifiedIssues = result.issues.toMutableList()
            
            verificationPattern.findAll(jsonContent).forEach { match ->
                val (indexStr, verifiedStr, reason) = match.destructured
                val index = indexStr.toIntOrNull() ?: return@forEach
                
                if (index in verifiedIssues.indices) {
                    verifiedIssues[index] = verifiedIssues[index].copy(
                        verified = verifiedStr.toBoolean(),
                        verificationReason = reason,
                        falsePositive = !verifiedStr.toBoolean()
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
    
    private fun parseEnhancedVerificationResponse(
        result: CodeHealthAnalyzer.MethodHealthResult,
        llmResponse: String
    ): CodeHealthAnalyzer.MethodHealthResult {
        try {
            val jsonStart = llmResponse.indexOf("{")
            val jsonEnd = llmResponse.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) {
                return result
            }
            
            val jsonContent = llmResponse.substring(jsonStart, jsonEnd + 1)
            
            // Enhanced parsing pattern including contextual evidence
            val verificationPattern = Regex(
                """"issueIndex"\s*:\s*(\d+)[^}]*"verified"\s*:\s*(true|false)[^}]*"verificationReason"\s*:\s*"([^"]+)"[^}]*"adjustedSeverity"\s*:\s*(\d+)[^}]*"adjustedConfidence"\s*:\s*([\d.]+)(?:[^}]*"contextualEvidence"\s*:\s*"([^"]+)")?""",
                RegexOption.DOT_MATCHES_ALL
            )
            
            val verifiedIssues = result.issues.toMutableList()
            
            verificationPattern.findAll(jsonContent).forEach { match ->
                val groups = match.groupValues
                val index = groups[1].toIntOrNull() ?: return@forEach
                val verified = groups[2].toBoolean()
                val reason = groups[3]
                val severity = groups[4].toIntOrNull() ?: verifiedIssues[index].severity
                val confidence = groups[5].toDoubleOrNull() ?: verifiedIssues[index].confidence
                val contextualEvidence = groups.getOrNull(6)
                
                if (index in verifiedIssues.indices) {
                    val enhancedReason = if (contextualEvidence != null) {
                        "$reason\nEvidence: $contextualEvidence"
                    } else {
                        reason
                    }
                    
                    verifiedIssues[index] = verifiedIssues[index].copy(
                        verified = verified,
                        verificationReason = enhancedReason,
                        falsePositive = !verified,
                        severity = severity,
                        confidence = confidence
                    )
                }
            }
            
            // Recalculate health score based on verified issues
            val verifiedRealIssues = verifiedIssues.filter { it.verified && !it.falsePositive }
            val newHealthScore = calculateEnhancedHealthScore(
                verifiedRealIssues,
                result.modificationCount,
                result.impactedCallers.size
            )
            
            return result.copy(
                issues = verifiedIssues,
                healthScore = newHealthScore
            )
        } catch (e: Exception) {
            return result
        }
    }
    
    private fun calculateHealthScore(
        issues: List<CodeHealthAnalyzer.HealthIssue>,
        modificationCount: Int,
        callerCount: Int
    ): Int {
        var score = 100
        
        // Deduct points based on issue severity
        issues.forEach { issue ->
            val deduction = when (issue.severity) {
                5 -> 20
                4 -> 15
                3 -> 10
                2 -> 5
                else -> 2
            }
            score -= deduction
        }
        
        // Factor in modification frequency
        score -= minOf(modificationCount * 2, 20)
        
        // Factor in impact
        score -= when {
            callerCount > 10 -> 10
            callerCount > 5 -> 5
            else -> 0
        }
        
        return score.coerceIn(0, 100)
    }
    
    private fun calculateEnhancedHealthScore(
        issues: List<CodeHealthAnalyzer.HealthIssue>,
        modificationCount: Int,
        callerCount: Int
    ): Int {
        var score = 100
        
        // More nuanced scoring based on verified issues
        issues.forEach { issue ->
            val baseDeduction = when (issue.severity) {
                5 -> 20  // Critical
                4 -> 15  // High
                3 -> 10  // Medium
                2 -> 5   // Low
                else -> 2 // Minor
            }
            
            // Adjust deduction based on confidence
            val adjustedDeduction = (baseDeduction * issue.confidence).toInt()
            score -= adjustedDeduction
        }
        
        // Factor in code churn (high modification count = higher risk)
        val churnPenalty = when {
            modificationCount > 20 -> 15
            modificationCount > 10 -> 10
            modificationCount > 5 -> 5
            else -> modificationCount
        }
        score -= churnPenalty
        
        // Factor in blast radius (many callers = higher impact)
        val impactPenalty = when {
            callerCount > 50 -> 20
            callerCount > 20 -> 15
            callerCount > 10 -> 10
            callerCount > 5 -> 5
            else -> 0
        }
        score -= impactPenalty
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * Batch verify multiple method results
     */
    fun verifyBatch(
        results: List<CodeHealthAnalyzer.MethodHealthResult>
    ): CompletableFuture<List<CodeHealthAnalyzer.MethodHealthResult>> {
        val futures = results.map { result ->
            verifyIssuesWithExploration(result)
        }
        
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                futures.map { it.get() }
            }
    }
    
    fun dispose() {
        verificationExecutor.shutdown()
    }
}
