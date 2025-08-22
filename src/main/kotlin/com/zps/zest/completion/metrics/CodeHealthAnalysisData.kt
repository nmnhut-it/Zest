package com.zps.zest.completion.metrics

/**
 * Strongly-typed data class for Code Health analysis data
 */
data class CodeHealthAnalysisData(
    val trigger: String,  // "git_commit", "manual", "post_commit_auto", etc.
    val criticalIssues: Int,
    val highIssues: Int,
    val totalIssues: Int,
    val methodsAnalyzed: Int,
    val averageHealthScore: Int,
    val issuesByCategory: Map<String, Int>,
    val userAction: String? = null,  // "fix_now_clicked", "view", etc.
    val elapsedMs: Long? = null,
    val additionalContext: Map<String, Any> = emptyMap()
) {
    /**
     * Convert to map for compatibility with existing systems
     */
    fun toMap(): Map<String, Any> = mapOf(
        "trigger" to trigger,
        "critical_issues" to criticalIssues,
        "high_issues" to highIssues,
        "total_issues" to totalIssues,
        "methods_analyzed" to methodsAnalyzed,
        "average_health_score" to averageHealthScore,
        "issues_by_category" to issuesByCategory,
        "user_action" to (userAction ?: ""),
        "elapsed_ms" to (elapsedMs ?: 0)
    ) + additionalContext
    
    companion object {
        /**
         * Create from existing map data (for migration)
         */
        fun fromMap(data: Map<String, Any>): CodeHealthAnalysisData {
            return CodeHealthAnalysisData(
                trigger = data["trigger"]?.toString() ?: "unknown",
                criticalIssues = (data["critical_issues"] as? Number)?.toInt() ?: 0,
                highIssues = (data["high_issues"] as? Number)?.toInt() ?: 0,
                totalIssues = (data["total_issues"] as? Number)?.toInt() ?: 0,
                methodsAnalyzed = (data["methods_analyzed"] as? Number)?.toInt() ?: 0,
                averageHealthScore = (data["average_health_score"] as? Number)?.toInt() ?: 0,
                issuesByCategory = (data["issues_by_category"] as? Map<String, Int>) ?: emptyMap(),
                userAction = data["user_action"]?.toString(),
                elapsedMs = (data["elapsed_ms"] as? Number)?.toLong(),
                additionalContext = data.filterKeys { 
                    it !in setOf("trigger", "critical_issues", "high_issues", "total_issues", 
                                "methods_analyzed", "average_health_score", "issues_by_category", 
                                "user_action", "elapsed_ms")
                }
            )
        }
    }
}