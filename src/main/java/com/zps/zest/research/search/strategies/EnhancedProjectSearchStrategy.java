package com.zps.zest.research.search.strategies;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.application.ReadAction;
import com.zps.zest.browser.FileService;
import com.zps.zest.browser.utils.FunctionExtractionUtils;
import com.zps.zest.browser.utils.StringMatchingUtils;
import com.zps.zest.browser.utils.CodeExtractionUtils;
import com.zps.zest.browser.utils.JavaMethodUtils;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced search strategy for searching project files and functions.
 * Now includes full file content (for files < 150 lines) or containing function in search results.
 * This provides better context for LLM analysis.
 */
public class EnhancedProjectSearchStrategy implements SearchStrategy {
    private static final Logger LOG = Logger.getInstance(EnhancedProjectSearchStrategy.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_PROJECT_RESULTS = 5;
    private static final int MAX_TEXT_RESULTS_PER_KEYWORD = 2;
    private static final int MAX_LINES_FOR_FULL_CONTENT = 150;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    
    private final Project project;
    private final FileService fileService;
    
    public EnhancedProjectSearchStrategy(@NotNull Project project) {
        this.project = project;
        this.fileService = new FileService(project);
    }
    
    @Override
    public CompletableFuture<JsonArray> search(List<String> keywords) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Enhanced project search started with keywords: " + keywords);
            JsonArray results = new JsonArray();
            
            try {
                for (String keyword : keywords) {
                    // Try to find functions first
                    LOG.info("Searching functions for keyword: " + keyword);
                    
                    JsonObject searchData = new JsonObject();
                    searchData.addProperty("functionName", keyword);
                    searchData.addProperty("path", "/");
                    searchData.addProperty("caseSensitive", false);
                    
                    String funcResult = fileService.findFunctions(searchData);
                    JsonObject funcResponse = GSON.fromJson(funcResult, JsonObject.class);
                    
                    if (funcResponse.get("success").getAsBoolean() && 
                        funcResponse.has("results")) {
                        JsonArray funcResults = funcResponse.getAsJsonArray("results");
                        if (funcResults.size() > 0) {
                            LOG.info("Found " + funcResults.size() + " functions for keyword: " + keyword);
                            
                            // Enhance function results with full content/context
                            JsonArray enhancedFuncResults = enhanceFunctionResultsWithContext(funcResults);
                            
                            JsonObject result = new JsonObject();
                            result.addProperty("type", "function");
                            result.addProperty("keyword", keyword);
                            result.add("matches", enhancedFuncResults);
                            results.add(result);
                        } else {
                            LOG.info("No functions found for keyword: " + keyword);
                        }
                    }
                    
                    // If we haven't hit the limit, try text search
                    if (results.size() < MAX_PROJECT_RESULTS) {
                        LOG.info("Searching text for keyword: " + keyword);
                        
                        JsonObject textSearchData = new JsonObject();
                        textSearchData.addProperty("searchText", keyword);
                        textSearchData.addProperty("path", "/");
                        textSearchData.addProperty("maxResults", MAX_TEXT_RESULTS_PER_KEYWORD);
                        textSearchData.addProperty("caseSensitive", false);
                        
                        String textResult = fileService.searchInFiles(textSearchData);
                        JsonObject textResponse = GSON.fromJson(textResult, JsonObject.class);
                        
                        if (textResponse.get("success").getAsBoolean() && 
                            textResponse.has("results")) {
                            JsonArray textResults = textResponse.getAsJsonArray("results");
                            if (textResults.size() > 0) {
                                LOG.info("Found text matches in " + textResults.size() + 
                                        " files for keyword: " + keyword);
                                
                                // Enhanced text search results with full content or containing function
                                JsonArray enhancedResults = enhanceTextSearchResultsWithContext(textResults, keyword);
                                
                                JsonObject result = new JsonObject();
                                result.addProperty("type", "text");
                                result.addProperty("keyword", keyword);
                                result.add("matches", enhancedResults);
                                results.add(result);
                            } else {
                                LOG.info("No text matches found for keyword: " + keyword);
                            }
                        }
                    }
                    
                    // Limit total results
                    if (results.size() >= MAX_PROJECT_RESULTS) {
                        LOG.info("Reached project result limit (" + MAX_PROJECT_RESULTS + ")");
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in enhanced project search", e);
            }
            
            LOG.info("Enhanced project search completed with " + results.size() + " results");
            return results;
        });
    }
    
