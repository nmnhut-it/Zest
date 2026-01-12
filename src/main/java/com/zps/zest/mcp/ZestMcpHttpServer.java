package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.testgen.tools.LookupClassTool;
import com.zps.zest.testgen.tools.LookupMethodTool;
import com.zps.zest.testgen.analysis.UsageAnalyzer;
import com.zps.zest.testgen.analysis.UsageContext;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP HTTP Server for Zest using SSE transport over HTTP.
 * Provides IntelliJ project tools via HTTP endpoints using MCP protocol.
 *
 * The server creates two endpoints:
 * - Message endpoint (POST): Receives JSON-RPC requests from clients
 * - SSE endpoint (GET): Streams server-to-client events and notifications
 *
 * Per MCP spec, the HttpServletSseServerTransport handles both endpoints internally.
 */
public class ZestMcpHttpServer {
    private static final Logger LOG = Logger.getInstance(ZestMcpHttpServer.class);
    private static final String MESSAGE_ENDPOINT = "/mcp";

    private final McpSyncServer mcpServer;
    private final Server jettyServer;
    private final HttpServletSseServerTransportProvider transport;
    private final int port;
    private final Gson gson;

    public ZestMcpHttpServer(int port) {
        this.port = port;
        this.gson = new Gson();

        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);

        this.transport = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(MESSAGE_ENDPOINT)
                .build();

