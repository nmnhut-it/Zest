package com.zps.zest.langchain4j.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents one round of exploration.
 */
public class ExplorationRound {
    private final String name;
    private String llmResponse;
    private final List<ToolExecution> toolExecutions = new ArrayList<>();

    public ExplorationRound(String name) {
        this.name = name;
    }

    public ExplorationRound(String name, String llmResponse, List<ToolExecution> executions) {
        this.name = name;
        this.llmResponse = llmResponse;
        this.toolExecutions.addAll(executions);
    }

    public void setLlmResponse(String response) {
        this.llmResponse = response;
    }

    public void addToolExecution(ToolExecution execution) {
        toolExecutions.add(execution);
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getLlmResponse() {
        return llmResponse;
    }

    public List<ToolExecution> getToolExecutions() {
        return toolExecutions;
    }
}