    /**
     * Enhances function results with full file content or expanded context.
     */
    private JsonArray enhanceFunctionResultsWithContext(JsonArray funcResults) {
        JsonArray enhanced = new JsonArray();
        
        for (int i = 0; i < funcResults.size(); i++) {
            JsonObject fileResult = funcResults.get(i).getAsJsonObject();
            String filePath = fileResult.get("file").getAsString();
            JsonArray functions = fileResult.getAsJsonArray("functions");
            
            // Create enhanced result
            JsonObject enhancedResult = new JsonObject();
            enhancedResult.addProperty("file", filePath);
            
            // Get file content info
            FileContentInfo contentInfo = getFileContentInfo(filePath);
            
            if (contentInfo != null && contentInfo.hasFullContent) {
                // If we have full content, include it
                enhancedResult.addProperty("fullContent", contentInfo.fullContent);
                enhancedResult.addProperty("hasFullContent", true);
                enhancedResult.addProperty("totalLines", contentInfo.totalLines);
                LOG.info("Including full content for " + filePath + " (" + contentInfo.totalLines + " lines)");
            } else {
                // Otherwise, just include the functions with their implementations
                enhancedResult.addProperty("hasFullContent", false);
                if (contentInfo != null) {
                    enhancedResult.addProperty("totalLines", contentInfo.totalLines);
                    enhancedResult.addProperty("fileSize", contentInfo.fileSize);
                }
            }
            
            enhancedResult.add("functions", functions);
            enhanced.add(enhancedResult);
        }
        
        return enhanced;
    }
    
    /**
     * Enhances text search results by adding full file content or containing function.
     */
    private JsonArray enhanceTextSearchResultsWithContext(JsonArray textResults, String keyword) {
        JsonArray enhanced = new JsonArray();
        
        for (int i = 0; i < textResults.size(); i++) {
            JsonObject fileResult = textResults.get(i).getAsJsonObject();
            String filePath = fileResult.get("file").getAsString();
            JsonArray matches = fileResult.getAsJsonArray("matches");
            
            JsonObject enhancedResult = new JsonObject();
            enhancedResult.addProperty("file", filePath);
            
            // Get file content info
            FileContentInfo contentInfo = getFileContentInfo(filePath);
            
            if (contentInfo != null && contentInfo.hasFullContent) {
                // If file is small, include full content
                enhancedResult.addProperty("fullContent", contentInfo.fullContent);
                enhancedResult.addProperty("hasFullContent", true);
                enhancedResult.addProperty("totalLines", contentInfo.totalLines);
                enhancedResult.add("matches", matches);
                LOG.info("Including full content for " + filePath + " (" + contentInfo.totalLines + " lines)");
            } else {
                // For larger files, try to extract containing functions
                JsonArray enhancedMatches = new JsonArray();
                Set<String> extractedFunctions = new HashSet<>();
                
                for (int j = 0; j < matches.size(); j++) {
                    JsonObject match = matches.get(j).getAsJsonObject();
                    JsonObject enhancedMatch = match.deepCopy();
                    
                    int matchLine = match.get("line").getAsInt();
                    
                    // Try to extract the containing function
                    ContainingFunction containingFunc = extractContainingFunction(filePath, matchLine, keyword);
                    if (containingFunc != null && !extractedFunctions.contains(containingFunc.functionKey)) {
                        extractedFunctions.add(containingFunc.functionKey);
                        
                        JsonObject funcInfo = new JsonObject();
                        funcInfo.addProperty("name", containingFunc.name);
                        funcInfo.addProperty("startLine", containingFunc.startLine);
                        funcInfo.addProperty("endLine", containingFunc.endLine);
                        funcInfo.addProperty("implementation", containingFunc.implementation);
                        funcInfo.addProperty("type", containingFunc.type);
                        
                        enhancedMatch.add("containingFunction", funcInfo);
                        LOG.info("Extracted containing function '" + containingFunc.name + 
                                "' for match at line " + matchLine);
                    }
                    
                    enhancedMatches.add(enhancedMatch);
                }
                
                enhancedResult.add("matches", enhancedMatches);
                enhancedResult.addProperty("hasFullContent", false);
                if (contentInfo != null) {
                    enhancedResult.addProperty("totalLines", contentInfo.totalLines);
                    enhancedResult.addProperty("fileSize", contentInfo.fileSize);
                }
            }
            
            enhanced.add(enhancedResult);
        }
        
        return enhanced;
    }
    
