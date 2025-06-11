package com.zps.zest.git;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhancements for commit template processing.
 * Supports additional placeholders and template features.
 */
public class CommitTemplateEnhancements {
    private static final Logger LOG = Logger.getInstance(CommitTemplateEnhancements.class);
    
    // Pattern to match placeholders like {PLACEHOLDER_NAME}
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([A-Z_]+)\\}");
    
    /**
     * Process template with extended placeholders support.
     */
    public static String processTemplate(String template, Project project, 
                                       String filesList, String diffs) {
        if (template == null) return "";
        
        Map<String, String> placeholders = new HashMap<>();
        
        // Basic placeholders
        placeholders.put("FILES_LIST", filesList != null ? filesList : "");
        placeholders.put("DIFFS", diffs != null ? diffs : "");
        
        // Extended placeholders
        placeholders.put("PROJECT_NAME", project.getName());
        placeholders.put("BRANCH_NAME", getCurrentBranch(project));
        placeholders.put("DATE", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        placeholders.put("TIME", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        placeholders.put("USER_NAME", System.getProperty("user.name", "Unknown"));
        placeholders.put("FILES_COUNT", String.valueOf(countFiles(filesList)));
        
        // Replace all placeholders
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        
        // Warn about unknown placeholders
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(result);
        while (matcher.find()) {
            String unknownPlaceholder = matcher.group(1);
            LOG.warn("Unknown placeholder in template: {" + unknownPlaceholder + "}");
        }
        
        return result;
    }
    
    private static String getCurrentBranch(Project project) {
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) return "main";
            
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(new java.io.File(projectPath))
                .start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String branch = reader.readLine();
                return branch != null ? branch.trim() : "main";
            }
        } catch (Exception e) {
            LOG.warn("Failed to get current branch", e);
            return "main";
        }
    }
    
    private static int countFiles(String filesList) {
        if (filesList == null || filesList.trim().isEmpty()) return 0;
        return filesList.split("\n").length;
    }
    
    /**
     * Template examples for user guidance.
     */
    public static class TemplateExamples {
        public static final String CONVENTIONAL_COMMIT = 
            "Generate a conventional commit message:\n\n" +
            "Files: {FILES_LIST}\n" +
            "Diffs: {DIFFS}\n\n" +
            "Format: <type>(<scope>): <subject>";
            
        public static final String DETAILED_COMMIT = 
            "Project: {PROJECT_NAME}\n" +
            "Branch: {BRANCH_NAME}\n" +
            "Date: {DATE}\n" +
            "Modified {FILES_COUNT} files:\n\n" +
            "{FILES_LIST}\n\n" +
            "Changes:\n{DIFFS}\n\n" +
            "Generate a detailed commit message.";
            
        public static final String JIRA_STYLE = 
            "Generate commit message for JIRA:\n\n" +
            "Branch: {BRANCH_NAME}\n" +
            "Files:\n{FILES_LIST}\n\n" +
            "Changes:\n{DIFFS}\n\n" +
            "Include ticket number from branch name if present.";
    }
}
