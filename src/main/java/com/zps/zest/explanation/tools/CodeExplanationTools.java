package com.zps.zest.explanation.tools;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.explanation.agents.CodeExplanationAgent;
import com.zps.zest.testgen.tools.ReadFileTool;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.function.Consumer;

/**
 * Tools container for code explanation with language-agnostic approach.
 * Uses LLM for keyword extraction and grep/search for finding related code.
 */
public class CodeExplanationTools {
    private final Project project;
    private final CodeExplorationToolRegistry toolRegistry;
    private Consumer<String> progressNotifier;
    private Consumer<String> toolNotifier;
    private CodeExplanationAgent explanationAgent;
    
    // Shared data storage
    private final List<String> extractedKeywords = new ArrayList<>();
    private final List<String> explanationNotes = new ArrayList<>();
    private final Map<String, String> readFiles = new HashMap<>();
    private final Set<String> relatedFiles = new HashSet<>();
    private final List<String> usagePatterns = new ArrayList<>();
    
    // Individual tool instances
    private final KeywordExtractionTool keywordExtractionTool;
    private final GrepCodeTool grepCodeTool;
    private final ReadFileTool readFileTool;
    private final TakeExplanationNoteTool takeNoteTool;

    public CodeExplanationTools(@NotNull Project project, 
                               @NotNull CodeExplorationToolRegistry toolRegistry,
                               @Nullable Consumer<String> toolNotifier,
                               @Nullable CodeExplanationAgent explanationAgent) {
        this.project = project;
        this.toolRegistry = toolRegistry;
        this.toolNotifier = toolNotifier;
        this.explanationAgent = explanationAgent;
        
        // Initialize tools with shared data
        ZestLangChain4jService langChainService = project.getService(ZestLangChain4jService.class);
        this.keywordExtractionTool = new KeywordExtractionTool(project, langChainService, extractedKeywords);
        this.grepCodeTool = new GrepCodeTool(project, relatedFiles, usagePatterns);
        this.readFileTool = new ReadFileTool(project, readFiles);
        this.takeNoteTool = new TakeExplanationNoteTool(explanationNotes);
    }

    public void reset() {
        extractedKeywords.clear();
        explanationNotes.clear();
        readFiles.clear();
        relatedFiles.clear();
        usagePatterns.clear();
    }

    public void setProgressCallback(Consumer<String> callback) {
        this.progressNotifier = callback;
    }

    public Map<String, Object> getGatheredData() {
        Map<String, Object> data = new HashMap<>();
        
        // Ensure we return proper List<String> objects, not arrays or other types
        List<String> keywordsList = new ArrayList<>(extractedKeywords);
        List<String> notesList = new ArrayList<>(explanationNotes);
        List<String> relatedFilesList = new ArrayList<>(relatedFiles);
        List<String> usagePatternsList = new ArrayList<>(usagePatterns);
        
        data.put("extractedKeywords", keywordsList);
        data.put("explanationNotes", notesList);
        data.put("readFiles", new HashMap<>(readFiles));
        data.put("relatedFiles", relatedFilesList);
        data.put("usagePatterns", usagePatternsList);
        
        // Debug logging to verify data types
        System.out.println("[DEBUG] Gathered data summary:");
        System.out.println("  - Keywords: " + keywordsList.size() + " (" + keywordsList.getClass().getSimpleName() + ")");
        System.out.println("  - Notes: " + notesList.size() + " (" + notesList.getClass().getSimpleName() + ")");
        System.out.println("  - Read files: " + readFiles.size());
        System.out.println("  - Related files: " + relatedFilesList.size() + " (" + relatedFilesList.getClass().getSimpleName() + ")");
        System.out.println("  - Usage patterns: " + usagePatternsList.size() + " (" + usagePatternsList.getClass().getSimpleName() + ")");
        
        // Additional debug - check if the lists contain proper String objects
        if (!keywordsList.isEmpty()) {
            System.out.println("  - First keyword type: " + keywordsList.get(0).getClass().getSimpleName());
            System.out.println("  - First keyword value: " + keywordsList.get(0));
        }
        
        return data;
    }

