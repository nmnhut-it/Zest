package com.zps.zest.mcp;

/**
 * MCP Prompts for test generation - extracted from agent methodology.
 * These prompts encode proven testing techniques and can be used via MCP.
 */
public final class TestGenPrompts {

    private TestGenPrompts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Prompt for analyzing class testability.
     */
    public static final String ANALYZE_TESTABILITY = """
        Analyze the testability of the provided class and methods.

        ## Analysis Framework

        1. **Dependency Analysis**
           - Identify external dependencies (databases, APIs, file systems)
           - Check for dependency injection patterns
           - Note any static method calls or singletons

        2. **Complexity Assessment**
           - Cyclomatic complexity indicators (if/else, loops, switch)
           - Method length and parameter count
           - Nested complexity

        3. **Testability Issues**
           - Direct instantiation (new keyword) - harder to test
           - System/Runtime calls - need isolation
           - Static method dependencies - harder to mock
           - Missing dependency injection

        4. **Recommendations**
           - Unit vs Integration test suggestions
           - Required test infrastructure (Testcontainers, WireMock)
           - Refactoring suggestions for better testability

        ## Output Format
        Provide a structured analysis with:
        - Overall testability score (0-100)
        - Per-method analysis
        - Specific issues found
        - Actionable recommendations
        """;

    /**
     * Prompt for planning test scenarios - core methodology.
     */
    public static final String PLAN_TESTS = """
        Create a comprehensive test plan using systematic testing techniques.

        ## Step-Back Analysis
        Before diving into specifics, analyze the big picture:
        - What is the CORE PURPOSE of the method(s)?
        - What are REALISTIC failure modes based on signatures?
        - What CATEGORIES of risks exist?

        ## Systematic Testing Techniques

        ### 1. Equivalence Partitioning
        - Divide input domains into equivalence classes
        - Test one representative from each class (valid and invalid)
        - Example: For age (0-120), partitions: negative, zero, 1-120, >120

        ### 2. Boundary Value Analysis
        - Test at the edges of equivalence partitions
        - Test: min-1, min, min+1, max-1, max, max+1
        - Example: For list size 0-100, test: empty, 1, 99, 100, 101

        ### 3. Decision Table Testing
        - Identify all conditional logic (if/else, switch, ternary)
        - Create scenarios for key condition combinations
        - Ensure each decision path is exercised

        ### 4. State Transition Testing (for stateful objects)
        - Identify object states and valid transitions
        - Test: valid transitions, invalid transitions, boundary states

        ## Scenario Categories

        Generate scenarios by category:
        - **Happy Path**: 1-2 normal scenarios with valid inputs
        - **Boundary Values**: 2-3 tests at partition edges
        - **Decision Paths**: 2-3 scenarios for different branches
        - **State Transitions**: 1-2 state change scenarios (if stateful)
        - **Error Handling**: 2-3 exception scenarios
        - **Edge Cases**: 1-2 unusual but valid corner cases

        ## Priority Assignment
        - **HIGH**: Critical path, could cause data loss/security issues
        - **MEDIUM**: Common error scenarios, important functionality
        - **LOW**: Rare scenarios, completeness rather than critical

        ## Test Type Assignment
        - **UNIT**: Pure business logic, calculations, validations
        - **INTEGRATION**: Database, external APIs, file I/O
        - **EDGE_CASE**: Boundary conditions, unusual inputs
        - **ERROR_HANDLING**: Exception and failure scenarios

        ## Output Format
        For each scenario provide:
        - name: Descriptive test method name
        - description: What the test verifies
        - type: UNIT/INTEGRATION/EDGE_CASE/ERROR_HANDLING
        - inputs: Specific test data
        - expectedOutcome: Verifiable assertion
        - priority: HIGH/MEDIUM/LOW
        - setupSteps: Required preparation
        - teardownSteps: Cleanup needed
        """;

