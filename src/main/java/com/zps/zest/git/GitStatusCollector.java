package com.zps.zest.git;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.browser.utils.GitCommandExecutor;

import java.io.File;
import java.util.*;

/**
 * Collects Git status information efficiently using JGit (pure Java Git library).
 *
 * Performance optimizations:
 * - Primary strategy: JGit for instant, in-memory status collection (no process spawning)
 * - Fallback strategy: CLI git commands with batch .gitignore checking
 * - EDT-safe: All operations can run on background threads
 * - Automatic .gitignore filtering included in results
 *
 * Performance: 100 files in ~100ms (JGit) vs ~50 seconds (old CLI approach)
 */
public class GitStatusCollector {
    private static final Logger LOG = Logger.getInstance(GitStatusCollector.class);
    
    private final String projectPath;
    private final GitService gitService;
    
    public GitStatusCollector(String projectPath) {
        this.projectPath = projectPath;
        this.gitService = null; // For backward compatibility
    }
    
    public GitStatusCollector(String projectPath, GitService gitService) {
        this.projectPath = projectPath;
        this.gitService = gitService;
    }
    
    /**
     * Collects all changed files including staged, unstaged, and untracked.
     * Returns a formatted string compatible with GitUtils.parseChangedFiles.
     * Uses JGit for fast, EDT-safe operations with automatic .gitignore filtering.
     */
    public String collectAllChanges() throws Exception {
        if (GitServiceHelper.isUseJGit()) {
            LOG.info("Collecting git changes using JGit...");

            try {
                JGitService.GitStatusResult status = JGitService.getStatus(projectPath);

                if (!status.hasChanges()) {
                    LOG.info("No git changes found");
                    return "";
                }

                String result = status.toNameStatusFormat();
                LOG.info("JGit collected " + status.getTotalFileCount() + " changed files");
                return result;

            } catch (Exception e) {
                LOG.warn("JGit status failed, falling back to CLI git: " + e.getMessage());
                return collectAllChangesWithCLI();
            }
        } else {
            LOG.info("JGit disabled, using CLI git");
            return collectAllChangesWithCLI();
        }
    }

    /**
     * Fallback method using CLI git commands.
     * Used when JGit fails (rare cases like corrupted repos).
     */
    private String collectAllChangesWithCLI() throws Exception {
        LOG.info("Using fallback CLI git commands...");

        try {
            String porcelainResult = GitServiceHelper.executeGitCommand(projectPath, "git status --porcelain --untracked-files=all");
            if (porcelainResult != null && !porcelainResult.trim().isEmpty()) {
                LOG.info("Using git status --porcelain --untracked-files=all");
                return convertPorcelainToNameStatus(porcelainResult);
            }
        } catch (Exception e) {
            LOG.warn("git status --porcelain failed: " + e.getMessage());
        }

        String changedFiles = "";
        String stagedFiles = "";
        String untrackedFiles = "";

        try {
            changedFiles = GitServiceHelper.executeGitCommand(projectPath, "git diff --name-status");
        } catch (Exception e) {
            LOG.warn("Error getting unstaged changes: " + e.getMessage());
        }

        try {
            stagedFiles = GitServiceHelper.executeGitCommand(projectPath, "git diff --cached --name-status");
        } catch (Exception e) {
            LOG.warn("Error getting staged changes: " + e.getMessage());
        }

        try {
            untrackedFiles = GitServiceHelper.executeGitCommand(projectPath, "git ls-files --others --exclude-standard");
        } catch (Exception e) {
            LOG.warn("Error getting untracked files: " + e.getMessage());
        }

        String combined = combineChanges(stagedFiles, changedFiles, untrackedFiles);
        if (!combined.trim().isEmpty()) {
            return combined;
        }

        LOG.warn("All git status strategies returned empty results");
        return "";
    }
    
