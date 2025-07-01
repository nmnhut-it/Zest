package com.zps.zest.codehealth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.langchain4j.util.LLMService
import java.util.concurrent.*

/**
 * Analysis engine that examines modified methods for issues and impact.
 * Uses LLM for intelligent analysis and existing Zest tools for context.
 */
@Service(Service.Level.PROJECT)
class CodeHealthAnalyzer(private val project: Project) {

    companion object {
        private const val MAX_CONCURRENT_ANALYSES = 3
        private const val LLM_DELAY_MS = 1000L // Delay between LLM calls to avoid spikes
        
        fun getInstance(project: Project): CodeHealthAnalyzer =
            project.getService(CodeHealthAnalyzer::class.java)
    }

    private val contextCollector = ZestLeanContextCollector(project)
    private val llmService: LLMService = project.service()
    private val executorService: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT_ANALYSES)

    /**
     * Health issue types
     */
    enum class IssueType(val severity: Int, val displayName: String) {
        NPE_RISK(3, "Null Pointer Risk"),
        LOGIC_ERROR(3, "Logic Error"),
        PERFORMANCE_ISSUE(2, "Performance Issue"),
        SECURITY_VULNERABILITY(3, "Security Risk"),
        CODE_SMELL(1, "Code Smell"),
        MISSING_VALIDATION(2, "Missing Validation"),
        RESOURCE_LEAK(3, "Resource Leak"),
        CONCURRENCY_ISSUE(3, "Concurrency Issue"),
        API_BREAKING_CHANGE(3, "API Breaking Change"),
        TEST_COVERAGE_GAP(1, "Test Coverage Gap")
    }

    /**
     * Health issue data class
     */
    data class HealthIssue(
        val type: IssueType,
        val description: String,
        val suggestedPrompt: String,
        val lineNumber: Int? = null
    )

    /**
     * Analysis result for a method
     */
    data class MethodHealthResult(
        val fqn: String,
        val issues: List<HealthIssue>,
        val impactedCallers: List<String>,
        val healthScore: Int,
        val modificationCount: Int
    )

    /**
     * Analyze all modified methods with rate limiting
     */
    fun analyzeAllMethods(methods: List<CodeHealthTracker.ModifiedMethod>): List<MethodHealthResult> {
        val results = ConcurrentHashMap<String, MethodHealthResult>()
        val semaphore = Semaphore(MAX_CONCURRENT_ANALYSES)
        val futures = mutableListOf<Future<*>>()
        
        methods.forEach { method ->
            val future = executorService.submit {
                try {
                    semaphore.acquire()
                    try {
                        val result = analyzeMethod(method)
                        results[method.fqn] = result
                        
                        // Rate limiting delay
                        Thread.sleep(LLM_DELAY_MS)
                    } finally {
                        semaphore.release()
                    }
                } catch (e: Exception) {
                    // Log error but continue with other analyses
                    e.printStackTrace()
                }
            }
            futures.add(future)
        }
        
        // Wait for all analyses to complete
        futures.forEach { it.get() }
        
        return results.values.toList()
            .sortedByDescending { it.healthScore * it.modificationCount }
    }

    /**
     * Analyze a single method for issues and impact
     */
    private fun analyzeMethod(method: CodeHealthTracker.ModifiedMethod): MethodHealthResult {
        // Get method context
        val context = ApplicationManager.getApplication().runReadAction<String> {
            getMethodContext(method.fqn)
        }
        
        // Find callers
        val callers = findCallers(method.fqn)
        
        // Perform LLM analysis
        val issues = performLLMAnalysis(method.fqn, context, callers)
        
        // Calculate health score
        val healthScore = calculateHealthScore(issues, method.modificationCount, callers.size)
        
        return MethodHealthResult(
            fqn = method.fqn,
            issues = issues,
            impactedCallers = callers,
            healthScore = healthScore,
            modificationCount = method.modificationCount
        )
    }

    private fun getMethodContext(fqn: String): String {
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
                appendLine("Method: $fqn")
                appendLine("Signature: ${psiMethod.text.lines().first()}")
                appendLine("\nMethod body:")
                append(psiMethod.body?.text ?: "// No body found")
            }
        } ?: "// Method not found: $fqn"
    }

    private fun findCallers(fqn: String): List<String> {
        return try {
            ApplicationManager.getApplication().runReadAction<List<String>> {
                val parts = fqn.split(".")
                if (parts.size < 2) return@runReadAction emptyList()
                
                val className = parts.dropLast(1).joinToString(".")
                val methodName = parts.last()
                
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
                
                val psiMethod = psiClass?.methods?.find { it.name == methodName }
                    ?: return@runReadAction emptyList()
                
                val references = MethodReferencesSearch.search(psiMethod).findAll()
                references.mapNotNull { ref ->
                    val element = ref.element
                    val containingMethod = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                        element, 
                        com.intellij.psi.PsiMethod::class.java
                    )
                    containingMethod?.let { method ->
                        "${method.containingClass?.qualifiedName}.${method.name}"
                    }
                }.distinct()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun performLLMAnalysis(
        fqn: String, 
        context: String, 
        callers: List<String>
    ): List<HealthIssue> {
        val prompt = buildAnalysisPrompt(fqn, context, callers)
        
        return try {
            val response = llmService.query(prompt, "local-model-mini", com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH)
            if (response != null) {
                parseHealthIssues(response)
            } else {
                performBasicAnalysis(context)
            }
        } catch (e: Exception) {
            // Fallback to basic analysis if LLM fails
            performBasicAnalysis(context)
        }
    }

    private fun buildAnalysisPrompt(fqn: String, context: String, callers: List<String>): String {
        return """
            Analyze this Java method for potential issues. Return a structured JSON response.
            
            Method: $fqn
            Called by ${callers.size} methods: ${callers.take(5).joinToString(", ")}${if (callers.size > 5) " and ${callers.size - 5} more" else ""}
            
            Code:
            ```java
            $context
            ```
            
            Analyze for:
            1. Null pointer exceptions (NPE_RISK)
            2. Logic errors (LOGIC_ERROR)
            3. Performance issues (PERFORMANCE_ISSUE)
            4. Security vulnerabilities (SECURITY_VULNERABILITY)
            5. Resource leaks (RESOURCE_LEAK)
            6. Concurrency issues (CONCURRENCY_ISSUE)
            7. Missing validation (MISSING_VALIDATION)
            
            Return JSON format:
            {
                "issues": [
                    {
                        "type": "NPE_RISK",
                        "description": "Parameter 'user' not checked for null before access",
                        "line": 15,
                        "fix": "Add null check: if (user == null) throw new IllegalArgumentException(\"User cannot be null\");"
                    }
                ]
            }
            
            Only include actual issues found. Be concise and specific.
        """.trimIndent()
    }

    private fun parseHealthIssues(llmResponse: String): List<HealthIssue> {
        return try {
            // Simple JSON parsing - in production, use proper JSON library
            val issues = mutableListOf<HealthIssue>()
            
            // Extract JSON content
            val jsonStart = llmResponse.indexOf("{")
            val jsonEnd = llmResponse.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) {
                return performBasicAnalysis(llmResponse)
            }
            
            val jsonContent = llmResponse.substring(jsonStart, jsonEnd + 1)
            
            // Parse issues (simplified - use proper JSON parser in production)
            val issueMatches = Regex(""""type"\s*:\s*"([^"]+)"[^}]*"description"\s*:\s*"([^"]+)"[^}]*"fix"\s*:\s*"([^"]+)"""")
                .findAll(jsonContent)
            
            issueMatches.forEach { match ->
                val (typeStr, description, fix) = match.destructured
                val issueType = try {
                    IssueType.valueOf(typeStr)
                } catch (e: Exception) {
                    IssueType.CODE_SMELL
                }
                
                issues.add(HealthIssue(
                    type = issueType,
                    description = description,
                    suggestedPrompt = "Fix ${issueType.displayName}: $fix"
                ))
            }
            
            issues
        } catch (e: Exception) {
            performBasicAnalysis(llmResponse)
        }
    }

    private fun performBasicAnalysis(context: String): List<HealthIssue> {
        val issues = mutableListOf<HealthIssue>()
        
        // Basic pattern matching for common issues
        if (context.contains("null") && !context.contains("!= null") && !context.contains("== null")) {
            issues.add(HealthIssue(
                type = IssueType.NPE_RISK,
                description = "Potential null reference without explicit null check",
                suggestedPrompt = "Add null safety checks to prevent NullPointerException"
            ))
        }
        
        if (context.contains("while (true)") || context.contains("for (;;)")) {
            issues.add(HealthIssue(
                type = IssueType.LOGIC_ERROR,
                description = "Infinite loop detected",
                suggestedPrompt = "Add proper loop termination condition"
            ))
        }
        
        if (context.contains("synchronized") && context.contains("wait(") && !context.contains("notifyAll")) {
            issues.add(HealthIssue(
                type = IssueType.CONCURRENCY_ISSUE,
                description = "wait() without corresponding notify/notifyAll",
                suggestedPrompt = "Ensure proper thread notification in synchronized blocks"
            ))
        }
        
        if (context.contains("Connection") || context.contains("Stream")) {
            if (!context.contains("close()") && !context.contains("try-with-resources")) {
                issues.add(HealthIssue(
                    type = IssueType.RESOURCE_LEAK,
                    description = "Resource may not be properly closed",
                    suggestedPrompt = "Use try-with-resources or ensure resource is closed in finally block"
                ))
            }
        }
        
        return issues
    }

    private fun calculateHealthScore(
        issues: List<HealthIssue>, 
        modificationCount: Int,
        callerCount: Int
    ): Int {
        // Base score starts at 100
        var score = 100
        
        // Deduct points for issues based on severity
        issues.forEach { issue ->
            score -= issue.type.severity * 10
        }
        
        // Factor in modification frequency (more changes = more risk)
        score -= modificationCount * 2
        
        // Factor in impact (more callers = higher impact)
        if (callerCount > 10) score -= 10
        else if (callerCount > 5) score -= 5
        
        return score.coerceIn(0, 100)
    }

    fun dispose() {
        executorService.shutdown()
    }
}
