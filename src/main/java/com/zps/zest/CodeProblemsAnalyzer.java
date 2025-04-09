package com.zps.zest;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A tool for retrieving code problems from IntelliJ's daemon analyzer.
 * Supports analyzing individual files, directories, or the entire project.
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
     *
     * @param scope The scope of analysis ("current_file", "directory", or "project")
     * @param path The path to analyze (file path or directory path)
     * @return A formatted report of code problems
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
            switch (scope.toLowerCase()) {
                case "current_file":
                    // Analyze a single file
                    PsiFile file = findFileByPath(path);
                    if (file == null) {
                        return Collections.singletonList(new CodeProblem(
                                "File not found",
                                "Could not find file: " + path,
                                "N/A",
                                -1,
                                ProblemSeverity.ERROR
                        ));
                    }
                    results.addAll(analyzeFile(file));
                    break;

                case "directory":
                    // Analyze files in a directory
                    VirtualFile directory = findDirectoryByPath(path);
                    if (directory == null || !directory.isDirectory()) {
                        return Collections.singletonList(new CodeProblem(
                                "Directory not found",
                                "Could not find directory: " + path,
                                "N/A",
                                -1,
                                ProblemSeverity.ERROR
                        ));
                    }
                    results.addAll(analyzeDirectory(directory));
                    break;

                case "project":
                    // Analyze all files in the project
                    results.addAll(analyzeProject());
                    break;

                default:
                    return Collections.singletonList(new CodeProblem(
                            "Invalid scope",
                            "Scope must be one of: current_file, directory, project",
                            "N/A",
                            -1,
                            ProblemSeverity.ERROR
                    ));
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
     * Analyzes a single file for problems.
     */
    private List<CodeProblem> analyzeFile(PsiFile file) {
        List<CodeProblem> results = new ArrayList<>();

        // Get document for the file
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            results.add(new CodeProblem(
                    "Document not available",
                    "Could not get document for file: " + file.getName(),
                    file.getName(),
                    -1,
                    ProblemSeverity.ERROR
            ));
            return results;
        }

        // Get highlight info for the entire file
        List<HighlightInfo> highlights = DaemonCodeAnalyzerImpl.getHighlights(
                document, HighlightSeverity.ERROR, project);

        if (highlights == null || highlights.isEmpty()) {
            return results;
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
    }

    /**
     * Analyzes all files in a directory for problems.
     */
    private List<CodeProblem> analyzeDirectory(VirtualFile directory) {
        List<CodeProblem> results = new ArrayList<>();

        // Process each child file in the directory
        for (VirtualFile child : directory.getChildren()) {
            if (results.size() >= maxResults) {
                break;
            }

            if (child.isDirectory()) {
                // Recursively analyze subdirectories
                results.addAll(analyzeDirectory(child));
            } else if (isAnalyzableFile(child)) {
                // Analyze individual file
                PsiFile psiFile = PsiManager.getInstance(project).findFile(child);
                if (psiFile != null) {
                    results.addAll(analyzeFile(psiFile));
                }
            }
        }

        return results;
    }

    /**
     * Analyzes all files in the project for problems.
     */
    private List<CodeProblem> analyzeProject() {
        List<CodeProblem> results = new ArrayList<>();

        // Get all Java files in the project
        Collection<VirtualFile> javaFiles = FilenameIndex.getAllFilesByExt(
                project, "java", GlobalSearchScope.projectScope(project));

        // Analyze each Java file
        for (VirtualFile file : javaFiles) {
            if (results.size() >= maxResults) {
                break;
            }

            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                results.addAll(analyzeFile(psiFile));
            }
        }

        return results;
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
     * Finds a directory by its path.
     */
    private VirtualFile findDirectoryByPath(String path) {
        // Try as absolute path
        VirtualFile directory = LocalFileSystem.getInstance().findFileByPath(path);
        if (directory != null && directory.exists() && directory.isDirectory()) {
            return directory;
        }

        // Try as relative path from project root
        String projectBasePath = project.getBasePath();
        if (projectBasePath != null) {
            String absolutePath = new File(projectBasePath, path).getAbsolutePath();
            directory = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (directory != null && directory.exists() && directory.isDirectory()) {
                return directory;
            }
        }

        return null;
    }

    /**
     * Checks if a file should be analyzed for problems.
     */
    private boolean isAnalyzableFile(VirtualFile file) {
        String extension = file.getExtension();
        // For now, only analyze Java files
        return "java".equalsIgnoreCase(extension);
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