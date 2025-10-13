package com.zps.zest.testgen.evaluation;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Utility for validating test code using IntelliJ's CodeSmellDetector.
 * Extracted from AITestMergerAgent for reuse in metrics tracking.
 */
public class TestCodeValidator {
    private static final Logger LOG = Logger.getInstance(TestCodeValidator.class);

    /**
     * Count compilation errors in test code.
     * Uses IntelliJ's CodeSmellDetector to find all ERROR-level issues.
     *
     * @return Number of compilation errors, or -1 if validation failed/skipped
     */
    public static int countCompilationErrors(@NotNull Project project, @NotNull String testCode, @NotNull String className) {
        try {
            FileType javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java");

            LightVirtualFile virtualFile = new LightVirtualFile(
                    className + ".java",
                    javaFileType,
                    testCode
            );

            CompletableFuture<List<CodeSmellInfo>> future = new CompletableFuture<>();

            ProgressManager.getInstance().run(
                    new Task.Backgroundable(project, "Validating test code", false) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            try {
                                indicator.setText("Analyzing test code for compilation errors...");
                                indicator.setIndeterminate(true);

                                CodeSmellDetector detector = CodeSmellDetector.getInstance(project);
                                List<CodeSmellInfo> detectedIssues = detector.findCodeSmells(
                                        Arrays.asList(virtualFile)
                                ).stream()
                                 .filter(v -> v.getSeverity().equals(HighlightSeverity.ERROR))
                                 .toList();

                                future.complete(detectedIssues);
                            } catch (Exception e) {
                                LOG.warn("Error in code smell detection", e);
                                future.complete(Collections.emptyList());
                            }
                        }
                    }
            );

            // Wait for result with timeout
            List<CodeSmellInfo> errors = future.get(10, TimeUnit.SECONDS);
            return errors.size();

        } catch (Exception e) {
            LOG.warn("Error validating test code", e);
            return -1;  // Validation failed/skipped
        }
    }

    /**
     * Check if test code compiles successfully (no errors).
     *
     * @return true if no compilation errors, false otherwise
     */
    public static boolean testCompiles(@NotNull Project project, @NotNull String testCode, @NotNull String className) {
        int errorCount = countCompilationErrors(project, testCode, className);
        return errorCount == 0;
    }

    /**
     * Validation result with details
     */
    public static class ValidationResult {
        private final boolean compiles;
        private final int errorCount;

        public ValidationResult(boolean compiles, int errorCount) {
            this.compiles = compiles;
            this.errorCount = errorCount;
        }

        public boolean compiles() {
            return compiles;
        }

        public int getErrorCount() {
            return errorCount;
        }
    }

    /**
     * Get detailed validation result
     */
    public static ValidationResult validate(@NotNull Project project, @NotNull String testCode, @NotNull String className) {
        int errorCount = countCompilationErrors(project, testCode, className);
        boolean compiles = errorCount == 0;
        return new ValidationResult(compiles, errorCount);
    }
}