    /**
     * Gets file content information.
     */
    private FileContentInfo getFileContentInfo(String filePath) {
        try {
            VirtualFile vFile = project.getBaseDir().findFileByRelativePath(filePath);
            if (vFile == null || vFile.isDirectory()) {
                return null;
            }
            
            // Check file size first
            if (vFile.getLength() > MAX_FILE_SIZE) {
                LOG.info("File too large for content extraction: " + filePath + " (" + vFile.getLength() + " bytes)");
                return new FileContentInfo(null, false, 0, vFile.getLength());
            }
            
            // Read file content
            String content = new String(vFile.contentsToByteArray(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            
            FileContentInfo info = new FileContentInfo();
            info.totalLines = lines.length;
            info.fileSize = vFile.getLength();
            
            // If file is small enough, include full content
            if (lines.length <= MAX_LINES_FOR_FULL_CONTENT) {
                info.fullContent = content;
                info.hasFullContent = true;
            } else {
                info.hasFullContent = false;
            }
            
            return info;
            
        } catch (Exception e) {
            LOG.warn("Error getting file content info for: " + filePath, e);
            return null;
        }
    }
    
    /**
     * Extracts the containing function for a match at a specific line.
     */
    private ContainingFunction extractContainingFunction(String filePath, int targetLine, String keyword) {
        try {
            VirtualFile vFile = project.getBaseDir().findFileByRelativePath(filePath);
            if (vFile == null) {
                return null;
            }
            
            String content = new String(vFile.contentsToByteArray(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // Calculate position for the target line
            int position = 0;
            for (int i = 0; i < Math.min(targetLine - 1, lines.length); i++) {
                position += lines[i].length() + 1;
            }
            
            // Try language-specific extraction first
            ContainingFunction result = null;
            
            if (filePath.endsWith(".java")) {
                result = extractContainingJavaMethod(content, position, lines);
            } else if (filePath.matches(".*\\.(js|jsx|ts|tsx)$")) {
                result = extractContainingJavaScriptFunction(content, position, lines);
            } else if (filePath.endsWith(".py")) {
                result = extractContainingPythonFunction(content, position, lines);
            }
            
            // If language-specific extraction failed, try generic context extraction
            if (result == null) {
                result = extractGenericContext(lines, targetLine, keyword);
            }
            
            return result;
            
        } catch (Exception e) {
            LOG.warn("Error extracting containing function", e);
            return null;
        }
    }
    
    /**
     * Extracts containing Java method using PSI and utilities.
     */
    private ContainingFunction extractContainingJavaMethod(String content, int position, String[] lines) {
        // Try to find method boundaries
        int lineAtPosition = CodeExtractionUtils.getLineNumber(content, position);
        
        // Search backwards for method signature
        String methodName = null;
        int methodStartLine = -1;
        
        for (int i = lineAtPosition - 1; i >= Math.max(0, lineAtPosition - 100); i--) {
            String line = lines[i];
            if (line.matches(".*\\b(public|private|protected|static|final|synchronized|abstract)\\s+.*\\(.*")) {
                // Extract method name
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b(\\w+)\\s*\\([^)]*\\)");
                java.util.regex.Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    methodName = matcher.group(1);
                    methodStartLine = i + 1;
                    break;
                }
            }
        }
        
        if (methodName != null && methodStartLine > 0) {
            // Calculate method start position
            int methodStartPos = 0;
            for (int i = 0; i < methodStartLine - 1; i++) {
                methodStartPos += lines[i].length() + 1;
            }
            
            // Use FunctionExtractionUtils to get the full method
            String implementation = FunctionExtractionUtils.extractJavaMethodAtPosition(
                content, methodStartPos, methodName);
            
            if (implementation != null) {
                // Count lines in implementation
                String[] implLines = implementation.split("\n");
                int endLine = methodStartLine + implLines.length - 1;
                
                return new ContainingFunction(
                    methodName,
                    methodStartLine,
                    endLine,
                    implementation,
                    "java-method",
                    methodName + "_" + methodStartLine
                );
            }
        }
        
        return null;
    }
    
    /**
     * Extracts containing JavaScript/TypeScript function.
     */
    private ContainingFunction extractContainingJavaScriptFunction(String content, int position, String[] lines) {
        int lineAtPosition = CodeExtractionUtils.getLineNumber(content, position);
        
        // Search backwards for function signature
        String functionName = null;
        int functionStartLine = -1;
        
        for (int i = lineAtPosition - 1; i >= Math.max(0, lineAtPosition - 50); i--) {
            String line = lines[i];
            
            // Check various function patterns
            java.util.regex.Pattern[] patterns = {
                java.util.regex.Pattern.compile("function\\s+(\\w+)\\s*\\("),
                java.util.regex.Pattern.compile("(\\w+)\\s*:\\s*function\\s*\\("),
                java.util.regex.Pattern.compile("(const|let|var)\\s+(\\w+)\\s*=\\s*(async\\s+)?function"),
                java.util.regex.Pattern.compile("(const|let|var)\\s+(\\w+)\\s*=\\s*(async\\s+)?\\("),
                java.util.regex.Pattern.compile("(\\w+)\\s*\\([^)]*\\)\\s*\\{")
            };
            
            for (java.util.regex.Pattern pattern : patterns) {
                java.util.regex.Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    functionName = matcher.group(1);
                    if (pattern.pattern().contains("(const|let|var)")) {
                        functionName = matcher.group(2);
                    }
                    functionStartLine = i + 1;
                    break;
                }
            }
            
            if (functionName != null) break;
        }
        
        if (functionName != null && functionStartLine > 0) {
            // Calculate function start position
            int funcStartPos = 0;
            for (int i = 0; i < functionStartLine - 1; i++) {
                funcStartPos += lines[i].length() + 1;
            }
            
            // Use FunctionExtractionUtils to get the full function
            String implementation = FunctionExtractionUtils.extractJavaScriptFunctionAtPosition(
                content, funcStartPos, functionName);
            
            if (implementation != null) {
                // Count lines in implementation
                String[] implLines = implementation.split("\n");
                int endLine = functionStartLine + implLines.length - 1;
                
                return new ContainingFunction(
                    functionName,
                    functionStartLine,
                    endLine,
                    implementation,
                    "javascript-function",
                    functionName + "_" + functionStartLine
                );
            }
        }
        
        return null;
    }
    
    /**
     * Extracts containing Python function.
     */
    private ContainingFunction extractContainingPythonFunction(String content, int position, String[] lines) {
        int lineAtPosition = CodeExtractionUtils.getLineNumber(content, position);
        
        // Search backwards for function definition
        String functionName = null;
        int functionStartLine = -1;
        
        for (int i = lineAtPosition - 1; i >= Math.max(0, lineAtPosition - 50); i--) {
            String line = lines[i];
            
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(");
            java.util.regex.Matcher matcher = pattern.matcher(line);
            
            if (matcher.find()) {
                functionName = matcher.group(1);
                functionStartLine = i + 1;
                break;
            }
        }
        
        if (functionName != null && functionStartLine > 0) {
            // Calculate function start position
            int funcStartPos = 0;
            for (int i = 0; i < functionStartLine - 1; i++) {
                funcStartPos += lines[i].length() + 1;
            }
            
            // Use FunctionExtractionUtils to get the full function
            String implementation = FunctionExtractionUtils.extractPythonFunctionAtPosition(
                content, funcStartPos, functionName);
            
            if (implementation != null) {
                // Count lines in implementation
                String[] implLines = implementation.split("\n");
                int endLine = functionStartLine + implLines.length - 1;
                
                return new ContainingFunction(
                    functionName,
                    functionStartLine,
                    endLine,
                    implementation,
                    "python-function",
                    functionName + "_" + functionStartLine
                );
            }
        }
        
        return null;
    }
    
    /**
     * Extracts generic context when language-specific extraction fails.
     */
    private ContainingFunction extractGenericContext(String[] lines, int targetLine, String keyword) {
        // Provide a reasonable context window
        int contextSize = 30; // lines before and after
        int startLine = Math.max(1, targetLine - contextSize);
        int endLine = Math.min(lines.length, targetLine + contextSize);
        
        StringBuilder context = new StringBuilder();
        for (int i = startLine - 1; i < endLine; i++) {
            context.append(lines[i]).append("\n");
        }
        
        return new ContainingFunction(
            "<context-around-" + keyword + ">",
            startLine,
            endLine,
            context.toString(),
            "context",
            "context_" + targetLine
        );
    }
    
    @Override
    public String getSourceType() {
        return "project";
    }
    
    @Override
    public void dispose() {
        if (fileService != null) {
            fileService.dispose();
        }
    }
    
    /**
     * Helper class to store file content information.
     */
    private static class FileContentInfo {
        String fullContent;
        boolean hasFullContent;
        int totalLines;
        long fileSize;
        
        FileContentInfo() {}
        
        FileContentInfo(String fullContent, boolean hasFullContent, int totalLines, long fileSize) {
            this.fullContent = fullContent;
            this.hasFullContent = hasFullContent;
            this.totalLines = totalLines;
            this.fileSize = fileSize;
        }
    }
    
    /**
     * Helper class to store containing function information.
     */
    private static class ContainingFunction {
        String name;
        int startLine;
        int endLine;
        String implementation;
        String type;
        String functionKey;
        
        ContainingFunction(String name, int startLine, int endLine, 
                         String implementation, String type, String functionKey) {
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
            this.implementation = implementation;
            this.type = type;
            this.functionKey = functionKey;
        }
    }
}