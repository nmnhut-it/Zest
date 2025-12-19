package com.zps.zest.codehealth.v2.model

/**
 * Represents a health issue detected in code.
 * Immutable data class for thread safety and testability.
 */
data class HealthIssue(
    val issueCategory: String,
    val severity: Int,
    val title: String,
    val description: String,
    val impact: String,
    val suggestedFix: String,
    val confidence: Double = 1.0,
    val verified: Boolean = false,
    val falsePositive: Boolean = false,
    val verificationReason: String? = null,
    val lineNumbers: List<Int> = emptyList(),
    val codeSnippet: String? = null
) {
    companion object {
        const val SEVERITY_CRITICAL = 5
        const val SEVERITY_HIGH = 4
        const val SEVERITY_MEDIUM = 3
        const val SEVERITY_LOW = 2
        const val SEVERITY_INFO = 1
    }

    fun isCritical(): Boolean = severity >= SEVERITY_HIGH

    /**
     * Returns true if this issue should be displayed to the user.
     * Shows: verified issues OR detected issues where verification was skipped.
     * Hides: confirmed false positives.
     */
    fun isVerifiedIssue(): Boolean = !falsePositive && (verified || isVerificationSkipped())

    /** True if this issue was detected but verification was intentionally skipped. */
    fun isVerificationSkipped(): Boolean = verificationReason?.contains("skipped", ignoreCase = true) == true
}

/**
 * Represents health analysis result for a single method/code unit.
 */
data class MethodHealthResult(
    val fqn: String,
    val healthScore: Int,
    val modificationCount: Int = 0,
    val summary: String = "",
    val issues: List<HealthIssue> = emptyList(),
    val impactedCallers: List<String> = emptyList(),
    val codeContext: String = "",
    val actualModel: String = "unknown"
) {
    fun getVerifiedIssues(): List<HealthIssue> = issues.filter { it.isVerifiedIssue() }
    fun getCriticalIssues(): List<HealthIssue> = getVerifiedIssues().filter { it.isCritical() }
    fun hasIssues(): Boolean = getVerifiedIssues().isNotEmpty()
}

/**
 * Aggregated report containing multiple method results.
 */
data class HealthReport(
    val timestamp: Long = System.currentTimeMillis(),
    val triggerType: ReportTriggerType,
    val results: List<MethodHealthResult>,
    val label: String = ""
) {
    fun getTotalIssueCount(): Int = results.sumOf { it.getVerifiedIssues().size }
    fun getCriticalIssueCount(): Int = results.sumOf { it.getCriticalIssues().size }
    fun getAverageScore(): Int = if (results.isEmpty()) 100 else results.map { it.healthScore }.average().toInt()
    fun isEmpty(): Boolean = results.isEmpty()
}

/**
 * Type of trigger that initiated the health report.
 */
enum class ReportTriggerType {
    SCHEDULED,      // Daily scheduled check
    GIT_COMMIT,     // Triggered after git commit
    MANUAL,         // User manually triggered
    IMMEDIATE       // Immediate file review
}

/**
 * Result of storing a report, useful for testing and verification.
 */
data class StoreResult(
    val success: Boolean,
    val storageKey: String,
    val timestamp: Long,
    val resultCount: Int,
    val issueCount: Int
)
