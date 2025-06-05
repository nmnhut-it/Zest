package com.zps.zest.langchain4j;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.tools.BaseAgentTool;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced RAG search tool that uses LangChain4j for improved semantic search.
 */
public class EnhancedRagSearchTool extends BaseAgentTool {
    private final Project project;
    private final HybridRagAgent hybridAgent;
    
    public EnhancedRagSearchTool(Project project) {
        super("search_project_code_enhanced",
                "Enhanced semantic search for project code using local embeddings. " +
                "Provides faster and more accurate results than cloud-based search.");
        this.project = project;
        this.hybridAgent = project.getService(HybridRagAgent.class);
    }
    
    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String query = "";
        int maxResults = 5;
        boolean codeOnly = false;
        
        // Parse parameters
        if (params != null) {
            if (params.has("query")) {
                query = getStringParam(params, "query", "");
            } else if (params.has("args") && params.get("args").isJsonArray() &&
                    params.get("args").getAsJsonArray().size() > 0) {
                // Handle array format
                query = params.get("args").getAsJsonArray().get(0).getAsString();
                if (params.get("args").getAsJsonArray().size() > 1) {
                    maxResults = params.get("args").getAsJsonArray().get(1).getAsInt();
                }
            }
            
            if (params.has("maxResults")) {
                maxResults = params.get("maxResults").getAsInt();
            }
            
            if (params.has("codeOnly")) {
                codeOnly = params.get("codeOnly").getAsBoolean();
            }
        }
        
        if (query.isEmpty()) {
            return "Error: Please provide a search query.";
        }
        
        try {
            // Perform search
            List<HybridRagAgent.HybridSearchResult> results;
            
            if (codeOnly) {
                results = hybridAgent.searchCode(query, maxResults)
                    .get(30, TimeUnit.SECONDS);
            } else {
                results = hybridAgent.search(query, maxResults)
                    .get(30, TimeUnit.SECONDS);
            }
            
            if (results.isEmpty()) {
                return "No relevant code found for query: " + query;
            }
            
            // Format results
            StringBuilder output = new StringBuilder();
            output.append("Found ").append(results.size())
                  .append(" relevant results (using enhanced semantic search):\n\n");
            
            for (int i = 0; i < results.size(); i++) {
                HybridRagAgent.HybridSearchResult result = results.get(i);
                
                output.append("### Result ").append(i + 1).append("\n");
                output.append("**Score:** ").append(String.format("%.3f", result.getScore())).append("\n");
                output.append("**Source:** ").append(result.getSource()).append("\n");
                
                // Add metadata info
                if (result.getMetadata().containsKey("file_path")) {
                    output.append("**File:** ").append(result.getMetadata().get("file_path")).append("\n");
                }
                
                if (result.getMetadata().containsKey("signature_id")) {
                    output.append("**ID:** ").append(result.getMetadata().get("signature_id")).append("\n");
                }
                
                // Add content preview
                output.append("\n```\n");
                String content = result.getContent();
                if (content.length() > 500) {
                    output.append(content.substring(0, 500)).append("\n... (truncated)");
                } else {
                    output.append(content);
                }
                output.append("\n```\n\n");
                
                // Try to get full code for high-scoring results
                if (result.getScore() > 0.7 && result.getMetadata().containsKey("signature_id")) {
                    String fullCode = hybridAgent.getFullCode(
                        (String) result.getMetadata().get("signature_id")
                    );
                    
                    if (fullCode != null && !fullCode.isEmpty() && !fullCode.equals(content)) {
                        output.append("**Full Implementation:**\n```java\n");
                        if (fullCode.length() > 1000) {
                            output.append(fullCode.substring(0, 1000)).append("\n... (truncated)");
                        } else {
                            output.append(fullCode);
                        }
                        output.append("\n```\n");
                    }
                }
                
                output.append("---\n\n");
            }
            
            return output.toString();
            
        } catch (Exception e) {
            return "Error performing enhanced search: " + e.getMessage();
        }
    }

    
    @Override
    public JsonObject getExampleParams() {
        JsonObject example = new JsonObject();
        example.addProperty("query", "authentication and authorization");
        example.addProperty("maxResults", 5);
        example.addProperty("codeOnly", true);
        return example;
    }
}
