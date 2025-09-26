package com.zps.zest.testgen.ui.model

/**
 * Simplified data model for displaying complete generated test classes.
 * Contains only essential information for showing the full test class code.
 */
data class GeneratedTestDisplayData(
    val className: String,           // Test class name
    val fullTestCode: String,        // Complete test class code
    val timestamp: Long = System.currentTimeMillis()
)