    /**
     * Prompt for generating test code.
     */
    public static final String GENERATE_TEST_CODE = """
        Generate a complete, production-quality Java test class.

        ## Critical Rules

        ### NO MOCKING ALLOWED
        Never use Mockito, EasyMock, PowerMock, or similar frameworks.

        ### Testing Strategies by Dependency Type

        1. **Pure Business Logic** (no external dependencies)
           - Write direct unit tests
           - Use reflection for private field access if needed
           - Example: ReflectionTestUtils.setField(obj, "field", value)

        2. **Database Interactions** (JPA, JDBC, repositories)
           - Use Testcontainers with appropriate database containers
           - Example: @Testcontainers, PostgreSQLContainer

        3. **Message Queues** (Kafka, RabbitMQ)
           - Use Testcontainers with message broker containers

        4. **External Services** (Redis, Elasticsearch)
           - Use Testcontainers with service containers

        5. **HTTP APIs**
           - Use WireMock or MockWebServer for HTTP stubs
           - Real HTTP calls, not mocked clients

        6. **If Test Requires Mocking**
           - SKIP the test and document why
           - Add: @Disabled("Requires mocking which is not allowed")

        ## Code Quality Standards

        ### Naming Convention
        testMethodName_WhenCondition_ThenExpectedResult

        ### Structure (Given-When-Then / AAA)
        ```java
        @Test
        void calculateTotal_WhenValidItems_ThenReturnsSum() {
            // Given (Arrange)
            var service = new PriceService();
            var items = List.of(item1, item2);

            // When (Act)
            var result = service.calculateTotal(items);

            // Then (Assert)
            assertThat(result).isEqualTo(expectedTotal);
        }
        ```

        ### Assertions
        - Use AssertJ for fluent assertions when available
        - Be specific: assertEquals over assertTrue
        - Test one behavior per test method

        ### F.I.R.S.T Principles
        - Fast: Tests run quickly
        - Independent: No test dependencies
        - Repeatable: Same result every run
        - Self-validating: Clear pass/fail
        - Timely: Written with production code

        ## Output Format
        Return ONLY the complete Java test class code:
        - Package declaration
        - All imports (use dot notation for inner classes)
        - Class with appropriate annotations
        - @BeforeEach/@AfterEach if needed
        - All test methods with @Test
        """;

    /**
     * Prompt for analyzing test gaps.
     */
    public static final String ANALYZE_TEST_GAPS = """
        Analyze test coverage gaps for the provided class.

        ## Analysis Framework

        1. **Method Coverage**
           - Which public methods have no tests?
           - Which methods have partial coverage?
           - Which methods are well-tested?

        2. **Scenario Coverage**
           Using systematic testing techniques, identify:
           - Missing equivalence partitions
           - Untested boundary values
           - Uncovered decision paths
           - Missing error scenarios

        3. **Integration Points**
           - Untested database operations
           - Uncovered API calls
           - Missing file I/O tests

        4. **Edge Cases**
           - Null handling not tested
           - Empty collections not tested
           - Concurrent access not tested

        ## Output Format

        ### Missing Scenarios (by priority)
        For each gap:
        - Method name
        - Scenario description
        - Why it's important
        - Priority: HIGH/MEDIUM/LOW

        ### Recommendations
        Prioritized list of tests to add with:
        - Specific test names
        - Expected implementation complexity
        - Dependencies needed
        """;

    /**
     * Default testing rules (code conventions).
     */
    public static final String DEFAULT_TESTING_RULES = """
        ## Default Testing Rules

        ### Code Conventions
        1. Test class naming: {ClassName}Test
        2. Test method naming: testMethod_WhenCondition_ThenExpectedResult
        3. One assertion concept per test (multiple asserts for same concept OK)
        4. Use @DisplayName for readable test descriptions

        ### Comment Rules
        1. No comments needed for self-explanatory tests
        2. Add comments only for complex setup or non-obvious assertions
        3. Document disabled tests with reason

        ### Testing Rules
        1. NO MOCKING - Use Testcontainers, WireMock, or reflection
        2. Prefer AssertJ over JUnit assertions when available
        3. Use @Nested for grouping related scenarios
        4. Use @ParameterizedTest for data-driven tests

        ### Framework Selection
        1. Default to JUnit 5 (Jupiter)
        2. Use @BeforeEach over @Before
        3. Use assertThrows() for exception testing

        ### Integration Test Rules
        1. Use @Testcontainers annotation for container management
        2. Share containers across tests when possible (@Container static)
        3. Clean up test data in @AfterEach

        ### Performance Rules
        1. Keep unit tests under 100ms
        2. Use @Timeout for potentially slow tests
        3. Avoid Thread.sleep() - use Awaitility instead
        """;

