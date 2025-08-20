package com.zps.zest.explanation.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Language-agnostic grep-based code search tool.
 * Searches through project files to find code patterns, usages, and dependencies.
 */
public class GrepCodeTool {
    private final Project project;
    private final Set<String> relatedFiles;
    private final List<String> usagePatterns;
    private static final int MAX_RESULTS = 20;
    private static final int MAX_LINE_LENGTH = 200;

    public GrepCodeTool(@NotNull Project project, @NotNull Set<String> relatedFiles, @NotNull List<String> usagePatterns) {
        this.project = project;
        this.relatedFiles = relatedFiles;
        this.usagePatterns = usagePatterns;
    }

    /**
     * Search for code patterns across the project using grep-like functionality.
     */
    public String searchCode(String query, @Nullable String filePattern, @Nullable String excludePattern) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                List<SearchResult> results = new ArrayList<>();
                
                // Get all files in project matching the pattern
                Collection<VirtualFile> filesToSearch = getFilesToSearch(filePattern, excludePattern);
                
                // Compile regex pattern if possible
                Pattern searchPattern = null;
                boolean useRegex = false;
                try {
                    searchPattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
                    useRegex = true;
                } catch (PatternSyntaxException e) {
                    // Fall back to simple string matching
                    useRegex = false;
                }

                // Search through files
                for (VirtualFile file : filesToSearch) {
                    if (results.size() >= MAX_RESULTS) break;
                    
                    try {
                        searchInFile(file, query, searchPattern, useRegex, results);
                    } catch (Exception e) {
                        // Continue with other files if one fails
                        continue;
                    }
                }

                // Sort results by relevance (file name matches first, then line matches)
                results.sort(this::compareResults);

