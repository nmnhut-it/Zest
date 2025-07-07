package com.zps.zest.codehealth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Extension for CodeHealthTracker to support JavaScript and TypeScript files
 */
class JsTsHealthTracker(private val project: Project) {
    
    private val regionModifications = ConcurrentHashMap<String, ModifiedRegion>()
    private val contextHelper = JsTsContextHelper(project)
    
    companion object {
        const val MAX_REGIONS_TO_TRACK = 300
        private const val CONTEXT_LINES = 20 // Lines before and after cursor
    }
    
    /**
     * Check if we should handle this file
     */
    fun shouldHandleFile(fileName: String): Boolean {
        return fileName.endsWith(".js") || fileName.endsWith(".ts")
    }
    
    /**
     * Handle document change for JS/TS files
     */
    fun handleJsTsDocument(document: Document, editor: Editor, fileName: String) {
        if (project.isDisposed) return
        
        val offset = editor.caretModel.offset
        val lineNumber = document.getLineNumber(offset)
        
        println("[JsTsHealthTracker] Document changed in $fileName at line ${lineNumber + 1}")
        
        // Extract region context
        val regionContext = contextHelper.extractRegionContext(document, lineNumber, CONTEXT_LINES)
        val framework = regionContext.framework.name.lowercase().replace("_", "")
        
        // Create or update region
        val regionId = "$fileName:$lineNumber"
        val now = System.currentTimeMillis()
        
        regionModifications.compute(regionId) { _, existing ->
            if (existing != null) {
                // Check if we should merge with nearby regions
                val nearbyRegion = findNearbyRegion(fileName, lineNumber, existing)
                if (nearbyRegion != null) {
                    // Merge regions
                    regionModifications.remove(nearbyRegion.getIdentifier())
                    nearbyRegion.mergeWith(existing).copy(
                        centerLine = lineNumber,
                        lastModified = now
                    )
                } else {
                    // Update existing region
                    existing.copy(
                        centerLine = lineNumber,
                        modificationCount = existing.modificationCount + 1,
                        lastModified = now
                    )
                }
            } else {
                // Create new region
                ModifiedRegion(
                    filePath = fileName,
                    centerLine = lineNumber,
                    startLine = regionContext.startLine,
                    endLine = regionContext.endLine,
                    language = if (fileName.endsWith(".ts")) "ts" else "js",
                    framework = framework
                )
            }
        }
        
        // Queue for background review after inactivity
        val backgroundReviewer = BackgroundHealthReviewer.getInstance(project)
        backgroundReviewer.updateMethodModificationTime(regionId, now)
        
        // Limit regions
        if (regionModifications.size > MAX_REGIONS_TO_TRACK) {
            removeOldestRegion()
        }
        
        println("[JsTsHealthTracker] Total tracked regions: ${regionModifications.size}")
    }
    
    /**
     * Find nearby region that might overlap with current changes
     */
    private fun findNearbyRegion(
        fileName: String, 
        lineNumber: Int, 
        currentRegion: ModifiedRegion
    ): ModifiedRegion? {
        return regionModifications.values
            .filter { it.filePath == fileName && it != currentRegion }
            .find { region ->
                // Check if regions overlap or are very close (within 5 lines)
                val distance = minOf(
                    kotlin.math.abs(region.startLine - currentRegion.endLine),
                    kotlin.math.abs(region.endLine - currentRegion.startLine)
                )
                distance <= 5
            }
    }
    
    /**
     * Remove the oldest region to maintain size limit
     */
    private fun removeOldestRegion() {
        regionModifications.entries
            .minByOrNull { it.value.lastModified }
            ?.let { oldest ->
                println("[JsTsHealthTracker] Removing oldest region: ${oldest.key}")
                regionModifications.remove(oldest.key)
            }
    }
    
    /**
     * Get all modified regions as ModifiedMethod objects for compatibility
     */
    fun getModifiedRegionsAsMethods(): List<CodeHealthTracker.ModifiedMethod> {
        return regionModifications.values
            .map { it.toModifiedMethod() }
            .sortedByDescending { it.modificationCount }
    }
    
    /**
     * Get all modified regions
     */
    fun getModifiedRegions(): List<ModifiedRegion> {
        return regionModifications.values
            .sortedByDescending { it.modificationCount }
    }
    
    /**
     * Clear all tracked regions
     */
    fun clearAllRegions() {
        regionModifications.clear()
    }
    
    /**
     * Clear old regions based on cutoff time
     */
    fun clearOldRegions(cutoffTime: Long) {
        regionModifications.entries.removeIf { it.value.lastModified < cutoffTime }
    }
}
