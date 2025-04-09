package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeProblemsAnalyzer;

public class AnalyzeCodeProblemsTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(AnalyzeCodeProblemsTool.class);
    private final Project project;

    public AnalyzeCodeProblemsTool(Project project) {
        super("analyze_code_problems", "Analyzes code problems in the specified scope. The scope could be 'project', 'current_file' or 'directory'");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String scope = getStringParam(params, "scope", null);
        String path = getStringParam(params, "path", null);
        String filter = getStringParam(params, "filter", "");

        if (scope == null || scope.isEmpty()) {
            return "Error: Scope is required";
        }
        if (path == null || path.isEmpty()) {
            return "Error: Path is required";
        }

        return analyzeCodeProblems(scope, path, filter);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("scope", "project"); // or "current_file" or "directory"
        params.addProperty("path", "path/to/file_or_directory");
        params.addProperty("filter", "error:"); // or "warning:" or "all:"
        return params;
    }

    private String analyzeCodeProblems(String scope, String path, String filter) {
        try {
            // Create analyzer and apply filters
            CodeProblemsAnalyzer analyzer = new CodeProblemsAnalyzer(project);
            // Apply text filter if remaining
            if (!filter.isEmpty()) {
                analyzer.setTextFilter(filter);
            }
            // Analyze code and return results
            return analyzer.analyzeCode(scope, path);
        } catch (Exception e) {
            LOG.error("Error analyzing code problems: " + e.getMessage(), e);
            return "Error analyzing code problems: " + e.getMessage();
        }
    }
}