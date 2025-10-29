package com.zps.zest.testgen.evaluation;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        ValidationResult result = validate(project, testCode, className);
        return result.getErrorCount();
    }

    /**
     * Check if test code compiles successfully (no errors).
     *
     * @return true if no compilation errors, false otherwise
     */
    public static boolean testCompiles(@NotNull Project project, @NotNull String testCode, @NotNull String className) {
        ValidationResult result = validate(project, testCode, className);
        return result.compiles();
    }

    /**
     * Validation result with details
     */
    public static class ValidationResult {
        private final boolean compiles;
        private final int errorCount;
        private final List<String> errors;

        public ValidationResult(boolean compiles, int errorCount, List<String> errors) {
            this.compiles = compiles;
            this.errorCount = errorCount;
            this.errors = errors != null ? errors : Collections.emptyList();
        }

        public boolean compiles() {
            return compiles;
        }

        public int getErrorCount() {
            return errorCount;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    /**
     * Get detailed validation result with error messages
     */
    public static ValidationResult validate(@NotNull Project project, @NotNull String testCode, @NotNull String className) {
        try {
            FileType javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java");
            VirtualFile testSourceRoot = com.zps.zest.testgen.util.TestSourceRootUtil.findBestTestSourceRootVirtualFile(project);

            LightVirtualFile virtualFile = new LightVirtualFile(
                    className + ".java",
                    javaFileType,
                    testCode
            );

            if (testSourceRoot != null) {
                virtualFile.setOriginalFile(testSourceRoot);
            }

            CompletableFuture<List<CodeSmellInfo>> future = new CompletableFuture<>();

            ProgressManager.getInstance().run(
                    new Task.Backgroundable(project, "Validating test code", false) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            try {
                                indicator.setText("Analyzing test code for errors...");
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

            List<CodeSmellInfo> errorInfos = future.get(300, TimeUnit.SECONDS);

            // Format error messages with Rust-like code context
            List<String> errorMessages = new java.util.ArrayList<>();
            String[] codeLines = testCode.split("\n");

            for (CodeSmellInfo issue : errorInfos) {
                int lineNum = issue.getStartLine();
                String errorMsg = issue.getDescription();

                // Build Rust-like error with code context
                StringBuilder errorBlock = new StringBuilder();
                errorBlock.append("Error at Line ").append(lineNum).append(":\n");

                // Show 2 lines before, error line, 2 lines after
                int startLine = Math.max(0, lineNum - 3);
                int endLine = Math.min(codeLines.length, lineNum + 2);

                for (int i = startLine; i < endLine; i++) {
                    String linePrefix = (i == lineNum - 1) ? " →  " : "    ";
                    errorBlock.append(String.format("%s%4d | %s\n", linePrefix, i + 1, codeLines[i]));
                }

                errorBlock.append("         |\n");
                errorBlock.append("         | ").append(errorMsg).append("\n");

                errorMessages.add(errorBlock.toString());
            }

            boolean compiles = errorInfos.isEmpty();
            return new ValidationResult(compiles, errorInfos.size(), errorMessages);

        } catch (Exception e) {
            LOG.warn("Error validating test code", e);
            return new ValidationResult(false, -1, Collections.singletonList("Validation failed: " + e.getMessage()));
        }
    }

}
