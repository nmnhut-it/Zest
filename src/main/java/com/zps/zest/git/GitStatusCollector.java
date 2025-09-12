package com.zps.zest.git;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Collects Git status information efficiently by combining multiple git commands.
 * This eliminates duplication between getGitStatus() and refreshGitFileList() methods.
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
     * Uses multiple strategies with fallbacks for robustness.
     */
    public String collectAllChanges() throws Exception {
        LOG.info("Collecting git changes with robust strategies...");
        
        // Strategy 1: Try porcelain format first (most reliable for all file states)
        try {
            String porcelainResult = GitServiceHelper.executeGitCommand(projectPath, "git status --porcelain --untracked-files=all");
            if (porcelainResult != null && !porcelainResult.trim().isEmpty()) {
                LOG.info("Using git status --porcelain --untracked-files=all");
                return convertPorcelainToNameStatus(porcelainResult);
            }
        } catch (Exception e) {
            LOG.warn("git status --porcelain failed: " + e.getMessage());
        }
        
        // Strategy 2: Fallback to combining individual commands
        String changedFiles = "";
        String stagedFiles = "";
        String untrackedFiles = "";
        
        // Get unstaged changes
        try {
            changedFiles = GitServiceHelper.executeGitCommand(projectPath, "git diff --name-status");
        } catch (Exception e) {
            LOG.warn("Error getting unstaged changes: " + e.getMessage());
        }
        
        // Get staged changes
        try {
            stagedFiles = GitServiceHelper.executeGitCommand(projectPath, "git diff --cached --name-status");
        } catch (Exception e) {
            LOG.warn("Error getting staged changes: " + e.getMessage());
        }
        
        // Get untracked files
        try {
            untrackedFiles = GitServiceHelper.executeGitCommand(projectPath, "git ls-files --others --exclude-standard");
        } catch (Exception e) {
            LOG.warn("Error getting untracked files: " + e.getMessage());
        }
        
        String combined = combineChanges(stagedFiles, changedFiles, untrackedFiles);
        if (!combined.trim().isEmpty()) {
            return combined;
        }
        
        // Strategy 3: Try basic porcelain without untracked flag
        try {
            String basicPorcelain = GitServiceHelper.executeGitCommand(projectPath, "git status --porcelain");
            if (basicPorcelain != null && !basicPorcelain.trim().isEmpty()) {
                LOG.info("Using basic git status --porcelain");
                return convertPorcelainToNameStatus(basicPorcelain);
            }
        } catch (Exception e) {
            LOG.warn("Basic git status --porcelain failed: " + e.getMessage());
        }
        
        LOG.warn("All git status strategies returned empty results");
        return "";
    }
    
    /**
     * Combines changes from different git commands into a single format.
     */
    private String combineChanges(String stagedFiles, String changedFiles, String untrackedFiles) {
        StringBuilder allChanges = new StringBuilder();
        
        // Add staged files first (they take precedence)
        if (!stagedFiles.trim().isEmpty()) {
            allChanges.append(stagedFiles);
            if (!allChanges.toString().endsWith("\n")) {
                allChanges.append("\n");
            }
        }
        
        // Add unstaged files (filter out already staged ones)
        if (!changedFiles.trim().isEmpty()) {
            String[] changedLines = changedFiles.split("\n");
            for (String line : changedLines) {
                if (!line.trim().isEmpty() && !stagedFiles.contains(line.substring(2))) {
                    allChanges.append(line).append("\n");
                }
            }
        }
        
        // Add untracked files with 'A' status
        if (!untrackedFiles.trim().isEmpty()) {
            String[] untracked = untrackedFiles.split("\n");
            for (String file : untracked) {
                if (!file.trim().isEmpty()) {
                    allChanges.append("A\t").append(file).append("\n");
                }
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
     * Handles special cases like renamed files.
     */
    private String convertPorcelainToNameStatus(String porcelainOutput) {
        StringBuilder result = new StringBuilder();
        String[] lines = porcelainOutput.split("\n");
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            // Porcelain format: XY filename
            // X = staged status, Y = unstaged status
            // ?? = untracked file
            if (line.length() >= 3) {
                String statusChars = line.substring(0, 2);
                String filenamePart = line.substring(3); // Skip XY and space
                
                char status;
                String filename;
                
                // Handle special cases
                if (statusChars.equals("??")) {
                    // Untracked file
                    status = 'A';
                    filename = filenamePart;
                } else if (statusChars.startsWith("R")) {
                    // Renamed file (format: "R  old_name -> new_name")
                    status = 'R';
                    if (filenamePart.contains(" -> ")) {
                        // Use the new name (after arrow)
                        String[] renameParts = filenamePart.split(" -> ");
                        filename = renameParts[1].trim();
                    } else {
                        filename = filenamePart;
                    }
                } else {
                    // For other statuses, prioritize the staged status (X) if present
                    // Otherwise use unstaged status (Y)
                    char stagedStatus = statusChars.charAt(0);
                    char unstagedStatus = statusChars.charAt(1);
                    
                    if (stagedStatus != ' ' && stagedStatus != '?') {
                        status = stagedStatus;
                    } else if (unstagedStatus != ' ' && unstagedStatus != '?') {
                        status = unstagedStatus;
                    } else {
                        continue; // Skip if no valid status
                    }
                    filename = filenamePart;
                }
                
                // Remove quotes if present
                if (filename.startsWith("\"") && filename.endsWith("\"")) {
                    filename = filename.substring(1, filename.length() - 1);
                }
                
                result.append(status).append("\t").append(filename).append("\n");
            }
        }
        
        return result.toString();
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