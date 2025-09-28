package com.zps.zest.testgen.agents;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.testgen.tools.AnalyzeClassTool;
import com.zps.zest.testgen.tools.ListFilesTool;
import com.zps.zest.testgen.tools.TakeNoteTool;
import com.zps.zest.langchain4j.tools.impl.ReadFileTool;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.ui.model.ContextDisplayData;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI Agent responsible for gathering project context and understanding codebase.
 *
 * This agent acts as the "explorer" - it can navigate the project structure,
 * search for code patterns, analyze classes, and build a mental model of
 * the codebase architecture.
 *
 * Key capabilities:
 * - File system navigation (list files, find files)
 * - Code search with ripgrep (regex patterns, context lines)
 * - Class analysis (structure, dependencies, methods)
 * - Note-taking for important findings
 * - Build file analysis (pom.xml, build.gradle)
 *
 * The agent maintains internal state of explored classes and findings,
 * which can be accessed by the coordinator agent.
 */
public class ContextAgent extends StreamingBaseAgent {
    private static final Logger LOG = Logger.getInstance(ContextAgent.class);
    private static final int DEFAULT_MAX_TOOLS_PER_RESPONSE = 20;

    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextGatheringTools contextTools;
    private final ContextGatheringAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    private int maxToolsPerResponse = DEFAULT_MAX_TOOLS_PER_RESPONSE;

