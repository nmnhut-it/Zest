package com.zps.zest.rag;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.zps.zest.tools.BaseAgentTool;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tool for searching related code using RAG.
 */
public class RagSearchTool extends BaseAgentTool {
    private final Project project;

    public RagSearchTool(Project project) {
        super("search_project_code",
                "Searches for related classes, methods, and code based on a query. Returns relevant code signatures and snippets.");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String query = "";
        int maxResults = 5;

        // Handle different parameter formats
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
        }

        if (query.isEmpty()) {
            return "Error: Please provide a search query.";
        }

        try {
            OpenWebUIRagAgent openWebUIRagAgent = OpenWebUIRagAgent.getInstance(project);

            // Search for related code
            List<OpenWebUIRagAgent.CodeMatch> matches = openWebUIRagAgent.findRelatedCode(query)
                    .get(30, TimeUnit.SECONDS);

            if (matches.isEmpty()) {
                return "No relevant code found for query: " + query;
            }

            StringBuilder result = new StringBuilder();
            result.append("Found ").append(matches.size()).append(" relevant code elements:\n\n");

            int count = 0;
            for (OpenWebUIRagAgent.CodeMatch match : matches) {
                if (count >= maxResults) break;

                CodeSignature sig = match.getSignature();
                result.append("## ").append(sig.getId()).append("\n");
                result.append("**Signature:** `").append(sig.getSignature()).append("`\n");
                result.append("**File:** ").append(sig.getFilePath()).append("\n");
                result.append("**Relevance:** ").append(String.format("%.2f", match.getRelevance())).append("\n");

                // Get full code if it's highly relevant
                if (match.getRelevance() > 0.5) {
                    String fullCode = openWebUIRagAgent.getFullCode(sig.getId());
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

    @Override
    public JsonObject getExampleParams() {
        JsonObject example = new JsonObject();
        example.addProperty("query", "authentication logic");
        example.addProperty("maxResults", 10);
        return example;
    }
}
