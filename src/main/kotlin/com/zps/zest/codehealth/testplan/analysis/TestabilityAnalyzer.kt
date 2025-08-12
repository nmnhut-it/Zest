package com.zps.zest.codehealth.testplan.analysis

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.zps.zest.codehealth.testplan.models.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for analyzing method testability and generating test recommendations
 */
@Service(Service.Level.PROJECT)
class TestabilityAnalyzer(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): TestabilityAnalyzer {
            return project.getService(TestabilityAnalyzer::class.java)
        }
    }
    
    /**
     * Analyzes testability of multiple methods asynchronously
     */
    suspend fun analyzeMethodTestability(methods: List<PsiMethod>): Flow<TestabilityResult> = flow {
        val total = methods.size
        methods.forEachIndexed { index, method ->
            val result = analyzeMethod(method)
            emit(result.copy(progress = (index + 1) / total.toFloat()))
            delay(50) // Don't block UI
        }
    }
    
    /**
     * Analyzes a single method for testability
     */
    suspend fun analyzeMethod(method: PsiMethod): TestabilityResult {
        return TestabilityResult(
            methodFqn = "${method.containingClass?.qualifiedName}.${method.name}",
            score = calculateTestabilityScore(method),
            complexity = calculateCyclomaticComplexity(method),
            dependencies = analyzeDependencies(method),
            mockingRequirements = analyzeMockingRequirements(method),
            sideEffects = analyzeSideEffects(method),
            recommendations = generateRecommendations(method)
        )
    }
    
    /**
     * Analyzes testability by method FQN (for integration with existing code)
     */
    suspend fun analyzeMethodByFqn(methodFqn: String): TestabilityResult {
        // TODO: Implement PSI method lookup by FQN
        // For now, return mock data based on FQN analysis
        return createMockAnalysis(methodFqn)
    }
    
    private fun calculateTestabilityScore(method: PsiMethod): Int {
        var score = 100
        
        // Deduct points for complexity
        val complexity = calculateCyclomaticComplexity(method)
        score -= (complexity - 1) * 5
        
        // Deduct points for dependencies
        val dependencies = analyzeDependencies(method)
        score -= dependencies.size * 10
        
        // Deduct points for side effects
        val sideEffects = analyzeSideEffects(method)
        score -= sideEffects.size * 15
        
        // Ensure score stays within bounds
        return maxOf(0, minOf(100, score))
    }
    
    private fun calculateCyclomaticComplexity(method: PsiMethod): Int {
        // TODO: Implement actual cyclomatic complexity calculation
        // For now, estimate based on method body length and keywords
        val bodyText = method.body?.text ?: return 1
        
        var complexity = 1 // Base complexity
        
        // Count decision points
        complexity += bodyText.split("if").size - 1
        complexity += bodyText.split("while").size - 1
        complexity += bodyText.split("for").size - 1
        complexity += bodyText.split("case").size - 1
        complexity += bodyText.split("catch").size - 1
        complexity += bodyText.split("&&").size - 1
        complexity += bodyText.split("||").size - 1
        
        return complexity
    }
    
    private fun analyzeDependencies(method: PsiMethod): List<String> {
        // TODO: Implement actual dependency analysis using PSI
        // For now, return mock dependencies based on method context
        val dependencies = mutableListOf<String>()
        
        val bodyText = method.body?.text ?: return dependencies
        
        // Simple heuristic: look for common dependency patterns
        if (bodyText.contains("Repository") || bodyText.contains("Dao")) {
            dependencies.add("Database Repository")
        }
        if (bodyText.contains("Service")) {
            dependencies.add("External Service")
        }
        if (bodyText.contains("HttpClient") || bodyText.contains("RestTemplate")) {
            dependencies.add("HTTP Client")
        }
        if (bodyText.contains("File") || bodyText.contains("Path")) {
            dependencies.add("File System")
        }
        
        return dependencies
    }
    
    private fun analyzeMockingRequirements(method: PsiMethod): List<MockingRequirement> {
        val dependencies = analyzeDependencies(method)
        return dependencies.map { dep ->
            MockingRequirement(
                className = dep,
                reason = "External dependency requires mocking",
                mockType = when {
                    dep.contains("Database") -> MockType.MOCK
                    dep.contains("Service") -> MockType.MOCK
                    dep.contains("Client") -> MockType.MOCK
                    else -> MockType.STUB
                }
            )
        }
    }
    
    private fun analyzeSideEffects(method: PsiMethod): List<SideEffect> {
        val sideEffects = mutableListOf<SideEffect>()
        val bodyText = method.body?.text ?: return sideEffects
        
        // Look for common side effect patterns
        if (bodyText.contains("File") || bodyText.contains("Path") || bodyText.contains("write") || bodyText.contains("read")) {
            sideEffects.add(SideEffect(
                type = SideEffectType.FILE_IO,
                description = "Method performs file I/O operations",
                impact = "Tests may be slow and brittle due to file system dependencies",
                mitigation = "Use temporary files or mock file system operations"
            ))
        }
        
        if (bodyText.contains("Thread") || bodyText.contains("async") || bodyText.contains("CompletableFuture")) {
            sideEffects.add(SideEffect(
                type = SideEffectType.THREADING,
                description = "Method uses threading or async operations",
                impact = "Tests may be non-deterministic due to timing issues",
                mitigation = "Use test frameworks with async support or mock threading"
            ))
        }
        
        if (bodyText.contains("Random") || bodyText.contains("UUID")) {
            sideEffects.add(SideEffect(
                type = SideEffectType.RANDOM,
                description = "Method uses random values or UUIDs",
                impact = "Tests may be non-reproducible",
                mitigation = "Inject random number generator or use fixed seeds"
            ))
        }
        
        if (bodyText.contains("System.currentTimeMillis") || bodyText.contains("LocalDateTime.now")) {
            sideEffects.add(SideEffect(
                type = SideEffectType.TIME_DEPENDENT,
                description = "Method depends on current time",
                impact = "Tests may fail at different times",
                mitigation = "Inject clock or use fixed time in tests"
            ))
        }
        
        return sideEffects
    }
    
    private fun generateRecommendations(method: PsiMethod): List<String> {
        val recommendations = mutableListOf<String>()
        
        val complexity = calculateCyclomaticComplexity(method)
        if (complexity > 10) {
            recommendations.add("Consider breaking this method into smaller, focused methods to reduce complexity")
        }
        
        val dependencies = analyzeDependencies(method)
        if (dependencies.size > 3) {
            recommendations.add("This method has many dependencies - consider using dependency injection")
        }
        
        val sideEffects = analyzeSideEffects(method)
        if (sideEffects.isNotEmpty()) {
            recommendations.add("Extract side effects to separate methods to improve testability")
        }
        
        // Check for common testability issues
        val bodyText = method.body?.text ?: ""
        if (bodyText.contains("new ") && bodyText.contains("Service")) {
            recommendations.add("Avoid creating service instances directly - use dependency injection instead")
        }
        
        if (bodyText.contains("static")) {
            recommendations.add("Static method calls can make testing difficult - consider using instance methods")
        }
        
        return recommendations
    }
    
    private fun createMockAnalysis(methodFqn: String): TestabilityResult {
        // Create realistic mock data based on method name patterns
        val score = when {
            methodFqn.contains("get") && !methodFqn.contains("Service") -> 90
            methodFqn.contains("validate") || methodFqn.contains("check") -> 85
            methodFqn.contains("calculate") || methodFqn.contains("compute") -> 80
            methodFqn.contains("save") || methodFqn.contains("update") || methodFqn.contains("delete") -> 60
            methodFqn.contains("Service") || methodFqn.contains("Repository") -> 50
            else -> 75
        }
        
        return TestabilityResult(
            methodFqn = methodFqn,
            score = score,
            complexity = if (score > 80) 3 else if (score > 60) 7 else 12,
            dependencies = when {
                methodFqn.contains("Service") -> listOf("UserRepository", "EmailService")
                methodFqn.contains("save") -> listOf("Database", "ValidationService")
                methodFqn.contains("calculate") -> listOf("ConfigurationService")
                else -> emptyList()
            },
            mockingRequirements = when {
                methodFqn.contains("Service") -> listOf(
                    MockingRequirement("UserRepository", "Database access"),
                    MockingRequirement("EmailService", "External service")
                )
                methodFqn.contains("save") -> listOf(
                    MockingRequirement("Database", "Data persistence"),
                    MockingRequirement("ValidationService", "Business rule validation")
                )
                else -> emptyList()
            },
            sideEffects = when {
                methodFqn.contains("save") || methodFqn.contains("update") -> listOf(
                    SideEffect(SideEffectType.DATABASE, "Modifies database state", "Tests need cleanup", "Use transactions")
                )
                methodFqn.contains("send") || methodFqn.contains("notify") -> listOf(
                    SideEffect(SideEffectType.NETWORK, "External API calls", "Tests may be slow", "Use mock services")
                )
                else -> emptyList()
            },
            recommendations = listOf(
                "Add input validation for edge cases",
                "Consider extracting complex logic to helper methods",
                "Add comprehensive error handling"
            ).take(if (score > 80) 1 else if (score > 60) 2 else 3)
        )
    }
}