    public ContextAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull NaiveLLMService naiveLlmService) {
        this(project, langChainService, naiveLlmService, DEFAULT_MAX_TOOLS_PER_RESPONSE);
    }

    public ContextAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull NaiveLLMService naiveLlmService,
                        int maxToolsPerResponse) {
        super(project, langChainService, naiveLlmService, "ContextAgent");
        this.maxToolsPerResponse = maxToolsPerResponse;
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.contextTools = new ContextGatheringTools(project, toolRegistry, this::sendToUI, this);

        // Build the agent with streaming support
        // LangChain4j will handle the conversation and tool orchestration
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(100);
        this.assistant = AgenticServices
                .agentBuilder(ContextGatheringAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(maxToolsPerResponse*2) // Use the same limit as told to AI
                .chatMemory(chatMemory)
                .tools(contextTools)
                .build();
    }

    /**
     * Compatibility constructor for older code (Kotlin) - delegates to main constructor.
     */
    public ContextAgent(Project project, Object langChainService, Object llmService) {
        this(project, (ZestLangChain4jService) langChainService, (NaiveLLMService) llmService);
    }

    /**
     * Streamlined interface - LangChain4j handles the conversation loop internally
     */
    public interface ContextGatheringAssistant {
        @SystemMessage("""
You are a context gatherer for test generation. The class analyzer captures all static dependencies. Your goal is to understand the code under test without any assumption

IMPORTANT: All tool usage examples in this conversation are for illustration only. You MUST follow the actual tool signatures and parameter names as defined.
IMPORTANT: You MUST call markContextCollectionDone when you have gathered sufficient context to test the code without assumptions.

ALWAYS START WITH:
1. Call findProjectDependencies() to understand available test frameworks and dependencies
2. Then analyze the target class (if not already analyzed) 

INITIAL EXPLORATION PLAN (mandatory before any tool usage):
📋 What needs exploring, for example:
- [Item 1: specific file/pattern/config to find]
- [Item 2: dependencies or callers to trace]
- [Item 3: external resources or configs or API contracts]
- [Continue listing all items that need investigation]

RESPONSE TEMPLATE (mandatory for each subsequent tool usage):
📍 Phase: [Discovery|Analysis|Validation|Summary]
🔍 Exploring: [What you're looking for from the plan above]
📊 Found: [Key findings - bullet points]
🎯 Confidence: [High|Medium|Low]
⚡ Next: [Specific next action from plan or new discovery]
💰 Budget: [X/N tool calls used] (N is provided at session start)

VALIDATION:
- After you have finish the initial exploration plan, review what is missing/being assumed without confirmation. Go on to one more plan if necessary. 

EXPLORATION THINKING GUIDE:
Ask yourself before each exploration:
- What context do I have up to now?
- What is missing or left to be explored?
- Why is this important for understanding the code under test?
- What evidence do I have that this exists? (string literals, imports, patterns)
- How directly does this impact test generation?
- Is there a more efficient way to gather this information?

State your reasoning: "Exploring [target] because [specific evidence/reason]"
Avoid speculation - follow concrete leads from the code.

CONTEXT AWARENESS:
- Periodically assess: "Am I gathering essential or tangential information?"
- After multiple discoveries, synthesize: "The key insights so far are..."
- If you notice repetition, pivot to unexplored areas
- Be mindful of information density vs. verbosity
- Smart file exploration: use searchCode() with beforeLines/afterLines for specific functions/classes
  Example: searchCode("class ClassName", "*.java", null, 5, 10) gives you the class definition with context

CODE UNDERSTANDING APPROACH:
1. Review the analysis and identify what additional context is needed. State your exploration plan briefly.
2. Use appropriate tools to gather that context (tool limit per response will be provided at session start). Always start with file listing or find file.
3. Do not assume file path. Search for file path before you want to read it, unless you are absolutely sure about the path.
Do not read a file because you think it might exist - you need to prove that it is indeed used or is related to the code under test.
4. Prefer searchCode() with context lines over readFile(). Only use readFile() for non-code files (config, properties, etc.).
5. After gathering context, or reaching your tool limit, stop and use takeNote() to record key findings

YOUR TASK: Find context needed to understand the code under test:
- IMPORTANT: Find where and how other classes call or depend on the method(s) being tested.
- External APIs, services or script called dynamically. This should be noted via takeNote tools
- Unknown function/method implementation that is crucial to code understanding or writing correct test code.
- Configuration files (JSON, XML, YAML, properties) referenced by string literals
- Resource files loaded at runtime
- Database schemas or migration files
- Message formats or protocol definitions
- Existing test classes of the code under test, if such classes exist.
- Read *.iml (Intelij project file), pom (maven), gradle or ./lib(s) folders to understand what frameworks are being used and takeNote accordingly. 

AVOID:
- Classes already in the static dependency graph
- General project exploration
- Unrelated test examples   
- Files whose existence is unclear.

Your goal is not to fully explore the codebase, but to understand the code under test without any assumption.

Before any tool call, give a brief (about 50 words) on what you have explored.
After each tool usage, explain what you found and what else needs investigation.
Always take at least one note about project dependencies and libraries. You should take more notes if necessary. 
Stop when you can test the code without making assumptions about external resources.
""")
        @dev.langchain4j.agentic.Agent
        String gatherContext(String request);
    }

    /**
     * Gather context for test generation using the AI assistant.
     * Returns a completed future since the AI handles everything.
     */
    public CompletableFuture<Void> gatherContext(TestGenerationRequest request,
                                                 Consumer<Map<String, Object>> updateCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting context gathering with LangChain4j orchestration");

                // Reset tools for new session
                contextTools.reset();
                contextTools.setContextUpdateCallback(updateCallback);

                // Build the context request
                String contextRequest = buildContextRequest(request);

                // Send initial request to UI
                sendToUI("🔍 Gathering context for test generation...\n\n");
                sendToUI("📋 Request: " + contextRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Keep gathering context until explicitly marked as done
                int maxIterations = 3 ; // Safety limit to prevent infinite loops
                int iteration = 0;

                while (!contextTools.isContextCollectionDone() && iteration < maxIterations) {
                    iteration++;

                    try {
                        // For first iteration, use full context request; for subsequent iterations, just continue
                        String promptToUse = (iteration == 1) ? contextRequest :
                            "Continue gathering context. Remember to call markContextCollectionDone when you have sufficient context.";

                        // Let LangChain4j handle the conversation with streaming
                        String response = assistant.gatherContext(promptToUse);
                        sendToUI(response);
                        sendToUI("\n" + "-".repeat(40) + "\n");

                        // Check if context collection is now done
                        if (contextTools.isContextCollectionDone()) {
                            sendToUI("✅ Context collection marked as complete by assistant.\n");
                            break;
                        }

                        // If not done and not at max iterations, show continuation message
                        if (!contextTools.isContextCollectionDone() && iteration < maxIterations) {
                            sendToUI("\n🔄 Continuing context gathering (iteration " + (iteration + 1) + ")...\n");
                        }

                    } catch (Exception e) {
                        LOG.warn("Context agent encountered an error but continuing", e);
                        sendToUI("\n⚠️ Context agent stopped: " + e.getMessage());
                        sendToUI("\nContinuing with available context...\n");
                        break; // Exit loop on error
                    }
                }

                if (iteration >= maxIterations && !contextTools.isContextCollectionDone()) {
                    LOG.warn("Context gathering reached maximum iterations without completion");
                    sendToUI("\n⚠️ Context gathering reached maximum iterations. Proceeding with gathered context.\n");
                }

                return null;

            } catch (Exception e) {
                LOG.error("Failed to gather context", e);
                sendToUI("\n❌ Context gathering failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Context gathering failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Build the initial context request with complete target class information.
     */
    private String buildContextRequest(TestGenerationRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Gather comprehensive context for test generation.\n");
        prompt.append("Tool usage limit for this session: ").append(maxToolsPerResponse).append(" tool calls per response.\n\n");

        // Include target class analysis if available from contextTools
        String targetFilePath = request.getTargetFile().getVirtualFile().getPath();

        String targetClassAnalysis = contextTools.getAnalyzedClasses().get(targetFilePath);
        if (targetClassAnalysis == null){
            contextTools.analyzeClass(targetFilePath);
            targetClassAnalysis = contextTools.getAnalyzedClasses().get(targetFilePath);
        }
        if (targetClassAnalysis != null && !targetClassAnalysis.trim().isEmpty()) {
            prompt.append("=== TARGET CLASS ANALYSIS ===\n");
            prompt.append(targetClassAnalysis).append("\n");
            prompt.append("=== END TARGET CLASS ANALYSIS ===\n\n");
            prompt.append("The target class analysis above is complete. Do not re-analyze this class.\n");
        }

        // Include target method names from request
        if (!request.getTargetMethods().isEmpty()) {
            java.util.List<String> targetMethodNames = new java.util.ArrayList<>();
            for (com.intellij.psi.PsiMethod method : request.getTargetMethods()) {
                targetMethodNames.add(method.getName());
            }
            prompt.append("Target methods to focus on: ").append(String.join(", ", targetMethodNames)).append("\n");
        }


        // Include user-provided files from contextTools
        @NotNull List<String> userProvidedFiles = request.getUserProvidedFiles();
        if (!userProvidedFiles.isEmpty()) {
            prompt.append("\n=== USER PROVIDED FILES ===\n");
            for (String entry : userProvidedFiles) {
                // Skip target file as it's already analyzed above
                if (!entry.equals(targetFilePath)) {
                    prompt.append("File: ").append(entry).append("\n");
                    contextTools.readFile(entry);
                    prompt.append(contextTools.readFiles.get(entry)).append("\n");
                    prompt.append("---\n");
                }
            }
            prompt.append("=== END USER PROVIDED FILES ===\n\n");
            prompt.append("The files above were provided by the user. Use them as context but do not re-read them.\n");
        }

        // Include context notes if any exist
        java.util.List<String> contextNotes = contextTools.getContextNotes();
        if (!contextNotes.isEmpty()) {
            prompt.append("\n=== EXISTING CONTEXT NOTES ===\n");
            for (String note : contextNotes) {
                prompt.append("- ").append(note).append("\n");
            }
            prompt.append("=== END EXISTING CONTEXT NOTES ===\n\n");
        }

        // Check if user has provided context
        if (request.hasUserProvidedContext()) {
            prompt.append("User provided context is also in chat history above. Prioritize it.\n\n");
        }

        prompt.append("Target file: ").append(targetFilePath).append("\n");


        if (request.hasSelection()) {
            prompt.append("Note: User has selected specific code to test.\n");
        }

        prompt.append("\nAnalyze the provided class info and gather additional context needed for test generation.");

        return prompt.toString();
    }

    /**
     * Get context tools for direct access (used by other components).
     */
    public ContextGatheringTools getContextTools() {
        return contextTools;
    }

    /**
     * Get the chat memory for this agent.
     */
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }

    /**
     * Get the maximum number of tools allowed per response.
     */
    public int getMaxToolsPerResponse() {
        return maxToolsPerResponse;
    }

    /**
     * Set the maximum number of tools allowed per response.
     */
    public void setMaxToolsPerResponse(int maxToolsPerResponse) {
        this.maxToolsPerResponse = maxToolsPerResponse;
    }

    /**
     * Set event listener (backward compatibility).
     */
    public void setEventListener(Object listener) {
        // No-op for backward compatibility
    }

    /**
     * Send context file analysis to UI.
     */
    protected void sendContextFileAnalyzed(ContextDisplayData data) {
        // Send to UI through streaming callback if available
        sendToUI("📄 Analyzed: " + data.getFileName() + "\n");
    }

    /**
     * Context gathering tools - actual implementation of all tool methods.
     * This is a static inner class that contains all the @Tool annotated methods
     * that the AI assistant can call.
     */
    public static class ContextGatheringTools {
        private final Project project;
        private final CodeExplorationToolRegistry toolRegistry;
        private String sessionId;
        private Consumer<String> toolNotifier;
        private Consumer<Map<String, Object>> contextUpdateCallback;
        private ContextAgent contextAgent; // Reference to parent agent

        // Shared data storage - thread-safe collections for concurrent access
        private final Map<String, String> analyzedClasses = new ConcurrentHashMap<>();
        private final List<String> contextNotes = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, String> readFiles = new ConcurrentHashMap<>();
        private String frameworkInfo = "";
        private String projectDependencies = "";  // Structured dependency information from build files
        private volatile boolean contextCollectionDone = false;

        // Individual tool instances
        private final AnalyzeClassTool analyzeClassTool;
        private final ListFilesTool listFilesTool;
        private final RipgrepCodeTool ripgrepCodeTool;
        private final TakeNoteTool takeNoteTool;
        private final ReadFileTool readFileTool;

        public ContextGatheringTools(@NotNull Project project,
                                    @NotNull CodeExplorationToolRegistry toolRegistry,
                                    @Nullable Consumer<String> toolNotifier,
                                    @Nullable ContextAgent contextAgent) {
            this.project = project;
            this.toolRegistry = toolRegistry;
            this.toolNotifier = toolNotifier;
            this.contextAgent = contextAgent;

            // Initialize tools with shared data
            this.analyzeClassTool = new AnalyzeClassTool(project, analyzedClasses);
            this.listFilesTool = new ListFilesTool(project);
            this.takeNoteTool = new TakeNoteTool(contextNotes);
            this.ripgrepCodeTool = new RipgrepCodeTool(project, new HashSet<>(), new ArrayList<>());
            this.readFileTool = new ReadFileTool(project);
        }

        public void reset() {
            analyzedClasses.clear();
            contextNotes.clear();
            readFiles.clear();
            frameworkInfo = "";
            projectDependencies = "";
            contextCollectionDone = false;
        }

        public boolean isContextCollectionDone() {
            return contextCollectionDone;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public void setContextUpdateCallback(Consumer<Map<String, Object>> callback) {
            this.contextUpdateCallback = callback;
        }

        public Map<String, Object> getGatheredData() {
            Map<String, Object> data = new HashMap<>();
            data.put("analyzedClasses", new HashMap<>(analyzedClasses));
            data.put("contextNotes", new ArrayList<>(contextNotes));
            data.put("readFiles", new HashMap<>(readFiles));
            return data;
        }

        // Getters for backward compatibility
        public List<String> getContextNotes() {
            return new ArrayList<>(contextNotes);
        }

        public Map<String, String> getAnalyzedClasses() {
            return new HashMap<>(analyzedClasses);
        }

        public Map<String, String> getReadFiles() {
            return new HashMap<>(readFiles);
        }

        public String getFrameworkInfo() {
            detectFramework();
            return frameworkInfo;
        }

        public String getProjectDependencies() {
            return projectDependencies;
        }

        /**
         * Detect testing framework from analyzed classes and build files
         */
        public void detectFramework() {
            String allContent = String.join("\n", analyzedClasses.values()) +
                               String.join("\n", readFiles.values());

            if (allContent.contains("org.junit.jupiter") || allContent.contains("@Test") && allContent.contains("jupiter")) {
                frameworkInfo = "JUnit 5";
            } else if (allContent.contains("org.junit.Test") || allContent.contains("junit:junit")) {
                frameworkInfo = "JUnit 4";
            } else if (allContent.contains("testng") || allContent.contains("org.testng")) {
                frameworkInfo = "TestNG";
            } else if (allContent.contains("spring-boot-starter-test") || allContent.contains("@SpringBootTest")) {
                frameworkInfo = "Spring Boot Test";
            } else {
                frameworkInfo = "JUnit 5"; // Default fallback
            }
        }

        private void notifyTool(String toolName, String params) {
            if (toolNotifier != null) {
                SwingUtilities.invokeLater(() ->
                    toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params)));
            }
        }

        private void notifyContextUpdate() {
            if (contextUpdateCallback != null) {
                SwingUtilities.invokeLater(() ->
                    contextUpdateCallback.accept(getGatheredData()));
            }
        }

        @Tool("""
            Analyze a Java class to extract complete structure, methods, fields, and dependencies.
            ⚠️ EXPENSIVE: This performs deep analysis - use sparingly for key classes only.

            INPUT FORMATS (tries in order):
            1. Fully qualified name: "com.example.service.UserService" (best)
            2. File path: "/src/main/java/com/example/UserService.java"
            3. Simple name: "UserService" (may find wrong class if multiple exist)

            RETURNS: Complete class source code, structure, javadoc, and all dependencies.

            TIPS: Use findFiles() or searchCode() first if unsure of exact class name.
            """)
        public String analyzeClass(String filePathOrClassName) {
            notifyTool("analyzeClass", filePathOrClassName);
            String result = analyzeClassTool.analyzeClass(filePathOrClassName);

            // Create and send ContextDisplayData object directly to UI
            if (contextAgent != null) {
                ContextDisplayData contextData = createContextDisplayData(filePathOrClassName, result);
                contextAgent.sendContextFileAnalyzed(contextData);
            }

            notifyContextUpdate();
            return result;
        }

        @Tool("""
            List files and subdirectories in a directory with controlled recursion depth.

            Parameters:
            - directoryPath: The directory to list (e.g., "config/", "src/main/resources")
            - recursiveLevel: How deep to explore:
              * 0 = current directory only (non-recursive) - use for large dirs like src/
              * 1 = current + immediate subdirectories - use for most exploration
              * 2 = two levels deep - use for config/templates structures
              * 3+ = deeper exploration - use only when absolutely necessary

            Examples:
            - listFiles("config/", 0) → lists only files directly in config/
            - listFiles("config/", 2) → explores config/ and 2 levels deep
            - listFiles("src/main/resources", 1) → resources/ and immediate subdirs
            - listFiles("scripts/", 1) → scripts/ and immediate subdirs
            """)
        public String listFiles(String directoryPath, Integer recursiveLevel) {
            try {
                // Validate parameters
                if (directoryPath == null || directoryPath.trim().isEmpty()) {
                    return "Error: directoryPath cannot be null or empty";
                }

                // Default recursiveLevel to 1 if null
                if (recursiveLevel == null) {
                    recursiveLevel = 1;
                }

                // Validate recursiveLevel range
                if (recursiveLevel < 0 || recursiveLevel > 10) {
                    return "Error: recursiveLevel must be between 0 and 10";
                }

                notifyTool("listFiles", directoryPath + " (level=" + recursiveLevel + ")");
                return listFilesTool.listFiles(directoryPath, recursiveLevel);
            } catch (Exception e) {
                LOG.error("Error in listFiles tool", e);
                return "Error listing files in " + directoryPath + ": " + e.getMessage();
            }
        }

        @Tool("""
            Find files by NAME/PATH that match specific glob patterns using ripgrep.
            This searches for file names/paths, NOT file contents. Use searchCode() to search inside files.

            🔍 EFFICIENT MULTI-PATTERN SEARCH:
            Use comma-separated patterns for finding multiple file types:

            BUILD & CONFIG FILES (single search):
            - "pom.xml,build.gradle,*.properties,application*.yml,*.json" → All config files at once

            TEST FILES (single search):
            - "*Test.java,*Tests.java,*IT.java,test-*.xml" → All test-related files

            RESOURCE FILES (single search):
            - "*.sql,*.yaml,*.yml,*.md,*.iml" → All resource/doc files

            JAVA SOURCE FILES:
            - "*Service.java,*Repository.java,*Controller.java" → Common Spring components
            - "*Entity.java,*Model.java,*Dto.java" → Data classes

            Examples:
            - findFiles("pom.xml,build.gradle") → Find build files efficiently
            - findFiles("*Test.java,*Tests.java") → Find all test classes in one search
            - findFiles("*.properties,*.yml,*.yaml") → Find all config files together
            - findFiles("**/*Service.java,**/*Repository.java") → Find service/repo classes
            """)
        public String findFiles(String pattern) {
            notifyTool("findFiles", pattern);
            return ripgrepCodeTool.findFiles(pattern);
        }

        @Tool("""
            Powerful code search with blazing-fast ripgrep backend. Supports regex, file filtering, and context lines.

            KEY DIFFERENCE:
            - query: Uses REGEX (| works for OR, e.g., "TODO|FIXME")
            - filePattern: Uses GLOB (use comma for multiple, e.g., "*.java,*.kt")

            Core Capabilities:
            - Regex patterns with | (OR) operator for content search
            - File filtering with comma-separated glob patterns
            - Context lines (before/after) for understanding surroundings
            - Case-sensitive searching available

            🔍 POWERFUL PATTERNS FOR TEST WRITING:

            1. USAGE/INSTANTIATION PATTERNS - Find where classes are used:
               - "new ClassName\\(" → Find instantiations
               - "ClassName\\." → Find static method calls
               - "\\bClassName\\b" → Find any reference (word boundary)
               - "new (UserService|AuthService)\\(" → Multiple classes with |

            2. CALLERS/CONSUMERS - Find who calls your methods:
               - "methodName\\(" → All calls to method
               - "\\.methodName\\(" → Instance method calls
               - "(save|update|delete)\\(" → Multiple methods with |
               - "getUserById\\(.*\\)" → Calls with any arguments

            3. CASE-SENSITIVE TIPS:
               - CamelCase: "[A-Z][a-z]+[A-Z]" → getUserName, firstName
               - snake_case: "[a-z]+_[a-z]+" → user_name, first_name
               - CONSTANTS: "[A-Z_]+" → MAX_SIZE, DEFAULT_VALUE
               - Mixed: "(userId|user_id|UserID)" → All variations

            4. COMBINED SEARCHES:
               - "@Test.*void.*test" → Test methods
               - "@(Test|ParameterizedTest|RepeatedTest)" → Any test annotation
               - "assert(Equals|True|NotNull)\\(" → Any assertion
               - "mock\\(|when\\(|verify\\(" → Mockito patterns

            Parameters:
            - query: Search pattern (REGEX - use | for OR in content)
            - filePattern: Include files (GLOB - use comma for multiple, e.g., "*.java,*.kt")
            - excludePattern: Exclude files (comma-separated, e.g., "test,generated")
            - beforeLines: Lines to show before match (0-10)
            - afterLines: Lines to show after match (0-10)

            Examples:
            - searchCode("TODO|FIXME", "*.java,*.kt", null, 0, 0)
              → Find TODO or FIXME in Java/Kotlin files
            - searchCode("new (User|Admin|Guest)Service\\(", "*.java", "test,generated", 2, 2)
              → Find service instantiations with context
            """)
        public String searchCode(String query, String filePattern, String excludePattern,
                                Integer beforeLines, Integer afterLines) {
            try {
                // Validate required parameters
                if (query == null || query.trim().isEmpty()) {
                    return "Error: query cannot be null or empty";
                }

                // Default integer parameters if null
                if (beforeLines == null) beforeLines = 0;
                if (afterLines == null) afterLines = 0;

                // Validate ranges
                if (beforeLines < 0 || beforeLines > 10) {
                    return "Error: beforeLines must be between 0 and 10";
                }
                if (afterLines < 0 || afterLines > 10) {
                    return "Error: afterLines must be between 0 and 10";
                }

                notifyTool("searchCode", String.format("query=%s, filePattern=%s, excludePattern=%s, before=%d, after=%d",
                                                       query, filePattern, excludePattern, beforeLines, afterLines));
                return ripgrepCodeTool.searchCode(query, filePattern, excludePattern, beforeLines, afterLines);
            } catch (Exception e) {
                LOG.error("Error in searchCode tool", e);
                return "Error searching code with query '" + query + "': " + e.getMessage();
            }
        }

        @Tool("Record crucial technical details needed for precise" +
                " test writing. Focus on missing information not provided in initial context " +
                "- concrete values, behaviors, contracts, or dependencies that tests must account for. " +
                "Be specific: exact values, paths, formats, or behaviors rather than general observations. " +
                "The note will be passed to a test writer. " +
                "Format: '[Relevance] Finding - Specific detail/value'")
        public String takeNote(String note) {
            notifyTool("takeNote", note.length() > 50 ? note.substring(0, 50) + "..." : note);
            String result = takeNoteTool.takeNote(note);
            notifyContextUpdate();
            return result;
        }

        @Tool("Read the complete content of any file in the project")
        public String readFile(String filePath) {
            notifyTool("readFile", filePath);

            // Use ReadFileTool with JsonObject parameter
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.addProperty("filePath", filePath);

            var toolResult = readFileTool.execute(params);
            String result = toolResult.isSuccess() ? toolResult.getContent() : toolResult.getContent();

            // Ensure result is never null or blank for LangChain4j
            if (result == null || result.trim().isEmpty()) {
                result = "File not found or empty: " + filePath;
            }

            // Store in readFiles map
            readFiles.put(filePath, result);

            // Also send file read as context update
            if (contextAgent != null && !result.startsWith("File not found")) {
                ContextDisplayData contextData = createContextDisplayData(filePath, result);
                contextAgent.sendContextFileAnalyzed(contextData);
            }

            notifyContextUpdate();
            return result;
        }

        @Tool("Mark context collection as complete when you have gathered sufficient context to test the code without assumptions. " +
              "Call this tool when you are confident that you have all necessary information.")
        public String markContextCollectionDone() {
            notifyTool("markContextCollectionDone", "");
            contextCollectionDone = true;

            int totalItems = analyzedClasses.size() + contextNotes.size() + readFiles.size();
            String summary = String.format("✅ Context collection completed: %d classes analyzed, %d notes taken, %d files read",
                                         analyzedClasses.size(), contextNotes.size(), readFiles.size());

            notifyContextUpdate();
            return summary;
        }

        @Tool("Find and analyze project build files (pom.xml, build.gradle, *.iml) to understand available test frameworks and dependencies. " +
              "This should be called at the start to understand what testing libraries are available in the project.")
        public String findProjectDependencies(String dummyParams) {
            notifyTool("findProjectDependencies", "Searching for build files");

            StringBuilder dependencyInfo = new StringBuilder();
            dependencyInfo.append("=== PROJECT DEPENDENCIES ===\n");

            // Find build files using existing findFiles tool
            String buildFilePatterns = "pom.xml,build.gradle,build.gradle.kts,*.iml";
            String buildFilesFound = findFiles(buildFilePatterns);

            if (buildFilesFound.contains("No files found")) {
                projectDependencies = "No build files found. Cannot determine project dependencies.";
                return projectDependencies;
            }

            // Parse the file list - look for lines with "Full path:" to get absolute paths
            String[] lines = buildFilesFound.split("\n");
            List<String> buildFiles = new ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Full path:")) {
                    // Extract the full path for reading
                    String fullPath = line.substring("Full path:".length()).trim();
                    buildFiles.add(fullPath);
                }
            }

            // Analyze each build file
            Set<String> testFrameworks = new HashSet<>();
            Set<String> mockFrameworks = new HashSet<>();
            Set<String> otherDependencies = new HashSet<>();

            for (String buildFile : buildFiles) {
                if (buildFile.endsWith("pom.xml")) {
                    // Read and analyze Maven POM
                    String pomContent = readFile(buildFile);
                    analyzeMavenDependencies(pomContent, testFrameworks, mockFrameworks, otherDependencies);
                    dependencyInfo.append("\nMaven project detected (").append(buildFile).append(")\n");
                } else if (buildFile.contains("build.gradle")) {
                    // Read and analyze Gradle build file
                    String gradleContent = readFile(buildFile);
                    analyzeGradleDependencies(gradleContent, testFrameworks, mockFrameworks, otherDependencies);
                    dependencyInfo.append("\nGradle project detected (").append(buildFile).append(")\n");
                } else if (buildFile.endsWith(".iml")) {
                    // Read and analyze IntelliJ module file
                    String imlContent = readFile(buildFile);
                    analyzeImlDependencies(imlContent, testFrameworks, mockFrameworks, otherDependencies);
                    dependencyInfo.append("\nIntelliJ module detected (").append(buildFile).append(")\n");
                }
            }

            // Build structured dependency information
            if (!testFrameworks.isEmpty()) {
                dependencyInfo.append("\nTest Frameworks:\n");
                for (String framework : testFrameworks) {
                    dependencyInfo.append("  - ").append(framework).append("\n");
                    // Auto-create note about test framework
                    takeNote("[TEST_FRAMEWORK] " + framework + " is available for testing");
                }
            }

            if (!mockFrameworks.isEmpty()) {
                dependencyInfo.append("\nMocking Frameworks:\n");
                for (String framework : mockFrameworks) {
                    dependencyInfo.append("  - ").append(framework).append("\n");
                    // Auto-create note about mocking framework
                    takeNote("[MOCK_FRAMEWORK] " + framework + " is available for mocking");
                }
            }

            if (!otherDependencies.isEmpty()) {
                dependencyInfo.append("\nOther Testing Dependencies:\n");
                for (String dep : otherDependencies) {
                    dependencyInfo.append("  - ").append(dep).append("\n");
                }
            }

            if (testFrameworks.isEmpty() && mockFrameworks.isEmpty()) {
                dependencyInfo.append("\n⚠️ No test frameworks detected in build files\n");
                takeNote("[WARNING] No test frameworks found in build files - tests may need manual dependency setup");
            }

            projectDependencies = dependencyInfo.toString();
            notifyContextUpdate();
            return projectDependencies;
        }

        private void analyzeMavenDependencies(String pomContent, Set<String> testFrameworks,
                                             Set<String> mockFrameworks, Set<String> otherDeps) {
            // Check for JUnit
            if (pomContent.contains("junit") || pomContent.contains("JUnit")) {
                if (pomContent.contains("junit-jupiter") || pomContent.contains("junit5")) {
                    testFrameworks.add("JUnit 5 (Jupiter)");
                } else if (pomContent.contains("junit4") || pomContent.contains("<version>4.")) {
                    testFrameworks.add("JUnit 4");
                } else {
                    testFrameworks.add("JUnit");
                }
            }

            // Check for TestNG
            if (pomContent.contains("testng")) {
                testFrameworks.add("TestNG");
            }

            // Check for Mockito
            if (pomContent.contains("mockito")) {
                mockFrameworks.add("Mockito");
            }

            // Check for PowerMock
            if (pomContent.contains("powermock")) {
                mockFrameworks.add("PowerMock");
            }

            // Check for TestContainers
            if (pomContent.contains("testcontainers")) {
                otherDeps.add("TestContainers");
            }

            // Check for AssertJ
            if (pomContent.contains("assertj")) {
                otherDeps.add("AssertJ");
            }

            // Check for Hamcrest
            if (pomContent.contains("hamcrest")) {
                otherDeps.add("Hamcrest");
            }

            // Check for Spring Boot Test
            if (pomContent.contains("spring-boot-starter-test")) {
                otherDeps.add("Spring Boot Test");
            }

            // Check for REST Assured
            if (pomContent.contains("rest-assured")) {
                otherDeps.add("REST Assured");
            }
        }

        private void analyzeGradleDependencies(String gradleContent, Set<String> testFrameworks,
                                              Set<String> mockFrameworks, Set<String> otherDeps) {
            // Similar analysis for Gradle
            if (gradleContent.contains("junit") || gradleContent.contains("JUnit")) {
                if (gradleContent.contains("junit-jupiter") || gradleContent.contains("junit:5")) {
                    testFrameworks.add("JUnit 5 (Jupiter)");
                } else if (gradleContent.contains("junit:4")) {
                    testFrameworks.add("JUnit 4");
                } else {
                    testFrameworks.add("JUnit");
                }
            }

            if (gradleContent.contains("testng")) {
                testFrameworks.add("TestNG");
            }

            if (gradleContent.contains("mockito")) {
                mockFrameworks.add("Mockito");
            }

            if (gradleContent.contains("testcontainers")) {
                otherDeps.add("TestContainers");
            }

            if (gradleContent.contains("assertj")) {
                otherDeps.add("AssertJ");
            }

            if (gradleContent.contains("spring-boot-starter-test")) {
                otherDeps.add("Spring Boot Test");
            }
        }

        private void analyzeImlDependencies(String imlContent, Set<String> testFrameworks,
                                           Set<String> mockFrameworks, Set<String> otherDeps) {
            // Analyze IntelliJ module file for library references
            if (imlContent.contains("junit") || imlContent.contains("JUnit")) {
                testFrameworks.add("JUnit");
            }

            if (imlContent.contains("testng")) {
                testFrameworks.add("TestNG");
            }

            if (imlContent.contains("mockito")) {
                mockFrameworks.add("Mockito");
            }

            if (imlContent.contains("testcontainers")) {
                otherDeps.add("TestContainers");
            }
        }

        public ContextDisplayData createContextDisplayData(String filePath, String analysisResult) {
            // Extract file name
            String fileName = filePath;
            int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                fileName = filePath.substring(lastSlash + 1);
            }

            // Extract summary (first paragraph or first 200 chars)
            String summary = analysisResult;
            if (summary.length() > 200) {
                int firstNewline = summary.indexOf('\n');
                if (firstNewline > 0 && firstNewline < 200) {
                    summary = summary.substring(0, firstNewline);
                } else {
                    summary = summary.substring(0, 200) + "...";
                }
            }

            // Extract classes and methods from analysis
            List<String> classes = extractClasses(analysisResult);
            List<String> methods = extractMethods(analysisResult);

            return new ContextDisplayData(
                filePath,
                fileName,
                ContextDisplayData.AnalysisStatus.COMPLETED,
                summary,
                analysisResult, // full analysis
                classes,
                methods,
                Collections.emptyList(), // dependencies
                System.currentTimeMillis()
            );
        }

        private List<String> extractClasses(String analysis) {
            List<String> classes = new ArrayList<>();
            // Look for class declarations
            String[] lines = analysis.split("\n");
            for (String line : lines) {
                if (line.contains("class ") && (line.contains("public") || line.contains("private"))) {
                    int classIndex = line.indexOf("class ") + 6;
                    int endIndex = line.indexOf(' ', classIndex);
                    if (endIndex == -1) endIndex = line.indexOf('{', classIndex);
                    if (endIndex > classIndex) {
                        String className = line.substring(classIndex, endIndex).trim();
                        if (!className.isEmpty()) {
                            classes.add(className);
                        }
                    }
                }
            }
            return classes;
        }

        private List<String> extractMethods(String analysis) {
            List<String> methods = new ArrayList<>();
            // Look for method signatures
            String[] lines = analysis.split("\n");
            for (String line : lines) {
                if ((line.contains("public ") || line.contains("private ") || line.contains("protected "))
                    && line.contains("(") && line.contains(")") && !line.contains("class ")) {
                    // Try to extract method name
                    int parenIndex = line.indexOf('(');
                    if (parenIndex > 0) {
                        int spaceBeforeParen = line.lastIndexOf(' ', parenIndex);
                        if (spaceBeforeParen >= 0 && spaceBeforeParen < parenIndex) {
                            String methodName = line.substring(spaceBeforeParen + 1, parenIndex).trim();
                            if (!methodName.isEmpty() && !methodName.contains(" ")) {
                                methods.add(methodName);
                            }
                        }
                    }
                }
            }
            return methods;
        }
    }
}