package com.zps.zest.langchain4j.tools;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Minimal ripgrep wrapper for code search functionality.
 * Invokes the rg command-line tool for fast code searches.
 */
public class RipgrepCodeTool {
    private static final Logger LOG = Logger.getInstance(RipgrepCodeTool.class);
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_RESULTS = 100;

    private final Project project;
    private final Set<String> relatedFiles;
    private final List<String> usagePatterns;

    public RipgrepCodeTool(@NotNull Project project, Set<String> relatedFiles, List<String> usagePatterns) {
        this.project = project;
        this.relatedFiles = relatedFiles != null ? relatedFiles : new HashSet<>();
        this.usagePatterns = usagePatterns != null ? usagePatterns : new ArrayList<>();
    }

    /**
     * Search code using ripgrep with context lines
     */
    public String searchCode(String query, String filePattern, String excludePattern,
                            int beforeLines, int afterLines, boolean multiline) {
        if (!isRipgrepAvailable()) {
            return "❌ Ripgrep not available. Install with: choco install ripgrep (Windows), brew install ripgrep (Mac), apt install ripgrep (Linux)";
        }

        String basePath = project.getBasePath();
        if (basePath == null) {
            return "❌ Project base path not available";
        }

        List<String> command = buildSearchCommand(query, filePattern, excludePattern, beforeLines, afterLines, multiline, basePath);
        return executeCommand(command, basePath);
    }

    /**
     * Find files matching a pattern
     */
    public String findFiles(String pattern) {
        if (!isRipgrepAvailable()) {
            return "❌ Ripgrep not available";
        }

        String basePath = project.getBasePath();
        if (basePath == null) {
            return "❌ Project base path not available";
        }

        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--files");
        if (pattern != null && !pattern.isEmpty()) {
            command.add("-g");
            command.add(pattern);
        }
        command.add(basePath);

        return executeCommand(command, basePath);
    }

    private List<String> buildSearchCommand(String query, String filePattern, String excludePattern,
                                            int beforeLines, int afterLines, boolean multiline, String basePath) {
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--no-heading");
        command.add("--with-filename");
        command.add("--line-number");
        command.add("-M");
        command.add("500"); // Max line length

        if (beforeLines > 0) {
            command.add("-B");
            command.add(String.valueOf(beforeLines));
        }
        if (afterLines > 0) {
            command.add("-A");
            command.add(String.valueOf(afterLines));
        }
        if (multiline) {
            command.add("-U"); // Multiline mode
        }
        if (filePattern != null && !filePattern.isEmpty()) {
            command.add("-g");
            command.add(filePattern);
        }
        if (excludePattern != null && !excludePattern.isEmpty()) {
            command.add("-g");
            command.add("!" + excludePattern);
        }

        // Default excludes
        command.add("-g");
        command.add("!node_modules/**");
        command.add("-g");
        command.add("!.git/**");
        command.add("-g");
        command.add("!build/**");
        command.add("-g");
        command.add("!target/**");

        command.add(query);
        command.add(basePath);

        return command;
    }

    private String executeCommand(List<String> command, String workingDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            int lineCount = 0;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < MAX_RESULTS) {
                    output.append(line).append("\n");
                    lineCount++;
                }
            }

            boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return output + "\n⚠️ Search timed out after " + TIMEOUT_SECONDS + " seconds";
            }

            if (output.length() == 0) {
                return "No matches found";
            }

            if (lineCount >= MAX_RESULTS) {
                output.append("\n⚠️ Results truncated at ").append(MAX_RESULTS).append(" lines");
            }

            return output.toString();

        } catch (Exception e) {
            LOG.warn("Ripgrep command failed", e);
            return "❌ Search failed: " + e.getMessage();
        }
    }

    private boolean isRipgrepAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("rg", "--version");
            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            return completed && process.exitValue() == 0;
        } catch (Exception e) {
            LOG.debug("Ripgrep not available: " + e.getMessage());
            return false;
        }
    }

    public Set<String> getRelatedFiles() {
        return relatedFiles;
    }

    public List<String> getUsagePatterns() {
        return usagePatterns;
    }
}