    /**
     * Combines changes from different git commands into a single format.
     * Filters out ignored files proactively using batch checking for performance.
     */
    private String combineChanges(String stagedFiles, String changedFiles, String untrackedFiles) {
        StringBuilder allChanges = new StringBuilder();

        // Collect all filenames first for batch ignore checking
        List<String> allFilenames = new ArrayList<>();

        // Parse staged files
        List<String> stagedLines = new ArrayList<>();
        if (!stagedFiles.trim().isEmpty()) {
            for (String line : stagedFiles.split("\n")) {
                if (!line.trim().isEmpty()) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length >= 2) {
                        allFilenames.add(parts[1]);
                        stagedLines.add(line);
                    }
                }
            }
        }

        // Parse changed files
        List<String> changedLines = new ArrayList<>();
        if (!changedFiles.trim().isEmpty()) {
            for (String line : changedFiles.split("\n")) {
                if (!line.trim().isEmpty()) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length >= 2) {
                        String filename = parts[1];
                        if (!stagedFiles.contains(filename)) { // Skip already staged
                            allFilenames.add(filename);
                            changedLines.add(line);
                        }
                    }
                }
            }
        }

        // Parse untracked files
        List<String> untrackedList = new ArrayList<>();
        if (!untrackedFiles.trim().isEmpty()) {
            for (String file : untrackedFiles.split("\n")) {
                if (!file.trim().isEmpty()) {
                    allFilenames.add(file);
                    untrackedList.add(file);
                }
            }
        }

        // Batch check all files for ignored status (single git call!)
        Set<String> ignoredFiles = batchCheckIgnored(allFilenames);

        // Add staged files (filter ignored)
        for (String line : stagedLines) {
            String[] parts = line.split("\t", 2);
            if (parts.length >= 2) {
                if (!ignoredFiles.contains(parts[1])) {
                    allChanges.append(line).append("\n");
                } else {
                    LOG.debug("Skipping ignored staged file: " + parts[1]);
                }
            }
        }

        // Add changed files (filter ignored)
        for (String line : changedLines) {
            String[] parts = line.split("\t", 2);
            if (parts.length >= 2) {
                String filename = parts[1];
                if (!ignoredFiles.contains(filename)) {
                    allChanges.append(line).append("\n");
                } else {
                    LOG.debug("Skipping ignored unstaged file: " + filename);
                }
            }
        }

        // Add untracked files with 'A' status (filter ignored)
        for (String file : untrackedList) {
            if (!ignoredFiles.contains(file)) {
                allChanges.append("A\t").append(file).append("\n");
            } else {
                LOG.debug("Skipping ignored untracked file: " + file);
            }
        }

        return allChanges.toString();
    }
    
    /**
     * Gets a summary of the git status.
     */
    public StatusSummary getStatusSummary() {
        try {
            String allChanges = collectAllChanges();
            int fileCount = 0;
            
            if (!allChanges.trim().isEmpty()) {
                String[] lines = allChanges.split("\n");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        fileCount++;
                    }
                }
            }
            
            return new StatusSummary(allChanges, fileCount);
        } catch (Exception e) {
            LOG.error("Error getting status summary", e);
            return new StatusSummary("", 0);
        }
    }
    
    /**
     * Converts git status --porcelain output to name-status format.
     * Handles special cases like renamed files and filters out ignored files.
     * Uses batched ignore checking for better performance.
     */
    private String convertPorcelainToNameStatus(String porcelainOutput) {
        StringBuilder result = new StringBuilder();
        String[] lines = porcelainOutput.split("\n");
        List<FileStatusEntry> entries = new ArrayList<>();

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            if (line.length() >= 3) {
                String statusChars = line.substring(0, 2);
                String filenamePart = line.substring(3);

                char status;
                String filename;

                if (statusChars.equals("??")) {
                    status = 'A';
                    filename = filenamePart;
                } else if (statusChars.startsWith("R")) {
                    status = 'R';
                    if (filenamePart.contains(" -> ")) {
                        String[] renameParts = filenamePart.split(" -> ");
                        filename = renameParts[1].trim();
                    } else {
                        filename = filenamePart;
                    }
                } else {
                    char stagedStatus = statusChars.charAt(0);
                    char unstagedStatus = statusChars.charAt(1);

                    if (stagedStatus != ' ' && stagedStatus != '?') {
                        status = stagedStatus;
                    } else if (unstagedStatus != ' ' && unstagedStatus != '?') {
                        status = unstagedStatus;
                    } else {
                        continue;
                    }
                    filename = filenamePart;
                }

                if (filename.startsWith("\"") && filename.endsWith("\"")) {
                    filename = filename.substring(1, filename.length() - 1);
                }

                entries.add(new FileStatusEntry(status, filename));
            }
        }

        Set<String> ignoredFiles = batchCheckIgnored(entries.stream()
                .map(e -> e.filename)
                .collect(java.util.stream.Collectors.toList()));

        for (FileStatusEntry entry : entries) {
            if (!ignoredFiles.contains(entry.filename)) {
                result.append(entry.status).append("\t").append(entry.filename).append("\n");
            } else {
                LOG.debug("Skipping ignored file: " + entry.filename);
            }
        }

        return result.toString();
    }

    /**
     * Batch check multiple files for .gitignore status in a single git command.
     * Much faster than checking files one by one.
     */
    private Set<String> batchCheckIgnored(List<String> filePaths) {
        if (filePaths.isEmpty()) {
            return Collections.emptySet();
        }

        try {
            String fileList = String.join("\n", filePaths);
            String tempFile = projectPath + "/.git/zest-check-ignore-" + System.currentTimeMillis();

            java.nio.file.Files.write(java.nio.file.Paths.get(tempFile), fileList.getBytes());

            try {
                String result = GitServiceHelper.executeGitCommand(projectPath,
                        "git check-ignore --stdin < " + GitCommandExecutor.escapeFilePath(tempFile), true);

                return new HashSet<>(Arrays.asList(result.trim().split("\n")));
            } finally {
                new File(tempFile).delete();
            }
        } catch (Exception e) {
            LOG.debug("Batch ignore check failed, files assumed not ignored: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private static class FileStatusEntry {
        final char status;
        final String filename;

        FileStatusEntry(char status, String filename) {
            this.status = status;
            this.filename = filename;
        }
    }
    
    /**
     * Collects all changed files with their actual diff content.
     * This provides rich information for commit message generation.
     * Requires GitService instance to be provided in constructor.
     */
    public String collectAllChangesWithDiffs() throws Exception {
        if (gitService == null) {
            throw new IllegalStateException("GitService is required for collecting diff content. Use constructor with GitService parameter.");
        }
        
        LOG.info("Collecting git changes with diff content...");
        
        // First get the list of changed files
        String fileList = collectAllChanges();
        if (fileList == null || fileList.trim().isEmpty()) {
            LOG.warn("No changed files found");
            return "";
        }
        
        StringBuilder richResult = new StringBuilder();
        String[] lines = fileList.split("\n");
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            // Parse status and filename from line (format: "STATUS\tfilename")
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) continue;
            
            String status = parts[0].trim();
            String filePath = parts[1].trim();
            
            richResult.append("File: ").append(filePath).append("\n");
            richResult.append("Status: ").append(getStatusDescription(status)).append("\n");
            richResult.append("Changes:\n");
            
            try {
                // Get actual diff content for this file
                String diffContent = gitService.getFileDiffContent(filePath, status);
                if (diffContent != null && !diffContent.trim().isEmpty()) {
                    richResult.append(diffContent);
                } else {
                    richResult.append("(No diff content available)");
                }
            } catch (Exception e) {
                LOG.warn("Failed to get diff for file " + filePath + ": " + e.getMessage());
                richResult.append("(Error getting diff: ").append(e.getMessage()).append(")");
            }
            
            richResult.append("\n\n");
        }
        
        return richResult.toString();
    }
    
    /**
     * Converts status character to human-readable description.
     */
    private String getStatusDescription(String status) {
        switch (status) {
            case "A": return "A (Added)";
            case "M": return "M (Modified)";
            case "D": return "D (Deleted)";
            case "R": return "R (Renamed)";
            case "C": return "C (Copied)";
            case "U": return "U (Unmerged)";
            case "T": return "T (Type changed)";
            default: return status + " (Unknown)";
        }
    }
    
    /**
     * Status summary data structure.
     */
    public static class StatusSummary {
        public final String changedFiles;
        public final int fileCount;
        
        public StatusSummary(String changedFiles, int fileCount) {
            this.changedFiles = changedFiles;
            this.fileCount = fileCount;
        }
        
        public boolean hasChanges() {
            return fileCount > 0;
        }
    }
}