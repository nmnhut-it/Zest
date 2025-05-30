package com.zps.zest.browser.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiClass;
import com.intellij.psi.util.PsiTreeUtil;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for analyzing unstaged changes and extracting relevant code context.
 */
public class UnstagedChangesUtils {
    private static final Logger LOG = Logger.getInstance(UnstagedChangesUtils.class);
    private static final int MAX_FUNCTION_LENGTH = 5000; // Max chars for a single function
    private static final int MAX_TOTAL_LENGTH = 20000; // Max total chars for all functions
    
    /**
     * Helper class to represent a range of lines.
     */
    public static class LineRange {
        public final int start;
        public final int end;
        
        public LineRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        public boolean contains(int line) {
            return line >= start && line <= end;
        }
        
        public boolean overlapsOrAdjacent(LineRange other) {
            return start <= other.end + 1 && end >= other.start - 1;
        }
        
        public void merge(LineRange other) {
            // Note: This modifies the object, consider making immutable
            throw new UnsupportedOperationException("Use createMerged instead");
        }
        
        public LineRange createMerged(LineRange other) {
            return new LineRange(
                Math.min(start, other.start),
                Math.max(end, other.end)
            );
        }
        
        @Override
        public String toString() {
            if (start == end) {
                return String.valueOf(start);
            }
            return start + "-" + end;
        }
    }
    
    /**
     * Analyzes an unstaged file and extracts changed functions with full context.
     */
    public static JsonObject analyzeUnstagedFile(Project project, String filePath, 
                                                String diff, List<String> keywords) {
        try {
            LOG.info("Analyzing unstaged file: " + filePath);
            
            JsonObject analysis = new JsonObject();
            analysis.addProperty("filePath", filePath);
            
            // Parse diff to find changed line ranges
            List<LineRange> changedRanges = parseDiffForLineRanges(diff);
            
            if (changedRanges.isEmpty()) {
                LOG.info("No changed lines found in diff for: " + filePath);
                return analysis;
            }
            
            // Get the file content
            VirtualFile file = project.getBaseDir().findFileByRelativePath(filePath);
            if (file == null) {
                LOG.warn("Could not find file: " + filePath);
                return analysis;
            }

            String content = ReadAction.compute(() -> {
                try {
                    return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    LOG.error("Error reading file content", e);
                    return null;
                }
            });

            if (content == null) {
                return analysis;
            }
            
            // Extract functions that contain changed lines
            JsonArray changedFunctions = new JsonArray();
            int totalLength = 0;
            
            if (filePath.endsWith(".java")) {
                extractChangedJavaFunctions(project, file, content, changedRanges, 
                                          changedFunctions, keywords, totalLength);
            } else if (filePath.matches(".*\\.(js|jsx|ts|tsx)$")) {
                extractChangedJavaScriptFunctions(content, changedRanges, 
                                                changedFunctions, keywords, totalLength);
            } else if (filePath.endsWith(".py")) {
                extractChangedPythonFunctions(content, changedRanges, 
                                            changedFunctions, keywords, totalLength);
            } else {
                // For other file types, include the changed sections with context
                extractChangedSections(content, changedRanges, changedFunctions, keywords, totalLength);
            }
            
            analysis.add("changedFunctions", changedFunctions);
            analysis.addProperty("changedLineCount", countChangedLines(changedRanges));
            analysis.add("changedLineRanges", createLineRangesArray(changedRanges));
            
            // Add file-level statistics
            JsonObject stats = new JsonObject();
            stats.addProperty("totalLines", content.split("\n").length);
            stats.addProperty("changedFunctionCount", changedFunctions.size());
            analysis.add("statistics", stats);
            
            return analysis;
            
        } catch (Exception e) {
            LOG.error("Error analyzing unstaged file: " + filePath, e);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }
    
    /**
     * Parses a git diff to extract changed line ranges.
     */
    public static List<LineRange> parseDiffForLineRanges(String diff) {
        List<LineRange> ranges = new ArrayList<>();
        Pattern hunkPattern = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
        
        String[] lines = diff.split("\n");
        int currentLine = -1;
        
        for (String line : lines) {
            Matcher matcher = hunkPattern.matcher(line);
            if (matcher.find()) {
                int startLine = Integer.parseInt(matcher.group(1));
                int lineCount = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
                currentLine = startLine;
            } else if (currentLine > 0) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    // This is an added line
                    ranges.add(new LineRange(currentLine, currentLine));
                    currentLine++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    // This is a deleted line, don't increment
                } else if (!line.startsWith("\\")) {
                    // Context line
                    currentLine++;
                }
            }
        }
        
