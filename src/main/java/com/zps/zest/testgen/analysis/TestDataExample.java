package com.zps.zest.testgen.analysis;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a real example of test data extracted from actual code usage.
 * These examples are more realistic than generated test data.
 */
public class TestDataExample {
    private final String parameterName;
    private final String value;
    private final String type;
    private final String source; // Where this example was found
    private final boolean isValid; // Is this a valid or invalid example?

    public TestDataExample(@NotNull String parameterName, @NotNull String value,
                          @NotNull String type, @NotNull String source, boolean isValid) {
        this.parameterName = parameterName;
        this.value = value;
        this.type = type;
        this.source = source;
        this.isValid = isValid;
    }

    @NotNull
    public String getParameterName() {
        return parameterName;
    }

    @NotNull
    public String getValue() {
        return value;
    }

    @NotNull
    public String getType() {
        return type;
    }

    @NotNull
    public String getSource() {
        return source;
    }

    public boolean isValid() {
        return isValid;
    }

    @NotNull
    public String getLabel() {
        return (isValid ? "✓ Valid" : "✗ Invalid") + " " + parameterName + " (" + type + ")";
    }

    @NotNull
    public String formatForLLM() {
        return String.format("%s = %s // %s (from: %s)",
                           parameterName, value, isValid ? "valid" : "invalid", source);
    }

    @Override
    public String toString() {
        return "TestDataExample{" +
               "param=" + parameterName +
               ", value='" + value + '\'' +
               ", valid=" + isValid +
               '}';
    }

    /**
     * Factory methods for common test data patterns
     */
    @NotNull
    public static TestDataExample fromLiteral(@NotNull String paramName, @NotNull String literal,
                                             @NotNull String type, @NotNull String source) {
        return new TestDataExample(paramName, literal, type, source, true);
    }

    @NotNull
    public static TestDataExample fromBuilder(@NotNull String paramName, @NotNull String builderCode,
                                             @NotNull String type, @NotNull String source) {
        return new TestDataExample(paramName, builderCode, type, source, true);
    }

    @NotNull
    public static TestDataExample nullValue(@NotNull String paramName, @NotNull String type, @NotNull String source) {
        return new TestDataExample(paramName, "null", type, source, false);
    }

    @NotNull
    public static TestDataExample invalidValue(@NotNull String paramName, @NotNull String value,
                                               @NotNull String type, @NotNull String source) {
        return new TestDataExample(paramName, value, type, source, false);
    }
}
