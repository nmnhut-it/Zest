package com.zps.zest.testgen.constants;

/**
 * Constants for user-facing messages in the test generation UI.
 *
 * <p>Centralizes all UI strings including status messages, section headers,
 * and error prefixes to ensure consistency and ease internationalization if needed.
 */
public enum UIMessageConstants {

    // Test Writer Messages
    GENERATING_TESTS("✍️ Generating complete test class...\n\n"),
    REQUEST_HEADER("📋 Request:\n"),
    ASSISTANT_RESPONSE("🤖 Assistant Response:\n"),
    SEPARATOR("-".repeat(40) + "\n"),
    PARSING_CLASS("🔄 Parsing generated test class...\n"),
    PARSING_COMPLETE("✅ Parsing completed!\n"),

    // Section headers for context building
    SECTION_TARGET_CLASS("**TARGET CLASS INFO**\n```\n"),
    SECTION_TEST_SCENARIOS("**TEST SCENARIOS TO IMPLEMENT**\n```\n"),
    SECTION_CODE_CONTEXT("**CODE CONTEXT**\n```\n"),
    SECTION_INSTRUCTIONS("**INSTRUCTIONS**\n```\n"),
    SECTION_ANALYZED_CLASSES("**ALL ANALYZED CLASSES (WITH USAGE CONTEXT)**\n```\n"),
    SECTION_METHOD_USAGE("**METHOD USAGE PATTERNS (HOW CODE IS ACTUALLY USED)**\n```\n"),
    SECTION_RELATED_FILES("**RELATED FILES**\n```\n"),
    SECTION_CONTEXT_INSIGHTS("**CONTEXT INSIGHTS**\n```\n"),

    // Status indicators
    ERROR_PREFIX("❌ Error: "),
    WARNING_PREFIX("⚠️ Warning: "),
    SUCCESS_PREFIX("✅ "),
    INFO_PREFIX("ℹ️ "),

    // Summary labels
    SUMMARY_HEADER("📊 Test Generation Summary:\n"),
    SUMMARY_TEST_CLASS("  - Test class: "),
    SUMMARY_PACKAGE("  - Package: "),
    SUMMARY_FRAMEWORK("  - Framework: "),
    SUMMARY_METHOD_COUNT("  - Methods generated: "),
    SUMMARY_LOC("  - Lines of code: "),

    // Error messages
    ERROR_TEST_GENERATION("Test generation failed: "),
    ERROR_STREAMING("Test generation streaming failed"),
    ERROR_NO_METHODS("No test methods were generated"),

    // Context messages
    CONTEXT_SUMMARIZED("(Context automatically summarized with usage analysis to fit token limits)\n\n"),
    CONTEXT_FILE_SUMMARIZED("(File contents automatically summarized to fit token limits)\n\n"),
    TOKEN_BUDGET_OK("Token budget OK, using enhanced summarization for quality improvement"),

    // Detected info labels
    DETECTED_CLASS("  - Detected class: "),
    DETECTED_PACKAGE("  - Detected package: "),
    DETECTED_FRAMEWORK("  - Detected framework: "),
    EXTRACTED_IMPORTS("  - Extracted "),
    IMPORTS_LABEL(" imports\n"),
    EXTRACTED_FIELDS("  - Extracted "),
    FIELDS_LABEL(" fields\n"),
    EXTRACTED_METHODS("  - Extracted "),
    TEST_METHODS_LABEL(" test methods\n");

    private final String message;

    UIMessageConstants(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Get message with formatted arguments.
     * Usage: UIMessageConstants.ERROR_PREFIX.format("Connection timeout")
     */
    public String format(Object... args) {
        return String.format(message, args);
    }

    /**
     * Append additional text to the message.
     * Usage: UIMessageConstants.ERROR_PREFIX.append("Connection failed")
     */
    public String append(String suffix) {
        return message + suffix;
    }

    @Override
    public String toString() {
        return message;
    }
}