        // Merge adjacent ranges
        return mergeLineRanges(ranges);
    }
    
    /**
     * Merges adjacent or overlapping line ranges.
     */
    private static List<LineRange> mergeLineRanges(List<LineRange> ranges) {
        if (ranges.isEmpty()) return ranges;
        
        List<LineRange> merged = new ArrayList<>();
        ranges.sort((a, b) -> Integer.compare(a.start, b.start));
        
        LineRange current = ranges.get(0);
        
        for (int i = 1; i < ranges.size(); i++) {
            LineRange next = ranges.get(i);
            if (current.overlapsOrAdjacent(next)) {
                current = current.createMerged(next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        
        return merged;
    }
    
    /**
     * Extracts Java functions that contain changed lines.
     */
    private static void extractChangedJavaFunctions(Project project, VirtualFile file, String content,
                                                  List<LineRange> changedRanges, JsonArray results,
                                                  List<String> keywords, int currentTotalLength) {
        // Try PSI-based extraction first
        boolean psiSuccess = ReadAction.compute(() -> {
            try {
                PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
                if (!(psiFile instanceof PsiJavaFile)) {
                    return false;
                }
                
                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                Set<PsiMethod> processedMethods = new HashSet<>();
                
                // Find all methods that contain changed lines
                for (LineRange range : changedRanges) {
                    for (int line = range.start; line <= range.end; line++) {
                        int offset = getOffsetFromLine(content, line);
                        if (offset < 0) continue;
                        
                        com.intellij.psi.PsiElement element = psiFile.findElementAt(offset);
                        if (element == null) continue;
                        
                        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                        if (method != null && !processedMethods.contains(method)) {
                            processedMethods.add(method);
                            
                            // Check if method matches keywords
                            boolean matches = matchesKeywords(method.getName(), method.getText(), keywords);
                            
                            if (matches || keywords.isEmpty()) {
                                String methodText = method.getText();
                                if (methodText.length() <= MAX_FUNCTION_LENGTH && 
                                    currentTotalLength + methodText.length() <= MAX_TOTAL_LENGTH) {
                                    
                                    JsonObject func = new JsonObject();
                                    func.addProperty("name", method.getName());
                                    func.addProperty("signature", buildMethodSignature(method));
                                    func.addProperty("implementation", methodText);
                                    func.addProperty("startLine", getLineNumber(method, psiFile));
                                    
                                    // Add containing class info
                                    PsiClass containingClass = method.getContainingClass();
                                    if (containingClass != null) {
                                        func.addProperty("className", containingClass.getName());
                                    }
                                    
                                    // Mark which lines changed within this function
                                    List<LineRange> functionChangedRanges = 
                                        findChangedLinesInRange(changedRanges, 
                                                              getLineNumber(method, psiFile),
                                                              getEndLineNumber(method, psiFile));
                                    func.add("changedLines", createLineRangesArray(functionChangedRanges));
                                    
                                    results.add(func);
                                }
                            }
                        }
                    }
                }
                
                return true;
            } catch (Exception e) {
                LOG.warn("Error in PSI-based extraction", e);
                return false;
            }
        });
        
        if (!psiSuccess) {
            // Fall back to text-based extraction
            extractChangedFunctionsFromText(content, changedRanges, results, keywords, "java", currentTotalLength);
        }
    }
    
    /**
     * Extracts JavaScript/TypeScript functions that contain changed lines.
     */
    private static void extractChangedJavaScriptFunctions(String content, List<LineRange> changedRanges,
                                                        JsonArray results, List<String> keywords,
                                                        int currentTotalLength) {
        extractChangedFunctionsFromText(content, changedRanges, results, keywords, "javascript", currentTotalLength);
    }
    
    /**
     * Extracts Python functions that contain changed lines.
     */
    private static void extractChangedPythonFunctions(String content, List<LineRange> changedRanges,
                                                    JsonArray results, List<String> keywords,
                                                    int currentTotalLength) {
        extractChangedFunctionsFromText(content, changedRanges, results, keywords, "python", currentTotalLength);
    }
    
    /**
     * Generic text-based function extraction for changed lines.
     */
    private static void extractChangedFunctionsFromText(String content, List<LineRange> changedRanges,
                                                      JsonArray results, List<String> keywords,
                                                      String language, int currentTotalLength) {
        String[] lines = content.split("\n");
        Set<String> processedFunctions = new HashSet<>();
        
        // For each changed range, find the containing function
        for (LineRange range : changedRanges) {
            for (int lineNum = range.start; lineNum <= range.end && lineNum <= lines.length; lineNum++) {
                // Calculate position in content
                int position = getOffsetFromLine(content, lineNum);
                if (position < 0) continue;
                
                // Try to find the function containing this position
                String functionImpl = null;
                String functionName = null;
                
                switch (language) {
                    case "java":
                        functionImpl = FunctionExtractionUtils.extractJavaMethodAtPosition(content, position, null);
                        break;
                    case "javascript":
                        functionImpl = FunctionExtractionUtils.extractJavaScriptFunctionAtPosition(content, position, null);
                        break;
                    case "python":
                        functionImpl = FunctionExtractionUtils.extractPythonFunctionAtPosition(content, position, null);
                        break;
                }
                
                if (functionImpl != null && !functionImpl.isEmpty() && 
                    functionImpl.length() <= MAX_FUNCTION_LENGTH &&
                    currentTotalLength + functionImpl.length() <= MAX_TOTAL_LENGTH) {
                    
                    // Extract function name from implementation
                    functionName = extractFunctionNameFromImpl(functionImpl, language);
                    
                    if (functionName != null && !processedFunctions.contains(functionName)) {
                        processedFunctions.add(functionName);
                        
                        // Check if function matches keywords
                        boolean matches = matchesKeywords(functionName, functionImpl, keywords);
                        
                        if (matches || keywords.isEmpty()) {
                            JsonObject func = new JsonObject();
                            func.addProperty("name", functionName);
                            func.addProperty("implementation", functionImpl);
                            func.addProperty("language", language);
                            
                            // Find which lines changed within this function
                            int funcStartLine = getLineNumberFromContent(content, functionImpl);
                            int funcEndLine = funcStartLine + functionImpl.split("\n").length - 1;
                            List<LineRange> functionChangedRanges = 
                                findChangedLinesInRange(changedRanges, funcStartLine, funcEndLine);
                            func.add("changedLines", createLineRangesArray(functionChangedRanges));
                            
                            results.add(func);
                            currentTotalLength += functionImpl.length();
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Extracts changed sections for non-code files or when function extraction fails.
     */
    private static void extractChangedSections(String content, List<LineRange> changedRanges,
                                             JsonArray results, List<String> keywords,
                                             int currentTotalLength) {
        String[] lines = content.split("\n");
        int contextLines = 10; // Lines of context before and after changes
        
        for (LineRange range : changedRanges) {
            int startLine = Math.max(1, range.start - contextLines);
            int endLine = Math.min(lines.length, range.end + contextLines);
            
            StringBuilder section = new StringBuilder();
            for (int i = startLine - 1; i < endLine && i < lines.length; i++) {
                section.append(lines[i]).append("\n");
            }
            
            String sectionText = section.toString();
            if (sectionText.length() <= MAX_FUNCTION_LENGTH &&
                currentTotalLength + sectionText.length() <= MAX_TOTAL_LENGTH) {
                
                JsonObject change = new JsonObject();
                change.addProperty("type", "section");
                change.addProperty("startLine", startLine);
                change.addProperty("endLine", endLine);
                change.addProperty("content", sectionText);
                change.add("changedLines", createLineRangesArray(Arrays.asList(range)));
                
                results.add(change);
                currentTotalLength += sectionText.length();
            }
        }
    }
    
    /**
     * Checks if function name or content matches any of the keywords.
     */
    private static boolean matchesKeywords(String functionName, String content, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }
        
        for (String keyword : keywords) {
            if (StringMatchingUtils.matchesAcrossNamingConventions(functionName, keyword, false) ||
                content.toLowerCase().contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Extracts function name from implementation based on language.
     */
    private static String extractFunctionNameFromImpl(String impl, String language) {
        try {
            Pattern pattern = null;
            
            switch (language) {
                case "java":
                    pattern = Pattern.compile(
                        "(?:public|private|protected|static|final|synchronized|abstract)?\\s*" +
                        "(?:<[^>]+>\\s*)?\\s*" +
                        "(?:\\w+\\s+)?" +
                        "(\\w+)\\s*\\("
                    );
                    break;
                    
                case "javascript":
                    // Try multiple patterns
                    String[] jsPatterns = {
                        "function\\s+(\\w+)\\s*\\(",
                        "(\\w+)\\s*:\\s*function\\s*\\(",
                        "(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:function\\s*)?\\(",
                        "(\\w+)\\s*\\([^)]*\\)\\s*(?:=>|{)",
                        "(?:async\\s+)?(\\w+)\\s*\\([^)]*\\)\\s*{"
                    };
                    
                    for (String patternStr : jsPatterns) {
                        Pattern p = Pattern.compile(patternStr);
                        Matcher m = p.matcher(impl);
                        if (m.find()) {
                            return m.group(1);
                        }
                    }
                    return "anonymous";
                    
                case "python":
                    pattern = Pattern.compile("def\\s+(\\w+)\\s*\\(");
                    break;
                    
                default:
                    return "unknown";
            }
            
            if (pattern != null) {
                Matcher matcher = pattern.matcher(impl);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            
        } catch (Exception e) {
            LOG.warn("Error extracting function name", e);
        }
        
        return "unknown";
    }
    
    /**
     * Builds a method signature string from a PSI method.
     */
    private static String buildMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();
        
        // Add modifiers
        com.intellij.psi.PsiModifierList modifiers = method.getModifierList();
        if (modifiers.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) sig.append("public ");
        if (modifiers.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE)) sig.append("private ");
        if (modifiers.hasModifierProperty(com.intellij.psi.PsiModifier.PROTECTED)) sig.append("protected ");
        if (modifiers.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) sig.append("static ");
        if (modifiers.hasModifierProperty(com.intellij.psi.PsiModifier.FINAL)) sig.append("final ");
        
        // Add return type
        if (!method.isConstructor()) {
            com.intellij.psi.PsiType returnType = method.getReturnType();
            if (returnType != null) {
                sig.append(returnType.getPresentableText()).append(" ");
            }
        }
        
        // Add method name and parameters
        sig.append(method.getName()).append("(");
        com.intellij.psi.PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(params[i].getType().getPresentableText()).append(" ");
            sig.append(params[i].getName());
        }
        sig.append(")");
        
        return sig.toString();
    }
    
    /**
     * Gets the line number for a PSI element.
     */
    private static int getLineNumber(com.intellij.psi.PsiElement element, PsiFile file) {
        if (file != null) {
            com.intellij.openapi.editor.Document document = 
                com.intellij.psi.PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
            if (document != null) {
                int offset = element.getTextOffset();
                return document.getLineNumber(offset) + 1; // Convert to 1-based
            }
        }
        // Fallback to manual calculation
        String text = file.getText();
        int offset = element.getTextOffset();
        return getLineNumberFromOffset(text, offset);
    }
    
    /**
     * Gets the end line number for a PSI element.
     */
    private static int getEndLineNumber(com.intellij.psi.PsiElement element, PsiFile file) {
        if (file != null) {
            com.intellij.openapi.editor.Document document = 
                com.intellij.psi.PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
            if (document != null) {
                int offset = element.getTextOffset() + element.getTextLength();
                return document.getLineNumber(offset) + 1; // Convert to 1-based
            }
        }
        // Fallback to manual calculation
        String text = file.getText();
        int offset = element.getTextOffset() + element.getTextLength();
        return getLineNumberFromOffset(text, offset);
    }
    
    /**
     * Calculates line number from text offset.
     */
    private static int getLineNumberFromOffset(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
    
    /**
     * Gets offset from line number.
     */
    private static int getOffsetFromLine(String content, int lineNumber) {
        if (lineNumber <= 0) return -1;
        
        String[] lines = content.split("\n");
        if (lineNumber > lines.length) return -1;
        
        int offset = 0;
        for (int i = 0; i < lineNumber - 1; i++) {
            offset += lines[i].length() + 1; // +1 for newline
        }
        return offset;
    }
    
    /**
     * Finds the line number where a specific content starts.
     */
    private static int getLineNumberFromContent(String fullContent, String searchContent) {
        int index = fullContent.indexOf(searchContent);
        if (index == -1) return 1;
        
        return getLineNumberFromOffset(fullContent, index);
    }
    
    /**
     * Finds changed lines within a specific range.
     */
    private static List<LineRange> findChangedLinesInRange(List<LineRange> allChangedRanges, 
                                                         int startLine, int endLine) {
        List<LineRange> result = new ArrayList<>();
        
        for (LineRange range : allChangedRanges) {
            if (range.start >= startLine && range.end <= endLine) {
                result.add(range);
            } else if (range.start <= endLine && range.end >= startLine) {
                // Partial overlap
                result.add(new LineRange(
                    Math.max(range.start, startLine),
                    Math.min(range.end, endLine)
                ));
            }
        }
        
        return result;
    }
    
    /**
     * Creates a JSON array from line ranges.
     */
    private static JsonArray createLineRangesArray(List<LineRange> ranges) {
        JsonArray array = new JsonArray();
        for (LineRange range : ranges) {
            array.add(range.toString());
        }
        return array;
    }
    
    /**
     * Counts total changed lines.
     */
    private static int countChangedLines(List<LineRange> ranges) {
        int count = 0;
        for (LineRange range : ranges) {
            count += range.end - range.start + 1;
        }
        return count;
    }
    
    /**
     * Checks if analysis is needed based on keywords.
     */
    public static boolean needsContentAnalysis(List<String> keywords) {
        // Always analyze content if we have specific keywords to match
        return keywords != null && !keywords.isEmpty();
    }
}