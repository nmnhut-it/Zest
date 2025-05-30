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
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Search strategy for searching project files and functions.
 */
public class ProjectSearchStrategy implements SearchStrategy {
    private static final Logger LOG = Logger.getInstance(ProjectSearchStrategy.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_PROJECT_RESULTS = 5;
    private static final int MAX_TEXT_RESULTS_PER_KEYWORD = 2;
    
    private final Project project;
    private final FileService fileService;
    
    public ProjectSearchStrategy(@NotNull Project project) {
        this.project = project;
        this.fileService = new FileService(project);
    }
    
    @Override
    public CompletableFuture<JsonArray> search(List<String> keywords) {
        return CompletableFuture.supplyAsync(() -> {
            LOG.info("Project search started with keywords: " + keywords);
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
                            
                            JsonObject result = new JsonObject();
                            result.addProperty("type", "function");
                            result.addProperty("keyword", keyword);
                            result.add("matches", funcResults);
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
                                
                                // Try to extract full function implementations
                                JsonArray enhancedResults = enhanceTextSearchResults(textResults, keyword);
                                
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
                LOG.error("Error in project search", e);
            }
            
            LOG.info("Project search completed with " + results.size() + " results");
            return results;
        });
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
            
            boolean foundFunction = false;
            
            for (int j = 0; j < matches.size(); j++) {
                JsonObject match = matches.get(j).getAsJsonObject();
                String matchText = match.has("text") ? match.get("text").getAsString() : "";
                int matchLine = match.get("line").getAsInt();
                
                // Check if this might be a function/method declaration
                if (looksLikeFunction(filePath, matchText, keyword)) {
                    LOG.info("Text match appears to be a function declaration: " + matchText);
                    
                    // Try to extract the full function
                    JsonObject extractResult = extractFunctionFromFile(filePath, keyword, matchLine);
                    if (extractResult != null) {
                        JsonObject enhancedResult = new JsonObject();
                        enhancedResult.addProperty("file", filePath);
                        
                        JsonArray functions = new JsonArray();
                        functions.add(extractResult);
                        enhancedResult.add("functions", functions);
                        
                        enhanced.add(enhancedResult);
                        foundFunction = true;
                        break;
                    }
                }
            }
            
            // If no function was found, keep the original text match result
            if (!foundFunction) {
                enhanced.add(fileResult);
            }
        }
        
