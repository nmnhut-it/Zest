package com.zps.zest.codehealth

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Service to store health reports for the last 3 days
 */
@Service(Service.Level.PROJECT)
@State(
    name = "CodeHealthReportStorage",
    storages = [Storage("zest-health-reports.xml")]
)
class CodeHealthReportStorage(private val project: Project) : PersistentStateComponent<CodeHealthReportStorage.State> {
    
    companion object {
        private const val MAX_DAYS_TO_STORE = 3
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        
        fun getInstance(project: Project): CodeHealthReportStorage =
            project.getService(CodeHealthReportStorage::class.java)
    }
    
    private var state = State()
    
    /**
     * Persistent state
     */
    data class State(
        var reports: MutableMap<String, SerializableHealthReport> = mutableMapOf(),
        var gitTriggeredReport: SerializableHealthReport? = null,
        var immediateReviewReport: SerializableHealthReport? = null,
        var immediateReviewFileName: String? = null
    )
    
    /**
     * Serializable version of health report
     */
    data class SerializableHealthReport(
        var date: String = "",
        var results: MutableList<SerializableMethodResult> = mutableListOf()
    )
    
    /**
     * Serializable version of method health result
     */
    data class SerializableMethodResult(
        var fqn: String = "",
        var healthScore: Int = 100,
        var modificationCount: Int = 0,
        var summary: String = "",
        var issues: MutableList<SerializableHealthIssue> = mutableListOf(),
        var impactedCallers: MutableList<String> = mutableListOf()
    )
    
