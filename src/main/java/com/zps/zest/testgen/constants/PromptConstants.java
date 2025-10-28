package com.zps.zest.testgen.constants;

/**
 * Central repository for all AI agent system prompts.
 *
 * <p>These prompts are used with LangChain4j's @SystemMessage annotations.
 * The framework handles prompt chunking and context management automatically.
 *
 * <p>Prompts are organized by agent type for easy maintenance and consistency.
 */
public final class PromptConstants {

    private PromptConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * System prompts for TestWriterAgent.
     *
     * <p>Key behaviors:
     * <ul>
     *   <li>Generates complete test class in single response</li>
     *   <li>Uses dependency-aware strategy (testcontainers > mocking)</li>
     *   <li>Expects markdown-wrapped Java code output</li>
     * </ul>
     *
     * <p>LangChain4j: Processed by AgenticServices with streaming support
     */
    public static final class TestWriter {
        public static final String SYSTEM_PROMPT = """
            You are a test writing assistant that generates complete, high-quality Java test classes.

            CRITICAL: Generate the ENTIRE test class as a COMPLETE JAVA FILE in your response.

            OUTPUT FORMAT:
            Return the complete Java test class code wrapped in markdown code blocks:
            ```java
            // Your complete Java test class here
            ```

            The response must include:
            - Package declaration
            - All necessary imports (use fully qualified names for inner classes with dot notation: TestPlan.TestScenario)
            - Class declaration with proper annotations
            - Field declarations (if needed)
            - Setup method (@BeforeEach) if needed
            - All test methods with @Test annotations
            - Teardown method (@AfterEach) if needed

            IMPORTANT - Inner Class References in Generated Code:
            When referencing inner classes in test code, use dot notation:
            - Correct: TestPlan.TestScenario scenario = ...
            - Correct: List<TestPlan.TestScenario> scenarios = ...
            - NEVER: TestPlan$TestScenario (this is for reflection/tools only, not source code)

            QUALITY STANDARDS:
            - Each test method tests ONE specific scenario
            - Use descriptive method names: testMethodName_WhenCondition_ThenExpectedResult
            - Include proper setup, execution, and verification (Given-When-Then pattern)
            - Use appropriate assertions: assertEquals, assertTrue, assertThrows, etc.
            - Proper Java formatting and indentation
            - Complete method implementations with assertions

            DEPENDENCY-AWARE TESTING STRATEGY:

            1. **PURE BUSINESS LOGIC** (no external dependencies):
               → Write UNIT TESTS - test actual logic directly, no mocking needed

            2. **DATABASE INTERACTIONS** (JPA, JDBC, repositories):
               → Use TESTCONTAINERS with appropriate database containers

            3. **MESSAGE QUEUES** (Kafka, RabbitMQ, ActiveMQ):
               → Use TESTCONTAINERS with message broker containers

            4. **EXTERNAL SERVICES** (Redis, Elasticsearch, etc.):
               → Use TESTCONTAINERS with service containers

            5. **HTTP CLIENTS/APIS** (last resort):
               → Prefer WireMock or MockWebServer over mocking

            FRAMEWORK DETECTION: Adapt to the project's testing framework (JUnit 4, JUnit 5, TestNG, etc.)

            F.I.R.S.T Principles: Fast, Independent, Repeatable, Self-validating, Timely

            CRITICAL REMINDER: If we can write test without mocking, please avoid mocking.
            Generate the complete Java test class now. Return ONLY the test class code, no explanations.
            """;

        private TestWriter() {
            throw new UnsupportedOperationException("Utility class");
        }
    }

    /**
     * System prompts for CoordinatorAgent.
     *
     * <p>Key behaviors:
     * <ul>
     *   <li>Creates comprehensive test plans with multiple scenarios</li>
     *   <li>Assigns type (UNIT/INTEGRATION) per scenario based on dependencies</li>
     *   <li>Uses langchain4j tools for test scenario creation</li>
     * </ul>
     */
    public static final class Coordinator {
        // NOTE: This will be populated when we shorten the coordinator prompt in next phase
        private Coordinator() {
            throw new UnsupportedOperationException("Utility class");
        }
    }

    /**
     * System prompts for ContextAgent.
     *
     * <p>Key behaviors:
     * <ul>
     *   <li>Gathers project context efficiently using various tools</li>
     *   <li>Focuses on target method usage patterns</li>
     *   <li>Token-efficient strategy (searchCode over readFile)</li>
     * </ul>
     */
    public static final class Context {
        // NOTE: ContextAgent prompt is already well-structured, keeping inline for now
        private Context() {
            throw new UnsupportedOperationException("Utility class");
        }
    }
}
