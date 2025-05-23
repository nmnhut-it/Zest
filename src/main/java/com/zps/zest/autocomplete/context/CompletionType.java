package com.zps.zest.autocomplete.context;

/**
 * Types of completion contexts.
 */
public enum CompletionType {
    GENERAL,                    // General code completion
    MEMBER_ACCESS,             // After dot operator
    CONSTRUCTOR_CALL,          // After 'new' keyword
    CLASS_MEMBER_DECLARATION,  // Class-level declarations
    STATEMENT_START,           // Beginning of statement
    EXPRESSION                 // Within expression
}
