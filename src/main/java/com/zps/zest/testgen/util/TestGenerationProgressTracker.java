package com.zps.zest.testgen.util;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.testgen.model.TestGenerationProgress;
import com.zps.zest.testgen.model.TestGenerationProgressListener;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Enhanced progress tracking for test generation with detailed phase information
 */
public class TestGenerationProgressTracker {
    private static final Logger LOG = Logger.getInstance(TestGenerationProgressTracker.class);
    
    private final String sessionId;
    private final List<ProgressPhase> phases = new ArrayList<>();
    private final List<TestGenerationProgressListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();
    
    private int currentPhaseIndex = -1;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    
    public enum PhaseType {
        INITIALIZATION("Initializing agents", 0, 5),
        PLANNING("Planning test strategy", 5, 20),
        CONTEXT_GATHERING("Gathering context", 20, 40),
        TEST_GENERATION("Generating tests", 40, 80),
        VALIDATION("Validating and fixing tests", 80, 95),
        COMPLETION("Finalizing results", 95, 100);
        
        private final String description;
        private final int startPercent;
        private final int endPercent;
        
        PhaseType(String description, int startPercent, int endPercent) {
            this.description = description;
            this.startPercent = startPercent;
            this.endPercent = endPercent;
        }
        
        public String getDescription() { return description; }
        public int getStartPercent() { return startPercent; }
        public int getEndPercent() { return endPercent; }
        public int getRange() { return endPercent - startPercent; }
    }
    
    public TestGenerationProgressTracker(@NotNull String sessionId) {
        this.sessionId = sessionId;
        this.startTime = LocalDateTime.now();
        
        // Initialize all phases
        for (PhaseType type : PhaseType.values()) {
            phases.add(new ProgressPhase(type));
        }
    }
    
