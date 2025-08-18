package com.zps.zest.git;

import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Helper class for Git diff operations.
 */
public class GitDiffHelper {
    private static final Logger LOG = Logger.getInstance(GitDiffHelper.class);
    
    /**
     * Gets the diff for a new file (all lines shown as additions).
     */
    public static String getNewFileDiff(String projectPath, String filePath) {
        try {
            Path path = Paths.get(projectPath, filePath);
            if (!Files.exists(path)) {
                return "File not found: " + filePath;
            }
            
            String content = Files.readString(path);
            
            StringBuilder diff = new StringBuilder();
            diff.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
            diff.append("new file mode 100644\n");
            diff.append("index 0000000..1234567\n");
            diff.append("--- /dev/null\n");
            diff.append("+++ b/").append(filePath).append("\n");
            
            String[] lines = content.split("\n");
            diff.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
            
            for (String line : lines) {
                diff.append("+").append(line).append("\n");
            }
            
            return diff.toString();
            
        } catch (Exception e) {
            LOG.error("Error reading new file content", e);
            return "Error reading file: " + e.getMessage();
        }
    }
    
    /**
     * Gets the diff for a deleted file (all lines shown as deletions).
     */
    public static String getDeletedFileDiff(String content, String filePath) {
        StringBuilder diff = new StringBuilder();
        diff.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        diff.append("deleted file mode 100644\n");
        diff.append("index 1234567..0000000\n");
        diff.append("--- a/").append(filePath).append("\n");
        diff.append("+++ /dev/null\n");
        
        String[] lines = content.split("\n");
        diff.append("@@ -1,").append(lines.length).append(" +0,0 @@\n");
        
        for (String line : lines) {
            diff.append("-").append(line).append("\n");
        }
        
        return diff.toString();
    }
    
    /**
     * Generates diff header for a file.
     */
    public static String generateDiffHeader(String filePath, boolean isNew, boolean isDeleted) {
        StringBuilder header = new StringBuilder();
        header.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        
        if (isNew) {
            header.append("new file mode 100644\n");
            header.append("index 0000000..1234567\n");
            header.append("--- /dev/null\n");
            header.append("+++ b/").append(filePath).append("\n");
        } else if (isDeleted) {
            header.append("deleted file mode 100644\n");
            header.append("index 1234567..0000000\n");
            header.append("--- a/").append(filePath).append("\n");
            header.append("+++ /dev/null\n");
        } else {
            header.append("index 1234567..89abcde 100644\n");
            header.append("--- a/").append(filePath).append("\n");
            header.append("+++ b/").append(filePath).append("\n");
        }
        
        return header.toString();
    }
    
    /**
     * Parses a unified diff to extract summary information.
     */
    public static DiffSummary parseDiff(String diff) {
        if (diff == null || diff.isEmpty()) {
            return new DiffSummary(0, 0, 0);
        }
        
        int additions = 0;
        int deletions = 0;
        int files = 0;
        
        String[] lines = diff.split("\n");
        boolean inFile = false;
        
        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                files++;
                inFile = true;
            } else if (inFile && line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (inFile && line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        
        return new DiffSummary(files, additions, deletions);
    }
    
    /**
     * Gets diffs for all modified files in a single git command.
     */
    public static String getModifiedFilesDiffs(String projectPath, List<String> filePaths) throws Exception {
        if (filePaths.isEmpty()) return "";
        
        // Build command with all file paths
        StringBuilder command = new StringBuilder("git diff");
        for (String filePath : filePaths) {
            command.append(" -- ").append(com.zps.zest.browser.utils.GitCommandExecutor.escapeFilePath(filePath));
        }
        
        return GitServiceHelper.executeGitCommand(projectPath, command.toString());
    }
    
    /**
     * Parses the output of a batch git diff command into individual file diffs.
     */
    public static void parseBatchDiffOutput(String batchOutput, Map<String, String> diffs) {
        if (batchOutput == null || batchOutput.isEmpty()) return;
        
        String[] lines = batchOutput.split("\n");
        StringBuilder currentDiff = new StringBuilder();
        String currentFile = null;
        
        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                // Save previous diff if exists
                if (currentFile != null && currentDiff.length() > 0) {
                    diffs.put(currentFile, currentDiff.toString());
                }
                
                // Start new diff
                currentDiff = new StringBuilder();
                currentDiff.append(line).append("\n");
                
                // Extract file path from diff header
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    String filePath = parts[2].substring(2); // Remove "a/" prefix
                    currentFile = filePath;
                }
            } else if (currentFile != null) {
                currentDiff.append(line).append("\n");
            }
        }
        
        // Save last diff
        if (currentFile != null && currentDiff.length() > 0) {
            diffs.put(currentFile, currentDiff.toString());
        }
    }
    
    /**
     * Summary of diff statistics.
     */
    public static class DiffSummary {
        public final int files;
        public final int additions;
        public final int deletions;
        
        public DiffSummary(int files, int additions, int deletions) {
            this.files = files;
            this.additions = additions;
            this.deletions = deletions;
        }
        
        @Override
        public String toString() {
            return String.format("%d files changed, %d insertions(+), %d deletions(-)",
                                files, additions, deletions);
        }
    }
}