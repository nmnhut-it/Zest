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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Validates Java code using IntelliJ's CodeSmellDetector.
 * Detects real compilation errors without running the compiler.
 *
 * Note: Uses LightVirtualFile which may not resolve all imports.
 * Best for syntax checking; some semantic errors may be missed or false positives.
 */
public class TestCodeValidator {
    private static final Logger LOG = Logger.getInstance(TestCodeValidator.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 600;  // 10 minutes

    /**
     * Count compilation errors in code.
     * Uses IntelliJ's CodeSmellDetector to find ERROR-level issues.
     *
     * @return Number of compilation errors, or -1 if validation failed
     */
    public static int countCompilationErrors(@NotNull Project project, @NotNull String code, @NotNull String className) {
        ValidationResult result = validate(project, code, className);
        return result.isSuccess() ? result.getErrorCount() : -1;
    }

    /**
     * Check if code compiles without errors.
     */
    public static boolean codeCompiles(@NotNull Project project, @NotNull String code, @NotNull String className) {
        int errorCount = countCompilationErrors(project, code, className);
        return errorCount == 0;
    }

    /**
     * Validate code and return detailed results with error messages.
     * Shows progress in IntelliJ status bar: "Zest: check compile errors for [className]"
     */
    public static ValidationResult validate(@NotNull Project project, @NotNull String code, @NotNull String className) {
        try {
            String baseName = className.endsWith(".java")
                    ? className.substring(0, className.length() - 5)
                    : className;
            String fileName = baseName + ".java";
            FileType javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java");
            LightVirtualFile virtualFile = new LightVirtualFile(fileName, javaFileType, code);

            CompletableFuture<List<CodeSmellInfo>> future = new CompletableFuture<>();

            ProgressManager.getInstance().run(
                    new Task.Backgroundable(project, "Zest: check compile errors for " + baseName, false) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            try {
                                indicator.setIndeterminate(true);

                                CodeSmellDetector detector = CodeSmellDetector.getInstance(project);
                                List<CodeSmellInfo> allIssues = detector.findCodeSmells(List.of(virtualFile));

                                // Filter to only ERROR severity
                                List<CodeSmellInfo> errors = allIssues.stream()
                                        .filter(info -> info.getSeverity().equals(HighlightSeverity.ERROR))
                                        .toList();

                                future.complete(errors);
                            } catch (Exception e) {
                                LOG.warn("Error in code smell detection", e);
                                future.completeExceptionally(e);
                            }
                        }
                    }
            );

            List<CodeSmellInfo> errors = future.get(VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Convert to our result format
            List<CompilationError> compilationErrors = new ArrayList<>();
            for (CodeSmellInfo info : errors) {
                compilationErrors.add(new CompilationError(
                        info.getDescription(),
                        info.getStartLine(),
                        info.getStartLine()  // CodeSmellInfo doesn't expose end line
                ));
            }

            return ValidationResult.success(compilationErrors);

        } catch (TimeoutException e) {
            LOG.warn("Validation timed out after " + VALIDATION_TIMEOUT_SECONDS + " seconds");
            return ValidationResult.error("Validation timed out after " + VALIDATION_TIMEOUT_SECONDS +
                    "s. Try with smaller code or ensure IntelliJ is not busy indexing.");
        } catch (Exception e) {
            LOG.warn("Error validating code: " + e.getMessage(), e);
            return ValidationResult.error("Validation failed: " + e.getMessage());
        }
    }

    /**
     * Single compilation error with location and message.
     */
    public static class CompilationError {
        private final String message;
        private final int startLine;
        private final int endLine;

        public CompilationError(String message, int startLine, int endLine) {
            this.message = message;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public String getMessage() { return message; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
    }

    /**
     * Validation result with errors list.
     */
    public static class ValidationResult {
        private final boolean success;
        private final List<CompilationError> errors;
        private final String errorMessage;

        private ValidationResult(boolean success, List<CompilationError> errors, String errorMessage) {
            this.success = success;
            this.errors = errors != null ? errors : Collections.emptyList();
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success(List<CompilationError> errors) {
            return new ValidationResult(true, errors, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, null, message);
        }

        public boolean isSuccess() { return success; }
        public boolean compiles() { return success && errors.isEmpty(); }
        public int getErrorCount() { return errors.size(); }
        public List<CompilationError> getErrors() { return errors; }
        public String getErrorMessage() { return errorMessage; }

        public String toMarkdown() {
            if (!success) {
                return "❌ Validation failed: " + errorMessage;
            }

            if (errors.isEmpty()) {
                return "✅ **Code compiles successfully** - no errors found.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Compilation Errors\n\n");
            sb.append("Found **").append(errors.size()).append("** error(s):\n\n");

            for (int i = 0; i < errors.size(); i++) {
                CompilationError error = errors.get(i);
                sb.append(i + 1).append(". **Line ").append(error.getStartLine());
                if (error.getEndLine() != error.getStartLine()) {
                    sb.append("-").append(error.getEndLine());
                }
                sb.append("**: ").append(error.getMessage()).append("\n");
            }

            return sb.toString();
        }
    }
}
