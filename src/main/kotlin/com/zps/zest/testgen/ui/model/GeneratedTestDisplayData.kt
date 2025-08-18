package com.zps.zest.testgen.ui.model

/**
 * Data model for displaying generated test information in the UI.
 * Provides structured data for test code display and validation results.
 */
data class GeneratedTestDisplayData(
    val testName: String,
    val scenarioId: String,
    val scenarioName: String,
    val testCode: String,
    val validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED,
    val validationMessages: List<ValidationMessage> = emptyList(),
    val lineCount: Int = testCode.lines().size,
    val timestamp: Long = System.currentTimeMillis()
) {
    
    enum class ValidationStatus {
        NOT_VALIDATED,   // Not yet validated
        VALIDATING,      // Currently being validated
        PASSED,          // All validations passed
        WARNINGS,        // Passed with warnings
        FAILED           // Validation failed
    }
    
    data class ValidationMessage(
        val type: MessageType,
        val message: String,
        val line: Int? = null  // Line number if applicable
    ) {
        enum class MessageType {
            ERROR,
            WARNING,
            INFO
        }
        
        fun getIcon(): String = when (type) {
            MessageType.ERROR -> "❌"
            MessageType.WARNING -> "⚠️"
            MessageType.INFO -> "ℹ️"
        }
    }
    
    /**
     * Get status icon for display
     */
    fun getStatusIcon(): String = when (validationStatus) {
        ValidationStatus.NOT_VALIDATED -> "⏳"
        ValidationStatus.VALIDATING -> "🔄"
        ValidationStatus.PASSED -> "✅"
        ValidationStatus.WARNINGS -> "⚠️"
        ValidationStatus.FAILED -> "❌"
    }
    
    /**
     * Get a brief summary for list display
     */
    fun getSummary(): String = 
        "$testName (${lineCount} lines) ${getStatusIcon()}"
    
    /**
     * Check if test code can be viewed
     */
    fun canViewCode(): Boolean = testCode.isNotBlank()
    
    /**
     * Get error count
     */
    fun getErrorCount(): Int = 
        validationMessages.count { it.type == ValidationMessage.MessageType.ERROR }
    
    /**
     * Get warning count
     */
    fun getWarningCount(): Int = 
        validationMessages.count { it.type == ValidationMessage.MessageType.WARNING }
    
    /**
     * Get validation summary text
     */
    fun getValidationSummary(): String = when (validationStatus) {
        ValidationStatus.NOT_VALIDATED -> "Not validated"
        ValidationStatus.VALIDATING -> "Validating..."
        ValidationStatus.PASSED -> "All checks passed"
        ValidationStatus.WARNINGS -> "${getWarningCount()} warning(s)"
        ValidationStatus.FAILED -> "${getErrorCount()} error(s), ${getWarningCount()} warning(s)"
    }
}