        return enhanced;
    }
    
    /**
     * Determines if a text match looks like a function declaration.
     */
    private boolean looksLikeFunction(String filePath, String matchText, String keyword) {
        // For Java methods
        if (filePath.endsWith(".java")) {
            return matchText.matches(".*\\b(public|private|protected|static|final|synchronized|abstract)\\s+.*" + keyword + "\\s*\\(.*") ||
                   matchText.matches(".*\\b" + keyword + "\\s*\\([^)]*\\)\\s*(throws\\s+\\w+)?\\s*\\{?.*") ||
                   (matchText.contains(keyword) && matchText.contains("(") && matchText.contains(")"));
        }
        // For JavaScript/TypeScript
        else if (filePath.matches(".*\\.(js|jsx|ts|tsx)$")) {
            return matchText.matches(".*\\bfunction\\s+" + keyword + "\\s*\\(.*") ||
                   matchText.matches(".*\\b" + keyword + "\\s*:\\s*function.*") ||
                   matchText.matches(".*\\b" + keyword + "\\s*=\\s*(async\\s+)?\\([^)]*\\)\\s*=>.*") ||
                   matchText.matches(".*\\b" + keyword + "\\s*\\([^)]*\\)\\s*\\{.*") ||
                   matchText.matches(".*\\b(const|let|var)\\s+" + keyword + "\\s*=.*");
        }
        // For Python
        else if (filePath.endsWith(".py")) {
            return matchText.matches(".*\\bdef\\s+" + keyword + "\\s*\\(.*");
        }
        // For C/C++
        else if (filePath.matches(".*\\.(c|cpp|cc|h|hpp)$")) {
            return matchText.matches(".*\\b\\w+\\s+" + keyword + "\\s*\\(.*") ||
                   matchText.matches(".*\\b" + keyword + "\\s*\\([^)]*\\)\\s*\\{?.*");
        }
        
        return false;
    }
    
    /**
     * Attempts to extract a function implementation from a file.
     */
    private JsonObject extractFunctionFromFile(String filePath, String functionName, int lineNumber) {
        try {
            LOG.info("Attempting to extract function '" + functionName + 
                    "' from " + filePath + " near line " + lineNumber);
            
            // Get the virtual file
            VirtualFile vFile = project.getBaseDir().findFileByRelativePath(filePath);
            if (vFile == null) {
                LOG.warn("Could not find file: " + filePath);
                return null;
            }
            
            // For Java files, try PSI first
            if (filePath.endsWith(".java")) {
                JsonObject psiResult = extractJavaMethodUsingPSI(vFile, functionName, lineNumber);
                if (psiResult != null) {
                    LOG.info("Successfully extracted Java method using PSI");
                    return psiResult;
                }
                LOG.info("PSI extraction failed, falling back to text-based extraction");
            }
            
            // Fall back to text-based extraction
            String content = new String(vFile.contentsToByteArray(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            // Calculate position
            int position = 0;
            for (int i = 0; i < Math.min(lineNumber - 1, lines.length); i++) {
                position += lines[i].length() + 1;
            }
            
            // Try extraction based on file type
            String implementation = null;
            
            if (filePath.endsWith(".java")) {
                implementation = FunctionExtractionUtils.extractJavaMethodAtPosition(
                    content, position, functionName);
            } else if (filePath.matches(".*\\.(js|jsx|ts|tsx)$")) {
                implementation = FunctionExtractionUtils.extractJavaScriptFunctionAtPosition(
                    content, position, functionName);
            } else if (filePath.endsWith(".py")) {
                implementation = FunctionExtractionUtils.extractPythonFunctionAtPosition(
                    content, position, functionName);
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
     * Extracts a Java method using PSI.
     */
    private JsonObject extractJavaMethodUsingPSI(VirtualFile file, String methodName, int lineNumber) {
        return ReadAction.compute(() -> {
            try {
                PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
                if (!(psiFile instanceof com.intellij.psi.PsiJavaFile)) {
                    return null;
                }
                
                com.intellij.psi.PsiJavaFile javaFile = (com.intellij.psi.PsiJavaFile) psiFile;
                
                // Find the element at the position
                String content = psiFile.getText();
                String[] lines = content.split("\n");
                int offset = 0;
                for (int i = 0; i < Math.min(lineNumber - 1, lines.length); i++) {
                    offset += lines[i].length() + 1;
                }
                
                com.intellij.psi.PsiElement element = psiFile.findElementAt(offset);
                if (element == null) {
                    return null;
                }
                
                // Find containing method
                com.intellij.psi.PsiMethod method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                    element, com.intellij.psi.PsiMethod.class);
                
                if (method == null || !StringMatchingUtils.matchesAcrossNamingConventions(
                        method.getName(), methodName, false)) {
                    // Search in all classes
                    for (com.intellij.psi.PsiClass psiClass : javaFile.getClasses()) {
                        method = findMethodInClass(psiClass, methodName, lineNumber);
                        if (method != null) {
                            break;
                        }
                    }
                }
                
                if (method != null && StringMatchingUtils.matchesAcrossNamingConventions(
                        method.getName(), methodName, false)) {
                    JsonObject func = new JsonObject();
                    func.addProperty("name", method.getName());
                    func.addProperty("line", getLineNumber(method, psiFile));
                    func.addProperty("implementation", method.getText());
                    func.addProperty("type", "psi-extracted");
                    
                    // Add class context
                    com.intellij.psi.PsiClass containingClass = method.getContainingClass();
                    if (containingClass != null) {
                        func.addProperty("className", containingClass.getName());
                        func.addProperty("classQualifiedName", containingClass.getQualifiedName());
                    }
                    
                    return func;
                }
                
            } catch (Exception e) {
                LOG.warn("Error using PSI to extract method", e);
            }
            
            return null;
        });
    }
    
    private com.intellij.psi.PsiMethod findMethodInClass(
            com.intellij.psi.PsiClass psiClass, String methodName, int targetLine) {
        for (com.intellij.psi.PsiMethod method : psiClass.getMethods()) {
            if (StringMatchingUtils.matchesAcrossNamingConventions(method.getName(), methodName, false)) {
                int methodLine = getLineNumber(method, method.getContainingFile());
                if (Math.abs(methodLine - targetLine) <= 5) {
                    return method;
                }
            }
        }
        
        for (com.intellij.psi.PsiClass innerClass : psiClass.getInnerClasses()) {
            com.intellij.psi.PsiMethod found = findMethodInClass(innerClass, methodName, targetLine);
            if (found != null) {
                return found;
            }
        }
        
        return null;
    }
    
    private int getLineNumber(com.intellij.psi.PsiElement element, com.intellij.psi.PsiFile file) {
        if (file != null) {
            com.intellij.openapi.editor.Document document = 
                com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file);
            if (document != null) {
                int offset = element.getTextOffset();
                return document.getLineNumber(offset) + 1;
            }
        }
        
        String text = file.getText();
        int offset = element.getTextOffset();
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
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
}
