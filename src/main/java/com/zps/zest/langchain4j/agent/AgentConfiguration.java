package com.zps.zest.langchain4j.agent;

/**
 * Configuration constants for the ToolCallingAutonomousAgent.
 * Centralizes tuning parameters for better control over agent behavior.
 */
public final class AgentConfiguration {
    
    // Prevent instantiation
    private AgentConfiguration() {}
    
    /**
     * Maximum number of tool calls allowed in a single exploration session.
     * Higher values allow deeper exploration but increase cost and time.
     */
    public static final int MAX_TOOL_CALLS = 20;
    
    /**
     * Maximum number of exploration rounds.
     * Each round can generate multiple tool calls.
     */
    public static final int MAX_ROUNDS = 5;
    
    /**
     * Number of tool calls to generate in the initial planning phase.
     */
    public static final int INITIAL_TOOL_CALLS = 5;
    
    /**
     * Number of tool calls to generate per exploration round.
     */
    public static final int TOOLS_PER_ROUND = 5;
    
    /**
     * Maximum length for truncated text in prompts.
     */
    public static final int MAX_PROMPT_TEXT_LENGTH = 500;
    
    /**
     * Number of recent executions to include in exploration prompts.
     */
    public static final int RECENT_EXECUTIONS_COUNT = 5;
    
    /**
     * Minimum result length to consider a tool execution as a "key finding".
     */
    public static final int KEY_FINDING_MIN_LENGTH = 100;
    
    /**
     * Maximum number of key findings to include in summaries.
     */
    public static final int MAX_KEY_FINDINGS = 10;
    
    /**
     * Tool execution priorities (lower number = higher priority).
     * Used to guide the order of tool execution.
     */
    public static final class ToolPriorities {
        public static final int SEARCH_CODE = 1;
        public static final int FIND_BY_NAME = 1;
        public static final int GET_CURRENT_CONTEXT = 2;
        public static final int LIST_FILES_IN_DIRECTORY = 3;
        public static final int FIND_SIMILAR = 3;
        public static final int READ_FILE = 4;
        public static final int GET_CLASS_INFO = 4;
        public static final int FIND_METHODS = 4;
        public static final int FIND_RELATIONSHIPS = 5;
        public static final int FIND_CALLERS = 5;
        public static final int FIND_IMPLEMENTATIONS = 5;
        public static final int FIND_USAGES = 5;
    }
    
    /**
     * Patterns for common exploration scenarios.
     */
    public static final class ExplorationPatterns {
        
        public static final String HOW_DOES_X_WORK = """
            1. Use search_code or find_by_name to locate X
            2. Use read_file to see implementation
            3. Use find_relationships to understand dependencies
            4. Use find_callers to see usage patterns
            """;
        
        public static final String FIND_ALL_IMPLEMENTATIONS = """
            1. Use find_by_name to locate interface/abstract class
            2. Use find_implementations to get all implementations
            3. Use read_file on each implementation
            4. Use find_similar to find related patterns
            """;
        
        public static final String WHAT_USES_X = """
            1. Use find_by_name to locate X
            2. Use find_usages or find_relationships with USED_BY
            3. Use read_file to examine usage contexts
            4. Use find_callers for method-level usage
            """;
        
        public static final String ARCHITECTURE_EXPLORATION = """
            1. Use search_code with architectural terms
            2. Use list_files_in_directory to understand structure
            3. Use find_relationships to map dependencies
            4. Use get_class_info for structural details
            """;
        
        public static final String DEBUG_ISSUE = """
            1. Use search_code with error terms or symptoms
            2. Use find_callers to trace execution paths
            3. Use read_file to examine implementations
            4. Use find_relationships to check dependencies
            """;
    }
    
    /**
     * Tips for effective tool usage.
     */
    public static final class ToolUsageTips {
        
        public static final String SEARCH_CODE_TIPS = """
            - Use technical terms and full context
            - Be specific: "user authentication validation" not just "validation"
            - Include domain concepts: "payment processing" not just "payment"
            """;
        
        public static final String FIND_BY_NAME_TIPS = """
            - Case-sensitive! Use exact casing
            - Supports partial matches: "User" finds UserService, UserDao
            - Use fully qualified names when known
            """;
        
        public static final String RELATIONSHIPS_TIPS = """
            - Always specify relationType for focused results
            - CALLED_BY is great for understanding usage
            - IMPLEMENTS/IMPLEMENTED_BY for interface exploration
            """;
    }
}
