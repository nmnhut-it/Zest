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
    public static final int INITIAL_TOOL_CALLS = 2;
    
    /**
     * Number of tool calls to generate per exploration round.
     */
    public static final int TOOLS_PER_ROUND = 2;
    
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
     * Source vs Test exploration balance configuration.
     */
    public static final class SourceTestBalance {
        /**
         * Target percentage of source code exploration (0.7 = 70%)
         */
        public static final double TARGET_SOURCE_RATIO = 0.70;
        
        /**
         * Target percentage of test code exploration (0.3 = 30%)
         */
        public static final double TARGET_TEST_RATIO = 0.30;
        
        /**
         * Upper threshold for source ratio - if exceeded, prioritize test exploration
         */
        public static final double SOURCE_RATIO_UPPER_THRESHOLD = 0.75;
        
        /**
         * Lower threshold for source ratio - if below, prioritize source exploration
         */
        public static final double SOURCE_RATIO_LOWER_THRESHOLD = 0.65;
        
        /**
         * Minimum number of test files to explore
         */
        public static final int MIN_TEST_FILES = 3;
        
        /**
         * Test file patterns
         */
        public static final String[] TEST_FILE_PATTERNS = {
            "Test.java", "Tests.java", "Spec.java", "IT.java", // Integration Test
            "/test/", "/tests/", "\\test\\", "\\tests\\"
        };
        
        /**
         * Test annotation patterns
         */
        public static final String[] TEST_ANNOTATIONS = {
            "@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory",
            "@BeforeEach", "@AfterEach", "@BeforeAll", "@AfterAll"
        };
    }
    
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
            3. Use find_by_name to locate XTest or test files for X
            4. Use read_file on tests to understand expected behavior
            5. Use find_relationships to understand dependencies
            6. Use find_callers to see usage patterns
            """;
        
        public static final String FIND_ALL_IMPLEMENTATIONS = """
            1. Use find_by_name to locate interface/abstract class
            2. Use find_implementations to get all implementations
            3. Use read_file on each implementation
            4. Use find_by_name to locate test files for key implementations
            5. Use read_file on tests to see usage examples
            6. Use find_similar to find related patterns
            """;
        
        public static final String WHAT_USES_X = """
            1. Use find_by_name to locate X
            2. Use find_usages or find_relationships with USED_BY
            3. Check both source and test usages (tests often show best practices)
            4. Use read_file to examine usage contexts
            5. Pay special attention to test usages for documentation
            """;
        
        public static final String ARCHITECTURE_EXPLORATION = """
            1. Use search_code with architectural terms
            2. Use list_files_in_directory to understand structure
            3. Explore both /src/main and /src/test structures
            4. Use find_relationships to map dependencies
            5. Use get_class_info for structural details
            6. Check test organization for insights into component boundaries
            """;
        
        public static final String DEBUG_ISSUE = """
            1. Use search_code with error terms or symptoms
            2. Use find_callers to trace execution paths
            3. Use read_file to examine implementations
            4. Look for related test files - they often test edge cases
            5. Use find_relationships to check dependencies
            6. Check test files for expected vs actual behavior
            """;
        
        public static final String UNDERSTAND_FEATURE = """
            1. Use search_code with feature-related terms
            2. Use find_by_name for main feature classes
            3. Use read_file on implementation files
            4. ALWAYS check test files for feature specifications
            5. Use find_relationships to map feature components
            6. Tests often document business requirements
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
