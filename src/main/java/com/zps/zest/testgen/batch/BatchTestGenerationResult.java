package com.zps.zest.testgen.batch;

import com.zps.zest.testgen.model.TestGenerationResult;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Results from batch test generation across multiple files.
 * Tracks per-file results and aggregate metrics.
 */
public class BatchTestGenerationResult {

    private final List<FileResult> fileResults;
    private final long startTimeMs;
    private long endTimeMs;

    public BatchTestGenerationResult() {
        this.fileResults = new ArrayList<>();
        this.startTimeMs = System.currentTimeMillis();
    }

    public void addFileResult(@NotNull FileResult result) {
        fileResults.add(result);
    }

    public void markComplete() {
        this.endTimeMs = System.currentTimeMillis();
    }

    public long getTotalDurationMs() {
        return endTimeMs > 0 ? endTimeMs - startTimeMs : System.currentTimeMillis() - startTimeMs;
    }

    public int getSuccessCount() {
        return (int) fileResults.stream().filter(FileResult::isSuccess).count();
    }

    public int getFailureCount() {
        return (int) fileResults.stream().filter(r -> !r.isSuccess()).count();
    }

    public int getTotalFiles() {
        return fileResults.size();
    }

    public int getTotalMethodsProcessed() {
        return fileResults.stream().mapToInt(FileResult::getMethodCount).sum();
    }

    public int getTotalTestsGenerated() {
        return fileResults.stream().mapToInt(FileResult::getTestCount).sum();
    }

    public double getAverageDurationMs() {
        return fileResults.isEmpty() ? 0.0 :
            fileResults.stream().mapToLong(FileResult::getDurationMs).average().orElse(0.0);
    }

    public List<FileResult> getFileResults() {
        return new ArrayList<>(fileResults);
    }

    public List<FileResult> getSuccessfulResults() {
        return fileResults.stream().filter(FileResult::isSuccess).collect(Collectors.toList());
    }

    public List<FileResult> getFailedResults() {
        return fileResults.stream().filter(r -> !r.isSuccess()).collect(Collectors.toList());
    }

    /**
     * Export results to CSV format for analysis.
     */
    @NotNull
    public String toCSV() {
        StringBuilder csv = new StringBuilder();
        csv.append("File,Methods,Tests,Status,Duration(s),Error\n");

        for (FileResult result : fileResults) {
            csv.append("\"").append(result.getFileName()).append("\",");
            csv.append(result.getMethodCount()).append(",");
            csv.append(result.getTestCount()).append(",");
            csv.append(result.isSuccess() ? "SUCCESS" : "FAILED").append(",");
            csv.append(String.format("%.2f", result.getDurationMs() / 1000.0)).append(",");
            csv.append("\"").append(result.getErrorMessage() != null ? result.getErrorMessage() : "").append("\"\n");
        }

        return csv.toString();
    }

    /**
     * Get summary statistics as formatted string.
     */
    @NotNull
    public String getSummary() {
        return String.format(
            "Batch Generation Complete\n" +
            "Files: %d total (%d succeeded, %d failed)\n" +
            "Methods: %d processed\n" +
            "Tests: %d generated\n" +
            "Time: %.1f seconds (avg %.1f sec/file)",
            getTotalFiles(), getSuccessCount(), getFailureCount(),
            getTotalMethodsProcessed(),
            getTotalTestsGenerated(),
            getTotalDurationMs() / 1000.0,
            getAverageDurationMs() / 1000.0
        );
    }

    /**
     * Result for a single file.
     */
    public static class FileResult {
        private final String filePath;
        private final String fileName;
        private final int methodCount;
        private final int testCount;
        private final TestGenerationState finalState;
        private final String errorMessage;
        private final long durationMs;
        private final TestGenerationResult result;
        private final String sessionId;

        public FileResult(@NotNull String filePath,
                         @NotNull String fileName,
                         int methodCount,
                         int testCount,
                         @NotNull TestGenerationState finalState,
                         @Nullable String errorMessage,
                         long durationMs,
                         @Nullable TestGenerationResult result,
                         @NotNull String sessionId) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.methodCount = methodCount;
            this.testCount = testCount;
            this.finalState = finalState;
            this.errorMessage = errorMessage;
            this.durationMs = durationMs;
            this.result = result;
            this.sessionId = sessionId;
        }

        public boolean isSuccess() {
            return finalState == TestGenerationState.COMPLETED && errorMessage == null;
        }

        public String getFilePath() { return filePath; }
        public String getFileName() { return fileName; }
        public int getMethodCount() { return methodCount; }
        public int getTestCount() { return testCount; }
        public TestGenerationState getFinalState() { return finalState; }
        public String getErrorMessage() { return errorMessage; }
        public long getDurationMs() { return durationMs; }
        public TestGenerationResult getResult() { return result; }
        public String getSessionId() { return sessionId; }
    }
}