        this.mcpServer = McpServer.sync(transport)
                .jsonMapper(mcpJsonMapper)
                .serverInfo("zest-intellij-http-tools", "1.0.0")
                .jsonSchemaValidator(new DefaultJsonSchemaValidator())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .prompts(true)
                        .logging()
                        .build())
                .build();

        registerTools();
        registerPrompts();

        this.jettyServer = createJettyServer();

        LOG.info("Zest MCP HTTP Server created on port " + port);
    }

    private Server createJettyServer() {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("mcp-http-server");
        threadPool.setMinThreads(2);
        threadPool.setMaxThreads(10);

        Server server = new Server(threadPool);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder transportServlet = new ServletHolder(transport);
        context.addServlet(transportServlet, "/*");

        server.setHandler(context);

        return server;
    }

    private void registerTools() {
        ObjectMapper mapper = new ObjectMapper();
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);

        McpSchema.Tool currentFileTool = McpSchema.Tool.builder()
                .name("getCurrentFile")
                .description("Get the currently open file in the editor for a specific IntelliJ project")
                .inputSchema(jsonMapper, buildGetCurrentFileSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                currentFileTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetCurrentFile(projectPath);
                }
        ));

        McpSchema.Tool lookupMethodTool = McpSchema.Tool.builder()
                .name("lookupMethod")
                .description("Look up method signatures using fully qualified class name and method name. Works with project classes, library JARs, and JDK classes.")
                .inputSchema(jsonMapper, buildLookupMethodSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                lookupMethodTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleLookupMethod(projectPath, className, methodName);
                }
        ));

        McpSchema.Tool lookupClassTool = McpSchema.Tool.builder()
                .name("lookupClass")
                .description("Look up class implementation using fully qualified class name. Works with project classes, library JARs, and JDK classes. For inner classes, use $ separator.")
                .inputSchema(jsonMapper, buildLookupClassSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                lookupClassTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleLookupClass(projectPath, className);
                }
        ));

        McpSchema.Tool analyzeMethodUsageTool = McpSchema.Tool.builder()
                .name("analyzeMethodUsage")
                .description("Analyze how a method is used in the codebase - discovers edge cases, error handling patterns, and integration contexts from REAL usage")
                .inputSchema(jsonMapper, buildAnalyzeMethodUsageSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                analyzeMethodUsageTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleAnalyzeMethodUsage(projectPath, className, memberName);
                }
        ));

        // Testgen tools
        McpSchema.Tool getJavaCodeUnderTestTool = McpSchema.Tool.builder()
                .name("getJavaCodeUnderTest")
                .description("Interactive GUI to select Java code to test, returns complete source + static analysis")
                .inputSchema(jsonMapper, buildGetJavaCodeUnderTestSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getJavaCodeUnderTestTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetJavaCodeUnderTest(projectPath);
                }
        ));

        McpSchema.Tool validateCodeTool = McpSchema.Tool.builder()
                .name("validateCode")
                .description("Validate Java code for compilation errors. Returns error count and detailed error messages with code context.")
                .inputSchema(jsonMapper, buildValidateCodeSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                validateCodeTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String code = (String) arguments.get("code");
                    String className = (String) arguments.get("className");
                    return handleValidateCode(projectPath, code, className);
                }
        ));

        McpSchema.Tool showFileTool = McpSchema.Tool.builder()
                .name("showFile")
                .description("Open a file in IntelliJ editor to present it to the user. Use this to show generated test files, context files, or any file the user should see.")
                .inputSchema(jsonMapper, buildShowFileSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                showFileTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String filePath = (String) arguments.get("filePath");
                    return handleShowFile(projectPath, filePath);
                }
        ));

        // Refactor tools
        McpSchema.Tool askUserTool = McpSchema.Tool.builder()
                .name("askUser")
                .description("Ask user a question via IntelliJ dialog. Supports single choice, multiple choice, and free text. Returns user's answer.")
                .inputSchema(jsonMapper, buildAskUserSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                askUserTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String questionText = (String) arguments.get("questionText");
                    String questionType = (String) arguments.get("questionType");
                    List<Map<String, String>> options = (List<Map<String, String>>) arguments.get("options");
                    String header = (String) arguments.get("header");
                    return handleAskUser(projectPath, questionText, questionType, options, header);
                }
        ));

        McpSchema.Tool analyzeRefactorabilityTool = McpSchema.Tool.builder()
                .name("analyzeRefactorability")
                .description("Analyze code for refactoring opportunities using IntelliJ inspections and PSI analysis. Returns findings categorized by impact (TESTABILITY, COMPLEXITY, CODE_SMELLS) with specific line references and team rules from .zest/rules.md")
                .inputSchema(jsonMapper, buildAnalyzeRefactorabilitySchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                analyzeRefactorabilityTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String focusArea = (String) arguments.get("focusArea");
                    return handleAnalyzeRefactorability(projectPath, className, focusArea);
                }
        ));

        // Test coverage tools
        McpSchema.Tool getCoverageTool = McpSchema.Tool.builder()
                .name("getCoverageData")
                .description("Get current test coverage data for a class from IntelliJ's coverage runner. Returns coverage percentages per method and overall class coverage.")
                .inputSchema(jsonMapper, buildGetCoverageSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getCoverageTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetCoverageData(projectPath, className);
                }
        ));

        McpSchema.Tool analyzeCoverageTool = McpSchema.Tool.builder()
                .name("analyzeCoverage")
                .description("Analyze test coverage and get improvement suggestions. Returns uncovered methods, coverage percentage, and actionable suggestions.")
                .inputSchema(jsonMapper, buildGetCoverageSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                analyzeCoverageTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleAnalyzeCoverage(projectPath, className);
                }
        ));

        McpSchema.Tool getTestInfoTool = McpSchema.Tool.builder()
                .name("getTestInfo")
                .description("Get information about test class and test methods for a given class. Detects test framework (JUnit 4/5, TestNG) and counts test methods.")
                .inputSchema(jsonMapper, buildGetCoverageSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getTestInfoTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetTestInfo(projectPath, className);
                }
        ));

        McpSchema.Tool getBuildInfoTool = McpSchema.Tool.builder()
                .name("getBuildInfo")
                .description("Get comprehensive build system information: build tool (Gradle/Maven), Java SDK paths, wrapper scripts, test commands. Essential for running builds correctly.")
                .inputSchema(jsonMapper, buildProjectPathSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getBuildInfoTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetBuildInfo(projectPath);
                }
        ));

        LOG.info("Registered 13 MCP tools: getCurrentFile, lookupMethod, lookupClass, analyzeMethodUsage, getJavaCodeUnderTest, validateCode, showFile, askUser, analyzeRefactorability, getCoverageData, analyzeCoverage, getTestInfo, getBuildInfo");
    }

    private void registerPrompts() {
        registerPrompt("review", "Review code quality and suggest improvements",
                List.of(),
                """
                You are an experienced code reviewer. Guide users through interactive code review with multiple-choice options.

                WORKFLOW:

                PHASE 1: CHOOSE WHAT TO REVIEW

                Ask user to choose:
                ```
                What would you like to review?

                1. Current file (the file open in editor)

                2. Specify custom files or classes

                Type 1 or 2
                ```

                Then:
                - Option 1: Use getCurrentFile() tool
                - Option 2: Wait for user to specify files, then use lookupClass() to read them

                PHASE 2: ANALYZE USAGE PATTERNS (CRITICAL!)

                Before reviewing implementation, understand how code is ACTUALLY used:

                1. Extract Classes/Methods
                   Parse the code to identify key classes and methods

                2. Analyze Real Usage
                   Tool: analyzeMethodUsage(className, methodName)
                   Purpose: Discover edge cases from REAL production code

                   What to look for:
                   - "8 out of 15 callers do null checks → method CAN return null"
                   - "3 callers wrap in try-catch for NotFoundException"
                   - "2 callers use @Transactional annotation"
                   - Common patterns and integration contexts

                3. Get Class Structure
                   Tool: lookupClass(className)
                   Purpose: Understand inheritance, methods, fields

                PHASE 3: REVIEW IMPLEMENTATION

                Now review the actual code with insights from usage analysis.

                REVIEW CATEGORIES:

                **BUGS & CORRECTNESS:**
                - Null safety: Missing null checks that real callers handle
                - Logic errors: Incorrect conditions, off-by-one errors
                - Resource leaks: Unclosed streams, connections
                - Thread safety: Race conditions, synchronization issues

                **SECURITY:**
                - Injection vulnerabilities: SQL, XSS, command injection
                - Authentication/authorization flaws
                - Hardcoded credentials or sensitive data
                - Input validation gaps

                **PERFORMANCE:**
                - Algorithm efficiency: O(n²) where O(n) possible
                - Database issues: N+1 queries, missing indexes
                - Memory leaks: Holding unnecessary references
                - Caching opportunities

                **CODE QUALITY:**
                - Naming: Unclear variable/method names
                - Complexity: Methods >30 lines, deep nesting
                - Duplication: Repeated code blocks
                - Testability: Tight coupling, no dependency injection

                OUTPUT FORMAT:

                For each issue:
                1. **Category**: [BUGS/SECURITY/PERFORMANCE/QUALITY]
                2. **Severity**: [Critical/High/Medium/Low]
                3. **Location**: Class/method/line
                4. **Issue**: What's wrong
                5. **Evidence**: What analyzeMethodUsage revealed (if applicable)
                6. **Fix**: Specific solution with code example
                7. **Impact**: Who/what is affected (from usage analysis)

                Summary:
                - **Quality Rating**: Poor/Fair/Good/Excellent
                - **Top 3 Priorities**: Most critical fixes
                - **Edge Cases Found**: From usage analysis
                - **Affected Callers**: How many call sites would be impacted by fixes

                TIPS:
                - ALWAYS analyze usage before reviewing implementation
                - Usage patterns reveal hidden requirements and edge cases
                - Focus on issues that affect real callers
                """);

        registerPrompt("explain", "Explain how code works",
                List.of(),
                """
                You are a technical educator. Guide users through interactive code explanation with multiple-choice options.

                WORKFLOW:

                PHASE 1: CHOOSE WHAT TO EXPLAIN

                Ask user to choose:
                ```
                What would you like me to explain?

                1. Current file (the file open in editor)

                2. Specify custom files or classes

                Type 1 or 2
                ```

                Then:
                - Option 1: Use getCurrentFile() tool
                - Option 2: Wait for user to specify files/classes, then use lookupClass() to look them up

                PHASE 2: ANALYZE STRUCTURE & USAGE

                1. Get Class Structure
                   Tool: lookupClass(className)
                   Purpose: Understand class structure and responsibilities
                   Extract: Class purpose, methods, inheritance

                2. See Real-World Usage
                   Tool: analyzeMethodUsage(className, methodName)
                   Purpose: Show how code is ACTUALLY used in practice
                   Why: Real examples are better than theoretical descriptions

                   Examples:
                   - "Controllers call this with userId from request.getParameter()"
                   - "Service layer wraps calls in @Transactional context"
                   - "Tests use mockUserId = 123 as example data"

                3. Understand Dependencies (if complex)
                   Tool: lookupClass(dependencyClassName)
                   Purpose: Explain related classes and their roles

                PHASE 3: EXPLAIN CLEARLY

                Structure your explanation:

                **PURPOSE** (What problem does it solve?)
                - The business/technical problem this code addresses
                - Expected inputs and outputs
                - Typical use case

                **HOW IT WORKS** (The algorithm/approach)
                - Main algorithm or strategy used
                - Key data structures
                - Step-by-step flow of execution

                **REAL-WORLD USAGE** (How it's actually used)
                - Examples from analyzeMethodUsage
                - Common patterns callers follow
                - Integration context (transactions, async, loops, etc.)

                **IMPORTANT DETAILS** (What to watch out for)
                - Edge cases: null handling, empty collections, boundaries
                - Design patterns: Strategy, Factory, Observer, etc.
                - Performance: Time/space complexity
                - Thread safety: Synchronization needs

                **DEPENDENCIES** (What it relies on)
                - Related classes and their purposes
                - External systems or libraries
                - Assumptions and preconditions

                TIPS:
                - Use clear language, avoid jargon
                - Explain technical terms when you use them
                - Provide concrete examples from usage analysis
                - Show real code snippets from callers when helpful
                """);

        registerPrompt("commit", "Interactive git commit assistant",
                List.of(),
                """
                You are a git commit assistant. Help users create clean, well-structured commits.

                WORKFLOW:

                1. CHECK STATUS
                   Run: git status
                   Show user what files are modified/staged/untracked

                2. SMART STAGING
                   Group related files logically and ask user:
                   - "Stage all changes?" or
                   - Present 2-3 logical groupings (by feature, by type)
                   Run: git add <files>

                3. GENERATE COMMIT MESSAGE
                   Run: git diff --cached
                   Create message following these rules:

                   FORMAT: <type>(<scope>): <subject>

                   TYPES: feat, fix, refactor, test, docs, chore, style, perf

                   RULES:
                   - Subject max 50 chars, imperative mood ("add" not "added")
                   - Lowercase, no period at end
                   - Scope is optional but helpful (e.g., mcp, auth, ui)

                   Offer 2-3 message options, let user pick or customize

                4. EXECUTE
                   Run: git commit -m "message"
                   Confirm success

                INTERACTION:
                - Be concise, use numbered options
                - Warn if mixing unrelated changes
                - Ask before any destructive action 
                - Max 3 questions
                """);

        // ========== Test Generation Workflow Prompts ==========
        // These prompts guide modern AI agents through test generation workflow.
        // Each prompt produces a markdown file that feeds into the next step.

        registerPrompt("zest-test-context", "Gather context for test generation",
                List.of(),
                """
                You are a test context gatherer. Collect information needed to write comprehensive tests.
                **INTERACTIVE workflow** - ask clarifying questions before proceeding.

                ## AVAILABLE TOOLS

                **MCP Tools (Zest/IntelliJ):**
                - `getJavaCodeUnderTest`: Shows class picker, returns static analysis, creates session
                - `lookupClass`: Look up class signatures (project, JARs, JDK)
                - `lookupMethod`: Look up specific method signatures
                - `analyzeMethodUsage`: Find call sites and usage patterns
                - `showFile`: Open a file in IntelliJ editor to present to user

                **Agent's Built-in Tools:**
                - Search codebase for patterns
                - Read files
                - Write to files

                ## WORKFLOW

                ### Phase 1: Get Static Analysis
                Call `getJavaCodeUnderTest` - this returns:
                - ✅ Source code with line numbers
                - ✅ Public method signatures
                - ✅ Usage analysis (call sites, error patterns from real callers)
                - ✅ Related class signatures (dependencies)
                - ✅ External dependency detection (DB, HTTP, etc.)

                ### Phase 2: Review What's Already Known
                Present to user what the static analysis found:
                - "[N] public methods found: [list them]"
                - "[M] call sites analyzed showing usage patterns"
                - "Dependencies detected: [list]"

                ### Phase 3: ASK User (up to 3 questions)

                **Q1 - Test Scope:**
                "Which methods should I focus on?"
                - All public methods
                - Specific methods: [list them as options]
                - Only complex methods (methods with dependencies)

                **Q2 - Test Type:**
                "What type of tests?"
                - Unit tests (fast, isolated)
                - Integration tests (real dependencies)
                - Both

                **Q3 - Priority:**
                "Testing priority?"
                - Happy paths first
                - Edge cases and error handling
                - Cover specific scenarios: [ask user to describe]

                ### Phase 4: Explore Gaps (if needed)
                Based on user's answers, explore what static analysis CANNOT capture:

                **Only explore if CRUCIAL for testing:**
                - **SCHEMA**: External files (SQL, configs) → Read file
                - **INDIRECT**: Reflection, observers, event handlers → Search codebase
                - **TESTS**: Existing test patterns to match → Search for "@Test.*ClassName"

                **Skip exploration if:**
                - Pre-computed usage shows clear patterns
                - Methods are straightforward
                - User wants simple unit tests only

                Use ReAct pattern for each exploration:
                🤔 Thought: What specific info do I need?
                🎯 Action: [tool call]
                👁️ Observation: What did I find?
                ⚡ Next: Continue or done?

                ### Phase 5: Persist Data & Confirm

                **IMPORTANT**: APPEND to the existing context file - do NOT overwrite.
                The file already contains static analysis from Phase 1. Add your findings at the end.

                **Append** the following sections to `.zest/<ClassName>-context.md`:
                ```markdown
                ---

                ## User Testing Goals
                - Scope: [answer]
                - Type: [answer]
                - Priority: [answer]
                - Additional requests: [any specific scenarios user mentioned]

                ## Additional Findings
                [only if explored - with file:line references]
                ```

                ### Phase 6: Show Context File & Next Steps

                1. Use `showFile` to open the context file in IntelliJ so user can review it
                2. Tell user:

                ```
                ✅ Context saved to: .zest/<ClassName>-context.md

                📋 NEXT STEP: To save tokens, run one of these:
                   - `/clear` - Clear conversation and run `/zest-test-plan`
                   - Start a new conversation and run `/zest-test-plan`

                The context file contains all information needed for the next step.
                ```
                """);

        registerPrompt("zest-test-plan", "Create a test plan from context",
                List.of(),
                """
                You are a test architect. Create a test plan using systematic testing techniques.
                **INTERACTIVE workflow** - ask clarifying questions before finalizing.

                ## FILE LOCATIONS
                - **Context file**: `.zest/<ClassName>-context.md` (read this first)
                - **Plan file**: `.zest/<ClassName>-plan.md` (write here)

                ## AVAILABLE TOOLS

                **MCP Tools (Zest/IntelliJ):**
                - `lookupClass`, `lookupMethod`: Look up signatures if needed
                - `showFile`: Open a file in IntelliJ editor to present to user

                **Agent's Built-in Tools:**
                - Read files (context file, existing tests)
                - Search codebase for existing test patterns
                - Write to files (test plan)

                ## WORKFLOW

                ### Phase 1: Load Context
                Read `.zest/<ClassName>-context.md` to get:
                - Source code and method signatures
                - Usage analysis (call sites, patterns)
                - User's testing goals from context gathering phase

                ### Phase 2: Analyze & Draft Scenarios
                Apply systematic testing techniques based on user's goals:

                **Techniques to apply:**
                - **Equivalence Partitioning**: Divide inputs into valid/invalid classes
                - **Boundary Value Analysis**: Test at edges (min, max, empty, null)
                - **Decision Table**: Cover all conditional branches
                - **State Transitions**: For stateful objects

                **Draft scenarios for each method in scope:**
                - Happy path: Normal valid input → expected output
                - Boundaries: Edge cases from input domains
                - Errors: Invalid inputs, nulls, exceptions

                ### Phase 3: ASK User (up to 3 questions)

                Present draft summary and ask for feedback:

                **Q1 - Scenario Review:**
                "I've drafted [N] test scenarios:
                - [X] happy path
                - [Y] boundary cases
                - [Z] error handling

                Any to add/remove/modify?"

                **Q2 - Test Data:**
                "For [method], I'll use these inputs: [list]
                Any specific values you want tested?"

                **Q3 - Dependencies (if detected):**
                "External dependencies found: [list]
                How to handle?
                - Testcontainers (real DB)
                - WireMock (HTTP mock)
                - Skip integration tests"

                ### Phase 4: Finalize & Write Plan

                Incorporate feedback and write to `.zest/<ClassName>-plan.md`:

                ```markdown
                # Test Plan: <ClassName>

                ## Overview
                - **Class**: [full class name]
                - **Test Type**: UNIT | INTEGRATION
                - **Framework**: JUnit 5 (or detected)
                - **Total Scenarios**: N

                ## User Preferences
                - Scope: [from context]
                - Priority: [from context]
                - Feedback: [from Phase 3]

                ## Test Scenarios

                ### 1. methodName_scenario_expectedResult
                - **Method**: `methodName()`
                - **Type**: UNIT | INTEGRATION
                - **Priority**: HIGH | MEDIUM | LOW
                - **Input**: Specific test data
                - **Expected**: Verifiable outcome
                - **Setup**: Prerequisites
                - **Notes**: Any special handling
                ```

                ### Phase 5: Show Plan File & Next Steps

                1. Write plan to `.zest/<ClassName>-plan.md`
                2. Use `showFile` to open the plan file in IntelliJ so user can review it
                3. Tell user:

                ```
                ✅ Plan saved to: .zest/<ClassName>-plan.md
                   [N] test scenarios planned

                📋 NEXT STEP: To save tokens, run one of these:
                   - `/clear` - Clear conversation and run `/zest-test-write`
                   - Start a new conversation and run `/zest-test-write`

                The plan file contains all information needed for the next step.
                ```
                """);

        registerPrompt("zest-test-write", "Write tests from context and plan",
                List.of(),
                """
                You are a test writer. Write production-quality tests following the plan.
                **Execute workflow** - minimal interaction, implement the plan.

                ## FILE LOCATIONS
                - **Context file**: `.zest/<ClassName>-context.md` (read this)
                - **Plan file**: `.zest/<ClassName>-plan.md` (read this)
                - **Test output**: `src/test/java/<package>/<ClassName>Test.java` (write here)

                ## AVAILABLE TOOLS

                **MCP Tools (Zest/IntelliJ):**
                - `lookupClass`, `lookupMethod`: Verify signatures if unsure
                - `validateCode`: Validate before saving (REQUIRED)
                - `showFile`: Open a file in IntelliJ editor to present to user

                **Agent's Built-in Tools:**
                - Read files (context, plan, existing tests)
                - Write to files (test class)
                - Search codebase for existing test patterns

                ## WORKFLOW

                ### Phase 1: Load Inputs
                Read `.zest/<ClassName>-context.md` and `.zest/<ClassName>-plan.md`:
                - Source code and method signatures
                - User preferences (scope, type, priority)
                - Test scenarios to implement

                ### Phase 2: Check Existing Tests
                Search for existing test class (e.g., "class.*<ClassName>Test" in Java files).
                If exists, read to avoid duplicates and match style.

                ### Phase 3: Generate Test Class

                **Test path:** `src/main/java/.../Foo.java` → `src/test/java/.../FooTest.java`

                **NO MOCKING - Use real alternatives:**
                - DB: Testcontainers (`PostgreSQLContainer`, etc.)
                - HTTP: WireMock
                - Private fields: `ReflectionTestUtils.setField()`

                **Structure:**
                ```java
                @DisplayName("ClassName Tests")
                class ClassNameTest {
                    private ClassName underTest;

                    @BeforeEach
                    void setUp() { underTest = new ClassName(); }

                    @Nested
                    @DisplayName("methodName")
                    class MethodNameTests {
                        @Test
                        @DisplayName("should X when Y")
                        void methodName_scenario_expected() {
                            // Arrange
                            // Act
                            // Assert
                        }
                    }
                }
                ```

                **Naming:** `methodName_scenario_expectedResult`
                **Assertions:** Use AssertJ (`assertThat(...).isEqualTo(...)`)

                ### Phase 4: Validate
                Call `validateCode` with the generated code.
                - ✅ Compiles → proceed to save
                - ❌ Errors → fix and re-validate (up to 3 attempts)

                ### Phase 5: Save & Show
                1. Write test class to `src/test/java/<package>/<ClassName>Test.java`
                2. Use `showFile` to open the test file in IntelliJ so user can review it

                ### Phase 6: Report & Next Steps
                Tell user:

                ```
                ✅ Test file saved to: src/test/java/<package>/<ClassName>Test.java
                   [N] tests written
                   Validation: ✅ compiles / ❌ [N] errors remaining

                📋 NEXT STEPS:
                   - Run tests in IntelliJ to verify they pass
                   - If errors: `/clear` and run `/zest-test-fix`
                   - If all good: You're done! 🎉
                ```
                """);

        registerPrompt("zest-test-fix", "Fix failing tests or compilation errors",
                List.of(
                        new McpSchema.PromptArgument("errorOutput", "Error output (optional - will call validateCode if not provided)", false)
                ),
                """
                You are a test debugger. Diagnose and fix test failures systematically.
                **Debug workflow** - iterate until tests compile.

                ## FILE LOCATIONS
                - **Context file**: `.zest/<ClassName>-context.md` (source code & signatures)
                - **Plan file**: `.zest/<ClassName>-plan.md` (intended test scenarios)
                - **Test file**: `src/test/java/<package>/<ClassName>Test.java` (fix this)

                ## AVAILABLE TOOLS

                **MCP Tools (Zest/IntelliJ):**
                - `lookupClass`, `lookupMethod`: Verify types and signatures
                - `validateCode`: Check if fix compiles (CALL AFTER EACH FIX)
                - `showFile`: Open a file in IntelliJ editor to present to user

                **Agent's Built-in Tools:**
                - Read files (test file, context files)
                - Write/edit files to apply fixes

                ## WORKFLOW

                ### Phase 1: Get Context
                1. Read `.zest/<ClassName>-context.md` for source code and signatures
                2. Read the test file from `src/test/java/<package>/<ClassName>Test.java`
                3. If no errorOutput provided, call `validateCode` to get current errors

                ### Phase 2: Diagnose (use ReAct pattern)

                For each error:
                🤔 **Thought**: What type of error? What's the root cause?
                🎯 **Action**: Look up signature or read context
                👁️ **Observation**: What did I find?
                ⚡ **Fix**: What change will resolve this?

                **Error Categories:**

                **Compilation:**
                - `cannot find symbol` → Missing import, typo, wrong package
                - `incompatible types` → Check actual return type in context
                - `method does not exist` → Call `lookupMethod` to verify signature

                **Runtime/Assertion:**
                - `NullPointerException` → Missing setup, uninitialized field
                - `AssertionError` → Wrong expectation, check context for actual behavior
                - `expected X but was Y` → Update assertion to match reality

                ### Phase 3: Fix Iteratively

                For each error:
                1. Apply minimal fix (don't rewrite entire test)
                2. Call `validateCode` to check
                3. If new errors, continue fixing
                4. Max 5 iterations per error

                **Common Fixes:**
                - Wrong type → check context, update assertion
                - Missing import → add import statement
                - Wrong method name → use `lookupMethod` to find correct name
                - NPE → initialize in @BeforeEach

                ### Phase 4: Save, Show & Report

                When all errors fixed (or max iterations reached):
                1. Write fixed test to file
                2. Use `showFile` to open the fixed test file in IntelliJ
                3. Report to user:

                ```
                ✅ Test file fixed: src/test/java/<package>/<ClassName>Test.java
                   Errors fixed: [list]
                   Status: ✅ compiles / ❌ [N] issues need manual review

                📋 NEXT STEPS:
                   - Run tests in IntelliJ to verify they pass
                   - If more errors: `/clear` and run `/zest-test-fix` again
                   - If all good: You're done! 🎉
                ```

                {{errorOutput}}
                """);

        // Refactor prompt - load from file
        try {
            String refactorPrompt = new String(getClass().getResourceAsStream("/prompts/refactor.md").readAllBytes());
            registerPrompt("refactor", "Interactive code refactoring assistant with dynamic goal discovery",
                    List.of(),
                    refactorPrompt);
        } catch (Exception e) {
            LOG.warn("Failed to load refactor prompt", e);
        }

        LOG.info("Registered 8 MCP prompts: review, explain, commit, zest-test-context, zest-test-plan, zest-test-write, zest-test-fix, refactor");
    }

    private void registerPrompt(String name, String description, List<McpSchema.PromptArgument> arguments, String promptTemplate) {
        McpSchema.Prompt prompt = new McpSchema.Prompt(name, description, arguments);

        mcpServer.addPrompt(new McpServerFeatures.SyncPromptSpecification(
                prompt,
                (exchange, request) -> {
                    String filledPrompt = promptTemplate;

                    if (request.arguments() != null) {
                        for (var entry : request.arguments().entrySet()) {
                            String placeholder = "{{" + entry.getKey() + "}}";
                            String value = String.valueOf(entry.getValue());
                            filledPrompt = filledPrompt.replace(placeholder, value);
                        }
                    }

                    McpSchema.TextContent textContent = new McpSchema.TextContent(filledPrompt);
                    McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.USER, textContent);

                    return new McpSchema.GetPromptResult(description, List.of(message));
                }
        ));
    }

    private String buildGetCurrentFileSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    private String buildLookupMethodSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Method name to find"
                    }
                  },
                  "required": ["projectPath", "className", "methodName"]
                }
                """;
    }

    private String buildLookupClassSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name. For inner classes use $ separator."
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private McpSchema.CallToolResult handleGetCurrentFile(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                VirtualFile[] selectedFiles = editorManager.getSelectedFiles();

                if (selectedFiles.length == 0) {
                    return "No file is currently open in the editor";
                }

                VirtualFile currentFile = selectedFiles[0];
                PsiFile psiFile = PsiManager.getInstance(project).findFile(currentFile);

                if (psiFile == null) {
                    return "Could not read file: " + currentFile.getPath();
                }

                return "File: " + currentFile.getPath() + "\n" +
                       "Language: " + psiFile.getLanguage().getDisplayName() + "\n\n" +
                       psiFile.getText();
            });

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error getting current file", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleLookupMethod(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            LookupMethodTool tool = new LookupMethodTool(project);
            String result = tool.lookupMethod(className, methodName);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error looking up method", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleLookupClass(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            LookupClassTool tool = new LookupClassTool(project);
            String result = tool.lookupClass(className);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error looking up class", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private String buildAnalyzeMethodUsageSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "memberName": {
                      "type": "string",
                      "description": "Method or field name to analyze usage patterns"
                    }
                  },
                  "required": ["projectPath", "className", "memberName"]
                }
                """;
    }

    private McpSchema.CallToolResult handleAnalyzeMethodUsage(String projectPath, String className, String memberName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            if (memberName == null || memberName.trim().isEmpty()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("ERROR: memberName is required for usage analysis")),
                        true
                );
            }

            String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));

                if (psiClass == null) {
                    return "Class not found: " + className;
                }

                PsiMethod[] methods = psiClass.findMethodsByName(memberName, false);
                if (methods.length > 0) {
                    UsageAnalyzer analyzer = new UsageAnalyzer(project);
                    UsageContext usageContext = analyzer.analyzeMethod(methods[0]);
                    return usageContext.formatForLLM();
                }

                PsiField field = psiClass.findFieldByName(memberName, false);
                if (field != null) {
                    return "Field found: " + field.getName() + " (type: " + field.getType().getPresentableText() + ")\n" +
                           "Note: Usage analysis is currently only available for methods.";
                }

                return "Member not found: " + memberName + " in class " + className;
            });

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error analyzing method usage", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private Project findProject(String projectPath) {
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return null;
        }

        try {
            Path requestedPath = Paths.get(projectPath).toAbsolutePath().normalize();

            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            for (Project project : openProjects) {
                String basePath = project.getBasePath();
                if (basePath != null) {
                    Path projectBasePath = Paths.get(basePath).toAbsolutePath().normalize();
                    if (requestedPath.equals(projectBasePath)) {
                        return project;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Invalid project path: " + projectPath, e);
        }

        return null;
    }

    public void start() throws Exception {
        jettyServer.start();
        LOG.info("✅ Zest MCP HTTP Server started successfully");
        LOG.info("📋 MCP endpoint: http://localhost:" + port + MESSAGE_ENDPOINT);
        LOG.info("🔧 Available tools: getCurrentFile, lookupMethod, lookupClass, analyzeMethodUsage, getJavaCodeUnderTest, validateCode, showFile");
        LOG.info("💬 Available prompts: review, explain, commit, zest-test-context, zest-test-plan, zest-test-write, zest-test-fix");
    }

    public void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
        if (mcpServer != null) {
            mcpServer.close();
        }
        LOG.info("Zest MCP HTTP Server stopped");
    }

    public boolean isRunning() {
        return jettyServer != null && jettyServer.isRunning();
    }

    public int getPort() {
        return port;
    }

    // ========== New Testgen MCP Tools ==========

    private String buildGetJavaCodeUnderTestSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    private McpSchema.CallToolResult handleGetJavaCodeUnderTest(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            // Show GUI to select Java class
            String[] selection = showJavaCodeSelectionDialog(project);
            if (selection == null || selection.length == 0) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("No selection made (user cancelled)")),
                        false
                );
            }

            String className = selection[0];

            // Find class and generate context
            String[] result = new String[2]; // [0] = content, [1] = filePath
            ApplicationManager.getApplication().runReadAction(() -> {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));

                if (psiClass == null) {
                    result[0] = "Class not found: " + className;
                    return;
                }

                // Generate comprehensive context
                result[0] = formatCodeUnderTest(psiClass, project);
            });

            if (result[0] == null || result[0].startsWith("Class not found")) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(result[0] != null ? result[0] : "Error generating context")),
                        false
                );
            }

            // Write to .zest folder
            String filePath = writeContextToTempFile(result[0], className, project);

            // Extract simple class name for file naming convention
            String simpleClassName = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;

            // Return content with file path info
            StringBuilder response = new StringBuilder();
            response.append(result[0]);
            response.append("\n---\n\n");
            response.append("## File Locations\n\n");
            response.append("- **Context file**: `").append(filePath).append("`\n");
            response.append("- **Plan file** (create next): `.zest/").append(simpleClassName).append("-plan.md`\n");
            response.append("- **Test file**: `src/test/java/.../").append(simpleClassName).append("Test.java`\n\n");
            response.append("Next step: Ask user clarifying questions, then run `/zest-test-plan`.\n");

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(response.toString())), false);

        } catch (Exception e) {
            LOG.error("Error getting Java code under test", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private String[] showJavaCodeSelectionDialog(Project project) {
        // Show GUI dialog on EDT thread
        String[] result = new String[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            String className = JavaClassSelectionDialog.showAndGetClassName(project);
            if (className != null) {
                result[0] = className;
            }
        });
        return result[0] != null ? result : null;
    }

    private String buildValidateCodeSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "code": {
                      "type": "string",
                      "description": "Java code to validate"
                    },
                    "className": {
                      "type": "string",
                      "description": "Name of the class (used for error reporting)"
                    }
                  },
                  "required": ["projectPath", "code", "className"]
                }
                """;
    }

    private String buildShowFileSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "filePath": {
                      "type": "string",
                      "description": "Absolute path to the file to open in the editor"
                    }
                  },
                  "required": ["projectPath", "filePath"]
                }
                """;
    }

    private McpSchema.CallToolResult handleValidateCode(String projectPath, String code, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            // Use TestCodeValidator to check for compilation errors
            com.zps.zest.testgen.evaluation.TestCodeValidator.ValidationResult validation =
                    com.zps.zest.testgen.evaluation.TestCodeValidator.validate(project, code, className);

            StringBuilder result = new StringBuilder();
            result.append("## Validation Result: ").append(className).append("\n\n");

            if (validation.compiles()) {
                result.append("**Status:** ✅ Code compiles successfully\n");
                result.append("**Errors:** 0\n");
            } else {
                result.append("**Status:** ❌ Compilation errors found\n");
                result.append("**Errors:** ").append(validation.getErrorCount()).append("\n\n");
                result.append("### Error Details\n\n");

                for (String error : validation.getErrors()) {
                    result.append("```\n");
                    result.append(error);
                    result.append("```\n\n");
                }
            }

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result.toString())), false);

        } catch (Exception e) {
            LOG.error("Error validating code", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleShowFile(String projectPath, String filePath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            File file = new File(filePath);
            if (!file.exists()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("File not found: " + filePath)),
                        false
                );
            }

            // Open file in editor on EDT
            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFile virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(file);
                if (virtualFile != null) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true);
                }
            });

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Opened file in editor: " + filePath)),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error opening file", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    /**
     * Formats comprehensive test context for a class.
     * Includes: source code, usage analysis, related classes, dependencies.
     */
    private String formatCodeUnderTest(PsiClass psiClass, Project project) {
        StringBuilder result = new StringBuilder();
        String className = psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName();

        // Header
        result.append("# Test Context: ").append(className).append("\n\n");

        // File path
        PsiFile containingFile = psiClass.getContainingFile();
        if (containingFile != null && containingFile.getVirtualFile() != null) {
            result.append("**File:** `").append(containingFile.getVirtualFile().getPath()).append("`\n\n");
        }

        // Section 1: Source Code
        result.append("## 1. Source Code\n\n");
        result.append("```java\n");
        if (containingFile != null) {
            String[] lines = containingFile.getText().split("\n");
            for (int i = 0; i < lines.length; i++) {
                result.append(String.format("%4d  %s\n", i + 1, lines[i]));
            }
        }
        result.append("```\n\n");

        // Section 2: Public Methods Summary
        result.append("## 2. Public Methods\n\n");
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                result.append("- `").append(getMethodSignature(method)).append("`\n");
            }
        }
        result.append("\n");

        // Section 3: Usage Analysis (call sites)
        result.append("## 3. Usage Analysis\n\n");
        UsageAnalyzer usageAnalyzer = new UsageAnalyzer(project);
        boolean hasUsage = false;
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                UsageContext usage = usageAnalyzer.analyzeMethod(method);
                if (!usage.isEmpty()) {
                    hasUsage = true;
                    result.append(usage.formatForLLM()).append("\n");
                }
            }
        }
        if (!hasUsage) {
            result.append("_No call sites found in project. This may be a new class or entry point._\n\n");
        }

        // Section 4: Related Classes (dependencies)
        result.append("## 4. Related Classes\n\n");
        Set<PsiClass> relatedClasses = new HashSet<>();
        com.zps.zest.ClassAnalyzer.collectRelatedClasses(psiClass, relatedClasses);

        if (relatedClasses.isEmpty()) {
            result.append("_No project dependencies found._\n\n");
        } else {
            for (PsiClass related : relatedClasses) {
                if (related.equals(psiClass)) continue;
                String qualifiedName = related.getQualifiedName();
                if (qualifiedName == null || qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) {
                    continue;
                }
                result.append("### ").append(related.getName()).append("\n\n");
                result.append("```java\n");
                appendClassSignature(result, related);
                result.append("```\n\n");
            }
        }

        // Section 5: External Dependencies (for test type recommendation)
        result.append("## 5. External Dependencies\n\n");
        Set<String> dependencies = com.zps.zest.ClassAnalyzer.detectExternalDependencies(psiClass);
        if (dependencies.isEmpty()) {
            result.append("_No external dependencies detected. Unit tests recommended._\n\n");
        } else {
            result.append(com.zps.zest.ClassAnalyzer.formatDependenciesForTests(dependencies)).append("\n\n");
        }

        return result.toString();
    }

    /**
     * Gets method signature as a string.
     */
    private String getMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            sig.append(returnType.getPresentableText()).append(" ");
        }
        sig.append(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(params[i].getType().getPresentableText()).append(" ").append(params[i].getName());
        }
        sig.append(")");
        return sig.toString();
    }

    /**
     * Appends class signature (fields + method signatures, no bodies).
     */
    private void appendClassSignature(StringBuilder sb, PsiClass cls) {
        // Class declaration
        if (cls.isInterface()) {
            sb.append("interface ");
        } else {
            sb.append("class ");
        }
        sb.append(cls.getName());

        PsiClass superClass = cls.getSuperClass();
        if (superClass != null && !"Object".equals(superClass.getName())) {
            sb.append(" extends ").append(superClass.getName());
        }

        PsiClassType[] interfaces = cls.getImplementsListTypes();
        if (interfaces.length > 0) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces[i].getClassName());
            }
        }
        sb.append(" {\n");

        // Fields
        for (PsiField field : cls.getFields()) {
            if (field.getName().contains("$")) continue;
            sb.append("    ");
            if (field.hasModifierProperty(PsiModifier.PRIVATE)) sb.append("private ");
            if (field.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
            if (field.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
            if (field.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
            if (field.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
            sb.append(field.getType().getPresentableText()).append(" ").append(field.getName()).append(";\n");
        }

        sb.append("\n");

        // Method signatures only
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor()) continue;
            sb.append("    ");
            if (method.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
            if (method.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
            if (method.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
            PsiType returnType = method.getReturnType();
            if (returnType != null) {
                sb.append(returnType.getPresentableText()).append(" ");
            }
            sb.append(method.getName()).append("(");
            PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getType().getPresentableText());
            }
            sb.append(");\n");
        }
        sb.append("}\n");
    }

    /**
     * Writes context to a temp file and returns the file path.
     */
    private String writeContextToTempFile(String content, String className, Project project) {
        try {
            // Use project's .zest directory
            String basePath = project.getBasePath();
            if (basePath == null) {
                basePath = System.getProperty("java.io.tmpdir");
            }
            File zestDir = new File(basePath, ".zest");
            if (!zestDir.exists()) {
                zestDir.mkdirs();
            }

            String simpleClassName = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;
            File contextFile = new File(zestDir, simpleClassName + "-context.md");

            try (FileWriter writer = new FileWriter(contextFile)) {
                writer.write(content);
            }

            return contextFile.getAbsolutePath();
        } catch (IOException e) {
            LOG.error("Failed to write context file", e);
            return null;
        }
    }

    // ========== REFACTOR TOOL SCHEMAS ==========

    private String buildAskUserSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "questionText": {
                      "type": "string",
                      "description": "The question to ask the user"
                    },
                    "questionType": {
                      "type": "string",
                      "enum": ["SINGLE_CHOICE", "MULTI_CHOICE", "FREE_TEXT"],
                      "description": "Type of question: SINGLE_CHOICE (radio buttons), MULTI_CHOICE (checkboxes), FREE_TEXT (text area)"
                    },
                    "options": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "label": {"type": "string"},
                          "description": {"type": "string"}
                        },
                        "required": ["label"]
                      },
                      "description": "Options for SINGLE_CHOICE or MULTI_CHOICE questions"
                    },
                    "header": {
                      "type": "string",
                      "description": "Dialog header text (optional, defaults to 'Question')"
                    }
                  },
                  "required": ["projectPath", "questionText", "questionType"]
                }
                """;
    }

    private String buildAnalyzeRefactorabilitySchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name to analyze (optional - if not provided, uses current file in editor)"
                    },
                    "focusArea": {
                      "type": "string",
                      "enum": ["TESTABILITY", "COMPLEXITY", "CODE_SMELLS", "ALL"],
                      "description": "Focus area for analysis: TESTABILITY (DI, mocking), COMPLEXITY (cyclomatic complexity, long methods), CODE_SMELLS (god classes, too many params), ALL (everything)"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    // ========== REFACTOR TOOL HANDLERS ==========

    private McpSchema.CallToolResult handleAskUser(
            String projectPath,
            String questionText,
            String questionType,
            List<Map<String, String>> options,
            String header
    ) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.AskUserToolHandler.askUser(
                    project, questionText, questionType, options, header
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in askUser", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to ask user: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleAnalyzeRefactorability(
            String projectPath,
            String className,
            String focusArea
    ) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.RefactorabilityAnalyzer.analyze(
                    project, className, focusArea
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in analyzeRefactorability", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to analyze refactorability: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== TEST COVERAGE TOOLS ==========

    private String buildGetCoverageSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name to analyze coverage for"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildProjectPathSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    private McpSchema.CallToolResult handleGetCoverageData(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.TestCoverageToolHandler.getCoverageData(
                    project, className
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in getCoverageData", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to get coverage data: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleAnalyzeCoverage(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.TestCoverageToolHandler.analyzeCoverage(
                    project, className
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in analyzeCoverage", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to analyze coverage: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetTestInfo(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.TestCoverageToolHandler.getTestInfo(
                    project, className
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in getTestInfo", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to get test info: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetBuildInfo(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.BuildToolDetector.detectBuildInfo(project);

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in getBuildInfo", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to get build info: " + e.getMessage())),
                    true
            );
        }
    }
}