package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a question that the AI coordinator wants to ask the user during test planning.
 * Similar to Claude Code's AskUserQuestion functionality.
 */
public class UserQuestion {

    public enum QuestionType {
        SINGLE_CHOICE,    // User can select one option
        MULTI_CHOICE,     // User can select multiple options
        FREE_TEXT         // User can type free-form answer
    }

    private final String questionId;
    private final String questionText;
    private final String header;  // Short label (max 12 chars)
    private final QuestionType type;
    private final List<QuestionOption> options;

    // User's answer (set after user responds)
    private List<String> selectedOptions;
    private String freeTextAnswer;

    public UserQuestion(@NotNull String questionId,
                       @NotNull String questionText,
                       @NotNull String header,
                       @NotNull QuestionType type,
                       @NotNull List<QuestionOption> options) {
        this.questionId = questionId;
        this.questionText = questionText;
        this.header = header;
        this.type = type;
        this.options = new ArrayList<>(options);
        this.selectedOptions = new ArrayList<>();
    }

    public static class QuestionOption {
        private final String label;
        private final String description;

        public QuestionOption(@NotNull String label, @NotNull String description) {
            this.label = label;
            this.description = description;
        }

        @NotNull
        public String getLabel() {
            return label;
        }

        @NotNull
        public String getDescription() {
            return description;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            QuestionOption that = (QuestionOption) o;
            return Objects.equals(label, that.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label);
        }
    }

    @NotNull
    public String getQuestionId() {
        return questionId;
    }

    @NotNull
    public String getQuestionText() {
        return questionText;
    }

    @NotNull
    public String getHeader() {
        return header;
    }

    @NotNull
    public QuestionType getType() {
        return type;
    }

    @NotNull
    public List<QuestionOption> getOptions() {
        return new ArrayList<>(options);
    }

    @Nullable
    public List<String> getSelectedOptions() {
        return selectedOptions != null ? new ArrayList<>(selectedOptions) : null;
    }

    public void setSelectedOptions(@NotNull List<String> selectedOptions) {
        this.selectedOptions = new ArrayList<>(selectedOptions);
    }

    @Nullable
    public String getFreeTextAnswer() {
        return freeTextAnswer;
    }

    public void setFreeTextAnswer(@NotNull String freeTextAnswer) {
        this.freeTextAnswer = freeTextAnswer;
    }

    /**
     * Check if the question has been answered
     */
    public boolean isAnswered() {
        if (type == QuestionType.FREE_TEXT) {
            return freeTextAnswer != null && !freeTextAnswer.trim().isEmpty();
        } else {
            return selectedOptions != null && !selectedOptions.isEmpty();
        }
    }

    /**
     * Get the answer as a string for the LLM
     */
    @NotNull
    public String getAnswerForLLM() {
        if (!isAnswered()) {
            return "NOT_ANSWERED";
        }

        if (type == QuestionType.FREE_TEXT) {
            return freeTextAnswer;
        } else {
            return String.join(", ", selectedOptions);
        }
    }
}