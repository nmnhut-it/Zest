package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
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
import java.util.Set;

/**
 * MCP HTTP Server for Zest using Streamable HTTP transport.
 * Provides IntelliJ project tools via HTTP endpoints using MCP protocol.
 *
 * The server uses a single /mcp endpoint that handles:
 * - POST: JSON-RPC requests from clients (returns JSON or SSE stream)
 * - GET: Server-Sent Events for server-to-client notifications
 * - DELETE: Session cleanup
 *
 * This is the MCP 2025-03-26 spec compliant transport (replaces deprecated SSE).
 */
public class ZestMcpHttpServer {
    private static final Logger LOG = Logger.getInstance(ZestMcpHttpServer.class);
    private static final String MESSAGE_ENDPOINT = "/mcp";

    private final McpSyncServer mcpServer;
    private final Server jettyServer;
    private final HttpServletStreamableServerTransportProvider transport;
    private final int port;

    public ZestMcpHttpServer(int port) {
        this.port = port;

        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);

        this.transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .mcpEndpoint(MESSAGE_ENDPOINT)
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

        // ========== Core IDE Tools ==========

        McpSchema.Tool currentFileTool = McpSchema.Tool.builder()
                .name("getCurrentFile")
                .description("Get the currently open file in the editor")
                .inputSchema(jsonMapper, buildGetCurrentFileSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                currentFileTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetCurrentFile(projectPath);
                }
        ));

        McpSchema.Tool lookupClassTool = McpSchema.Tool.builder()
                .name("lookupClass")
                .description("Look up class/method signatures. Works with project classes, JARs, and JDK. Use methodName param to filter specific method.")
                .inputSchema(jsonMapper, buildLookupClassSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                lookupClassTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleLookupClass(projectPath, className, methodName);
                }
        ));

        McpSchema.Tool getJavaCodeUnderTestTool = McpSchema.Tool.builder()
                .name("getJavaCodeUnderTest")
                .description("Interactive GUI to select Java code, returns source + static analysis")
                .inputSchema(jsonMapper, buildGetJavaCodeUnderTestSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getJavaCodeUnderTestTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetJavaCodeUnderTest(projectPath);
                }
        ));

        McpSchema.Tool showFileTool = McpSchema.Tool.builder()
                .name("showFile")
                .description("Open a file in IntelliJ editor")
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

        // ========== PSI Navigation Tools ==========

        McpSchema.Tool findUsagesTool = McpSchema.Tool.builder()
                .name("findUsages")
                .description("Find all usages of a class, method, or field in the project")
                .inputSchema(jsonMapper, buildFindUsagesSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                findUsagesTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleFindUsages(projectPath, className, memberName);
                }
        ));

        McpSchema.Tool findImplementationsTool = McpSchema.Tool.builder()
                .name("findImplementations")
                .description("Find implementations of an interface or abstract method")
                .inputSchema(jsonMapper, buildFindImplementationsSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                findImplementationsTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleFindImplementations(projectPath, className, methodName);
                }
        ));

        McpSchema.Tool getTypeHierarchyTool = McpSchema.Tool.builder()
                .name("getTypeHierarchy")
                .description("Get type hierarchy - superclasses, interfaces, and subclasses")
                .inputSchema(jsonMapper, buildGetTypeHierarchySchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getTypeHierarchyTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetTypeHierarchy(projectPath, className);
                }
        ));

        McpSchema.Tool getCallHierarchyTool = McpSchema.Tool.builder()
                .name("getCallHierarchy")
                .description("Get call hierarchy - callers or callees of a method")
                .inputSchema(jsonMapper, buildGetCallHierarchySchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getCallHierarchyTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    Boolean callers = (Boolean) arguments.getOrDefault("callers", true);
                    return handleGetCallHierarchy(projectPath, className, methodName, callers);
                }
        ));

        // ========== Refactoring Tools ==========

        McpSchema.Tool renameTool = McpSchema.Tool.builder()
                .name("rename")
                .description("Rename a class, method, or field. Automatically updates all usages across the project.")
                .inputSchema(jsonMapper, buildRenameSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                renameTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    String newName = (String) arguments.get("newName");
                    return handleRename(projectPath, className, memberName, newName);
                }
        ));

        McpSchema.Tool getMethodBodyTool = McpSchema.Tool.builder()
                .name("getMethodBody")
                .description("Get method body with code blocks for refactoring analysis")
                .inputSchema(jsonMapper, buildGetMethodBodySchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                getMethodBodyTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleGetMethodBody(projectPath, className, methodName);
                }
        ));

        // ========== New Refactoring Tools ==========

        // Extract Constant tool
        McpSchema.Tool extractConstantTool = McpSchema.Tool.builder()
                .name("extractConstant")
                .description("Extract a literal value to a static final constant")
                .inputSchema(jsonMapper, buildExtractConstantSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                extractConstantTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    int lineNumber = ((Number) arguments.get("lineNumber")).intValue();
                    String constantName = (String) arguments.get("constantName");
                    return handleExtractConstant(projectPath, className, methodName, lineNumber, constantName);
                }
        ));

        // Extract Method tool
        McpSchema.Tool extractMethodTool = McpSchema.Tool.builder()
                .name("extractMethod")
                .description("Extract lines of code into a new method")
                .inputSchema(jsonMapper, buildExtractMethodSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                extractMethodTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String sourceMethodName = (String) arguments.get("sourceMethodName");
                    int startLine = ((Number) arguments.get("startLine")).intValue();
                    int endLine = ((Number) arguments.get("endLine")).intValue();
                    String newMethodName = (String) arguments.get("newMethodName");
                    return handleExtractMethod(projectPath, className, sourceMethodName, startLine, endLine, newMethodName);
                }
        ));

        // Safe Delete tool
        McpSchema.Tool safeDeleteTool = McpSchema.Tool.builder()
                .name("safeDelete")
                .description("Safely delete a class member if it has no usages")
                .inputSchema(jsonMapper, buildSafeDeleteSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                safeDeleteTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleSafeDelete(projectPath, className, memberName);
                }
        ));

        // Find Dead Code tool
        McpSchema.Tool findDeadCodeTool = McpSchema.Tool.builder()
                .name("findDeadCode")
                .description("Find unused methods and fields in a class")
                .inputSchema(jsonMapper, buildFindDeadCodeSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                findDeadCodeTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleFindDeadCode(projectPath, className);
                }
        ));

        // Move Class tool
        McpSchema.Tool moveClassTool = McpSchema.Tool.builder()
                .name("moveClass")
                .description("Move a class to a different package (updates all references)")
                .inputSchema(jsonMapper, buildMoveClassSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                moveClassTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String targetPackage = (String) arguments.get("targetPackage");
                    return handleMoveClass(projectPath, className, targetPackage);
                }
        ));

        LOG.info("Registered 15 MCP tools: getCurrentFile, lookupClass, getJavaCodeUnderTest, showFile, findUsages, findImplementations, getTypeHierarchy, getCallHierarchy, rename, getMethodBody, extractConstant, extractMethod, safeDelete, findDeadCode, moveClass");
    }

    private void registerPrompts() {
        registerPrompt("review", "Smart code review with focus options",
                List.of(),
                """
                You are a smart code reviewer. Auto-detect target, let user pick focus, always check testability.

                ## PHASE 1: AUTO-DETECT TARGET

                Use `getCurrentFile()` to get the currently open file/selection.

                **Quick confirm + focus selection:**
                ```
                Review `ClassName`?

                Focus:
                1. Bugs + Security (pre-merge)
                2. Performance + Quality (optimization)
                3. Full review (all categories)

                Pick [1/2/3]:
                ```

                ## PHASE 2: GATHER CONTEXT (use MCP tools in parallel)

                **Analysis Tools:**
                | Tool | Purpose |
                |------|---------|
                | `lookupClass(className)` | Structure, inheritance |
                | `findUsages(className, memberName)` | Real usage patterns, edge cases |
                | `getCallHierarchy(className, methodName)` | Callers reveal requirements |
                | `findDeadCode(className)` | Find unused methods/fields |
                | `getMethodBody(className, methodName)` | Get method details for refactoring |

                ## PHASE 3: REVIEW (based on focus)

                ### ALWAYS CHECK: TESTABILITY
                - Tight coupling? Dependencies injectable?
                - Static methods blocking mocks?
                - Hidden dependencies (new inside methods)?
                - Side effects making assertions hard?
                - Missing interfaces for test doubles?

                ### Focus 1: BUGS + SECURITY
                **Bugs:**
                - Null safety (check what callers expect)
                - Logic errors, off-by-one
                - Resource leaks (unclosed streams/connections)
                - Thread safety, race conditions

                **Security:**
                - Injection: SQL, XSS, command
                - Auth/authz flaws
                - Hardcoded secrets
                - Input validation gaps

                ### Focus 2: PERFORMANCE + QUALITY
                **Performance:**
                - Algorithm: O(n²) → O(n)?
                - DB: N+1 queries, missing indexes
                - Memory leaks, unnecessary allocations
                - Caching opportunities

                **Quality:**
                - Naming clarity
                - Complexity: methods >30 lines, deep nesting
                - Duplication
                - SOLID violations

                ### Focus 3: FULL REVIEW
                All of the above, plus:
                - API design / backward compatibility
                - Error handling patterns
                - Logging / observability
                - Documentation gaps

                ## OUTPUT FORMAT

                ```
                # Review: ClassName
                Focus: [1/2/3] | Testability: [Good/Fair/Poor]

                ## Issues Found

                ### 🔴 Critical
                - [Location] Issue description
                  Fix: `code suggestion`

                ### 🟡 Medium
                - ...

                ### 🟢 Low
                - ...

                ## Testability Assessment
                - [✓/✗] Dependencies injectable
                - [✓/✗] No hidden side effects
                - [✓/✗] Clear inputs/outputs
                Suggestion: ...

                ## Summary
                - **Rating**: Poor/Fair/Good/Excellent
                - **Top 3 fixes**: ...
                - **Callers affected**: N call sites
                ```

                ## PHASE 4: OFFER REFACTORING (after review)

                If issues can be fixed with refactoring tools, list them and ASK user before proceeding:

                ```
                ---
                🔧 **Refactoring Available**

                I can help fix some issues automatically:

                1. Extract magic number `30000` on line 42 → `TIMEOUT_MS`
                2. Extract long method lines 15-45 → `validateUserInput()`
                3. Rename unclear variable `x` → `userCount`
                4. Remove unused method `oldHelper()` (0 usages found)

                Want me to apply any of these? Reply with numbers (e.g., "1,2") or "all" or "skip".
                ```

                **IMPORTANT:** Do NOT call refactoring tools until user confirms.
                Only after user picks options, call the appropriate tools:
                - `extractConstant(className, methodName, lineNumber, constantName)`
                - `extractMethod(className, sourceMethod, startLine, endLine, newMethodName)`
                - `rename(className, memberName, newName)`
                - `safeDelete(className, memberName)`
                """);

        registerPrompt("explain", "Smart code explainer with auto-detection",
                List.of(),
                """
                You are a smart code explainer. Auto-detect target, confirm briefly, then explain thoroughly.

                ## PHASE 1: AUTO-DETECT TARGET

                Use `getCurrentFile()` to get the currently open file/selection.

                **Quick confirm with user:**
                ```
                Explain `ClassName` or `methodName`?
                Depth: [quick] / standard / deep
                Audience: [dev] / junior / non-tech
                ```
                Default: quick + dev. Proceed after 3 seconds or on confirmation.

                ## PHASE 2: GATHER CONTEXT (use MCP tools)

                Run these in parallel:
                | Tool | Purpose |
                |------|---------|
                | `lookupClass(className)` | Structure, methods, inheritance |
                | `findUsages(className, memberName)` | Real-world usage patterns |
                | `getCallHierarchy(className, methodName)` | Callers and callees |
                | `getTypeHierarchy(className)` | Inheritance tree |

                ## PHASE 3: EXPLAIN (adapt to depth + audience)

                ### QUICK (1-2 paragraphs)
                - What it does in plain English
                - When/why you'd use it

                ### STANDARD (structured)
                **Purpose**: Problem it solves, inputs/outputs
                **How it works**: Algorithm, key steps
                **Usage**: Real examples from findUsages
                **Watch out**: Edge cases, threading, perf

                ### DEEP (comprehensive)
                All of standard, plus:
                - Full inheritance/dependency tree
                - Design patterns used
                - Code flow diagram (ASCII/Mermaid)
                - Related classes explained
                - Historical context if visible in code

                ## AUDIENCE ADAPTATION

                | Audience | Style |
                |----------|-------|
                | **dev** | Technical, use proper terms, show code |
                | **junior** | Explain terms, more examples, step-by-step |
                | **non-tech** | Analogies, no code, business value focus |

                ## OUTPUT FORMAT

                ```
                # ClassName.methodName

                **TL;DR**: One sentence summary.

                [Explanation based on depth/audience]

                **See also**: RelatedClass, AnotherClass
                ```
                """);

        registerPrompt("commit", "Smart hands-free git commit assistant",
                List.of(),
                """
                You are a smart, hands-free git commit assistant. Automatically analyze, split if needed, and commit.

                **IMPORTANT**: Use your bash/terminal tool to execute git commands. Chain commands with `&&` for efficiency.

                ## PHASE 1: GATHER CONTEXT (single chained command)
                ```bash
                git status && git log --oneline -10 && git diff --stat && git diff --cached --stat
                ```
                If you need full diff content:
                ```bash
                git diff && git diff --cached
                ```

                ## PHASE 2: SMART ANALYSIS

                **Infer TYPE from diff content:**
                | Signal | Type |
                |--------|------|
                | New files created | feat |
                | "fix", "bug", "error", "crash", "null" in changes | fix |
                | Only test files changed | test |
                | Only README, *.md, comments | docs |
                | Rename/move without logic change | refactor |
                | Delete unused code/files | chore |
                | Formatting, whitespace only | style |
                | "optimize", "cache", "fast" | perf |

                **Infer SCOPE from file paths:**
                - `src/mcp/*` → scope: mcp
                - `src/auth/*` → scope: auth
                - `*Test.java` → scope: test
                - Multiple directories → use parent or omit scope

                **Match PROJECT STYLE from recent commits:**
                - Do they use scopes? Match it.
                - Emoji prefix? Match it.
                - Capitalized? Match it.
                - Body included? Match if >5 files.

                ## PHASE 3: SPLIT DETECTION

                Group changes by logical unit:
                - Same feature/fix = one commit
                - Unrelated changes = split into multiple commits

                **Split signals:**
                - Files in different modules with no dependency
                - Mix of feat + fix + docs with no relation
                - Test files for different classes

                If splitting, execute multiple commits in sequence.

                ## PHASE 4: EXECUTE (chain add + commit)

                **Single commit (chain it):**
                ```bash
                git add -A && git commit -m "feat(mcp): add transport"
                ```

                **With body (double -m):**
                ```bash
                git add -A && git commit -m "feat(mcp): add transport" -m "Implements streamable HTTP per MCP 2025 spec."
                ```

                **Split commits (run sequentially):**
                ```bash
                git add src/mcp/* && git commit -m "feat(mcp): add transport"
                git add src/auth/* && git commit -m "fix(auth): handle null token"
                ```

                **Include body when:** >5 files, breaking changes, non-obvious "why"

                ## OUTPUT FORMAT
                ```
                ✅ feat(mcp): add streamable http transport
                   5 files, +127 -43

                ✅ fix(auth): handle null token gracefully
                   2 files, +15 -3

                Done: 2 commits created
                ```

                ## STOP CONDITIONS (only these)
                - Empty diff: "Nothing to commit"
                - Sensitive files (.env, credentials): "Exclude secrets? [Y/n]"
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
                - `lookupClass(className, methodName?)`: Look up class/method signatures (project, JARs, JDK)
                - `findUsages(className, memberName)`: Find all usages of a class/method
                - `getCallHierarchy(className, methodName)`: Get callers/callees
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
                - `lookupClass(className, methodName?)`: Look up signatures if needed
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
                - `lookupClass(className, methodName?)`: Verify signatures if unsure
                - `showFile`: Open a file in IntelliJ editor to present to user

                **Validation:**
                After writing tests, compile with your agent's built-in tools to verify.

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
                Compile the test class using your agent's built-in tools.
                - ✅ Compiles → proceed to save
                - ❌ Errors → fix and re-compile (up to 3 attempts)

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
                        new McpSchema.PromptArgument("errorOutput", "Error output (optional - compile test to get errors if not provided)", false)
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
                - `lookupClass(className, methodName?)`: Verify types and signatures
                - `showFile`: Open a file in IntelliJ editor to present to user

                **Validation:**
                Compile with your agent's built-in tools after each fix.

                **Agent's Built-in Tools:**
                - Read files (test file, context files)
                - Write/edit files to apply fixes

                ## WORKFLOW

                ### Phase 1: Get Context
                1. Read `.zest/<ClassName>-context.md` for source code and signatures
                2. Read the test file from `src/test/java/<package>/<ClassName>Test.java`
                3. If no errorOutput provided, compile the test to get current errors

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
                - `method does not exist` → Call `lookupClass(className, methodName)` to verify signature

                **Runtime/Assertion:**
                - `NullPointerException` → Missing setup, uninitialized field
                - `AssertionError` → Wrong expectation, check context for actual behavior
                - `expected X but was Y` → Update assertion to match reality

                ### Phase 3: Fix Iteratively

                For each error:
                1. Apply minimal fix (don't rewrite entire test)
                2. Compile to check
                3. If new errors, continue fixing
                4. Max 5 iterations per error

                **Common Fixes:**
                - Wrong type → check context, update assertion
                - Missing import → add import statement
                - Wrong method name → use `lookupClass(className, methodName)` to find correct name
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

        // ========== Additional Test Quality Prompts ==========

        registerPrompt("zest-test-review", "Review existing tests for quality and completeness",
                List.of(),
                """
                You are a test quality reviewer. Analyze existing tests for quality, coverage, and best practices.

                ## AVAILABLE TOOLS

                **MCP Tools (Zest/IntelliJ):**
                - `getCurrentFile`: Get currently open test file
                - `lookupClass(className, methodName?)`: Look up source class being tested
                - `findUsages(className, memberName)`: Find what's tested vs. not
                - `showFile`: Open file in editor

                **Agent's Built-in Tools:**
                - Read/search files
                - Analyze code patterns

                ## WORKFLOW

                ### Phase 1: Get Test File
                Ask user:
                ```
                Which test class to review?
                1. Current file in editor
                2. Specify test class name
                ```

                ### Phase 2: Analyze Test Quality

                **STRUCTURE REVIEW:**
                - Proper test naming: `methodName_whenCondition_thenExpected`
                - Each test has clear Arrange/Act/Assert sections
                - @BeforeEach/@AfterEach used appropriately
                - @DisplayName for human-readable descriptions
                - Tests grouped with @Nested for related scenarios

                **ASSERTION REVIEW:**
                - Each test has at least one assertion
                - Assertions are specific (not just `assertNotNull`)
                - Use AssertJ for readable assertions
                - No silent catches hiding failures

                **COVERAGE REVIEW:**
                - Use `lookupClass` to get source class
                - Compare source methods vs. test methods
                - Check: happy paths, edge cases, error handling
                - Identify untested public methods

                **ANTI-PATTERN DETECTION:**
                - Mocking (should use Testcontainers/WireMock instead)
                - Tests over 30 lines (should split)
                - Multiple assertions per test (should focus)
                - Hardcoded magic values
                - Test interdependencies

                ### Phase 3: Generate Report

                ```markdown
                # Test Review: <TestClassName>

                ## Quality Score: X/100

                ## Summary
                - **Total tests:** N
                - **Well-structured:** M
                - **Needs improvement:** K
                - **Coverage estimate:** X%

                ## Issues Found

                ### Critical
                - [List critical issues]

                ### Improvements
                - [List suggested improvements]

                ## Missing Tests
                - `methodName()` - needs happy path test
                - `methodName()` - needs edge case: null input
                - ...

                ## Recommendations
                1. [Prioritized action items]
                ```

                ### Phase 4: Show Results
                Use `showFile` if user wants to see specific files.
                """);

        registerPrompt("zest-analyze-gaps", "Analyze test coverage gaps for a class",
                List.of(),
                """
                You are a test coverage analyst. Find what's missing from existing tests.

                ## AVAILABLE TOOLS

                **MCP Tools (Zest/IntelliJ):**
                - `lookupClass(className, methodName?)`: Get source class details
                - `findUsages(className, memberName)`: Find how methods are used
                - `getTypeHierarchy(className)`: Find related classes
                - `showFile`: Open file in editor

                **Agent's Built-in Tools:**
                - Read/search files
                - Analyze patterns

                ## WORKFLOW

                ### Phase 1: Get Target Class
                Ask user:
                ```
                Which class to analyze for test gaps?
                1. Current file in editor
                2. Specify class name
                ```

                ### Phase 2: Gather Information

                1. **Get Source Class**
                   `lookupClass(targetClass)` → Get all public methods

                2. **Find Existing Tests**
                   Search for `<ClassName>Test` in test directory

                3. **Analyze Real Usage**
                   For each public method:
                   `findUsages(className, methodName)` → How is it used?

                ### Phase 3: Gap Analysis

                For each public method, check:

                **HAPPY PATH:**
                - [ ] Normal valid input → expected output

                **BOUNDARY CONDITIONS:**
                - [ ] Empty collections
                - [ ] Null values
                - [ ] Min/max values
                - [ ] Single element

                **ERROR CONDITIONS:**
                - [ ] Invalid input handling
                - [ ] Exception scenarios
                - [ ] Timeout/retry logic

                **INTEGRATION POINTS:**
                - [ ] Database operations
                - [ ] HTTP calls
                - [ ] File I/O

                ### Phase 4: Generate Gap Report

                ```markdown
                # Test Gap Analysis: <ClassName>

                ## Coverage Summary
                | Method | Happy Path | Boundaries | Errors | Integration |
                |--------|------------|------------|--------|-------------|
                | method1() | ✅ | ❌ | ❌ | N/A |
                | method2() | ✅ | ✅ | ❌ | ❌ |

                ## Critical Gaps

                ### method1()
                **Missing tests:**
                - Boundary: empty input list
                - Error: null parameter handling

                **Why important:**
                - Called from 5 locations with varied inputs
                - Usage shows callers handle null returns

                ### method2()
                **Missing tests:**
                - Integration: database connection failure
                - Error: transaction rollback

                ## Recommended Test Additions

                1. **High Priority**
                   - `method1_whenInputEmpty_shouldReturnEmptyList`
                   - `method2_whenDbConnectionFails_shouldThrowException`

                2. **Medium Priority**
                   - [Additional tests]

                ## Next Steps
                Run `/zest-test-write` to generate missing tests.
                ```

                ### Phase 5: Show Results
                Use `showFile` to open source or test files as needed.
                """);

        LOG.info("Registered 9 MCP prompts: review, explain, commit, zest-test-context, zest-test-plan, zest-test-write, zest-test-fix, zest-test-review, zest-analyze-gaps");
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
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Optional method name to filter. If provided, returns only matching method signatures."
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

    private McpSchema.CallToolResult handleLookupClass(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            LookupClassTool tool = new LookupClassTool(project);
            String result;

            if (methodName != null && !methodName.trim().isEmpty()) {
                // Filter by method name - use LookupMethodTool for specific method
                LookupMethodTool methodTool = new LookupMethodTool(project);
                result = methodTool.lookupMethod(className, methodName);
            } else {
                result = tool.lookupClass(className);
            }

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error looking up class", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    /**
     * Find project by path, or return the first open project if path is invalid/empty.
     * This makes tools more resilient when LLMs pass incorrect paths like "/workspace/project".
     */
    private Project findProject(String projectPath) {
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();

        // If no projects are open, return null
        if (openProjects.length == 0) {
            return null;
        }

        // If projectPath is empty or null, return the first open project
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return openProjects[0];
        }

        try {
            Path requestedPath = Paths.get(projectPath).toAbsolutePath().normalize();

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
            // Path parsing failed (e.g., Linux path on Windows) - fall through to default
            LOG.debug("Could not parse project path '" + projectPath + "', using default project");
        }

        // If no match found or path was invalid, return the first open project
        LOG.info("Project path '" + projectPath + "' not found, using: " + openProjects[0].getBasePath());
        return openProjects[0];
    }

    public void start() throws Exception {
        jettyServer.start();
        LOG.info("✅ Zest MCP HTTP Server started successfully (Streamable HTTP transport)");
        LOG.info("📋 MCP endpoint: http://localhost:" + port + MESSAGE_ENDPOINT);
        LOG.info("🔧 Available tools (10): getCurrentFile, lookupClass, getJavaCodeUnderTest, showFile, findUsages, findImplementations, getTypeHierarchy, getCallHierarchy, rename, getMethodBody");
        LOG.info("💬 Available prompts (9): review, explain, commit, zest-test-context, zest-test-plan, zest-test-write, zest-test-fix, zest-test-review, zest-analyze-gaps");
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
        com.zps.zest.core.ClassAnalyzer.collectRelatedClasses(psiClass, relatedClasses);

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
        Set<String> dependencies = com.zps.zest.core.ClassAnalyzer.detectExternalDependencies(psiClass);
        if (dependencies.isEmpty()) {
            result.append("_No external dependencies detected. Unit tests recommended._\n\n");
        } else {
            result.append(com.zps.zest.core.ClassAnalyzer.formatDependenciesForTests(dependencies)).append("\n\n");
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

    // ========== PSI Navigation Tool Schemas ==========

    private String buildFindUsagesSchema() {
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
                      "description": "Optional method or field name. If omitted, finds usages of the class itself."
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildFindImplementationsSchema() {
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
                      "description": "Fully qualified interface or class name"
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Optional method name to find implementations of"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildGetTypeHierarchySchema() {
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
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildGetCallHierarchySchema() {
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
                      "description": "Method name to analyze"
                    },
                    "callers": {
                      "type": "boolean",
                      "description": "If true, shows who calls this method. If false, shows what this method calls. Default: true"
                    }
                  },
                  "required": ["projectPath", "className", "methodName"]
                }
                """;
    }

    private String buildRenameSchema() {
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
                      "description": "Optional method or field name to rename. If omitted, renames the class."
                    },
                    "newName": {
                      "type": "string",
                      "description": "The new name for the symbol"
                    }
                  },
                  "required": ["projectPath", "className", "newName"]
                }
                """;
    }

    private String buildGetMethodBodySchema() {
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
                      "description": "Method name to analyze"
                    }
                  },
                  "required": ["projectPath", "className", "methodName"]
                }
                """;
    }

    private String buildExtractConstantSchema() {
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
                      "description": "Method containing the literal to extract"
                    },
                    "lineNumber": {
                      "type": "integer",
                      "description": "Line number where the literal is located"
                    },
                    "constantName": {
                      "type": "string",
                      "description": "Name for the new constant (UPPER_SNAKE_CASE)"
                    }
                  },
                  "required": ["projectPath", "className", "methodName", "lineNumber", "constantName"]
                }
                """;
    }

    private String buildExtractMethodSchema() {
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
                    "sourceMethodName": {
                      "type": "string",
                      "description": "Method containing the code to extract"
                    },
                    "startLine": {
                      "type": "integer",
                      "description": "First line of code to extract"
                    },
                    "endLine": {
                      "type": "integer",
                      "description": "Last line of code to extract"
                    },
                    "newMethodName": {
                      "type": "string",
                      "description": "Name for the new extracted method"
                    }
                  },
                  "required": ["projectPath", "className", "sourceMethodName", "startLine", "endLine", "newMethodName"]
                }
                """;
    }

    private String buildSafeDeleteSchema() {
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
                      "description": "Method or field name to delete (optional, deletes class if omitted)"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildFindDeadCodeSchema() {
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
                      "description": "Fully qualified class name to analyze"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildMoveClassSchema() {
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
                      "description": "Fully qualified class name to move"
                    },
                    "targetPackage": {
                      "type": "string",
                      "description": "Target package name (e.g., com.example.newpackage)"
                    }
                  },
                  "required": ["projectPath", "className", "targetPackage"]
                }
                """;
    }

    // ========== PSI Navigation Tool Handlers ==========

    private McpSchema.CallToolResult handleFindUsages(String projectPath, String className, String memberName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.UsagesResult result = service.findUsages(className, memberName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error finding usages", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleFindImplementations(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.ImplementationsResult result = service.findImplementations(className, methodName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error finding implementations", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetTypeHierarchy(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.TypeHierarchyResult result = service.getTypeHierarchy(className);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting type hierarchy", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetCallHierarchy(String projectPath, String className,
                                                             String methodName, boolean callers) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.CallHierarchyResult result = service.getCallHierarchy(className, methodName, callers);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting call hierarchy", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== Refactoring Tool Handlers ==========

    private McpSchema.CallToolResult handleRename(String projectPath, String className,
                                                   String memberName, String newName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.RefactoringResult result = service.rename(className, memberName, newName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error renaming", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetMethodBody(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.MethodBodyResult result = service.getMethodBody(className, methodName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting method body", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== New Refactoring Tool Handlers ==========

    private McpSchema.CallToolResult handleExtractConstant(String projectPath, String className,
                                                            String methodName, int lineNumber, String constantName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.RefactoringResult result = service.extractConstant(className, methodName, lineNumber, constantName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error extracting constant", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleExtractMethod(String projectPath, String className,
                                                          String sourceMethodName, int startLine, int endLine, String newMethodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.RefactoringResult result = service.extractMethod(className, sourceMethodName, startLine, endLine, newMethodName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error extracting method", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleSafeDelete(String projectPath, String className, String memberName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.SafeDeleteResult result = service.safeDelete(className, memberName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in safe delete", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleFindDeadCode(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.DeadCodeResult result = service.findDeadCode(className);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error finding dead code", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleMoveClass(String projectPath, String className, String targetPackage) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.MoveClassResult result = service.moveClass(className, targetPackage);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error moving class", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

}