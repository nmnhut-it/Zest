package com.zps.zest.browser.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Utility class for formatting unstaged changes for prompt inclusion.
 */
public class UnstagedChangesFormatter {
    private static final Logger LOG = Logger.getInstance(UnstagedChangesFormatter.class);
    
    // Configuration constants
    private static final int MAX_UNSTAGED_FILES = 3;
    private static final int MAX_FUNCTIONS_PER_FILE = 2;
    private static final int MAX_FUNCTION_LENGTH = 2000;
    private static final int MAX_TOTAL_UNSTAGED_LENGTH = 5000;
    
    /**
     * Formats unstaged changes for inclusion in the prompt.
     * 
     * @param unstagedChanges The JSON array of unstaged changes
     * @return Formatted string for prompt inclusion
     */
    public static String formatUnstagedChanges(JsonArray unstagedChanges) {
        if (unstagedChanges == null || unstagedChanges.size() == 0) {
            return "";
        }
        
        StringBuilder formatted = new StringBuilder();
        formatted.append("\n## UNSTAGED CHANGES (not committed yet):\n");
        
        int totalLength = 0;
        int filesProcessed = 0;
        
        for (int i = 0; i < unstagedChanges.size() && filesProcessed < MAX_UNSTAGED_FILES; i++) {
            JsonObject change = unstagedChanges.get(i).getAsJsonObject();
            
            String fileFormatted = formatSingleUnstagedFile(change, totalLength);
            if (fileFormatted != null && !fileFormatted.isEmpty()) {
                formatted.append(fileFormatted);
                totalLength += fileFormatted.length();
                filesProcessed++;
                
                if (totalLength > MAX_TOTAL_UNSTAGED_LENGTH) {
                    formatted.append("\n... (truncated for brevity)\n");
                    break;
                }
            }
        }
        
        if (unstagedChanges.size() > filesProcessed) {
            formatted.append("\n... and ").append(unstagedChanges.size() - filesProcessed)
                    .append(" more files with unstaged changes\n");
        }
        
        return formatted.toString();
    }
    
    /**
     * Formats a single unstaged file's changes.
     */
    private static String formatSingleUnstagedFile(JsonObject change, int currentTotalLength) {
        try {
            String filePath = change.get("file").getAsString();
            String status = change.has("status") ? change.get("status").getAsString() : "M";
            
            StringBuilder fileFormatted = new StringBuilder();
            fileFormatted.append("\n### File: `").append(filePath).append("` (").append(getStatusDescription(status)).append(")\n");
            
            // Add line statistics if available
            if (change.has("linesAdded") || change.has("linesDeleted")) {
                fileFormatted.append("Changes: ");
                if (change.has("linesAdded")) {
                    fileFormatted.append("+").append(change.get("linesAdded").getAsInt()).append(" ");
                }
                if (change.has("linesDeleted")) {
                    fileFormatted.append("-").append(change.get("linesDeleted").getAsInt());
                }
                fileFormatted.append(" lines\n");
            }
            
            // Format the analysis if available
            if (change.has("analysis")) {
                JsonObject analysis = change.getAsJsonObject("analysis");
                String analysisFormatted = formatFileAnalysis(analysis, filePath, currentTotalLength);
                if (analysisFormatted != null && !analysisFormatted.isEmpty()) {
                    fileFormatted.append(analysisFormatted);
                }
            }
            
            return fileFormatted.toString();
            
        } catch (Exception e) {
            LOG.warn("Error formatting unstaged file", e);
            return null;
        }
    }
    
    /**
     * Formats the analysis of a file's changes.
     */
    private static String formatFileAnalysis(JsonObject analysis, String filePath, int currentTotalLength) {
        StringBuilder formatted = new StringBuilder();
        
        // Format changed functions
        if (analysis.has("changedFunctions")) {
            JsonArray functions = analysis.getAsJsonArray("changedFunctions");
            if (functions.size() > 0) {
                formatted.append("Changed functions:\n");
                
                int functionsShown = 0;
                for (int i = 0; i < functions.size() && functionsShown < MAX_FUNCTIONS_PER_FILE; i++) {
                    JsonObject function = functions.get(i).getAsJsonObject();
                    String funcFormatted = formatChangedFunction(function, filePath);
                    
                    if (funcFormatted != null && 
                        currentTotalLength + formatted.length() + funcFormatted.length() < MAX_TOTAL_UNSTAGED_LENGTH) {
                        formatted.append(funcFormatted);
                        functionsShown++;
                    }
                }
                
                if (functions.size() > functionsShown) {
                    formatted.append("... and ").append(functions.size() - functionsShown)
                            .append(" more changed functions\n");
                }
            }
        }
        
        // Add changed line ranges summary
        if (analysis.has("changedLineRanges")) {
            JsonArray ranges = analysis.getAsJsonArray("changedLineRanges");
            if (ranges.size() > 0) {
                formatted.append("Modified lines: ");
                for (int i = 0; i < Math.min(ranges.size(), 5); i++) {
                    if (i > 0) formatted.append(", ");
                    formatted.append(ranges.get(i).getAsString());
                }
                if (ranges.size() > 5) {
                    formatted.append(", ...");
                }
                formatted.append("\n");
            }
        }
        
        return formatted.toString();
    }
    