    /**
     * Serializable version of health issue
     */
    data class SerializableHealthIssue(
        var issueCategory: String = "",
        var severity: Int = 1,
        var title: String = "",
        var description: String = "",
        var impact: String = "",
        var suggestedFix: String = "",
        var confidence: Double = 1.0,
        var verified: Boolean = false,
        var falsePositive: Boolean = false,
        var verificationReason: String? = null
    )
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        // Clean up old reports on load
        cleanupOldReports()
    }
    
    /**
     * Store a health report for today
     */
    fun storeReport(results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        val today = LocalDate.now().format(DATE_FORMATTER)
        
        val serializableReport = SerializableHealthReport(
            date = today,
            results = results.map { result ->
                SerializableMethodResult(
                    fqn = result.fqn,
                    healthScore = result.healthScore,
                    modificationCount = result.modificationCount,
                    summary = result.summary,
                    issues = result.issues.map { issue ->
                        SerializableHealthIssue(
                            issueCategory = issue.issueCategory,
                            severity = issue.severity,
                            title = issue.title,
                            description = issue.description,
                            impact = issue.impact,
                            suggestedFix = issue.suggestedFix,
                            confidence = issue.confidence,
                            verified = issue.verified,
                            falsePositive = issue.falsePositive,
                            verificationReason = issue.verificationReason
                        )
                    }.toMutableList(),
                    impactedCallers = result.impactedCallers.toMutableList()
                )
            }.toMutableList()
        )
        
        state.reports[today] = serializableReport
        cleanupOldReports()
    }
    
    /**
     * Store a Git-triggered health report
     */
    fun storeGitTriggeredReport(results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        val now = LocalDate.now().format(DATE_FORMATTER) + " " + 
                  java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        
        val serializableReport = SerializableHealthReport(
            date = now,
            results = results.map { result ->
                SerializableMethodResult(
                    fqn = result.fqn,
                    healthScore = result.healthScore,
                    modificationCount = result.modificationCount,
                    summary = result.summary,
                    issues = result.issues.map { issue ->
                        SerializableHealthIssue(
                            issueCategory = issue.issueCategory,
                            severity = issue.severity,
                            title = issue.title,
                            description = issue.description,
                            impact = issue.impact,
                            suggestedFix = issue.suggestedFix,
                            confidence = issue.confidence,
                            verified = issue.verified,
                            falsePositive = issue.falsePositive,
                            verificationReason = issue.verificationReason
                        )
                    }.toMutableList(),
                    impactedCallers = result.impactedCallers.toMutableList()
                )
            }.toMutableList()
        )
        
        state.gitTriggeredReport = serializableReport
    }
    
    /**
     * Get report for a specific date
     */
    fun getReportForDate(date: LocalDate): List<CodeHealthAnalyzer.MethodHealthResult>? {
        val dateStr = date.format(DATE_FORMATTER)
        val report = state.reports[dateStr] ?: return null
        
        return report.results.map { result ->
            CodeHealthAnalyzer.MethodHealthResult(
                fqn = result.fqn,
                healthScore = result.healthScore,
                modificationCount = result.modificationCount,
                summary = result.summary,
                issues = result.issues.map { issue ->
                    CodeHealthAnalyzer.HealthIssue(
                        issueCategory = issue.issueCategory,
                        severity = issue.severity,
                        title = issue.title,
                        description = issue.description,
                        impact = issue.impact,
                        suggestedFix = issue.suggestedFix,
                        confidence = issue.confidence,
                        verified = issue.verified,
                        falsePositive = issue.falsePositive,
                        verificationReason = issue.verificationReason
                    )
                },
                impactedCallers = result.impactedCallers,
                codeContext = "", // Not stored for space reasons
                actualModel = "local-model-mini", // Default
                annotatedCode = "",
                originalCode = ""
            )
        }
    }
    
    /**
     * Check if we have a report for today
     */
    fun hasTodayReport(): Boolean {
        val today = LocalDate.now().format(DATE_FORMATTER)
        return state.reports.containsKey(today)
    }
    
    /**
     * Get the most recent report date
     */
    fun getMostRecentReportDate(): LocalDate? {
        return state.reports.keys
            .mapNotNull { 
                try {
                    LocalDate.parse(it, DATE_FORMATTER)
                } catch (e: Exception) {
                    null
                }
            }
            .maxOrNull()
    }
    
    /**
     * Get Git-triggered report
     */
    fun getGitTriggeredReport(): List<CodeHealthAnalyzer.MethodHealthResult>? {
        val report = state.gitTriggeredReport ?: return null
        
        return report.results.map { result ->
            CodeHealthAnalyzer.MethodHealthResult(
                fqn = result.fqn,
                healthScore = result.healthScore,
                modificationCount = result.modificationCount,
                summary = result.summary,
                issues = result.issues.map { issue ->
                    CodeHealthAnalyzer.HealthIssue(
                        issueCategory = issue.issueCategory,
                        severity = issue.severity,
                        title = issue.title,
                        description = issue.description,
                        impact = issue.impact,
                        suggestedFix = issue.suggestedFix,
                        confidence = issue.confidence,
                        verified = issue.verified,
                        falsePositive = issue.falsePositive,
                        verificationReason = issue.verificationReason
                    )
                },
                impactedCallers = result.impactedCallers,
                codeContext = "", // Not stored for space reasons
                actualModel = "local-model-mini", // Default
                annotatedCode = "",
                originalCode = ""
            )
        }
    }
    
    /**
     * Get Git-triggered report date
     */
    fun getGitTriggeredReportDate(): String? {
        return state.gitTriggeredReport?.date
    }
    
    /**
     * Save immediate review results
     */
    fun saveImmediateReviewResults(fileName: String, results: List<CodeHealthAnalyzer.MethodHealthResult>) {
        val today = LocalDate.now().format(DATE_FORMATTER)
        
        val serializableReport = SerializableHealthReport(
            date = today,
            results = results.map { result ->
                SerializableMethodResult(
                    fqn = result.fqn,
                    healthScore = result.healthScore,
                    modificationCount = result.modificationCount,
                    summary = result.summary,
                    issues = result.issues.map { issue ->
                        SerializableHealthIssue(
                            issueCategory = issue.issueCategory,
                            severity = issue.severity,
                            title = issue.title,
                            description = issue.description,
                            impact = issue.impact,
                            suggestedFix = issue.suggestedFix,
                            confidence = issue.confidence,
                            verified = issue.verified,
                            falsePositive = issue.falsePositive,
                            verificationReason = issue.verificationReason
                        )
                    }.toMutableList(),
                    impactedCallers = result.impactedCallers.toMutableList()
                )
            }.toMutableList()
        )
        
        state.immediateReviewReport = serializableReport
        state.immediateReviewFileName = fileName
    }
    
    /**
     * Get immediate review results
     */
    fun getImmediateReviewResults(): List<CodeHealthAnalyzer.MethodHealthResult>? {
        val report = state.immediateReviewReport ?: return null
        
        return report.results.map { result ->
            CodeHealthAnalyzer.MethodHealthResult(
                fqn = result.fqn,
                healthScore = result.healthScore,
                modificationCount = result.modificationCount,
                summary = result.summary,
                issues = result.issues.map { issue ->
                    CodeHealthAnalyzer.HealthIssue(
                        issueCategory = issue.issueCategory,
                        severity = issue.severity,
                        title = issue.title,
                        description = issue.description,
                        impact = issue.impact,
                        suggestedFix = issue.suggestedFix,
                        confidence = issue.confidence,
                        verified = issue.verified,
                        falsePositive = issue.falsePositive,
                        verificationReason = issue.verificationReason
                    )
                },
                impactedCallers = result.impactedCallers,
                originalCode = "",
                annotatedCode = "",
                codeContext = ""
            )
        }
    }
    
    /**
     * Get immediate review file name
     */
    fun getImmediateReviewFileName(): String? {
        return state.immediateReviewFileName
    }
    
    /**
     * Clean up reports older than MAX_DAYS_TO_STORE
     */
    private fun cleanupOldReports() {
        val cutoffDate = LocalDate.now().minusDays(MAX_DAYS_TO_STORE.toLong())
        
        state.reports.entries.removeIf { entry ->
            try {
                val reportDate = LocalDate.parse(entry.key, DATE_FORMATTER)
                reportDate.isBefore(cutoffDate)
            } catch (e: Exception) {
                true // Remove invalid entries
            }
        }
    }
}
