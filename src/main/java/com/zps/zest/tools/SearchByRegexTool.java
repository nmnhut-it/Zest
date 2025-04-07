package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindResult;
import com.intellij.find.FindInProjectSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.TextOccurenceProcessor;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.util.Processor;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced tool for searching code using regex patterns or symbol-based search.
 * Leverages IntelliJ's internal search capabilities for efficient code analysis.
 */
public class SearchByRegexTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(SearchByRegexTool.class);
    private final Project project;
    private static final int MAX_RESULTS = 500;
    private static final int MAX_FILES = 100;

    public SearchByRegexTool(Project project) {
        super("search_by_regex", "Searches for code patterns using regular expressions and IntelliJ's symbol search");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String regex = getStringParam(params, "regex", null);
        String scope = getStringParam(params, "scope", "project");
        String fileMask = getStringParam(params, "file_mask", "*.java");
        boolean caseSensitive = getBooleanParam(params, "case_sensitive", true);
        boolean symbolSearch = getBooleanParam(params, "symbol_search", false);

        if (regex == null || regex.isEmpty()) {
            return "Error: Regex pattern is required";
        }

        return symbolSearch ?
                searchSymbolsByPattern(regex, scope, fileMask, caseSensitive) :
                searchByRegex(regex, scope, fileMask, caseSensitive);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("regex", "TodoPromptDrafter");  // Example: find references to TodoPromptDrafter
        params.addProperty("scope", "project");            // Can be "project", "current_file", or a directory path
        params.addProperty("file_mask", "*.java");         // Optional: file mask for filtering
        params.addProperty("case_sensitive", true);        // Optional: case sensitivity flag
        params.addProperty("symbol_search", true);         // Optional: use symbol search instead of text search
        return params;
    }

    /**
     * Performs search for code symbols matching the pattern.
     * This leverages IntelliJ's PsiSearchHelper for more accurate code-aware searching.
     */
    private String searchSymbolsByPattern(String pattern, String scopeType, String fileMask, boolean caseSensitive) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();

                // Determine search scope
                GlobalSearchScope searchScope = determineSearchScope(scopeType, result, pattern);
                if (searchScope == null) {
                    return result.toString(); // Error already appended to result
                }

                // Add file mask info if specified
                if (fileMask != null && !fileMask.equals("*")) {
                    result.append(" in files matching '").append(fileMask).append("'");
                }
                result.append(":\n\n");

                // Get PsiSearchHelper instance
                PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(project);

                // Track results
                Map<String, List<SearchResult>> resultsByFile = new LinkedHashMap<>();
                AtomicInteger totalMatches = new AtomicInteger(0);

                // Set up search options
                short searchContext = UsageSearchContext.IN_CODE | UsageSearchContext.IN_COMMENTS;
                if (!caseSensitive) {
                    searchContext |= UsageSearchContext.IN_STRINGS;
                }

                // Use explicit TextOccurenceProcessor implementation
                final TextOccurenceProcessor processor = new TextOccurenceProcessor() {
                    @Override
                    public boolean execute(PsiElement element, int offsetInElement) {
                        PsiFile containingFile = element.getContainingFile();
                        if (containingFile == null) return true;

                        VirtualFile virtualFile = containingFile.getVirtualFile();
                        if (virtualFile == null) return true;

                        // Skip if we're filtering by file mask and this file doesn't match
                        if (fileMask != null && !fileMask.equals("*") && !matchesFilePattern(virtualFile.getName(), fileMask)) {
                            return true;
                        }

                        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
                        if (document == null) return true;

                        try {
                            // Get text surrounding the match
                            String filePath = virtualFile.getPath();
                            int lineNumber = document.getLineNumber(element.getTextOffset() + offsetInElement) + 1;
                            int lineStartOffset = document.getLineStartOffset(lineNumber - 1);
                            int lineEndOffset = document.getLineEndOffset(lineNumber - 1);
                            String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));

                            // Add to results
                            List<SearchResult> fileResults = resultsByFile.computeIfAbsent(filePath, k -> new ArrayList<>());
                            fileResults.add(new SearchResult(lineNumber, lineText, element.getTextOffset() + offsetInElement, element.getTextLength()));

                            totalMatches.incrementAndGet();
                        } catch (Exception e) {
                            LOG.warn("Error processing search result", e);
                        }

                        // Limit results
                        return totalMatches.get() < MAX_RESULTS && resultsByFile.size() < MAX_FILES;
                    }
                };

                // Process through files using search helper
                try {
                    searchHelper.processElementsWithWord(processor, searchScope, pattern, searchContext, caseSensitive);
                } catch (Exception e) {
                    LOG.warn("Error during symbol search", e);
                    result.append("Warning: Search may be incomplete due to an error\n\n");
                }

                // Format and append results
                formatSearchResults(result, resultsByFile, totalMatches.get());

                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error searching by symbol", e);
            return "Error searching by symbol: " + e.getMessage();
        }
    }

    /**
     * Performs regex search across the specified scope.
     */
    private String searchByRegex(String regex, String scopeType, String fileMask, boolean caseSensitive) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();

                // Create search model
                FindModel findModel = new FindModel();
                findModel.setStringToFind(regex);
                findModel.setRegularExpressions(true);
                findModel.setCaseSensitive(caseSensitive);
                findModel.setWholeWordsOnly(false);

                // Set file type filter if specified
                if (fileMask != null && !fileMask.equals("*")) {
                    findModel.setFileFilter(fileMask);
//                    findModel.setFileFilterEnabled(true);
                }

                // Determine search scope
                GlobalSearchScope searchScope = determineSearchScope(scopeType, result, regex);
                if (searchScope == null) {
                    return result.toString(); // Error already appended to result
                }

                // Add file mask info if specified
                if (fileMask != null && !fileMask.equals("*")) {
                    result.append(" in files matching '").append(fileMask).append("'");
                }
                result.append(":\n\n");

                // Special case for current file search
                if (scopeType.equalsIgnoreCase("current_file")) {
                    return searchInCurrentFile(regex, caseSensitive);
                }

                // Get PsiSearchHelper instance
                PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(project);

                // Results collection
                Map<String, List<SearchResult>> resultsByFile = new LinkedHashMap<>();

                // Create a Processor<PsiFile> for searching
                Processor<PsiFile> fileProcessor = psiFile -> {
                    if (psiFile == null) return true;

                    VirtualFile file = psiFile.getVirtualFile();
                    if (file == null || file.getFileType().isBinary()) return true;

                    // Check file mask
                    if (fileMask != null && !fileMask.equals("*") && !matchesFilePattern(file.getName(), fileMask)) {
                        return true;
                    }

                    Document document = FileDocumentManager.getInstance().getDocument(file);
                    if (document == null) return true;

                    // Search in document
                    List<SearchResult> fileResults = searchInDocument(document, file, FindManager.getInstance(project), findModel);
                    if (!fileResults.isEmpty()) {
                        resultsByFile.put(file.getPath(), fileResults);
                    }

                    // Limit number of files processed
                    return resultsByFile.size() < MAX_FILES;
                };

                try {
                    // Use processAllFilesWithWord with the correct signature
                    searchHelper.processAllFilesWithWord(regex, searchScope, fileProcessor, caseSensitive);
                } catch (Exception e) {
                    LOG.warn("Error during regex search", e);
                    result.append("Warning: Search may be incomplete due to an error\n\n");

                    // Try fallback approach if main search failed
                    tryFallbackSearch(regex, findModel, searchScope, resultsByFile);
                }

                // Format and append results
                formatSearchResults(result, resultsByFile, calculateTotalMatches(resultsByFile));

                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error searching by regex", e);
            return "Error searching by regex: " + e.getMessage();
        }
    }

    /**
     * Try a fallback search approach using FindManager directly
     */
    private void tryFallbackSearch(String regex, FindModel findModel, GlobalSearchScope scope, Map<String, List<SearchResult>> resultsByFile) {
        try {
            // Get Java files from scope
            Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(project, "java", scope);
            int count = 0;

            for (VirtualFile file : files) {
                if (file.getFileType().isBinary() || count >= MAX_FILES) continue;

                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null) continue;

                List<SearchResult> fileResults = searchInDocument(document, file, FindManager.getInstance(project), findModel);
                if (!fileResults.isEmpty()) {
                    resultsByFile.put(file.getPath(), fileResults);
                    count++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Fallback search also failed", e);
        }
    }

    /**
     * Determines the appropriate search scope based on user input.
     */
    private GlobalSearchScope determineSearchScope(String scopeType, StringBuilder result, String searchPattern) {
        switch (scopeType.toLowerCase()) {
            case "project":
                result.append("Search results in project for pattern '").append(searchPattern).append("'");
                return GlobalSearchScope.projectScope(project);

            case "current_file":
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    result.append("No active editor");
                    return null;
                }

                PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
                if (psiFile == null) {
                    result.append("No file in active editor");
                    return null;
                }

                result.append("Search results in current file for pattern '").append(searchPattern).append("'");
                // Convert to file scope for current file
                VirtualFile virtualFile = psiFile.getVirtualFile();
                if (virtualFile != null) {
                    return GlobalSearchScope.fileScope(project, virtualFile);
                }
                return null;

            default:
                // Try to find directory by path
                VirtualFile dir = findFileByPath(scopeType);
                if (dir == null || !dir.isDirectory()) {
                    result.append("Directory not found: ").append(scopeType);
                    return null;
                }

                // Save directory to recent directories
                FindInProjectSettings.getInstance(project).addDirectory(dir.getPath());

                result.append("Search results in directory '").append(scopeType).append("' for pattern '").append(searchPattern).append("'");
                return GlobalSearchScope.filesScope(project, Collections.singletonList(dir));
        }
    }

    /**
     * Searches in the current active file.
     */
    private String searchInCurrentFile(String regex, boolean caseSensitive) {
        StringBuilder result = new StringBuilder();
        result.append("Search results in current file for regex '").append(regex).append("':\n\n");

        // Get current editor
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return "No active editor";
        }

        PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
        if (psiFile == null) {
            return "No file in active editor";
        }

        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return "Cannot access the current file";
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return "Cannot access document for the current file";
        }

        // Create find model for current file
        FindModel findModel = new FindModel();
        findModel.setStringToFind(regex);
        findModel.setRegularExpressions(true);
        findModel.setCaseSensitive(caseSensitive);

        // Perform search
        List<SearchResult> searchResults = searchInDocument(document, file, FindManager.getInstance(project), findModel);

        // Format results
        if (searchResults.isEmpty()) {
            result.append("No matches found in the current file.\n");
        } else {
            result.append("File: ").append(file.getPath()).append("\n");

            for (SearchResult searchResult : searchResults) {
                result.append("  Line ").append(searchResult.lineNumber)
                        .append(": ").append(searchResult.lineText.trim()).append("\n");
            }

            result.append("\n  Total matches: ").append(searchResults.size()).append("\n");
        }

        return result.toString();
    }

    /**
     * Gets the collection of files to search based on the find model and scope.
     */
    private Collection<VirtualFile> getFilesToSearch(FindModel findModel, GlobalSearchScope scope) {
        List<VirtualFile> result = new ArrayList<>();

        try {
            if (findModel.getFileFilter() != null && !findModel.getFileFilter().isEmpty()) {
                String filePattern = findModel.getFileFilter();

                // Get extension for basic filtering
                String extension = null;
                if (filePattern.startsWith("*.")) {
                    extension = filePattern.substring(2);
                }

                if (extension != null) {
                    // Use extension index for better performance
                    return FilenameIndex.getAllFilesByExt(project, extension, scope);
                }
            }

            // Fall back to Java files by default
            return FilenameIndex.getAllFilesByExt(project, "java", scope);
        } catch (Exception e) {
            LOG.error("Error finding files to search", e);
            return result; // Return empty collection on error
        }
    }

    /**
     * Searches for regex matches within a document.
     */
    private List<SearchResult> searchInDocument(Document document, VirtualFile file, FindManager findManager, FindModel findModel) {
        List<SearchResult> results = new ArrayList<>();
        String text = document.getText();

        int offset = 0;
        while (offset < text.length()) {
            FindResult findResult = findManager.findString(text, offset, findModel, file);
            if (!findResult.isStringFound()) {
                break;
            }

            int startOffset = findResult.getStartOffset();
            int endOffset = findResult.getEndOffset();

            try {
                int lineNumber = document.getLineNumber(startOffset) + 1; // Convert to 1-based
                int lineStartOffset = document.getLineStartOffset(document.getLineNumber(startOffset));
                int lineEndOffset = document.getLineEndOffset(document.getLineNumber(startOffset));
                String lineText = text.substring(lineStartOffset, lineEndOffset);

                results.add(new SearchResult(lineNumber, lineText, startOffset, endOffset - startOffset));
            } catch (Exception e) {
                LOG.warn("Error getting line info for match", e);
            }

            offset = endOffset;
            // Handle zero-length matches to prevent infinite loop
            if (offset == startOffset) {
                offset++;
            }

            // Limit the number of results per file
            if (results.size() >= 50) {
                break;
            }
        }

        return results;
    }

    /**
     * Formats search results for display.
     */
    private void formatSearchResults(StringBuilder result, Map<String, List<SearchResult>> resultsByFile, int totalMatches) {
        if (resultsByFile.isEmpty()) {
            result.append("No matches found.\n");
        } else {
            // Format and append results
            for (Map.Entry<String, List<SearchResult>> entry : resultsByFile.entrySet()) {
                String filePath = entry.getKey();
                List<SearchResult> fileResults = entry.getValue();

                result.append("File: ").append(filePath).append("\n");

                for (SearchResult searchResult : fileResults) {
                    result.append("  Line ").append(searchResult.lineNumber)
                            .append(": ").append(searchResult.lineText.trim()).append("\n");
                }

                result.append("  Total matches: ").append(fileResults.size()).append("\n\n");
            }

            // Overall total
            result.append("Total matches: ").append(totalMatches)
                    .append(" in ").append(resultsByFile.size()).append(" files\n");

            if (resultsByFile.size() >= MAX_FILES) {
                result.append("(Search limited to first " + MAX_FILES + " files with matches)\n");
            }

            if (totalMatches >= MAX_RESULTS) {
                result.append("(Search limited to first " + MAX_RESULTS + " matches)\n");
            }
        }
    }

    /**
     * Calculates the total number of matches across all files.
     */
    private int calculateTotalMatches(Map<String, List<SearchResult>> resultsByFile) {
        int totalMatches = 0;
        for (List<SearchResult> fileResults : resultsByFile.values()) {
            totalMatches += fileResults.size();
        }
        return totalMatches;
    }

    /**
     * Checks if a filename matches a file pattern.
     */
    private boolean matchesFilePattern(String fileName, String pattern) {
        // Convert glob pattern to regex pattern
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return fileName.matches(regex);
    }

    /**
     * Finds a file by its path.
     */
    private VirtualFile findFileByPath(String filePath) {
        // Check if it's an absolute path
        File file = new File(filePath);
        if (file.exists() && file.isAbsolute()) {
            return LocalFileSystem.getInstance().findFileByPath(filePath);
        }

        // Try as a relative path from project root
        String projectBasePath = project.getBasePath();
        if (projectBasePath != null) {
            String absolutePath = projectBasePath + "/" + filePath;
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vFile != null && vFile.exists()) {
                return vFile;
            }
        }

        // Try as a relative path from source roots
        for (VirtualFile sourceRoot : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            String rootPath = sourceRoot.getPath();
            String absolutePath = rootPath + "/" + filePath;
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vFile != null && vFile.exists()) {
                return vFile;
            }
        }

        return null;
    }

    /**
     * Helper class to store search results.
     */
    private static class SearchResult {
        final int lineNumber;
        final String lineText;
        final int offset;
        final int length;

        SearchResult(int lineNumber, String lineText, int offset, int length) {
            this.lineNumber = lineNumber;
            this.lineText = lineText;
            this.offset = offset;
            this.length = length;
        }
    }
}