    private void notifyTool(String toolName, String params) {
        System.out.println("[DEBUG] Tool called: " + toolName + " with params: " + params);
        if (toolNotifier != null) {
            SwingUtilities.invokeLater(() -> 
                toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params)));
        }
        if (progressNotifier != null) {
            SwingUtilities.invokeLater(() -> 
                progressNotifier.accept("Using " + toolName + "..."));
        }
    }

    @Tool("Extract important keywords from code that can be used to search for related components")
    public String extractKeywords(String code, String language) {
        notifyTool("extractKeywords", "code snippet, language: " + (language != null ? language : "auto"));
        String result = keywordExtractionTool.extractKeywords(code, language);
        
        // Parse the result to extract actual keywords and add them to our list
        try {
            // The tool returns formatted text, we need to extract the actual keywords
            // Look for patterns like "keyword1, keyword2, keyword3" in the result
            String[] lines = result.split("\n");
            for (String line : lines) {
                if (line.contains("Keywords:") || line.contains("Methods/Functions:") || line.contains("Other Keywords:")) {
                    String keywordsPart = line.substring(line.indexOf(":") + 1).trim();
                    if (!keywordsPart.isEmpty() && !keywordsPart.equals("(none)")) {
                        String[] keywords = keywordsPart.split(",\\s*");
                        for (String keyword : keywords) {
                            keyword = keyword.trim();
                            if (!keyword.isEmpty() && keyword.length() > 1) {
                                extractedKeywords.add(keyword);
                            }
                        }
                    }
                }
            }
            System.out.println("[DEBUG] Extracted " + extractedKeywords.size() + " keywords: " + extractedKeywords);
        } catch (Exception e) {
            System.out.println("[DEBUG] Failed to parse keywords, adding fallback");
            extractedKeywords.add("Error parsing keywords");
        }
        
        return result;
    }

    @Tool("Search for code patterns, text, or keywords across the entire project using grep-like functionality")
    public String searchCode(String query, String filePattern, String excludePattern) {
        String params = String.format("query: '%s', files: %s", query, 
            filePattern != null ? filePattern : "all files");
        notifyTool("searchCode", params);
        String result = grepCodeTool.searchCode(query, filePattern, excludePattern);
        
        // Parse the search result to extract file paths and usage patterns
        try {
            String[] lines = result.split("\n");
            for (String line : lines) {
                // Look for file paths in the format "1. 📄 filename.java (line 123)"
                if (line.matches("\\d+\\.\\s*📄.*")) {
                    // Extract the file path from "Path: /full/path/to/file.java"
                    continue; // We'll get the path from the next line
                }
                if (line.trim().startsWith("Path: ")) {
                    String filePath = line.substring(line.indexOf("Path: ") + 6).trim();
                    if (!relatedFiles.contains(filePath)) {
                        relatedFiles.add(filePath);
                    }
                }
                // Look for usage patterns that were already added by the grep tool
                // (these are added directly in GrepCodeTool.recordUsagePattern)
            }
            System.out.println("[DEBUG] searchCode added " + relatedFiles.size() + " related files");
        } catch (Exception e) {
            System.out.println("[DEBUG] Failed to parse search results: " + e.getMessage());
        }
        
        return result;
    }

    @Tool("Read the complete content of a file to understand implementation details")
    public String readFile(String filePath) {
        notifyTool("readFile", filePath);
        String result = readFileTool.readFile(filePath);
        System.out.println("[DEBUG] readFile '" + filePath + "' returned " + 
            (result != null ? result.length() : 0) + " characters");
        return result;
    }

    @Tool("Record important findings about HOW the code works step-by-step during execution")
    public String takeNote(String note) {
        notifyTool("takeNote", note.length() > 50 ? note.substring(0, 50) + "..." : note);
        String result = takeNoteTool.takeNote(note);
        System.out.println("[DEBUG] takeNote recorded: " + note);
        return result;
    }
}