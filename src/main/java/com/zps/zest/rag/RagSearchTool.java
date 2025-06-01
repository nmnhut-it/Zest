package com.zps.zest.rag;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.BaseAgentTool;
import com.zps.zest.tools.ToolComponent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tool for searching related code using RAG.
 */
@ToolComponent(
    name = "search_project_code",
    description = "Searches for related classes, methods, and code based on a query. Returns relevant code signatures and snippets."
)
public class RagSearchTool extends BaseAgentTool {
    private final Project project;
    
    public RagSearchTool(Project project) {
        super("search_project_code", 
              "Searches for related classes, methods, and code based on a query. Returns relevant code signatures and snippets.");
        this.project = project;
    }
    
    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String query = getStringParam(params, "query", "");
        if (query.isEmpty()) {
            return "Error: Please provide a search query.";
        }
        
        int maxResults = params.has("maxResults") ? params.get("maxResults").getAsInt() : 5;
        
        try {
            RagAgent ragAgent = RagAgent.getInstance(project);
            
            // Search for related code
            List<RagAgent.CodeMatch> matches = ragAgent.findRelatedCode(query)
                .get(30, TimeUnit.SECONDS);
            
            if (matches.isEmpty()) {
                return "No relevant code found for query: " + query;
            }
            
            StringBuilder result = new StringBuilder();
            result.append("Found ").append(matches.size()).append(" relevant code elements:\n\n");
            
            int count = 0;
            for (RagAgent.CodeMatch match : matches) {
                if (count >= maxResults) break;
                
                CodeSignature sig = match.getSignature();
                result.append("## ").append(sig.getId()).append("\n");
                result.append("**Signature:** `").append(sig.getSignature()).append("`\n");
                result.append("**File:** ").append(sig.getFilePath()).append("\n");
                result.append("**Relevance:** ").append(String.format("%.2f", match.getRelevance())).append("\n");
                
                // Get full code if it's highly relevant
                if (match.getRelevance() > 0.5) {
                    String fullCode = ragAgent.getFullCode(sig.getId());
                    if (fullCode != null && !fullCode.isEmpty()) {
                        result.append("\n```java\n");
                        // Limit code length for readability
                        if (fullCode.length() > 500) {
                            result.append(fullCode.substring(0, 500)).append("\n... (truncated)");
                        } else {
                            result.append(fullCode);
                        }
                        result.append("\n```\n");
                    }
                }
                
                result.append("\n---\n\n");
                count++;
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "Error searching project code: " + e.getMessage();
        }
    }
    
    public String getUsageExample() {
        return "search_project_code \"authentication logic\" 10";
    }
}
