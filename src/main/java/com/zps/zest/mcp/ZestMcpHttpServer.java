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
import com.zps.zest.git.GitService;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

        McpSchema.Tool gitStatusTool = McpSchema.Tool.builder()
                .name("getGitStatus")
                .description("Get git status of the project showing modified, added, deleted files")
                .inputSchema(jsonMapper, buildGitStatusSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                gitStatusTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetGitStatus(projectPath);
                }
        ));

        McpSchema.Tool gitDiffTool = McpSchema.Tool.builder()
                .name("getGitDiff")
                .description("Get git diff for all changed files in the project")
                .inputSchema(jsonMapper, buildGitDiffSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                gitDiffTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetGitDiff(projectPath);
                }
        ));

        LOG.info("Registered 6 MCP tools: getCurrentFile, lookupMethod, lookupClass, analyzeMethodUsage, getGitStatus, getGitDiff");
    }

    private void registerPrompts() {
        registerPrompt("review", "Review code quality and suggest improvements",
                List.of(new McpSchema.PromptArgument("code", "Code to review", false)),
                """
                You are an experienced code reviewer. Follow this systematic methodology to conduct a thorough review.

                REVIEW METHODOLOGY (Step-by-Step):

                STEP 1: Understand Structure
                  Tool: lookupClass(className)
                  Purpose: Get class signature, inheritance, methods, fields
                  Output: "This is a UserService class with methods: getUserById, createUser..."

                STEP 2: Analyze Real Usage (CRITICAL - This is the killer feature!)
                  Tool: analyzeMethodUsage(className, methodName)
                  Purpose: Discover edge cases from REAL production code
                  Why: Shows how callers actually use it - null checks, error handling, patterns
                  Example: "8 out of 15 callers do null checks → method CAN return null"
                  Example: "3 callers wrap in try-catch for NotFoundException"
                  Example: "2 callers use @Transactional annotation"

                STEP 3: Read Implementation
                  Tool: getCurrentFile() or readFile(filePath)
                  Purpose: Examine actual code logic
                  Focus: Look for bugs, security issues, performance problems

                STEP 4: Check Impact (if needed)
                  Tool: analyzeMethodUsage to see who would be affected by changes

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
                - Complexity: Methods >20 lines, deep nesting
                - Duplication: Repeated code blocks
                - Testability: Tight coupling, no dependency injection

                OUTPUT FORMAT:
                For each issue:
                1. **Category**: [BUGS/SECURITY/PERFORMANCE/QUALITY]
                2. **Severity**: [Critical/High/Medium/Low]
                3. **Location**: Method or line number
                4. **Issue**: What's wrong
                5. **Evidence**: What analyzeMethodUsage revealed (if applicable)
                6. **Fix**: Specific solution with code example
                7. **Impact**: Who/what is affected

                Summary:
                - **Quality Rating**: Poor/Fair/Good/Excellent
                - **Top 3 Priorities**: Most critical fixes
                - **Edge Cases Found**: From usage analysis

                Code to review:
                {{code}}
                """);

        registerPrompt("explain", "Explain how code works",
                List.of(new McpSchema.PromptArgument("code", "Code to explain", false)),
                """
                You are a technical educator. Follow this methodology to explain code clearly to developers.

                EXPLANATION METHODOLOGY (Step-by-Step):

                STEP 1: High-Level Understanding
                  Tool: lookupClass(className)
                  Purpose: Understand class structure and responsibilities
                  Output: "This is a UserService class that manages user operations"
                  What to extract: Class purpose, methods, inheritance

                STEP 2: Read the Implementation
                  Tool: getCurrentFile() or readFile(filePath)
                  Purpose: Understand algorithm and logic
                  Focus: How does it work? What's the flow?

                STEP 3: See Real-World Examples
                  Tool: analyzeMethodUsage(className, methodName)
                  Purpose: Show how code is ACTUALLY used in practice
                  Why: Real examples are better than theoretical descriptions
                  Output: "In practice, controllers call this with userId from request.getParameter()"
                          "Service layer wraps calls in @Transactional context"
                          "Tests use mockUserId = 123 as example data"

                STEP 4: Understand Dependencies (if complex)
                  Tool: lookupClass(dependencyClassName)
                  Purpose: Explain related classes and their roles
                  When: If code uses complex dependencies

                STRUCTURE YOUR EXPLANATION:

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

                Use clear language, avoid jargon. When mentioning technical terms, explain them.
                Provide concrete examples from usage analysis.

                Code to explain:
                {{code}}
                """);

        registerPrompt("commit", "Interactive git commit with natural language",
                List.of(new McpSchema.PromptArgument("projectPath", "Project path for git operations", false)),
                """
                You are a conversational git assistant. Guide users through the entire commit workflow using natural language.

                GIT COMMIT WORKFLOW (Step-by-Step):

                STEP 1: Check Repository Status
                  Command: git status
                  Purpose: See all changed files (staged, unstaged, untracked)
                  Action: Show user a clear summary:
                    - Files already staged for commit
                    - Modified files not yet staged
                    - Untracked files
                  Note: Run in {{projectPath}}

                STEP 2: Ask User What to Stage (if there are unstaged changes)
                  Question: "I see you have [N] unstaged files. Which files would you like to include in this commit?"
                  Options:
                    - List files clearly with context
                    - Suggest grouping related changes
                    - Warn about unrelated changes that should be separate commits
                  User Response: Wait for user to specify which files

                STEP 3: Stage Selected Files
                  Command: git add <file1> <file2> ...
                  Purpose: Stage the files user wants to commit
                  Action: Confirm "Staged [N] files for commit"
                  Note: Skip if files are already staged

                STEP 4: Review What Will Be Committed
                  Command: git diff --cached
                  Purpose: See the actual changes that will be committed
                  Action: Analyze the diff to understand the changes

                STEP 5: Check Commit History Style
                  Command: git log -n 3 --oneline
                  Purpose: Match existing commit message conventions
                  Action: Note the style (conventional commits, simple messages, etc.)

                STEP 6: Understand Context (if needed)
                  Tool: lookupClass(className)
                  Purpose: Understand what changed classes do
                  When: Changes aren't self-explanatory from diff

                STEP 7: Analyze and Categorize Changes
                  Determine TYPE:
                  - feat: New feature (new class, new method, new functionality)
                  - fix: Bug fix (fixing incorrect behavior)
                  - refactor: Code restructuring (no behavior change)
                  - perf: Performance improvement
                  - docs: Documentation only
                  - test: Test files only
                  - chore: Build, dependencies, tooling, config

                  Determine SCOPE:
                  - Extract from package/directory: api, auth, ui, mcp, git, test, etc.
                  - Component affected

                STEP 8: Generate Commit Message

                CONVENTIONAL COMMITS FORMAT:
                ```
                <type>(<scope>): <subject>

                <body>

                <footer>
                ```

                **TYPE** (required):
                - feat, fix, refactor, perf, docs, test, chore, style, ci

                **SCOPE** (optional but recommended):
                - Module/component: api, auth, ui, db, mcp, etc.

                **SUBJECT** (required):
                - Imperative mood: "add" not "added"
                - Lowercase first letter
                - No period at end
                - Max 50 characters
                - Be specific: "add user authentication" not "add feature"

                **BODY** (optional):
                - Explain WHAT and WHY (not how)
                - Wrap at 72 characters
                - Blank line after subject

                **FOOTER** (optional):
                - Breaking changes: BREAKING CHANGE: description
                - Issue refs: Fixes #123

                EXAMPLES:

                ```
                feat(auth): add JWT token refresh mechanism

                Implement automatic token refresh to improve UX.
                Tokens refresh 5 minutes before expiration.

                Closes #234
                ```

                ```
                fix(mcp): prevent null pointer in project lookup

                Add null check before accessing project.basePath to avoid NPE
                when project path is not set.

                Fixes #567
                ```

                ```
                refactor(git): extract batch diff logic to helper

                Move getBatchFileDiffs implementation to GitServiceHelper
                for better code organization and testability.
                ```

                STEP 9: Present and Confirm
                  Action: Show the generated commit message to the user
                  Question: "Would you like to commit with this message? (yes/no)"
                  Or: "Would you like me to modify the message?"
                  User Response: Wait for confirmation

                STEP 10: Execute Commit
                  Command: git commit -m "the commit message"
                  Purpose: Create the commit with the approved message
                  Action: Confirm "Committed successfully" or show any errors
                  Note: Only run if user confirmed

                CONVERSATION STYLE:
                - Be friendly and conversational
                - Explain what you're doing at each step
                - Ask before staging or committing files
                - Suggest best practices (e.g., "These test changes should be in a separate commit")
                - Help users understand git concepts in simple terms

                EDGE CASES:
                - No changes: "Your working directory is clean. Nothing to commit."
                - Only unstaged: Ask which files to stage first
                - Mix of staged/unstaged: Focus on staged first, then ask about unstaged
                - Untracked files: Mention them but don't stage automatically
                - Large commits: Suggest splitting into multiple commits

                Project path: {{projectPath}}
                """);

        LOG.info("Registered 3 MCP prompts: review, explain, commit");
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

    private String buildGitStatusSchema() {
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

    private String buildGitDiffSchema() {
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

    private McpSchema.CallToolResult handleGetGitStatus(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            GitService gitService = project.getService(GitService.class);
            if (gitService == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("GitService not available for project")),
                        false
                );
            }

            String statusJson = gitService.getGitStatus();

            if (statusJson == null || statusJson.trim().isEmpty() || statusJson.equals("{}")) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("No changes in working directory")),
                        false
                );
            }

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(statusJson)), false);

        } catch (Exception e) {
            LOG.error("Error getting git status", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetGitDiff(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            GitService gitService = project.getService(GitService.class);
            if (gitService == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("GitService not available for project")),
                        false
                );
            }

            String statusJson = gitService.getGitStatus();
            if (statusJson == null || statusJson.trim().isEmpty() || statusJson.equals("{}")) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("No changes in working directory")),
                        false
                );
            }

            com.google.gson.JsonObject statusObj = gson.fromJson(statusJson, com.google.gson.JsonObject.class);
            String changedFiles = statusObj.get("changedFiles").getAsString();

            if (changedFiles == null || changedFiles.trim().isEmpty()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("No changes in working directory")),
                        false
                );
            }

            com.google.gson.JsonArray filesArray = new com.google.gson.JsonArray();
            String[] lines = changedFiles.split("\\n");

            for (String line : lines) {
                if (line.trim().isEmpty()) continue;

                String[] parts = line.split("\\t", 2);
                if (parts.length == 2) {
                    String status = parts[0].trim();
                    String filePath = parts[1].trim();

                    com.google.gson.JsonObject fileObj = new com.google.gson.JsonObject();
                    fileObj.addProperty("filePath", filePath);
                    fileObj.addProperty("status", status);
                    filesArray.add(fileObj);
                }
            }

            com.google.gson.JsonObject diffRequest = new com.google.gson.JsonObject();
            diffRequest.add("files", filesArray);

            String diffsJson = gitService.getBatchFileDiffs(diffRequest);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(diffsJson)), false);

        } catch (Exception e) {
            LOG.error("Error getting git diff", e);
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
        LOG.info("🔧 Available tools: getCurrentFile, lookupMethod, lookupClass, analyzeMethodUsage, getGitStatus, getGitDiff");
        LOG.info("💬 Available prompts: review, explain, commit");
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
}