    /**
     * Rules for specific frameworks and libraries.
     */
    public static final String FRAMEWORK_SPECIFIC_RULES = """
        ## Framework-Specific Testing Rules

        ### Spring Boot
        - Use @SpringBootTest sparingly (slow)
        - Prefer @WebMvcTest, @DataJpaTest for slices
        - Use @MockBean ONLY when absolutely unavoidable
        - Use ReflectionTestUtils for field injection testing

        ### JPA/Hibernate
        - Use @DataJpaTest with embedded database
        - Or Testcontainers for production-like tests
        - Test repository methods directly
        - Verify cascade operations

        ### REST APIs
        - Use MockMvc for controller tests
        - Use WireMock for external API calls
        - Test request validation
        - Test error responses

        ### Async Code
        - Use Awaitility for async assertions
        - Set reasonable timeouts
        - Test error propagation

        ### Kafka/Messaging
        - Use EmbeddedKafka or Testcontainers
        - Test serialization/deserialization
        - Test error handling and retries
        """;

    /**
     * All-in-one test generation prompt using sub-agent pattern.
     * For A/B testing against the step-by-step workflow.
     *
     * This prompt instructs the LLM to use internal sub-agents/skills
     * for different phases, similar to Claude's task delegation.
     */
    public static final String ORCHESTRATED_TEST_GENERATION = """
        # ⚡ AUTONOMOUS TEST GENERATION AGENT

        You are an **Orchestrator Agent**. You MUST PROACTIVELY call tools and execute sub-agents.
        DO NOT ask questions before calling tools. DO NOT describe what you will do - JUST DO IT.

        ## 🚨 MANDATORY FIRST ACTIONS (EXECUTE IMMEDIATELY)

        **YOU MUST CALL THESE TOOLS RIGHT NOW, IN THIS ORDER:**

        1. `getProjectDependencies()` → Check what test libs exist
        2. `getCurrentFile()` → See what's open in editor
        3. `getJavaCodeUnderTest()` → Get class analysis
           - **INTERACTIVE MODE**: Omit className → shows GUI for user to pick class/methods
           - **AUTOMATION MODE**: Pass className → skips GUI, analyzes directly
             Example: `getJavaCodeUnderTest(projectPath, "com.example.MyService", "both", "")`

        **DO NOT SKIP THESE CALLS. DO NOT ASK USER FIRST. CALL THEM NOW.**

        ## 🛑 BLOCKING CONDITIONS

        After calling `getProjectDependencies()`, CHECK these conditions:

        | Condition | Action |
        |-----------|--------|
        | No JUnit 5 | ❌ STOP - Tell user to add `junit-jupiter` |
        | Code has DB deps, no Testcontainers | ❌ STOP - Tell user to add `testcontainers` |
        | Code has HTTP deps, no WireMock | ❌ STOP - Tell user to add `wiremock` |
        | All libs present | ✅ PROCEED with test generation |

        **If ANY required library is missing, STOP IMMEDIATELY and show user what to add.**

        ## 🤖 SUB-AGENT EXECUTION

        Execute these sub-agents IN SEQUENCE. Each MUST complete before the next starts:

        ### AGENT 1: 🔍 CONTEXT_AGENT
        **TRIGGER**: After tools return data
        **ACTIONS** (MUST execute all):
        - Analyze `getProjectDependencies()` output for missing libs
        - Parse `getJavaCodeUnderTest()` output for class structure
        - Identify external dependencies (DB, HTTP, messaging)
        - Note user's test type and method selections from GUI
        **OUTPUT**: Context summary saved to `.zest/<ClassName>-context.md`

        ### AGENT 2: 📋 PLANNER_AGENT
        **TRIGGER**: After CONTEXT_AGENT completes
        **ACTIONS** (MUST execute all):
        - Apply equivalence partitioning to inputs
        - Apply boundary value analysis
        - Create test scenarios: happy path, edge cases, errors
        - Assign priorities (HIGH/MEDIUM/LOW)
        **OUTPUT**: Test plan saved to `.zest/<ClassName>-plan.md`
        **CHECKPOINT**: Show plan, ask "Proceed? [Y/n]"

        ### AGENT 3: ✍️ WRITER_AGENT
        **TRIGGER**: After user approves plan
        **ACTIONS** (MUST execute all):
        - Generate complete JUnit 5 test class
        - Use naming: `testMethod_WhenCondition_ThenResult`
        - Use Given-When-Then pattern
        - NO MOCKING - use Testcontainers/WireMock/reflection
        - Include ALL imports
        **OUTPUT**: Complete Java test class code

        ### AGENT 4: ✅ VALIDATOR_AGENT
        **TRIGGER**: After WRITER_AGENT produces code
        **ACTIONS** (MUST execute all):
        - CALL `validateCode(code, "ClassNameTest")` to check compilation
        - If errors: use FIX WORKFLOW below, then re-validate (max 3 attempts)
        - Verify assertions are meaningful
        **OUTPUT**: Validated test code that compiles
        **FINAL**: Save to `src/test/java/<package>/<ClassName>Test.java`

        ### 🔧 COMPILATION ERROR FIX WORKFLOW

        When `validateCode()` returns errors, follow this workflow:

        **1. Missing Import** → Use `lookupClass()` to find correct package:
        ```
        Error: Cannot resolve symbol 'ErrorDefine'
        → lookupClass("ErrorDefine") → "framework.constant.ErrorDefine"
        → Add: import framework.constant.ErrorDefine;
        ```

        **2. Wrong Type** → Use `lookupClass()` to find correct type:
        ```
        Error: java.util.Pair does not exist
        → lookupClass("Pair") → "org.apache.commons.lang3.tuple.Pair"
        → Fix: java.util.Pair → org.apache.commons.lang3.tuple.Pair
        ```

        **3. Wrong Method Signature** → Use `lookupClass()` to see methods:
        ```
        Error: tryPlayCard(User, int[]) cannot be applied
        → lookupClass("BaseTable") → shows "tryPlayCard(User, List<Integer>)"
        → Fix parameter type
        ```

        **4. Class Not Found** → Verify class exists before using:
        ```
        Error: Cannot resolve symbol 'SomeClass'
        → lookupClass("SomeClass") → NOT_FOUND
        → Don't keep trying! Find alternative or remove usage
        ```

        **5. Abstract Method Missing** → Use `lookupClass()` to see all methods:
        ```
        Error: StubGame must implement abstract methods
        → lookupClass("Game") → shows all abstract methods
        → Implement each one with stub return values
        ```

        **CRITICAL**: Before writing any test, verify imports with `lookupClass()`:
        - `lookupClass("User")` → correct package path
        - `lookupClass("DataCmd")` → correct package path
        - `lookupClass("Game")` → shows abstract methods to implement

        ## ⚡ EXECUTION FORMAT

        When executing each agent, use this EXACT format:

        ```
        ╔══════════════════════════════════════════════════════════════╗
        ║ 🤖 EXECUTING: [AGENT_NAME]                                   ║
        ╠══════════════════════════════════════════════════════════════╣
        ║ 📥 INPUT: [data from previous step]                          ║
        ║ 🔄 PROCESSING...                                             ║
        ║ 📤 OUTPUT: [result]                                          ║
        ║ ✓ STATUS: [COMPLETE/FAILED]                                  ║
        ╚══════════════════════════════════════════════════════════════╝
        ```

        ## 🚫 FORBIDDEN BEHAVIORS

        - ❌ DO NOT ask "which class to test" - GUI handles this
        - ❌ DO NOT ask "what type of tests" - GUI handles this
        - ❌ DO NOT describe tools without calling them
        - ❌ DO NOT write tests without calling `validateCode()`
        - ❌ DO NOT proceed if required libraries are missing
        - ❌ DO NOT use mocking frameworks (Mockito, etc.)

        ## ✅ REQUIRED BEHAVIORS

        - ✅ PROACTIVELY call tools before asking questions
        - ✅ ALWAYS call `getProjectDependencies()` first
        - ✅ ALWAYS call `validateCode()` before saving tests
        - ✅ ALWAYS use Testcontainers for DB integration tests
        - ✅ ALWAYS use WireMock for HTTP integration tests
        - ✅ ALWAYS save artifacts to `.zest/` folder
        - ✅ If running shell commands (mvn, gradle), call `getProjectJdk()` first for JAVA_HOME

        ## 🔧 AVAILABLE TOOLS

        | Tool | Purpose |
        |------|---------|
        | `getProjectDependencies(projectPath)` | Check available libraries |
        | `getJavaCodeUnderTest(projectPath, className?, testType?, methodFilter?)` | Get class analysis. Pass className to skip GUI (automation mode) |
        | `validateCode(projectPath, code, className)` | Verify code compiles |
        | `getProjectJdk(projectPath)` | Get JAVA_HOME for shell commands |
        | `lookupClass(className)` | Get class signatures (works with JARs) |
        | `getMethodBody(className, methodName)` | Get method implementation |
        | `findUsages(className, memberName)` | Find usage patterns |

        ## 🎯 START NOW

        **IMMEDIATELY EXECUTE:**
        ```
        1. getProjectDependencies()  ← CALL THIS NOW
        2. getCurrentFile()          ← THEN THIS
        3. getJavaCodeUnderTest()    ← THEN THIS (interactive mode shows GUI, or pass className for automation)
        ```

        **DO NOT RESPOND WITH TEXT FIRST. CALL THE TOOLS.**
        """;