    /**
     * Add a progress listener
     */
    public void addListener(@NotNull TestGenerationProgressListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove a progress listener
     */
    public void removeListener(@NotNull TestGenerationProgressListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Start a specific phase
     */
    public void startPhase(@NotNull PhaseType phaseType) {
        currentPhaseIndex = phaseType.ordinal();
        ProgressPhase phase = phases.get(currentPhaseIndex);
        phase.start();
        
        notifyProgress(phaseType.getDescription(), phaseType.getStartPercent());
        LOG.info("[" + sessionId + "] Started phase: " + phaseType.getDescription());
    }
    
    /**
     * Update progress within the current phase
     */
    public void updatePhaseProgress(@NotNull String message, double phaseProgressPercent) {
        if (currentPhaseIndex < 0 || currentPhaseIndex >= phases.size()) {
            LOG.warn("No active phase to update progress");
            return;
        }
        
        ProgressPhase currentPhase = phases.get(currentPhaseIndex);
        PhaseType phaseType = currentPhase.getType();
        
        // Calculate overall progress
        int phaseRange = phaseType.getRange();
        int overallProgress = phaseType.getStartPercent() + (int) (phaseRange * phaseProgressPercent / 100.0);
        
        currentPhase.updateProgress(message, phaseProgressPercent);
        notifyProgress(message, Math.min(overallProgress, 100));
    }
    
    /**
     * Complete the current phase
     */
    public void completePhase(@NotNull String message) {
        if (currentPhaseIndex < 0 || currentPhaseIndex >= phases.size()) {
            LOG.warn("No active phase to complete");
            return;
        }
        
        ProgressPhase currentPhase = phases.get(currentPhaseIndex);
        PhaseType phaseType = currentPhase.getType();
        
        currentPhase.complete(message);
        notifyProgress(message, phaseType.getEndPercent());
        LOG.info("[" + sessionId + "] Completed phase: " + phaseType.getDescription());
    }
    
    /**
     * Mark the entire process as complete
     */
    public void complete(@NotNull String finalMessage) {
        endTime = LocalDateTime.now();
        notifyProgress(finalMessage, 100);
        LOG.info("[" + sessionId + "] Test generation completed: " + finalMessage);
    }
    
    /**
     * Mark the process as failed
     */
    public void fail(@NotNull String errorMessage) {
        endTime = LocalDateTime.now();
        notifyProgress("Failed: " + errorMessage, 100);
        LOG.error("[" + sessionId + "] Test generation failed: " + errorMessage);
    }
    
    /**
     * Add metadata for tracking additional information
     */
    public void addMetadata(@NotNull String key, @NotNull Object value) {
        metadata.put(key, value);
    }
    
    /**
     * Get metadata value
     */
    public Object getMetadata(@NotNull String key) {
        return metadata.get(key);
    }
    
    /**
     * Get current phase information
     */
    public ProgressPhase getCurrentPhase() {
        if (currentPhaseIndex >= 0 && currentPhaseIndex < phases.size()) {
            return phases.get(currentPhaseIndex);
        }
        return null;
    }
    
    /**
     * Get all phases
     */
    @NotNull
    public List<ProgressPhase> getAllPhases() {
        return new ArrayList<>(phases);
    }
    
    /**
     * Get overall progress percentage
     */
    public int getOverallProgress() {
        if (currentPhaseIndex < 0) return 0;
        if (currentPhaseIndex >= phases.size()) return 100;
        
        ProgressPhase currentPhase = phases.get(currentPhaseIndex);
        PhaseType phaseType = currentPhase.getType();
        
        if (!currentPhase.isStarted()) return phaseType.getStartPercent();
        if (currentPhase.isCompleted()) return phaseType.getEndPercent();
        
        double phaseProgress = currentPhase.getProgressPercent();
        int phaseRange = phaseType.getRange();
        return phaseType.getStartPercent() + (int) (phaseRange * phaseProgress / 100.0);
    }
    
    /**
     * Get session duration in milliseconds
     */
    public long getDurationMillis() {
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        return java.time.Duration.between(startTime, end).toMillis();
    }
    
    /**
     * Generate detailed progress report
     */
    @NotNull
    public String generateProgressReport() {
        StringBuilder report = new StringBuilder();
        report.append("Test Generation Progress Report\n");
        report.append("================================\n");
        report.append("Session ID: ").append(sessionId).append("\n");
        report.append("Start Time: ").append(startTime).append("\n");
        if (endTime != null) {
            report.append("End Time: ").append(endTime).append("\n");
        }
        report.append("Duration: ").append(getDurationMillis()).append(" ms\n");
        report.append("Overall Progress: ").append(getOverallProgress()).append("%\n\n");
        
        report.append("Phase Details:\n");
        for (int i = 0; i < phases.size(); i++) {
            ProgressPhase phase = phases.get(i);
            report.append(String.format("%d. %s: %s (%.1f%%)\n", 
                i + 1, 
                phase.getType().getDescription(), 
                phase.getStatus(),
                phase.getProgressPercent()));
            
            if (phase.getLastMessage() != null && !phase.getLastMessage().isEmpty()) {
                report.append("   Last message: ").append(phase.getLastMessage()).append("\n");
            }
        }
        
        if (!metadata.isEmpty()) {
            report.append("\nMetadata:\n");
            metadata.forEach((key, value) -> 
                report.append("  ").append(key).append(": ").append(value).append("\n"));
        }
        
        return report.toString();
    }
    
    private void notifyProgress(@NotNull String message, int progressPercent) {
        TestGenerationProgress progress = new TestGenerationProgress(sessionId, message, progressPercent);
        
        for (TestGenerationProgressListener listener : listeners) {
            try {
                listener.onProgress(progress);
            } catch (Exception e) {
                LOG.warn("Progress listener failed for session: " + sessionId, e);
            }
        }
    }
    
    /**
     * Individual phase tracking
     */
    public static class ProgressPhase {
        private final PhaseType type;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String lastMessage = "";
        private double progressPercent = 0.0;
        private PhaseStatus status = PhaseStatus.PENDING;
        
        public enum PhaseStatus {
            PENDING("Pending"),
            RUNNING("Running"),
            COMPLETED("Completed"),
            FAILED("Failed");
            
            private final String displayName;
            
            PhaseStatus(String displayName) {
                this.displayName = displayName;
            }
            
            public String getDisplayName() { return displayName; }
        }
        
        public ProgressPhase(@NotNull PhaseType type) {
            this.type = type;
        }
        
        public void start() {
            this.startTime = LocalDateTime.now();
            this.status = PhaseStatus.RUNNING;
            this.progressPercent = 0.0;
        }
        
        public void updateProgress(@NotNull String message, double progressPercent) {
            this.lastMessage = message;
            this.progressPercent = Math.max(0.0, Math.min(100.0, progressPercent));
        }
        
        public void complete(@NotNull String message) {
            this.lastMessage = message;
            this.progressPercent = 100.0;
            this.status = PhaseStatus.COMPLETED;
            this.endTime = LocalDateTime.now();
        }
        
        public void fail(@NotNull String errorMessage) {
            this.lastMessage = errorMessage;
            this.status = PhaseStatus.FAILED;
            this.endTime = LocalDateTime.now();
        }
        
        // Getters
        @NotNull public PhaseType getType() { return type; }
        @NotNull public PhaseStatus getStatus() { return status; }
        @NotNull public String getLastMessage() { return lastMessage; }
        public double getProgressPercent() { return progressPercent; }
        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        
        public boolean isStarted() { return startTime != null; }
        public boolean isCompleted() { return status == PhaseStatus.COMPLETED; }
        public boolean isFailed() { return status == PhaseStatus.FAILED; }
        
        public long getDurationMillis() {
            if (startTime == null) return 0;
            LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
            return java.time.Duration.between(startTime, end).toMillis();
        }
    }
}