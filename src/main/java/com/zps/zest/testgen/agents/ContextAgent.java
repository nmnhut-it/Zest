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
import com.zps.zest.testgen.model.TestPlan;
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
import java.util.stream.Collectors;

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

    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextGatheringTools contextTools;
    private final ContextGatheringAssistant assistant;
    private final MessageWindowChatMemory chatMemory;

    public ContextAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull NaiveLLMService naiveLlmService) {
        super(project, langChainService, naiveLlmService, "ContextAgent");
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.contextTools = new ContextGatheringTools(project, toolRegistry, this::sendToUI, this);

        // Build the agent with streaming support
        // LangChain4j will handle the conversation and tool orchestration
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(100);
        this.assistant = AgenticServices
                .agentBuilder(ContextGatheringAssistant.class)
                .chatModel(getChatModelWithStreaming()) // Use wrapped model for streaming
                .maxSequentialToolsInvocations(10) // Limit tool calls per response
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

RESPONSE STYLE & CONSTRAINTS:
- Be concise: Less talk, more action. Focus on tool usage over lengthy explanations.
- Track your tool budget: Always state how many tool calls you have left (e.g., "3/5 tools remaining").
- Smart file exploration: Instead of reading entire files, use searchCode() with beforeLines/afterLines to see specific functions/classes in context.
  Example: searchCode("class ClassName", "*.java", null, 5, 10) gives you the class definition with surrounding context.
- This is much more efficient than readFile() for understanding code structure.

CODE UNDERSTANDING APPROACH:
1. Review the analysis and identify what additional context is needed. State your exploration plan briefly.
2. Use appropriate tools to gather that context (max 5 tools per response). Always start with file listing or find file.
3. Do not assume file path. Search for file path before you want to read it, unless you are absolutely sure about the path.
Do not read a file because you think it might exist - you need to prove that it is indeed used or is related to the code under test.
4. Prefer searchCode() with context lines over readFile(). Only use readFile() for non-code files (config, properties, etc.).
5. After gathering context, or using 5 tools, stop and use takeNote() to record key findings

YOUR TASK: Find context needed to understand the code under test:
- External APIs, services or script called dynamically
- Unknown function/method implementation that is crucial to code understanding or writing correct test code.
- Configuration files (JSON, XML, YAML, properties) referenced by string literals
- Resource files loaded at runtime
- Database schemas or migration files
- Message formats or protocol definitions
- Usage of the method under test throughout the project.
- Existing test classes of the code under test, if such classes exist.

AVOID:
- Classes already in the static dependency graph
- General project exploration
- Unrelated test examples
- Files whose existence is unclear.

Your goal is not to fully explore the codebase, but to understand the code under test without any assumption.

Before any tool call, give a brief (about 50 words) on what you have explored.
After each tool usage, explain what you found and what else needs investigation.
Stop when you can test the code without making assumptions about external resources.
""")
        @dev.langchain4j.agentic.Agent
        String gatherContext(String request);
    }

    /**
     * Gather context for test generation using the AI assistant.
     * Returns a completed future since the AI handles everything.
     */
    public CompletableFuture<Void> gatherContext(Object request, Object tools,
                                                  String systemPrompt,
                                                  Consumer<Map<String, Object>> updateCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting context gathering with LangChain4j orchestration");

                // Reset tools for new session
                contextTools.reset();
                contextTools.setContextUpdateCallback(updateCallback);

                // Build the context request
                String contextRequest;
                if (request instanceof TestGenerationRequest) {
                    contextRequest = buildContextRequest((TestGenerationRequest) request, null);
                } else {
                    contextRequest = request.toString();
                }

                // Send initial request to UI
                sendToUI("🔍 Gathering context for test generation...\n\n");
                sendToUI("📋 Request: " + contextRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Let LangChain4j handle the entire conversation with streaming
                try {
                    String response = assistant.gatherContext(contextRequest);
                    sendToUI(response);
                    sendToUI("\n" + "-".repeat(40) + "\n");
                } catch (Exception e) {
                    LOG.warn("Context agent encountered an error but continuing", e);
                    sendToUI("\n⚠️ Context agent stopped: " + e.getMessage());
                    sendToUI("\nContinuing with available context...\n");
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
     * Build the initial context request.
     */
    private String buildContextRequest(TestGenerationRequest request, TestPlan testPlan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Gather comprehensive context for test generation.\n\n");

        prompt.append("Target file: ").append(request.getTargetFile().getVirtualFile().getPath()).append("\n");

        if (testPlan != null) {
            prompt.append("Target class: ").append(testPlan.getTargetClass()).append("\n");
            if (testPlan.getTargetMethods() != null && !testPlan.getTargetMethods().isEmpty()) {
                prompt.append("Target methods: ").append(String.join(", ", testPlan.getTargetMethods())).append("\n");
            }
        }

        if (request.hasSelection()) {
            prompt.append("Note: User has selected specific code to test.\n");
        }

        prompt.append("\nAnalyze the provided class info and gather additional context needed for test generation.");
//        prompt.append("\nFocus on external dependencies, configurations, and runtime resources.");

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

        // Shared data storage
        private final Map<String, String> analyzedClasses = new ConcurrentHashMap<>();
        private final List<String> contextNotes = new ArrayList<>();
        private final Map<String, String> readFiles = new ConcurrentHashMap<>();
        private String frameworkInfo = "";

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

        @Tool("Analyze a Java class to extract its complete structure, dependencies, and relationships")
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
        public String listFiles(String directoryPath, int recursiveLevel) {
            notifyTool("listFiles", directoryPath + " (level=" + recursiveLevel + ")");
            return listFilesTool.listFiles(directoryPath, recursiveLevel);
        }

        @Tool("Find files matching a pattern in the project")
        public String findFiles(String pattern) {
            notifyTool("findFiles", pattern);
            return ripgrepCodeTool.findFiles(pattern);
        }

        @Tool("""
            Powerful code search with blazing-fast ripgrep backend. Supports regex, file filtering, and context lines.

            Core Capabilities:
            - Regex patterns with | (OR) operator for multiple patterns
            - File filtering with glob patterns
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
            - query: Search pattern (regex supported, use | for OR)
            - filePattern: Include files (e.g., "*.java", "**/*Test.java")
            - excludePattern: Exclude files (e.g., "*generated*", "target/**")
            - beforeLines: Lines to show before match (0-10)
            - afterLines: Lines to show after match (0-10)

            Examples:
            - searchCode("new (User|Admin|Guest)Service\\(", "*.java", null, 2, 2)
              → Find service instantiations with context
            """)
        public String searchCode(String query, String filePattern, String excludePattern,
                                int beforeLines, int afterLines) {
            notifyTool("searchCode", String.format("query=%s, filePattern=%s, excludePattern=%s, before=%d, after=%d",
                                                   query, filePattern, excludePattern, beforeLines, afterLines));
            return ripgrepCodeTool.searchCode(query, filePattern, excludePattern, beforeLines, afterLines);
        }

        // Overloaded searchCode for backward compatibility
        public String searchCode(String query) {
            return searchCode(query, null, null, 0, 0);
        }

        @Tool("Record important findings that impact test generation. Keep notes concise but complete.")
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

            // Store in readFiles map
            readFiles.put(filePath, result);

            // Also send file read as context update
            if (contextAgent != null && result != null && !result.isEmpty()) {
                ContextDisplayData contextData = createContextDisplayData(filePath, result);
                contextAgent.sendContextFileAnalyzed(contextData);
            }

            notifyContextUpdate();
            return result;
        }

        private ContextDisplayData createContextDisplayData(String filePath, String analysisResult) {
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