    /**
     * Formats a single changed function.
     */
    private static String formatChangedFunction(JsonObject function, String filePath) {
        try {
            String name = function.has("name") ? function.get("name").getAsString() : "unknown";
            String implementation = function.has("implementation") ? 
                function.get("implementation").getAsString() : "";
            
            if (implementation.isEmpty()) {
                return null;
            }
            
            StringBuilder formatted = new StringBuilder();
            formatted.append("\n**Function: `").append(name).append("`**\n");
            
            // Add signature if available and different from name
            if (function.has("signature")) {
                String signature = function.get("signature").getAsString();
                if (!signature.equals(name)) {
                    formatted.append("Signature: `").append(signature).append("`\n");
                }
            }
            
            // Add changed lines info
            if (function.has("changedLines")) {
                JsonArray changedLines = function.getAsJsonArray("changedLines");
                if (changedLines.size() > 0) {
                    formatted.append("Changed lines: ");
                    for (int i = 0; i < changedLines.size(); i++) {
                        if (i > 0) formatted.append(", ");
                        formatted.append(changedLines.get(i).getAsString());
                    }
                    formatted.append("\n");
                }
            }
            
            // Add implementation
            String lang = detectLanguage(filePath);
            String truncatedImpl = implementation;
            if (implementation.length() > MAX_FUNCTION_LENGTH) {
                // Try to truncate at a reasonable boundary
                truncatedImpl = implementation.substring(0, MAX_FUNCTION_LENGTH);
                int lastNewline = truncatedImpl.lastIndexOf('\n');
                if (lastNewline > MAX_FUNCTION_LENGTH * 0.8) {
                    truncatedImpl = truncatedImpl.substring(0, lastNewline);
                }
                truncatedImpl += "\n... (truncated)";
            }
            
            formatted.append("```").append(lang).append("\n");
            formatted.append(truncatedImpl);
            if (!truncatedImpl.endsWith("\n")) {
                formatted.append("\n");
            }
            formatted.append("```\n");
            
            return formatted.toString();
            
        } catch (Exception e) {
            LOG.warn("Error formatting changed function", e);
            return null;
        }
    }
    
    /**
     * Gets a human-readable description of the file status.
     */
    private static String getStatusDescription(String status) {
        switch (status) {
            case "A": return "added";
            case "M": return "modified";
            case "D": return "deleted";
            case "R": return "renamed";
            case "C": return "copied";
            case "U": return "updated";
            default: return status;
        }
    }
    
    /**
     * Detects programming language from file extension.
     */
    private static String detectLanguage(String filePath) {
        if (filePath == null) return "";
        
        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".java")) return "java";
        if (lowerPath.endsWith(".py")) return "python";
        if (lowerPath.endsWith(".js")) return "javascript";
        if (lowerPath.endsWith(".jsx")) return "jsx";
        if (lowerPath.endsWith(".ts")) return "typescript";
        if (lowerPath.endsWith(".tsx")) return "tsx";
        if (lowerPath.endsWith(".cpp") || lowerPath.endsWith(".cc")) return "cpp";
        if (lowerPath.endsWith(".c")) return "c";
        if (lowerPath.endsWith(".h") || lowerPath.endsWith(".hpp")) return "cpp";
        if (lowerPath.endsWith(".cs")) return "csharp";
        if (lowerPath.endsWith(".go")) return "go";
        if (lowerPath.endsWith(".rb")) return "ruby";
        if (lowerPath.endsWith(".php")) return "php";
        if (lowerPath.endsWith(".swift")) return "swift";
        if (lowerPath.endsWith(".kt")) return "kotlin";
        if (lowerPath.endsWith(".rs")) return "rust";
        if (lowerPath.endsWith(".scala")) return "scala";
        if (lowerPath.endsWith(".sh")) return "bash";
        if (lowerPath.endsWith(".sql")) return "sql";
        if (lowerPath.endsWith(".r")) return "r";
        
        return "";
    }
    
    /**
     * Creates a summary of unstaged changes for brief display.
     */
    public static String createUnstagedSummary(JsonArray unstagedChanges) {
        if (unstagedChanges == null || unstagedChanges.size() == 0) {
            return "";
        }
        
        int totalFiles = unstagedChanges.size();
        int totalFunctions = 0;
        int totalLinesAdded = 0;
        int totalLinesDeleted = 0;
        
        for (int i = 0; i < unstagedChanges.size(); i++) {
            JsonObject change = unstagedChanges.get(i).getAsJsonObject();
            
            if (change.has("linesAdded")) {
                totalLinesAdded += change.get("linesAdded").getAsInt();
            }
            if (change.has("linesDeleted")) {
                totalLinesDeleted += change.get("linesDeleted").getAsInt();
            }
            
            if (change.has("analysis")) {
                JsonObject analysis = change.getAsJsonObject("analysis");
                if (analysis.has("changedFunctions")) {
                    totalFunctions += analysis.getAsJsonArray("changedFunctions").size();
                }
            }
        }
        
        return String.format("Unstaged: %d files, %d functions changed (+%d/-%d lines)",
                totalFiles, totalFunctions, totalLinesAdded, totalLinesDeleted);
    }
}