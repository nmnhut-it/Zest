package com.zps.zest.testgen.snapshot

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Performs automatic cleanup of old agent snapshots on project startup.
 * Deletes snapshots older than the configured retention period (default: 7 days).
 */
class SnapshotCleanupActivity : ProjectActivity {

    companion object {
        // Retention period in milliseconds (7 days)
        private const val DEFAULT_RETENTION_DAYS = 7L
        private const val RETENTION_MILLIS = DEFAULT_RETENTION_DAYS * 24 * 60 * 60 * 1000
    }

    override suspend fun execute(project: Project) {
        try {
            cleanupOldSnapshots(project)
        } catch (e: Exception) {
            // Log but don't throw - cleanup failure shouldn't break project loading
            com.intellij.openapi.diagnostic.Logger.getInstance(SnapshotCleanupActivity::class.java)
                .warn("Failed to cleanup old snapshots", e)
        }
    }

    private fun cleanupOldSnapshots(project: Project) {
        val logger = com.intellij.openapi.diagnostic.Logger.getInstance(SnapshotCleanupActivity::class.java)

        // Get all snapshots
        val allSnapshots = AgentSnapshotSerializer.listSnapshots(project)
        if (allSnapshots.isEmpty()) {
            return
        }

        // Calculate cutoff time
        val cutoffTime = System.currentTimeMillis() - RETENTION_MILLIS

        // Find snapshots older than retention period
        val oldSnapshots = allSnapshots.filter { it.timestamp < cutoffTime }

        if (oldSnapshots.isEmpty()) {
            logger.info("No old snapshots to cleanup (checked ${allSnapshots.size} snapshots)")
            return
        }

        // Delete old snapshots
        var deletedCount = 0
        var failedCount = 0

        for (snapshot in oldSnapshots) {
            if (AgentSnapshotSerializer.deleteSnapshot(snapshot.filePath)) {
                deletedCount++
            } else {
                failedCount++
            }
        }

        logger.info("Snapshot cleanup: deleted $deletedCount old snapshots (older than $DEFAULT_RETENTION_DAYS days), " +
                "failed: $failedCount, kept: ${allSnapshots.size - oldSnapshots.size}")
    }
}
