package com.zps.zest.codehealth

/**
 * Data class representing a modified region in JS/TS files
 */
data class ModifiedRegion(
    val filePath: String,
    val centerLine: Int,      // Cursor position line
    val startLine: Int,       // centerLine - 20
    val endLine: Int,         // centerLine + 20
    val language: String,     // "js", "ts"
    val framework: String?,   // "cocos2dx", "react", etc.
    var modificationCount: Int = 1,
    var lastModified: Long = System.currentTimeMillis()
) {
    /**
     * Generate a unique identifier for this region
     */
    fun getIdentifier(): String = "$filePath:$centerLine"
    
    /**
     * Check if this region overlaps with another
     */
    fun overlaps(other: ModifiedRegion): Boolean {
        return filePath == other.filePath && 
               (startLine <= other.endLine && endLine >= other.startLine)
    }
    
    /**
     * Merge with another overlapping region
     */
    fun mergeWith(other: ModifiedRegion): ModifiedRegion {
        return copy(
            startLine = minOf(startLine, other.startLine),
            endLine = maxOf(endLine, other.endLine),
            centerLine = if (other.lastModified > lastModified) other.centerLine else centerLine,
            modificationCount = modificationCount + other.modificationCount,
            lastModified = maxOf(lastModified, other.lastModified),
            framework = framework ?: other.framework
        )
    }
    
    /**
     * Convert to a format compatible with the analyzer
     */
    fun toModifiedMethod(): ProjectChangesTracker.ModifiedMethod {
        return ProjectChangesTracker.ModifiedMethod(
            fqn = getIdentifier(),
            modificationCount = modificationCount,
            lastModified = lastModified
        )
    }
}
