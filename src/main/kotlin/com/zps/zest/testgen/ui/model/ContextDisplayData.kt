package com.zps.zest.testgen.ui.model

/**
 * Data model for displaying context information in the UI.
 * Separates display logic from parsing and provides both summary and full analysis.
 */
data class ContextDisplayData(
    val filePath: String,
    val fileName: String,
    val status: AnalysisStatus,
    val summary: String,  // Short summary for tree display
    val fullAnalysis: String?,  // Full analysis for dialog (null if not yet analyzed)
    val classes: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Status of file analysis
     */
    enum class AnalysisStatus {
        PENDING,    // File queued for analysis
        ANALYZING,  // Currently being analyzed
        COMPLETED,  // Analysis complete
        ERROR,      // Analysis failed
        SKIPPED     // File skipped (e.g., binary file)
    }
    
    /**
     * Get display-friendly status text
     */
    fun getStatusText(): String = when (status) {
        AnalysisStatus.PENDING -> "Pending..."
        AnalysisStatus.ANALYZING -> "Analyzing..."
        AnalysisStatus.COMPLETED -> "Analyzed"
        AnalysisStatus.ERROR -> "Error"
        AnalysisStatus.SKIPPED -> "Skipped"
    }
    
    /**
     * Get status icon for display
     */
    fun getStatusIcon(): String = when (status) {
        AnalysisStatus.PENDING -> "⏳"
        AnalysisStatus.ANALYZING -> "🔄"
        AnalysisStatus.COMPLETED -> "✅"
        AnalysisStatus.ERROR -> "❌"
        AnalysisStatus.SKIPPED -> "⏭️"
    }
    
    /**
     * Check if analysis is available for viewing
     */
    fun hasAnalysis(): Boolean = 
        status == AnalysisStatus.COMPLETED && !fullAnalysis.isNullOrBlank()
}