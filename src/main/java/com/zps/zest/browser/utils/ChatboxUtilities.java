package com.zps.zest.browser.utils;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Utility class for manipulating the chat box interface in the integrated browser.
 * Provides reliable methods for sending text to the chat box and triggering the send button.
 */
public class ChatboxUtilities {
    private static final Logger LOG = Logger.getInstance(ChatboxUtilities.class);
    private static final long PAGE_LOAD_TIMEOUT_SECONDS = 1;

    //    public static boolean newChat(Project project, String model) {
//        return newChat(project, model, null);
//    }

    public enum EnumUsage {
        // Agent-based actions (step-by-step with human interaction)
        AGENT_TEST_WRITING,           // Agent: Step-by-Step Test Writing
        AGENT_REFACTORING,            // Agent: Step-by-Step Refactor for Testability
        AGENT_ONE_CLICK_TEST,         // Agent: One-click Write Test
        AGENT_GENERATE_COMMENTS,      // Agent: Write Comment for Selected Text

        // Implementation actions
        IMPLEMENT_TODOS,              // Implement Your TODOs
        INLINE_COMPLETION,            // Inline completion
        INLINE_COMPLETION_LOGGING,    // Inline completion metrics logging

        // Chat-based actions
        CHAT_CODE_REVIEW,             // Chat: Review This Class
        CHAT_REFACTOR_ADVISORY,       // Chat: Refactor Advisory for Testability
        CHAT_WRITE_TESTS,             // Chat: Write Tests for This Class
        CHAT_GIT_COMMIT_MESSAGE,      // Chat: Generate Git Commit Message
        CHAT_QUICK_COMMIT,      // Chat: Generate Git Commit Message

        // Code Health
        CODE_HEALTH,                  // Code Health Analysis
        CODE_HEALTH_LOGGING,          // Code Health Analysis logging 
        
        // Quick Action
        QUICK_ACTION_LOGGING,         // Quick Action Metrics Logging

        // LangChain4j specific usages
        LANGCHAIN_TEST_GENERATION,    // LangChain4j test generation (generic)
        LANGCHAIN_CONTEXT_AGENT,      // LangChain4j context analysis
        LANGCHAIN_EMBEDDING,          // LangChain4j embedding generation
        LANGCHAIN_RETRIEVAL,          // LangChain4j RAG retrieval
        LANGCHAIN_GENERAL,            // LangChain4j general purpose
        
        // Specific agent usages
        AGENT_TEST_WRITER,            // TestWriterAgent - writes unit tests
        AGENT_CONTEXT_ANALYZER,       // ContextAgent - analyzes code context
        AGENT_COORDINATOR,            // CoordinatorAgent - coordinates multiple agents
        AGENT_TEST_MERGER,            // TestMergerAgent - merges test results
        AGENT_QUERY_TRANSFORMER,      // Query transformer agents (compressing/expanding)

        EXPLORE_TOOL, // Developer tools
//        TOGGLE_DEV_TOOLS              // Toggle ZPS Chat Developer Tools
         LLM_SERVICE
    }

}
