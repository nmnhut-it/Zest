package com.zps.zest.testgen.analysis;

import org.jetbrains.annotations.NotNull;

/**
 * Represents an edge case discovered from analyzing how code is used in practice.
 * These are more reliable than guessed edge cases because they're based on actual usage patterns.
 */
public class EdgeCase {
    private final EdgeCaseType type;
    private final String description;
    private final String discoverySource; // Where we found this edge case
    private final int confidence; // 0-100, how confident we are this needs testing

    public enum EdgeCaseType {
        NULL_HANDLING("Null value handling"),
        EMPTY_COLLECTION("Empty collection handling"),
        BOUNDARY_VALUE("Boundary value"),
        EXCEPTION_CASE("Exception/error condition"),
        CONCURRENT_ACCESS("Concurrent access pattern"),
        VALIDATION_FAILURE("Validation failure"),
        OPTIONAL_ABSENT("Optional.empty() case"),
        NEGATIVE_VALUE("Negative or invalid value"),
        DUPLICATE_ENTRY("Duplicate entry handling"),
        NOT_FOUND("Entity not found case");

        private final String displayName;

        EdgeCaseType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public EdgeCase(@NotNull EdgeCaseType type, @NotNull String description, @NotNull String discoverySource, int confidence) {
        this.type = type;
        this.description = description;
        this.discoverySource = discoverySource;
        this.confidence = Math.max(0, Math.min(100, confidence)); // Clamp to 0-100
    }

    @NotNull
    public EdgeCaseType getType() {
        return type;
    }

    @NotNull
    public String getDescription() {
        return description;
    }

    @NotNull
    public String getDiscoverySource() {
        return discoverySource;
    }

    public int getConfidence() {
        return confidence;
    }

    public boolean isHighConfidence() {
        return confidence >= 70;
    }

    @NotNull
    public String formatForLLM() {
        return String.format("%s: %s (found in: %s, confidence: %d%%)",
                           type.getDisplayName(), description, discoverySource, confidence);
    }

    @Override
    public String toString() {
        return "EdgeCase{" +
               "type=" + type +
               ", description='" + description + '\'' +
               ", confidence=" + confidence +
               '}';
    }

    /**
     * Factory methods for common edge case patterns
     */
    public static EdgeCase nullCheck(@NotNull String parameter, @NotNull String callerMethod) {
        return new EdgeCase(
            EdgeCaseType.NULL_HANDLING,
            "Parameter '" + parameter + "' can be null",
            "Null check in " + callerMethod,
            85
        );
    }

    public static EdgeCase optionalEmpty(@NotNull String methodCall, @NotNull String callerMethod) {
        return new EdgeCase(
            EdgeCaseType.OPTIONAL_ABSENT,
            "Method '" + methodCall + "' returns Optional.empty()",
            "Optional handling in " + callerMethod,
            80
        );
    }

    public static EdgeCase exceptionHandled(@NotNull String exceptionType, @NotNull String callerMethod) {
        return new EdgeCase(
            EdgeCaseType.EXCEPTION_CASE,
            "Exception '" + exceptionType + "' is thrown and caught",
            "Try-catch in " + callerMethod,
            90
        );
    }

    public static EdgeCase boundaryValue(@NotNull String parameter, @NotNull String value, @NotNull String source) {
        return new EdgeCase(
            EdgeCaseType.BOUNDARY_VALUE,
            "Parameter '" + parameter + "' tested with boundary value: " + value,
            source,
            75
        );
    }

    public static EdgeCase validationFailure(@NotNull String condition, @NotNull String source) {
        return new EdgeCase(
            EdgeCaseType.VALIDATION_FAILURE,
            "Validation fails when: " + condition,
            source,
            80
        );
    }
}
