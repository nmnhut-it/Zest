package com.zps.zest.util;

/**
 * Enum representing different LLM usage scenarios for metrics tracking.
 * Used by NaiveLLMService and test generation agents.
 */
public enum LLMUsage {
    // Test generation agents
    AGENT_COORDINATOR("agent_coordinator"),
    AGENT_TEST_WRITING("agent_test_writing"),
    AGENT_TEST_WRITER("agent_test_writer"),
    AGENT_TEST_MERGER("agent_test_merger"),
    AGENT_CONTEXT("agent_context"),
    AGENT_CONTEXT_ANALYZER("agent_context_analyzer"),
    LANGCHAIN_TEST_GENERATION("langchain_test_generation"),
    CHAT_CODE_REVIEW("chat_code_review"),

    // General usage
    TEST_GENERATION("test_generation"),
    EXPLORE_TOOL("explore_tool"),
    CODE_HEALTH("code_health"),
    LLM_SERVICE("llm_service"),
    GENERAL("general");

    private final String value;

    LLMUsage(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
