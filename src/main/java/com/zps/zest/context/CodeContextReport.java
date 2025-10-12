package com.zps.zest.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured report from code context exploration.
 * Contains detailed context with code snippets, architecture, dependencies, and insights.
 */
public class CodeContextReport {

    public enum Scope {
        CLASS, METHOD, FEATURE, PACKAGE
    }

    public enum Focus {
        ARCHITECTURE, USAGE, DEPENDENCIES, ALL
    }

    private final String target;
    private final Scope scope;
    private final Focus focus;
    private String summary;
    private final List<CodeSnippet> codeSnippets;
    private final List<String> relatedFiles;
    private final List<String> dependencies;
    private final List<String> insights;
    private String architectureNotes;
    private String usageNotes;

    public CodeContextReport(String target, Scope scope, Focus focus) {
        this.target = target;
        this.scope = scope;
        this.focus = focus;
        this.summary = "";
        this.codeSnippets = new ArrayList<>();
        this.relatedFiles = new ArrayList<>();
        this.dependencies = new ArrayList<>();
        this.insights = new ArrayList<>();
        this.architectureNotes = "";
        this.usageNotes = "";
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setArchitectureNotes(String architectureNotes) {
        this.architectureNotes = architectureNotes;
    }

    public void setUsageNotes(String usageNotes) {
        this.usageNotes = usageNotes;
    }

    public void addCodeSnippet(String file, String code, String description) {
        this.codeSnippets.add(new CodeSnippet(file, code, description));
    }

    public void addRelatedFile(String file) {
        if (!this.relatedFiles.contains(file)) {
            this.relatedFiles.add(file);
        }
    }

    public void addDependency(String dependency) {
        if (!this.dependencies.contains(dependency)) {
            this.dependencies.add(dependency);
        }
    }

    public void addInsight(String insight) {
        this.insights.add(insight);
    }

    public String toMarkdown() {
        // If summary contains a full LLM-generated report (starts with #), use it directly
        if (!summary.isEmpty() && (summary.startsWith("#") || summary.contains("## Overview"))) {
            StringBuilder md = new StringBuilder();
            md.append("# Context Report: ").append(target).append("\n\n");
            md.append("**Scope**: ").append(scope).append("  \n");
            md.append("**Focus**: ").append(focus).append("\n\n");
            md.append(summary);
            return md.toString();
        }

        // Otherwise, build from structured data (fallback)
        StringBuilder md = new StringBuilder();

        md.append("# Context Report: ").append(target).append("\n\n");
        md.append("**Scope**: ").append(scope).append("  \n");
        md.append("**Focus**: ").append(focus).append("\n\n");

        if (!summary.isEmpty()) {
            md.append("## Overview\n\n");
            md.append(summary).append("\n\n");
        }

        if (!architectureNotes.isEmpty()) {
            md.append("## Architecture & Structure\n\n");
            md.append(architectureNotes).append("\n\n");
        }

        if (!codeSnippets.isEmpty()) {
            md.append("## Key Code Snippets\n\n");
            for (CodeSnippet snippet : codeSnippets) {
                md.append("### ").append(snippet.file).append("\n\n");
                if (!snippet.description.isEmpty()) {
                    md.append(snippet.description).append("\n\n");
                }
                md.append("```\n");
                md.append(snippet.code);
                if (!snippet.code.endsWith("\n")) {
                    md.append("\n");
                }
                md.append("```\n\n");
            }
        }

        if (!dependencies.isEmpty()) {
            md.append("## Dependencies\n\n");
            for (String dep : dependencies) {
                md.append("- ").append(dep).append("\n");
            }
            md.append("\n");
        }

        if (!usageNotes.isEmpty()) {
            md.append("## Usage Patterns\n\n");
            md.append(usageNotes).append("\n\n");
        }

        if (!insights.isEmpty()) {
            md.append("## Notes & Insights\n\n");
            for (String insight : insights) {
                md.append("- ").append(insight).append("\n");
            }
            md.append("\n");
        }

        if (!relatedFiles.isEmpty()) {
            md.append("## Related Files\n\n");
            for (String file : relatedFiles) {
                md.append("- ").append(file).append("\n");
            }
            md.append("\n");
        }

        return md.toString();
    }

    public static class CodeSnippet {
        private final String file;
        private final String code;
        private final String description;

        public CodeSnippet(String file, String code, String description) {
            this.file = file;
            this.code = code;
            this.description = description;
        }

        public String getFile() {
            return file;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }
}
