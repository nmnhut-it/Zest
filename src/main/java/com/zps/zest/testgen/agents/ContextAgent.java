package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.testgen.tools.AnalyzeClassTool;
import com.zps.zest.testgen.tools.FindFilesTool;
import com.zps.zest.testgen.tools.ListFilesTool;
import com.zps.zest.testgen.tools.TakeNoteTool;
import com.zps.zest.langchain4j.tools.impl.ReadFileTool;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.TestContext;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.ui.model.ContextDisplayData;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

/**
 * Context gathering agent using LangChain4j's streaming capabilities.
 * No conversation loops - LangChain4j handles the agent orchestration.
 */
public class ContextAgent extends StreamingBaseAgent {
    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextGatheringTools contextTools;
    private final ContextGatheringAssistant assistant;
    private final MessageWindowChatMemory chatMemory;

    public ContextAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "ContextAgent");
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
     * Streamlined interface - LangChain4j handles the conversation loop internally
     */
    public interface ContextGatheringAssistant {
        @dev.langchain4j.service.SystemMessage("""
You are a context gatherer for test generation. The class analyzer captures all static dependencies. Your goal is to understand the code under test without any assumption

CONVERSATION APPROACH:
1. Review the analysis and identify what additional context is needed. List out what your want to explore first.
2. Use appropriate tools to gather that context (max 5 tools per response). Always start with file listing or find file. You have a budget of maximum 5 tool calls, so use it wisely.
3. Do not assume file path. Search for file path before you want to read it, unless you are absolutely sure about the path. 
Do not read a file because you think it might exist - you need to prove that it is indeed used or is related to the code under tes. 
4. Do not read whole code file if you have its signature, and that is enough to write tests. 
Before you read a file, give clear reason, and make sure you have not make the call to read it yet. 
5. After gathering context, or reading more than 5 files, stop and use takeNote() to record key findings

YOUR TASK: Find non-static context needed to understand the code under test:
- External APIs, services or script called dynamically
- Configuration files (JSON, XML, YAML, properties) referenced by string literals
- Resource files loaded at runtime
- Database schemas or migration files
- Message formats or protocol definitions
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

    @NotNull
    public CompletableFuture<TestContext> gatherContext(@NotNull TestGenerationRequest request,
                                                        @Nullable TestPlan testPlan,
                                                        @Nullable String sessionId,
                                                        @Nullable Consumer<Map<String, Object>> contextUpdateCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting context gathering with LangChain4j orchestration");
                
                // Notify UI that we're starting
                notifyStart();
                sendToUI("🔍 Gathering context for test generation...\n\n");

                // Reset tools for new session
                contextTools.reset();
                contextTools.setSessionId(sessionId);
                contextTools.setContextUpdateCallback(contextUpdateCallback);

                // Build the request with class analysis included
                String contextRequest = buildContextRequest(request, testPlan);
                
                // Send the initial request to UI
                sendToUI("📋 Request: " + contextRequest + "\n\n");
                sendToUI("🤖 Assistant Response:\n");
                sendToUI("-".repeat(40) + "\n");

                // Let LangChain4j handle the entire conversation with streaming
                // The assistant will make multiple tool calls as needed
                try {
                    String response = assistant.gatherContext(contextRequest);
                    sendToUI(response);
                    sendToUI("\n" + "-".repeat(40) + "\n");
                    notifyComplete();
                }
                catch (Exception e){
                    LOG.warn("Context agent encountered an error but continuing with static analyzer", e);
                    sendToUI("\n⚠️ Context agent stopped: " + e.getMessage());
                    sendToUI("\nContinuing with static analyzer results...\n");
                }
                // Log token usage for this session
//                if (getChatModelWithStreaming() instanceof ZestChatLanguageModel) {
//                    ZestChatLanguageModel zestModel = (ZestChatLanguageModel) getChatModelWithStreaming();
//                    TokenUsageTracker tracker = zestModel.getTokenTracker();
//                    LOG.info(String.format("Context gathering token usage - Total: %d, TPM: %d",
//                        tracker.getTotalTokens(), tracker.getTokensPerMinute()));
//                }
//
                // Send the response to UI


                // Build TestContext from gathered data
                TestContext context = buildTestContext(request, testPlan, contextTools.getGatheredData());
                
                LOG.debug("Context gathering complete. Items collected: " + context.getContextItemCount());
                
                return context;

            } catch (Exception e) {
                LOG.error("Failed to gather context", e);
                sendToUI("\n❌ Context gathering failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Context gathering failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Get the context gathering tools for direct access
     */
    public ContextGatheringTools getContextTools() {
        return contextTools;
    }

    /**
     * Build the initial context request with pre-analyzed class info to save tokens.
     */
    private String buildContextRequest(TestGenerationRequest request, TestPlan testPlan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Gather comprehensive context for test generation.\n\n");
        
        prompt.append("Target file: ").append(request.getTargetFile().getVirtualFile().getPath()).append("\n");
        
        if (testPlan != null) {
            prompt.append("Target class: ").append(testPlan.getTargetClass()).append("\n");
            if (testPlan.getTargetMethods() != null && !testPlan.getTargetMethods().isEmpty()) {
                prompt.append("Target method: ").append(testPlan.getTargetMethods().stream().collect(Collectors.joining(","))).append("\n");
            }
        }
        
        if (request.hasSelection()) {
            prompt.append("Note: User has selected specific code to test.\n");
        }
        
        // Pre-analyze the class to include in request (saves tokens)
        String classAnalysis = preAnalyzeClass(request, testPlan);
        if (classAnalysis != null) {
            prompt.append("\nPre-analyzed class structure:\n");
            prompt.append(compressWhitespace(classAnalysis));
            prompt.append("\n");
        }
        
        prompt.append("\nAnalyze the provided class info and gather additional context needed for test generation.");
        prompt.append("\nFocus on external dependencies, configurations, and runtime resources.");
        
        // Log token estimate
        int estimatedTokens = prompt.toString().length() / 4; // Rough estimate
        LOG.info("Context request estimated tokens: " + estimatedTokens);
        
        return prompt.toString();
    }

    /**
     * Pre-analyze the target class to include in the initial request.
     */
    private String preAnalyzeClass(TestGenerationRequest request, TestPlan testPlan) {
        try {

            return        this.contextTools.analyzeClass(request.getTargetFile().getVirtualFile().getPath());
        } catch (Exception e) {
            LOG.warn("Failed to pre-analyze class", e);
            return null;
        }
    }

    /**
     * Compress whitespace to save tokens (4 spaces -> 1 space).
     */
    private String compressWhitespace(String text) {
        return text.replaceAll("    ", " ")
                   .replaceAll("\\n\\s*\\n", "\n")
                   .trim();
    }

    /**
     * Build TestContext from gathered data.
     */
    private TestContext buildTestContext(TestGenerationRequest request,
                                         TestPlan testPlan,
                                         Map<String, Object> gatheredData) {
        try {
            String targetPath = request.getTargetFile().getVirtualFile().getPath();
            String targetFileName = request.getTargetFile().getName();
            
            // Use the static factory method from TestContext
            return TestContext.fromGatheredData(gatheredData, targetPath, targetFileName);
            
        } catch (Exception e) {
            LOG.error("Failed to build test context", e);
            // Return a minimal context on error
            return new TestContext(
                    new ArrayList<>(),
                    List.of(request.getTargetFile().getName()),
                    Map.of(),
                    new ArrayList<>(),
                    "JUnit 5",
                    Map.of("error", e.getMessage()),
                    null,
                    null,
                    new HashSet<>()
            );
        }
    }

    /**
     * Tools container with proper @Tool annotations for LangChain4j.
     * Each tool is isolated with clear responsibilities.
     */
    public static class ContextGatheringTools {
        private final Project project;
        private final CodeExplorationToolRegistry toolRegistry;
        private String sessionId;
        private Consumer<String> toolNotifier;
        private Consumer<Map<String, Object>> contextUpdateCallback;
        private ContextAgent contextAgent; // Reference to parent agent
        
        // Shared data storage
        private final Map<String, String> analyzedClasses = new HashMap<>();
        private final List<String> contextNotes = new ArrayList<>();
        private final Map<String, String> readFiles = new HashMap<>();
        
        // Individual tool instances
        private final AnalyzeClassTool analyzeClassTool;
        private final ListFilesTool listFilesTool;
        private final FindFilesTool findFilesTool;
        private final TakeNoteTool takeNoteTool;
        private final ReadFileTool readFileTool;
        private final RipgrepCodeTool ripgrepCodeTool;
        
        // For GrepCodeTool shared data
        private final Set<String> relatedFiles = new HashSet<>();
        private final List<String> usagePatterns = new ArrayList<>();

        public ContextGatheringTools(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
            this(project, toolRegistry, null, null);
        }

        public ContextGatheringTools(@NotNull Project project, 
                                   @NotNull CodeExplorationToolRegistry toolRegistry,
                                   @Nullable Consumer<String> toolNotifier) {
            this(project, toolRegistry, toolNotifier, null);
        }
        
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
            this.listFilesTool = new ListFilesTool(project, toolRegistry);
            this.findFilesTool = new FindFilesTool(project, toolRegistry);
            this.takeNoteTool = new TakeNoteTool(contextNotes);
            this.readFileTool = new ReadFileTool(project);
            this.ripgrepCodeTool = new RipgrepCodeTool(project, relatedFiles, usagePatterns);
        }

        public void reset() {
            analyzedClasses.clear();
            contextNotes.clear();
            readFiles.clear();
            relatedFiles.clear();
            usagePatterns.clear();
            takeNoteTool.clearNotes();
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
                
                // Also use the logToolResult method for enhanced logging
                String summary = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                contextAgent.logToolResult("analyzeClass", summary, "Class: " + filePathOrClassName);
            }
            
            notifyContextUpdate();
            return result;
        }

        @Tool("List all files and subdirectories in a specified directory")
        public String listFiles(String directoryPath) {
            notifyTool("listFiles", directoryPath);
            return listFilesTool.listFiles(directoryPath);
        }

        @Tool("Find files matching a pattern in the project")
        public String findFiles(String pattern) {
            notifyTool("findFiles", pattern);
            return findFilesTool.findFiles(pattern);
        }

        @Tool("""
            Search for code patterns, method calls, or text across the project using grep-like functionality with regex support.
            
            This tool provides powerful text-based search capabilities similar to grep/ripgrep with intelligent file filtering.
            Supports both literal text matching and regex patterns for complex searches.
            
            Common usage patterns:
            - Method calls: "getUserById(", ".save(", "validateInput"
            - Class references: "UserService", "implements Serializable", "extends BaseClass"
            - Annotations: "@Test", "@Autowired", "@Component", "@Override"
            - String literals: "config.properties", "SELECT * FROM", "error.message"
            - Code patterns: "throw new", "try {", "catch (", "finally"
            - Comments: "TODO", "FIXME", "deprecated", "bug"
            - Import statements: "import java.util", "import com.example"
            - Variable declarations: "private final", "public static", "List<String>"
            - Exception handling: "catch (Exception", "throws IOException"
            - Database queries: "SELECT", "INSERT INTO", "UPDATE SET"
            - Configuration keys: "server.port", "database.url", "api.key"
            
            Examples:
            - searchCode("@Test") → Find all test methods
            - searchCode("UserService") → Find all references to UserService class
            - searchCode("throw new IllegalArgumentException") → Find exception throwing patterns
            - searchCode("config\\.properties") → Find config file references (regex)
            - searchCode("TODO.*important") → Find important TODO comments (regex)
            - searchCode("private.*String.*name") → Find private String fields named 'name' (regex)
            
            Tips:
            - Use regex patterns with backslash escaping: "\\\\." for literal dots
            - Search is case-insensitive by default
            - Results show file path, line number, and surrounding context
            - Limited to 20 results for performance
            """)
        public String searchCode(String query) {
            notifyTool("searchCode", query);
            return ripgrepCodeTool.searchCode(query, null, null);
        }
        
        @Tool("""
            Advanced search with file pattern filtering and regex support for targeted searches.
            
            Use this when you need to:
            - Limit search to specific file types: "*.java", "*.kt", "*.xml"
            - Exclude certain files: "!*test*", "!*generated*", "!*.class"
            - Search in specific directories: "src/main/**", "config/**"
            - Combine patterns: "*.{java,kt}" for Java and Kotlin files
            
            Parameters:
            - query: Search pattern (literal text or regex)
            - filePattern: Include files matching this pattern (glob syntax)
            - excludePattern: Exclude files matching this pattern (glob syntax)
            
            Examples:
            - searchCodeAdvanced("@Entity", "*.java", null) → Find JPA entities only in Java files
            - searchCodeAdvanced("config", "*.properties", null) → Search only in properties files  
            - searchCodeAdvanced("SELECT", "*.java", "*test*") → Find SQL in Java files, excluding tests
            - searchCodeAdvanced("api\\\\.key", "*.yml", null) → Find API key configs in YAML files
            
            File pattern syntax:
            - * matches any characters within a directory name
            - ** matches any number of directories
            - ? matches a single character
            - {a,b} matches either 'a' or 'b'
            - [abc] matches any character in the set
            """)
        public String searchCodeAdvanced(String query, String filePattern, String excludePattern) {
            notifyTool("searchCodeAdvanced", String.format("query=%s, filePattern=%s, excludePattern=%s", query, filePattern, excludePattern));
            return ripgrepCodeTool.searchCode(query, filePattern, excludePattern);
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
            
            // Send file read results to context tab through StreamingBaseAgent methods
            if (contextAgent != null && result != null && !result.isEmpty()) {
                ContextDisplayData contextData = createContextDisplayData(filePath, result);
                contextAgent.sendContextFileAnalyzed(contextData);
                
                // Also use the logToolResult method for enhanced logging
                String summary = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                contextAgent.logToolResult("readFile", summary, "File: " + filePath);
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
                if ((line.contains("public") || line.contains("private") || line.contains("protected")) 
                    && line.contains("(") && line.contains(")") 
                    && !line.contains("class ") && !line.contains("interface ")) {
                    // Try to extract method name
                    int parenIndex = line.indexOf('(');
                    if (parenIndex > 0) {
                        String beforeParen = line.substring(0, parenIndex).trim();
                        String[] parts = beforeParen.split(" ");
                        if (parts.length > 0) {
                            String methodName = parts[parts.length - 1];
                            if (!methodName.isEmpty() && !methodName.equals("if") && !methodName.equals("for") && !methodName.equals("while")) {
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