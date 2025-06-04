package com.zps.zest.langchain4j.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of the exploration session.
 */
public class ExplorationResult {
    private final List<ExplorationRound> rounds = new ArrayList<>();
    private String summary;
    private boolean success = false;
    private final List<String> errors = new ArrayList<>();

    public void addRound(ExplorationRound round) {
        rounds.add(round);
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void addError(String error) {
        errors.add(error);
    }

    // Getters
    public List<ExplorationRound> getRounds() {
        return rounds;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getErrors() {
        return errors;
    }
}
