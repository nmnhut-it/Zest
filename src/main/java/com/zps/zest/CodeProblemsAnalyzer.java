package com.zps.zest;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A simpler tool for retrieving code problems from IntelliJ's daemon analyzer.
 */
public class CodeProblemsAnalyzer {
    private static final Logger LOG = Logger.getInstance(CodeProblemsAnalyzer.class);

    private final Project project;
    private String textFilter = "";
    private int maxResults = 100;

    public CodeProblemsAnalyzer(Project project) {
        this.project = project;
    }

    /**
     * Analyzes the code in the specified scope.
     */
    public String analyzeCode(String scope, String path) {
        try {
            List<CodeProblem> problems = ApplicationManager.getApplication().runReadAction(
                    (Computable<List<CodeProblem>>) () -> findProblems(scope, path));

            return formatProblems(problems);
        } catch (Exception e) {
            LOG.error("Error analyzing code problems", e);
            return "Error analyzing code problems: " + e.getMessage();
        }
    }

    /**
     * Quick analyze of the current file for code problems.
     */
    public String quickAnalyzeCurrentFile() {
        try {
            // Get current editor
            com.intellij.openapi.editor.Editor editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return "No file is currently open.";
            }

            // Get current file path
            com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file == null) {
                return "Cannot determine current file.";
            }