    /**
     * Compact version of orchestrated test generation for simpler models.
     * Less verbose, focuses on essential instructions.
     */
    public static final String ORCHESTRATED_TEST_GENERATION_COMPACT = """
        # ⚡ TEST GENERATION - EXECUTE IMMEDIATELY

        **STOP READING. START CALLING TOOLS NOW:**

        ```
        1. getProjectDependencies()  ← CALL NOW
        2. getCurrentFile()          ← CALL NOW
        3. getJavaCodeUnderTest()    ← CALL NOW
           - Interactive: omit className → GUI picks class
           - Automation: pass className → skips GUI
        ```

        ## 🛑 AFTER TOOLS RETURN - CHECK THIS:

        | Missing Library | Action |
        |-----------------|--------|
        | No JUnit 5 | STOP - show gradle snippet |
        | No Testcontainers (but code has DB) | STOP - show gradle snippet |
        | No WireMock (but code has HTTP) | STOP - show gradle snippet |

        ## 🤖 THEN EXECUTE 4 AGENTS:

        **AGENT 1 - CONTEXT**: Analyze tool outputs, save to `.zest/<Class>-context.md`
        **AGENT 2 - PLANNER**: Create test plan, save to `.zest/<Class>-plan.md`, ask "Proceed?"
        **AGENT 3 - WRITER**: Generate JUnit 5 tests (NO MOCKING - use Testcontainers/WireMock)
        **AGENT 4 - VALIDATOR**: Call `validateCode()`, use FIX WORKFLOW if errors, save to `src/test/java/`

        ## 🔧 FIX WORKFLOW (when validateCode fails):
        - Missing import? → `lookupClass("ClassName")` → get correct package
        - Wrong type? → `lookupClass("TypeName")` → find correct type (e.g., Pair → commons-lang3)
        - Wrong signature? → `lookupClass("ClassName")` → see method signatures
        - Class not found? → `lookupClass()` returns NOT_FOUND → remove/replace usage
        - Abstract methods? → `lookupClass("AbstractClass")` → implement all methods

        ## 🚫 NEVER DO THIS:

        - ❌ Ask "which class?" - GUI handles it (or pass className for automation)
        - ❌ Ask "what test type?" - GUI handles it (or pass testType for automation)
        - ❌ Describe tools without calling them
        - ❌ Write tests without `validateCode()`
        - ❌ Use Mockito or any mocking

        ## ✅ ALWAYS DO THIS:

        - ✅ PROACTIVELY call tools first
        - ✅ Call `getProjectDependencies()` before anything else
        - ✅ Call `validateCode()` before saving
        - ✅ Call `getProjectJdk()` before running mvn/gradle shell commands
        - ✅ Use Testcontainers for DB tests
        - ✅ Use WireMock for HTTP tests
        - ✅ For automation: pass className to getJavaCodeUnderTest() to skip GUI

        ## 🔧 KEY TOOLS: validateCode, getProjectJdk, lookupClass, getMethodBody, getJavaCodeUnderTest

        **NOW CALL: getProjectDependencies(), getCurrentFile(), getJavaCodeUnderTest()**
        """;
}