                return formatResults(query, results);

            } catch (Exception e) {
                return String.format("Error searching for '%s': %s\n" +
                                   "Try simplifying your search query or checking project indexing.",
                                   query, e.getMessage());
            }
        });
    }

    /**
     * Get files to search based on patterns
     */
    private Collection<VirtualFile> getFilesToSearch(@Nullable String filePattern, @Nullable String excludePattern) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> filesToSearch = new HashSet<>();

        if (filePattern != null && !filePattern.isEmpty()) {
            // Convert glob pattern to regex for file extension matching
            String regexPattern = filePattern.replace("*", ".*").replace("?", ".");
            Pattern pattern = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);
            
            // Get all filenames and then get the VirtualFiles for matches
            String[] allFilenames = FilenameIndex.getAllFilenames(project);
            for (String filename : allFilenames) {
                if (pattern.matcher(filename).matches()) {
                    Collection<VirtualFile> matchingFiles = FilenameIndex.getVirtualFilesByName(filename, scope);
                    for (VirtualFile file : matchingFiles) {
                        if (file.isInLocalFileSystem() && !file.isDirectory() && scope.contains(file)) {
                            filesToSearch.add(file);
                        }
                    }
                }
            }
        } else {
            // Get all text files in project by checking common extensions
            String[] textExtensions = {".java", ".kt", ".js", ".ts", ".py", ".cpp", ".c", ".h", ".hpp",
                ".cs", ".go", ".rs", ".php", ".rb", ".scala", ".clj", ".xml", ".json", ".yaml", 
                ".yml", ".properties", ".conf", ".config", ".txt", ".md", ".gradle", ".sql"};
            
            for (String ext : textExtensions) {
                Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(project, ext.substring(1));
                for (VirtualFile file : files) {
                    if (file.isInLocalFileSystem() && !file.isDirectory() && scope.contains(file)) {
                        filesToSearch.add(file);
                    }
                }
            }
        }

        // Apply exclude pattern if specified
        if (excludePattern != null && !excludePattern.isEmpty()) {
            String excludeRegex = excludePattern.replace("*", ".*").replace("?", ".");
            Pattern excludePat = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE);
            filesToSearch.removeIf(file -> excludePat.matcher(file.getName()).matches());
        }

        return filesToSearch;
    }

    /**
     * Check if file is likely a text file we should search
     */
    private boolean isTextFile(VirtualFile file) {
        String name = file.getName().toLowerCase();
        String[] textExtensions = {
            ".java", ".kt", ".js", ".ts", ".py", ".cpp", ".c", ".h", ".hpp",
            ".cs", ".go", ".rs", ".php", ".rb", ".scala", ".clj", ".xml",
            ".json", ".yaml", ".yml", ".properties", ".conf", ".config",
            ".txt", ".md", ".gradle", ".pom", ".sbt", ".sql", ".sh", ".bat"
        };
        
        for (String ext : textExtensions) {
            if (name.endsWith(ext)) return true;
        }
        
        return false;
    }

    /**
     * Search within a specific file
     */
    private void searchInFile(VirtualFile file, String query, @Nullable Pattern searchPattern, 
                             boolean useRegex, List<SearchResult> results) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            int lineNumber = 1;
            
            while ((line = reader.readLine()) != null && results.size() < MAX_RESULTS) {
                boolean matches = useRegex ? 
                    searchPattern.matcher(line).find() : 
                    line.toLowerCase().contains(query.toLowerCase());
                
                if (matches) {
                    // Truncate very long lines
                    String displayLine = line.length() > MAX_LINE_LENGTH ? 
                        line.substring(0, MAX_LINE_LENGTH) + "..." : line;
                    
                    results.add(new SearchResult(
                        file.getPath(),
                        file.getName(),
                        lineNumber,
                        displayLine.trim(),
                        determineContext(file.getName())
                    ));
                    
                    // Track related files and usage patterns
                    relatedFiles.add(file.getPath());
                    recordUsagePattern(line.trim(), query);
                }
                lineNumber++;
            }
        }
    }

    /**
     * Record usage patterns found during search
     */
    private void recordUsagePattern(String line, String query) {
        // Look for common usage patterns
        String lowerLine = line.toLowerCase();
        String lowerQuery = query.toLowerCase();
        
        if (lowerLine.contains("import") && lowerLine.contains(lowerQuery)) {
            usagePatterns.add("Import: " + line);
        } else if (lowerLine.contains("new ") && lowerLine.contains(lowerQuery)) {
            usagePatterns.add("Instantiation: " + line);
        } else if (lowerLine.contains("extends") || lowerLine.contains("implements")) {
            usagePatterns.add("Inheritance: " + line);
        } else if (lowerLine.contains("@") && lowerLine.contains(lowerQuery)) {
            usagePatterns.add("Annotation: " + line);
        } else if (lowerLine.contains(lowerQuery + "(")) {
            usagePatterns.add("Method call: " + line);
        }
    }

    /**
     * Determine context type based on file extension
     */
    private String determineContext(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".java")) return "Java";
        if (lower.endsWith(".kt")) return "Kotlin"; 
        if (lower.endsWith(".js") || lower.endsWith(".ts")) return "JavaScript/TypeScript";
        if (lower.endsWith(".py")) return "Python";
        if (lower.endsWith(".xml")) return "XML Config";
        if (lower.endsWith(".json")) return "JSON Config";
        if (lower.endsWith(".properties")) return "Properties";
        if (lower.endsWith(".gradle")) return "Gradle Build";
        if (lower.contains("test")) return "Test";
        return "Code";
    }

    /**
     * Compare results for sorting (relevance-based)
     */
    private int compareResults(SearchResult a, SearchResult b) {
        // Files with matching names are more relevant
        boolean aNameMatch = a.fileName.toLowerCase().contains("test") ? false : true;
        boolean bNameMatch = b.fileName.toLowerCase().contains("test") ? false : true;
        
        if (aNameMatch != bNameMatch) {
            return aNameMatch ? -1 : 1;
        }
        
        // Then by file type relevance (source code over config)
        int aScore = getFileTypeScore(a.fileName);
        int bScore = getFileTypeScore(b.fileName);
        
        return Integer.compare(bScore, aScore);
    }

    /**
     * Get relevance score for file type
     */
    private int getFileTypeScore(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".java") || lower.endsWith(".kt")) return 10;
        if (lower.endsWith(".js") || lower.endsWith(".ts")) return 9;
        if (lower.endsWith(".py") || lower.endsWith(".cpp") || lower.endsWith(".c")) return 8;
        if (lower.contains("test")) return 3;
        if (lower.endsWith(".xml") || lower.endsWith(".json")) return 5;
        return 6;
    }

    /**
     * Format search results for display
     */
    private String formatResults(String query, List<SearchResult> results) {
        if (results.isEmpty()) {
            return String.format("No results found for: '%s'\n" +
                               "Suggestions:\n" +
                               "- Check spelling and case sensitivity\n" +
                               "- Try broader or more specific search terms\n" +
                               "- Use regex patterns for complex searches\n" +
                               "- Specify file patterns to narrow search scope",
                               query);
        }

        StringBuilder output = new StringBuilder();
        output.append(String.format("Found %d result(s) for: '%s'\n", results.size(), query));
        output.append("═".repeat(60)).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append(String.format("%d. 📄 %s (line %d) [%s]\n", 
                                       i + 1, result.fileName, result.lineNumber, result.context));
            
            output.append("   Path: ").append(result.filePath).append("\n");
            output.append("   ```\n");
            output.append("   ").append(highlightQuery(result.lineContent, query)).append("\n");
            output.append("   ```\n\n");
        }

        if (results.size() >= MAX_RESULTS) {
            output.append("⚠️ Results limited to ").append(MAX_RESULTS)
                  .append(" matches. Use more specific search terms for complete results.\n");
        }

        // Add summary of usage patterns found
        if (!usagePatterns.isEmpty()) {
            output.append("\n🔍 Usage patterns discovered:\n");
            Set<String> uniquePatterns = new HashSet<>(usagePatterns);
            for (String pattern : uniquePatterns) {
                if (pattern.length() > 80) {
                    pattern = pattern.substring(0, 80) + "...";
                }
                output.append("   • ").append(pattern).append("\n");
            }
        }

        return output.toString();
    }

    /**
     * Highlight the search query in the line content
     */
    private String highlightQuery(String line, String query) {
        try {
            // Simple case-insensitive highlighting
            return line.replaceAll("(?i)" + Pattern.quote(query), ">>>" + query + "<<<");
        } catch (Exception e) {
            return line; // Return original if highlighting fails
        }
    }

    /**
     * Internal class to hold search results
     */
    private static class SearchResult {
        final String filePath;
        final String fileName;
        final int lineNumber;
        final String lineContent;
        final String context;

        SearchResult(String filePath, String fileName, int lineNumber, 
                    String lineContent, String context) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
            this.context = context;
        }
    }
}