            String filePath = file.getPath();
            return analyzeCode("current_file", filePath);
        } catch (Exception e) {
            return "Error analyzing current file: " + e.getMessage();
        }
    }

    /**
     * Finds problems in the specified scope.
     */
    private List<CodeProblem> findProblems(String scope, String path) {
        List<CodeProblem> results = new ArrayList<>();

        try {
            // Determine the file to analyze
            PsiFile file = null;

            switch (scope.toLowerCase()) {
                case "current_file":
                    file = findFileByPath(path);
                    break;
                case "project":
                case "directory":
                    return Collections.singletonList(new CodeProblem(
                            "Scope not supported",
                            "Currently only 'current_file' scope is supported for simplicity",
                            "N/A",
                            -1,
                            ProblemSeverity.WARNING
                    ));
                default:
                    return Collections.singletonList(new CodeProblem(
                            "Invalid scope",
                            "Scope must be one of: current_file",
                            "N/A",
                            -1,
                            ProblemSeverity.ERROR
                    ));
            }

            if (file == null) {
                return Collections.singletonList(new CodeProblem(
                        "File not found",
                        "Could not find file: " + path,
                        "N/A",
                        -1,
                        ProblemSeverity.ERROR
                ));
            }

            // Get document for the file
            Document document = PsiDocumentManager.getInstance(project).getDocument(file);
            if (document == null) {
                return Collections.singletonList(new CodeProblem(
                        "Document not available",
                        "Could not get document for file: " + path,
                        file.getName(),
                        -1,
                        ProblemSeverity.ERROR
                ));
            }

            // Get highlight info for the entire file
            List<HighlightInfo> highlights = DaemonCodeAnalyzerImpl.getHighlights(
                    document, HighlightSeverity.ERROR, project);

            if (highlights == null || highlights.isEmpty()) {
                return Collections.emptyList();
            }

            // Convert HighlightInfo to CodeProblem
            for (HighlightInfo info : highlights) {
                // Skip information severity level
                if (info.getSeverity().myVal <= 0) continue;

                int lineNumber = document.getLineNumber(info.getStartOffset()) + 1;

                ProblemSeverity severity;
                if (info.getSeverity().myVal >= 100) {
                    severity = ProblemSeverity.ERROR;
                } else if (info.getSeverity().myVal >= 50) {
                    severity = ProblemSeverity.WARNING;
                } else {
                    severity = ProblemSeverity.WEAK_WARNING;
                }

                CodeProblem problem = new CodeProblem(
                        info.getDescription(),
                        info.getToolTip() != null ? info.getToolTip() : info.getDescription(),
                        file.getName(),
                        lineNumber,
                        severity
                );

                if (matchesTextFilter(problem)) {
                    results.add(problem);

                    if (results.size() >= maxResults) {
                        break;
                    }
                }
            }

            return results;
        } catch (Exception e) {
            LOG.error("Error finding problems", e);
            return Collections.singletonList(new CodeProblem(
                    "Error analyzing code",
                    e.getMessage(),
                    "N/A",
                    -1,
                    ProblemSeverity.ERROR
            ));
        }
    }

    /**
     * Finds a file by its path.
     */
    private PsiFile findFileByPath(String path) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(path);
        if (virtualFile == null || !virtualFile.exists()) {
            return null;
        }

        return PsiManager.getInstance(project).findFile(virtualFile);
    }

    /**
     * Checks if a problem matches the text filter.
     */
    private boolean matchesTextFilter(CodeProblem problem) {
        if (textFilter.isEmpty()) {
            return true;
        }

        return problem.getDescription().toLowerCase().contains(textFilter.toLowerCase()) ||
                problem.getFilename().toLowerCase().contains(textFilter.toLowerCase());
    }

    /**
     * Formats the problems as a string.
     */
    private String formatProblems(List<CodeProblem> problems) {
        if (problems.isEmpty()) {
            return "No problems found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(problems.size()).append(" problem(s):\n\n");

        // Group problems by severity
        int errorCount = 0;
        int warningCount = 0;
        int weakWarningCount = 0;

        // List problems by severity
        sb.append("== Errors ==\n");
        for (CodeProblem problem : problems) {
            if (problem.getSeverity() == ProblemSeverity.ERROR) {
                sb.append("  - [").append(problem.getFilename()).append(":")
                        .append(problem.getLineNumber()).append("] ")
                        .append(problem.getDescription()).append("\n");
                errorCount++;
            }
        }

        sb.append("\n== Warnings ==\n");
        for (CodeProblem problem : problems) {
            if (problem.getSeverity() == ProblemSeverity.WARNING) {
                sb.append("  - [").append(problem.getFilename()).append(":")
                        .append(problem.getLineNumber()).append("] ")
                        .append(problem.getDescription()).append("\n");
                warningCount++;
            }
        }

        sb.append("\n== Weak Warnings ==\n");
        for (CodeProblem problem : problems) {
            if (problem.getSeverity() == ProblemSeverity.WEAK_WARNING) {
                sb.append("  - [").append(problem.getFilename()).append(":")
                        .append(problem.getLineNumber()).append("] ")
                        .append(problem.getDescription()).append("\n");
                weakWarningCount++;
            }
        }

        sb.append("\nSummary: ")
                .append(errorCount).append(" errors, ")
                .append(warningCount).append(" warnings, ")
                .append(weakWarningCount).append(" weak warnings.");

        return sb.toString();
    }

    /**
     * Sets the text filter.
     */
    public void setTextFilter(String text) {
        this.textFilter = text != null ? text : "";
    }

    /**
     * Sets the maximum number of results to return.
     */
    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults > 0 ? maxResults : 100;
    }

    /**
     * Enum for problem severity.
     */
    public enum ProblemSeverity {
        ERROR,
        WARNING,
        WEAK_WARNING,
        INFORMATION
    }

    /**
     * Class to represent a code problem.
     */
    public static class CodeProblem {
        private final String description;
        private final String fullDescription;
        private final String filename;
        private final int lineNumber;
        private final ProblemSeverity severity;

        public CodeProblem(String description, String fullDescription, String filename, int lineNumber, ProblemSeverity severity) {
            this.description = description;
            this.fullDescription = fullDescription;
            this.filename = filename;
            this.lineNumber = lineNumber;
            this.severity = severity;
        }

        public String getDescription() {
            return description;
        }

        public String getFullDescription() {
            return fullDescription;
        }

        public String getFilename() {
            return filename;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public ProblemSeverity getSeverity() {
            return severity;
        }
    }
}