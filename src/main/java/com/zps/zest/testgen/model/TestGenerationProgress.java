package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;

public class TestGenerationProgress {
    private final String sessionId;
    private final String message;
    private final int progressPercent;
    private final LocalDateTime timestamp;
    
    public TestGenerationProgress(@NotNull String sessionId,
                                @NotNull String message,
                                int progressPercent) {
        this.sessionId = sessionId;
        this.message = message;
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
        this.timestamp = LocalDateTime.now();
    }
    
    @NotNull
    public String getSessionId() {
        return sessionId;
    }
    
    @NotNull
    public String getMessage() {
        return message;
    }
    
    public int getProgressPercent() {
        return progressPercent;
    }
    
    @NotNull
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public boolean isComplete() {
        return progressPercent >= 100;
    }
    
    @Override
    public String toString() {
        return "TestGenerationProgress{" +
               "sessionId='" + sessionId + '\'' +
               ", message='" + message + '\'' +
               ", progress=" + progressPercent + "%" +
               ", timestamp=" + timestamp +
               '}';
    }
}