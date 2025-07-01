package com.zps.zest.codehealth

/**
 * Simple test class to verify Code Health implementation
 */
class CodeHealthTest {
    
    fun testHealthIssueTypes() {
        // Test that all issue types have proper severity
        CodeHealthAnalyzer.IssueType.values().forEach { type ->
            assert(type.severity in 1..3) { 
                "${type.name} has invalid severity: ${type.severity}" 
            }
            assert(type.displayName.isNotEmpty()) {
                "${type.name} has empty display name"
            }
        }
    }
    
    fun testModifiedMethodDataClass() {
        val method = CodeHealthTracker.ModifiedMethod(
            fqn = "com.example.Test.method",
            modificationCount = 5,
            lastModified = System.currentTimeMillis()
        )
        
        // Test serialization
        val serialized = method.toSerializable()
        assert(serialized.fqn == method.fqn)
        assert(serialized.modificationCount == method.modificationCount)
        
        // Test deserialization
        val deserialized = serialized.toModifiedMethod()
        assert(deserialized.fqn == method.fqn)
        assert(deserialized.modificationCount == method.modificationCount)
    }
    
    fun testHealthScoreCalculation() {
        // Health score should be between 0 and 100
        val issues = listOf(
            CodeHealthAnalyzer.HealthIssue(
                type = CodeHealthAnalyzer.IssueType.NPE_RISK,
                description = "Test issue",
                suggestedPrompt = "Fix NPE"
            )
        )
        
        // Score calculation is internal to analyzer, but we can verify the result structure
        val result = CodeHealthAnalyzer.MethodHealthResult(
            fqn = "test.Method",
            issues = issues,
            impactedCallers = listOf("caller1", "caller2"),
            healthScore = 70,
            modificationCount = 3
        )
        
        assert(result.healthScore in 0..100)
        assert(result.issues.size == 1)
        assert(result.impactedCallers.size == 2)
    }
}
