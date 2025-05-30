package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhances Agent Mode context by using Research Agent to find relevant code.
 */
public class AgentModeContextEnhancer {
    private static final Logger LOG = Logger.getInstance(AgentModeContextEnhancer.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final FileService fileService;
    private final GitService gitService;
    private final KeywordGeneratorService keywordGenerator;
    
    // Cache for research results (key -> result, timestamp)
    private final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    
    private static class CachedResult {
        final String result;
        final long timestamp;
        
        CachedResult(String result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
        }
    }
    
    public AgentModeContextEnhancer(@NotNull Project project) {
        this.project = project;
        this.fileService = new FileService(project);
        this.gitService = new GitService(project);
        this.keywordGenerator = new KeywordGeneratorService(project);
    }
    
    /**
     * Enhances the prompt with relevant context from the project.
     * This method is called during prompt building in Agent Mode.
     */
    public CompletableFuture<String> enhancePromptWithContext(String userQuery, String currentFileContext) {
        LOG.info("=== Agent Mode Context Enhancement Started ===");
        LOG.info("User Query: " + userQuery);
        LOG.info("Current File: " + currentFileContext);
        
        return keywordGenerator.generateKeywords(userQuery)
            .thenCompose(keywords -> {
                LOG.info("Generated " + keywords.size() + " keywords: " + keywords);
                
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        long startTime = System.currentTimeMillis();
                        
                        // 1. First, search recent git history for relevant changes
                        LOG.info("Starting git history search...");
                        JsonArray gitResults = searchGitHistory(keywords);
                        LOG.info("Git search completed, found " + gitResults.size() + " results");
                        
                        // 2. Then search the project for relevant code
                        LOG.info("Starting project code search...");
                        JsonArray projectResults = searchProject(keywords);
                        LOG.info("Project search completed, found " + projectResults.size() + " results");
                        
                        // 3. Combine and format results
                        JsonObject enhancedContext = new JsonObject();
                        enhancedContext.addProperty("userQuery", userQuery);
                        enhancedContext.addProperty("currentFile", currentFileContext);
                        enhancedContext.add("keywords", GSON.toJsonTree(keywords));
                        enhancedContext.add("recentChanges", gitResults);
                        enhancedContext.add("relatedCode", projectResults);
                        
                        long duration = System.currentTimeMillis() - startTime;
                        LOG.info("Context enhancement completed in " + duration + "ms");
                        LOG.info("Enhanced context size: " + GSON.toJson(enhancedContext).length() + " chars");
                        
                        return GSON.toJson(enhancedContext);
                        
                    } catch (Exception e) {
                        LOG.error("Error enhancing context", e);
                        return "{}"; // Return empty context on error
                    }
                });
            });
    }
    
    /**
     * Searches git history for relevant recent changes.
     */
    private JsonArray searchGitHistory(List<String> keywords) {
        LOG.info("Searching git history with keywords: " + keywords);
        JsonArray results = new JsonArray();
        
        try {
            for (String keyword : keywords) {
                String cacheKey = "git_" + keyword;
                CachedResult cached = resultCache.get(cacheKey);
                
                if (cached != null && !cached.isExpired()) {
                    LOG.info("Git cache hit for keyword: " + keyword);
                    results.add(GSON.fromJson(cached.result, JsonObject.class));
                } else {
                    LOG.info("Searching git commits for keyword: " + keyword);
                    
                    // Search git history
                    JsonObject searchData = new JsonObject();
                    searchData.addProperty("text", keyword);
                    
                    String gitResult = gitService.findCommitByMessage(searchData);
                    JsonObject gitResponse = GSON.fromJson(gitResult, JsonObject.class);
                    
                    if (gitResponse.get("success").getAsBoolean() && 
                        gitResponse.has("commits")) {
                        JsonArray commits = gitResponse.getAsJsonArray("commits");
                        LOG.info("Found " + commits.size() + " commits for keyword: " + keyword);
                        
                        JsonObject result = new JsonObject();
                        result.addProperty("keyword", keyword);
                        result.add("commits", commits);
                        results.add(result);
                        
                        // Cache the result
                        resultCache.put(cacheKey, new CachedResult(GSON.toJson(result)));
                        LOG.info("Cached git results for keyword: " + keyword);
                    } else {
                        LOG.info("No commits found for keyword: " + keyword);
                    }
                }
                
                // Limit to first 2 git results
                if (results.size() >= 2) {
                    LOG.info("Reached git result limit (2), stopping search");
                    break;
                }
            }
        } catch (Exception e) {
            LOG.warn("Error searching git history", e);
        }
        
        LOG.info("Git history search completed with " + results.size() + " results");
        return results;
    }
    
    /**
     * Searches the project for relevant code snippets and functions.
     */
    private JsonArray searchProject(List<String> keywords) {
        LOG.info("Searching project with keywords: " + keywords);
        JsonArray results = new JsonArray();
        
        try {
            for (String keyword : keywords) {
                // Try to find functions first
                String cacheKey = "func_" + keyword;
                CachedResult cached = resultCache.get(cacheKey);
                
                if (cached != null && !cached.isExpired()) {
                    LOG.info("Function cache hit for keyword: " + keyword);
                    results.add(GSON.fromJson(cached.result, JsonObject.class));
                } else {
                    LOG.info("Searching functions for keyword: " + keyword);
                    
                    JsonObject searchData = new JsonObject();
                    searchData.addProperty("functionName", keyword);
                    searchData.addProperty("path", "/");
                    
                    String funcResult = fileService.findFunctions(searchData);
                    JsonObject funcResponse = GSON.fromJson(funcResult, JsonObject.class);
                    
                    if (funcResponse.get("success").getAsBoolean() && 
                        funcResponse.has("results")) {
                        JsonArray funcResults = funcResponse.getAsJsonArray("results");
                        if (funcResults.size() > 0) {
                            LOG.info("Found " + funcResults.size() + " functions for keyword: " + keyword);
                            
                            JsonObject result = new JsonObject();
                            result.addProperty("type", "function");
                            result.addProperty("keyword", keyword);
                            result.add("matches", funcResults);
                            results.add(result);
                            
                            // Cache the result
                            resultCache.put(cacheKey, new CachedResult(GSON.toJson(result)));
                            LOG.info("Cached function results for keyword: " + keyword);
                        } else {
                            LOG.info("No functions found for keyword: " + keyword);
                        }
                    }
                }
                
                // If no functions found, try text search
                if (results.size() < 3) {
                    cacheKey = "text_" + keyword;
                    cached = resultCache.get(cacheKey);
                    
                    if (cached != null && !cached.isExpired()) {
                        LOG.info("Text cache hit for keyword: " + keyword);
                        results.add(GSON.fromJson(cached.result, JsonObject.class));
                    } else {
                        LOG.info("Searching text for keyword: " + keyword);
                        
                        JsonObject textSearchData = new JsonObject();
                        textSearchData.addProperty("searchText", keyword);
                        textSearchData.addProperty("path", "/");
                        textSearchData.addProperty("maxResults", 2);
                        
                        String textResult = fileService.searchInFiles(textSearchData);
                        JsonObject textResponse = GSON.fromJson(textResult, JsonObject.class);
                        
                        if (textResponse.get("success").getAsBoolean() && 
                            textResponse.has("results")) {
                            JsonArray textResults = textResponse.getAsJsonArray("results");
                            if (textResults.size() > 0) {
                                LOG.info("Found text matches in " + textResults.size() + " files for keyword: " + keyword);
                                
                                // Try to extract full function implementations for text matches
                                JsonArray enhancedResults = enhanceTextSearchResults(textResults, keyword);
                                
                                JsonObject result = new JsonObject();
                                result.addProperty("type", "text");
                                result.addProperty("keyword", keyword);
                                result.add("matches", enhancedResults);
                                results.add(result);
                                
                                // Cache the result
                                resultCache.put(cacheKey, new CachedResult(GSON.toJson(result)));
                                LOG.info("Cached text results for keyword: " + keyword);
                            } else {
                                LOG.info("No text matches found for keyword: " + keyword);
                            }
                        }
                    }
                }
                
                // Limit total results to 5
                if (results.size() >= 5) {
                    LOG.info("Reached project result limit (5), stopping search");
                    break;
                }
            }
        } catch (Exception e) {
            LOG.warn("Error searching project", e);
        }
        
        LOG.info("Project search completed with " + results.size() + " results");
        return results;
    }
    
    /**
     * Extracts keywords from the query.
     * This is now replaced by LLM-based generation but kept as fallback.
     */
    private String[] extractKeywords(String query) {
        // Simple keyword extraction - now used as fallback
        String[] words = query.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s_]", " ")
            .split("\\s+");
        
        // Filter out common words and limit to 10
        return java.util.Arrays.stream(words)
            .filter(w -> w.length() > 3)
            .filter(w -> !isCommonWord(w))
            .distinct()
            .limit(10)
            .toArray(String[]::new);
    }
    
    /**
     * Enhances text search results by attempting to extract full function implementations.
     */
    private JsonArray enhanceTextSearchResults(JsonArray textResults, String keyword) {
        JsonArray enhanced = new JsonArray();
        
        for (int i = 0; i < textResults.size(); i++) {
            JsonObject fileResult = textResults.get(i).getAsJsonObject();
            String filePath = fileResult.get("file").getAsString();
            JsonArray matches = fileResult.getAsJsonArray("matches");
            
            // Check if any match in this file looks like a function/method
            boolean foundFunction = false;
            
            for (int j = 0; j < matches.size(); j++) {
                JsonObject match = matches.get(j).getAsJsonObject();
                String matchText = match.has("text") ? match.get("text").getAsString() : "";
                int matchLine = match.get("line").getAsInt();
                
                // Enhanced heuristics to detect if this might be a function/method declaration
                boolean likelyFunction = false;
                
                // For Java methods
                if (filePath.endsWith(".java")) {
                    likelyFunction = matchText.matches(".*\\b(public|private|protected|static|final|synchronized|abstract)\\s+.*" + keyword + "\\s*\\(.*") ||
                                   matchText.matches(".*\\b" + keyword + "\\s*\\([^)]*\\)\\s*(throws\\s+\\w+)?\\s*\\{?.*") ||
                                   (matchText.contains(keyword) && matchText.contains("(") && matchText.contains(")"));
                }
                // For JavaScript/TypeScript
                else if (filePath.matches(".*\\.(js|jsx|ts|tsx)$")) {
                    likelyFunction = matchText.matches(".*\\bfunction\\s+" + keyword + "\\s*\\(.*") ||
                                   matchText.matches(".*\\b" + keyword + "\\s*:\\s*function.*") ||
                                   matchText.matches(".*\\b" + keyword + "\\s*=\\s*(async\\s+)?\\([^)]*\\)\\s*=>.*") ||
                                   matchText.matches(".*\\b" + keyword + "\\s*\\([^)]*\\)\\s*\\{.*") ||
                                   matchText.matches(".*\\b(const|let|var)\\s+" + keyword + "\\s*=.*");
                }
                // For Python
                else if (filePath.endsWith(".py")) {
                    likelyFunction = matchText.matches(".*\\bdef\\s+" + keyword + "\\s*\\(.*");
                }
                // For C/C++
                else if (filePath.matches(".*\\.(c|cpp|cc|h|hpp)$")) {
                    likelyFunction = matchText.matches(".*\\b\\w+\\s+" + keyword + "\\s*\\(.*") ||
                                   matchText.matches(".*\\b" + keyword + "\\s*\\([^)]*\\)\\s*\\{?.*");
                }
                
                if (likelyFunction) {
                    LOG.info("Text match appears to be a function declaration: " + matchText);
                    
                    // Try to extract the full function from the file
                    JsonObject extractResult = extractFunctionFromFile(filePath, keyword, matchLine);
                    if (extractResult != null) {
                        // Successfully extracted function, replace this result
                        JsonObject enhancedResult = new JsonObject();
                        enhancedResult.addProperty("file", filePath);
                        
                        JsonArray functions = new JsonArray();
                        functions.add(extractResult);
                        enhancedResult.add("functions", functions);
                        
                        enhanced.add(enhancedResult);
                        foundFunction = true;
                        break; // Found function, no need to check other matches in this file
                    }
                }
            }
            
            // If no function was found/extracted, keep the original text match result
            if (!foundFunction) {
                enhanced.add(fileResult);
            }
        }
        
        return enhanced;
    }
    
    /**
     * Attempts to extract a function implementation from a specific file at a specific line.
     */
    private JsonObject extractFunctionFromFile(String filePath, String functionName, int lineNumber) {
        try {
            LOG.info("Attempting to extract function '" + functionName + "' from " + filePath + " near line " + lineNumber);
            
            // Read the file content
            VirtualFile vFile = project.getBaseDir().findFileByRelativePath(filePath);
            if (vFile == null) {
                LOG.warn("Could not find file: " + filePath);
                return null;
            }
            
            String content = new String(vFile.contentsToByteArray(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // Calculate the position in the content based on line number
            int position = 0;
            for (int i = 0; i < Math.min(lineNumber - 1, lines.length); i++) {
                position += lines[i].length() + 1; // +1 for newline
            }
            
            // Try different extraction strategies based on file type
            String implementation = null;
            
            if (filePath.endsWith(".java")) {
                implementation = extractJavaMethodAtPosition(content, position, functionName);
            } else if (filePath.matches(".*\\.(js|jsx|ts|tsx)$")) {
                implementation = extractJavaScriptFunctionAtPosition(content, position, functionName);
            } else if (filePath.endsWith(".py")) {
                implementation = extractPythonFunctionAtPosition(content, position, functionName);
            }
            
            if (implementation != null && !implementation.isEmpty()) {
                JsonObject func = new JsonObject();
                func.addProperty("name", functionName);
                func.addProperty("line", lineNumber);
                func.addProperty("implementation", implementation);
                func.addProperty("type", "extracted-from-text");
                
                LOG.info("Successfully extracted function implementation");
                return func;
            }
            
        } catch (Exception e) {
            LOG.warn("Error extracting function from file", e);
        }
        
        return null;
    }
    
    /**
     * Extracts a Java method at a specific position.
     */
    private String extractJavaMethodAtPosition(String content, int position, String methodName) {
        // Search backwards for method start (including annotations and JavaDoc)
        int searchStart = Math.max(0, position - 500);
        String searchRegion = content.substring(searchStart, Math.min(position + 200, content.length()));
        
        // Look for the method signature
        Pattern methodPattern = Pattern.compile(
            "((?:/\\*\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/\\s*)?(?:@\\w+(?:\\([^)]*\\))?\\s*)*)" + // JavaDoc and annotations
            "((?:public|private|protected)\\s+)?" + // access modifier
            "((?:static|final|synchronized|abstract)\\s+)*" + // other modifiers  
            "([\\w<>\\[\\]]+\\s+)?" + // return type
            "(" + Pattern.quote(methodName) + ")\\s*\\([^)]*\\)\\s*" + // method name and params
            "(?:throws\\s+[^{]+)?\\s*\\{" // throws clause and opening brace
        );
        
        Matcher matcher = methodPattern.matcher(searchRegion);
        if (matcher.find()) {
            int methodStart = searchStart + matcher.start();
            int braceStart = searchStart + matcher.end() - 1; // Position of '{'
            
            // Find matching closing brace
            int braceEnd = findMatchingBrace(content, braceStart);
            if (braceEnd != -1) {
                return content.substring(methodStart, braceEnd + 1);
            }
        }
        
        return null;
    }
    
    /**
     * Extracts a JavaScript/TypeScript function at a specific position.
     */
    private String extractJavaScriptFunctionAtPosition(String content, int position, String functionName) {
        // Search around the position
        int searchStart = Math.max(0, position - 200);
        int searchEnd = Math.min(content.length(), position + 100);
        
        // Start from the line containing the match
        int lineStart = position;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        
        // Try to find the function start from the beginning of the line
        String fromLine = content.substring(lineStart, searchEnd);
        
        // Check for various function patterns
        if (fromLine.contains("function " + functionName) || 
            fromLine.contains(functionName + " = function") ||
            fromLine.contains(functionName + " = (") ||
            fromLine.contains(functionName + " = async") ||
            fromLine.contains(functionName + ": function") ||
            fromLine.contains(functionName + "(")) {
            
            // Find where the function body starts
            int bodyStart = -1;
            boolean isArrowFunction = false;
            
            for (int i = lineStart; i < searchEnd; i++) {
                if (content.charAt(i) == '{') {
                    bodyStart = i;
                    break;
                } else if (i < content.length() - 1 && content.charAt(i) == '=' && content.charAt(i + 1) == '>') {
                    isArrowFunction = true;
                    i += 2;
                    // Skip whitespace
                    while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
                        i++;
                    }
                    if (i < content.length() && content.charAt(i) == '{') {
                        bodyStart = i;
                        break;
                    } else {
                        // Arrow function without braces
                        return extractArrowFunctionExpression(content, i);
                    }
                }
            }
            
            if (bodyStart != -1) {
                int bodyEnd = findMatchingBrace(content, bodyStart);
                if (bodyEnd != -1) {
                    // Include any export/const/let/var prefix
                    int realStart = lineStart;
                    String prefix = content.substring(Math.max(0, lineStart - 50), lineStart);
                    if (prefix.matches(".*\\b(export|const|let|var)\\s*$")) {
                        realStart = Math.max(0, lineStart - 50);
                        while (realStart < lineStart && !Character.isWhitespace(content.charAt(realStart))) {
                            realStart++;
                        }
                    }
                    
                    return content.substring(realStart, bodyEnd + 1).trim();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extracts a Python function at a specific position.
     */
    private String extractPythonFunctionAtPosition(String content, int position, String functionName) {
        // Find the start of the function definition
        int defStart = position;
        while (defStart > 0 && !content.substring(Math.max(0, defStart - 4), defStart).equals("def ")) {
            defStart--;
        }
        
        if (defStart > 0) {
            defStart -= 4; // Include "def "
            
            // Find the end of the function (next function or class at same indentation level)
            String[] lines = content.substring(defStart).split("\n");
            StringBuilder function = new StringBuilder();
            
            // Get indentation of the def line
            int defIndent = 0;
            for (char c : lines[0].toCharArray()) {
                if (c == ' ' || c == '\t') defIndent++;
                else break;
            }
            
            function.append(lines[0]).append("\n");
            
            // Collect lines until we find something at the same or lower indentation
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    function.append(line).append("\n");
                    continue;
                }
                
                // Count indentation
                int indent = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ' || c == '\t') indent++;
                    else break;
                }
                
                // If we find something at the same or lower indentation, stop
                if (indent <= defIndent && line.trim().matches("^(def|class|if|for|while|import|from)\\b.*")) {
                    break;
                }
                
                function.append(line).append("\n");
            }
            
            return function.toString().trim();
        }
        
        return null;
    }
    
    /**
     * Helper method to find matching brace.
     */
    private int findMatchingBrace(String content, int openBracePos) {
        int braceCount = 1;
        boolean inString = false;
        boolean inComment = false;
        boolean inMultiLineComment = false;
        char stringChar = 0;
        
        for (int i = openBracePos + 1; i < content.length(); i++) {
            char current = content.charAt(i);
            char prev = i > 0 ? content.charAt(i - 1) : 0;
            char next = i < content.length() - 1 ? content.charAt(i + 1) : 0;
            
            // Handle multi-line comments
            if (!inString && !inComment && current == '/' && next == '*') {
                inMultiLineComment = true;
                i++; // Skip next char
                continue;
            }
            if (inMultiLineComment && current == '*' && next == '/') {
                inMultiLineComment = false;
                i++; // Skip next char
                continue;
            }
            if (inMultiLineComment) continue;
            
            // Handle single-line comments
            if (!inString && current == '/' && next == '/') {
                inComment = true;
                continue;
            }
            if (inComment && current == '\n') {
                inComment = false;
                continue;
            }
            if (inComment) continue;
            
            // Handle strings
            if (!inString && (current == '"' || current == '\'' || current == '`')) {
                inString = true;
                stringChar = current;
                continue;
            }
            if (inString && current == stringChar && prev != '\\') {
                inString = false;
                continue;
            }
            if (inString) continue;
            
            // Count braces
            if (current == '{') {
                braceCount++;
            } else if (current == '}') {
                braceCount--;
                if (braceCount == 0) {
                    return i;
                }
            }
        }
        
        return -1; // No matching brace found
    }
    
    /**
     * Extracts an arrow function expression (for arrow functions without braces).
     */
    private String extractArrowFunctionExpression(String content, int exprStart) {
        // Find the end of the expression
        int end = exprStart;
        int parenCount = 0;
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean inTemplate = false;
        char stringChar = '\0';
        
        while (end < content.length()) {
            char c = content.charAt(end);
            char prev = end > 0 ? content.charAt(end - 1) : '\0';
            
            // Handle strings
            if (!inString && !inTemplate && (c == '"' || c == '\'')) {
                inString = true;
                stringChar = c;
            } else if (inString && c == stringChar && prev != '\\') {
                inString = false;
            } else if (!inString && c == '`') {
                inTemplate = !inTemplate;
            }
            
            if (!inString && !inTemplate) {
                // Track nesting
                if (c == '(') parenCount++;
                else if (c == ')') parenCount--;
                else if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
                
                // Check for expression end
                if (parenCount == 0 && braceCount == 0 && bracketCount == 0) {
                    if (c == ';' || c == ',' || c == '\n') {
                        break;
                    }
                }
            }
            
            end++;
        }
        
        // Back up to find the full declaration
        int start = exprStart;
        while (start > 0 && content.charAt(start - 1) != '\n') {
            start--;
            if (content.substring(start, exprStart).contains("=>")) {
                // Found the arrow, now find the beginning
                String beforeArrow = content.substring(Math.max(0, start - 100), start);
                if (beforeArrow.matches(".*\\b(const|let|var)\\s+\\w+\\s*=\\s*$")) {
                    start = Math.max(0, start - 100);
                    while (start < exprStart && !content.substring(start).matches("^(const|let|var)\\s+.*")) {
                        start++;
                    }
                }
                break;
            }
        }
        
        return content.substring(start, end).trim();
    }
    
    private boolean isCommonWord(String word) {
        return java.util.Set.of("this", "that", "with", "from", "have", "been", 
            "will", "would", "could", "should", "make", "need", "want", "please",
            "help", "write", "create", "update", "modify", "change", "code").contains(word);
    }
    
    /**
     * Clears expired cache entries.
     */
    public void cleanCache() {
        resultCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * Disposes resources.
     */
    public void dispose() {
        resultCache.clear();
        if (fileService != null) fileService.dispose();
        if (gitService != null) gitService.dispose();
    }
}
