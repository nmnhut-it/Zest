package com.zps.zest.testgen.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool for searching code patterns, usages, and references across the project.
 * Provides semantic code search capabilities beyond simple text matching.
 */
public class SearchCodeTool {
    private final Project project;
    private final CodeExplorationToolRegistry toolRegistry;
    private static final int MAX_RESULTS = 10;

    public SearchCodeTool(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
        this.project = project;
        this.toolRegistry = toolRegistry;
    }

    @Tool("""
        Search for code patterns, method calls, class usages, or any text across the project.
        This tool performs intelligent code search that understands code structure.
        
        Parameters:
        - query: The search query. Can be:
          * Method names: "getUserById", "save", "validate"
          * Class names: "UserService", "Controller", "Repository"
          * Annotations: "@Test", "@Autowired", "@Component"
          * Code patterns: "throw new", "implements Serializable", "extends BaseClass"
          * String literals: "config.properties", "SELECT * FROM"
          * Comments: "TODO", "FIXME", "deprecated"
          * Any text that appears in code
        
        Returns: Up to 10 most relevant code snippets with file locations and context.
        
        Search capabilities:
        - Finds usages of methods, classes, and fields
        - Locates string literals and constants
        - Searches in comments and documentation
        - Identifies patterns across multiple files
        
        Example usage:
        - searchCode("@Test")                    // Find all test methods
        - searchCode("getUserById")              // Find method usages
        - searchCode("implements Comparable")    // Find classes implementing interface
        - searchCode("config.properties")        // Find references to config file
        - searchCode("TODO")                     // Find TODO comments
        """)
    public String searchCode(String query) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // First try using the CodeExplorationTool if available
                CodeExplorationTool searchTool = toolRegistry.getTool("retrieve_context");
                if (searchTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("query", query);
                    params.addProperty("max_results", MAX_RESULTS);

                    CodeExplorationTool.ToolResult result = searchTool.execute(params);
                    if (result.isSuccess()) {
                        return formatSearchResult(query, result.getContent());
                    }
                }

                // Fallback to IntelliJ's search capabilities
                return searchCodeDirectly(query);
                
            } catch (Exception e) {
                return String.format("Error searching for '%s': %s\n" +
                                   "Try simplifying your search query or checking if the project is indexed.",
                                   query, e.getMessage());
            }
        });
    }

    /**
     * Directly searches code using IntelliJ's PsiSearchHelper.
     */
    private String searchCodeDirectly(String query) {
        PsiSearchHelper searchHelper = PsiSearchHelper.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        
        List<SearchResult> results = new ArrayList<>();
        
        // Process search results
        searchHelper.processElementsWithWord((element, offsetInElement) -> {
            if (results.size() >= MAX_RESULTS) {
                return false; // Stop searching after MAX_RESULTS
            }
            
            PsiFile file = element.getContainingFile();
            if (file != null) {
                SearchResult result = createSearchResult(element, file, query, offsetInElement);
                if (result != null) {
                    results.add(result);
                }
            }
            
            return true; // Continue searching
        }, scope, query, UsageSearchContext.ANY, true);

        return formatSearchResults(query, results);
    }

    /**
     * Creates a search result with context.
     */
    private SearchResult createSearchResult(PsiElement element, PsiFile file, String query, int offset) {
        try {
            // Get surrounding context
            String fileText = file.getText();
            int lineStart = fileText.lastIndexOf('\n', element.getTextOffset() + offset) + 1;
            int lineEnd = fileText.indexOf('\n', element.getTextOffset() + offset);
            if (lineEnd == -1) lineEnd = fileText.length();
            
            String line = fileText.substring(lineStart, lineEnd).trim();
            int lineNumber = getLineNumber(fileText, element.getTextOffset() + offset);
            
            // Get method/class context
            String context = getElementContext(element);
            
            return new SearchResult(
                file.getVirtualFile().getPath(),
                file.getName(),
                lineNumber,
                line,
                context
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the context of an element (e.g., containing method or class).
     */
    private String getElementContext(PsiElement element) {
        PsiElement current = element;
        List<String> context = new ArrayList<>();
        
        while (current != null && context.size() < 3) {
            if (current instanceof com.intellij.psi.PsiMethod) {
                com.intellij.psi.PsiMethod method = (com.intellij.psi.PsiMethod) current;
                context.add("method: " + method.getName());
            } else if (current instanceof com.intellij.psi.PsiClass) {
                com.intellij.psi.PsiClass clazz = (com.intellij.psi.PsiClass) current;
                context.add("class: " + clazz.getName());
            }
            current = current.getParent();
        }
        
        return context.isEmpty() ? "" : String.join(" > ", context);
    }

    /**
     * Calculates line number from text offset.
     */
    private int getLineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Formats search results for display.
     */
    private String formatSearchResults(String query, List<SearchResult> results) {
        if (results.isEmpty()) {
            return String.format("No results found for: '%s'\n" +
                               "Suggestions:\n" +
                               "- Check spelling and case\n" +
                               "- Try broader search terms\n" +
                               "- Ensure the code exists in the project\n" +
                               "- Verify the project is properly indexed",
                               query);
        }

        StringBuilder output = new StringBuilder();
        output.append(String.format("Found %d result(s) for: '%s'\n", results.size(), query));
        output.append("═".repeat(70)).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append(String.format("%d. 📄 %s (line %d)\n", 
                                       i + 1, result.fileName, result.lineNumber));
            
            if (!result.context.isEmpty()) {
                output.append("   Context: ").append(result.context).append("\n");
            }
            
            output.append("   Path: ").append(result.filePath).append("\n");
            output.append("   ```java\n");
            output.append("   ").append(highlightQuery(result.lineContent, query)).append("\n");
            output.append("   ```\n\n");
        }

        if (results.size() >= MAX_RESULTS) {
            output.append("⚠️ Results limited to ").append(MAX_RESULTS)
                  .append(" matches. Refine your search for more specific results.\n");
        }

        return output.toString();
    }

    /**
     * Highlights the search query in the line content.
     */
    private String highlightQuery(String line, String query) {
        // Simple highlighting with >>> markers
        return line.replace(query, ">>>" + query + "<<<");
    }

    /**
     * Formats the result from CodeExplorationTool.
     */
    private String formatSearchResult(String query, String content) {
        return String.format("Code search results for: '%s'\n%s", query, content);
    }

    /**
     * Internal class to hold search results.
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