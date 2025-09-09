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

CRITICAL RULE: ONLY search for files SPECIFICALLY REFERENCED in the analyzed code that are NOT already in the static context analysis.

TARGETED APPROACH:
1. **Scan the provided static analysis** for string literals, file paths, and resource references
2. **ONLY search for files explicitly mentioned** in the code - no random exploration
3. **Skip files already captured** in the static dependency graph
4. **Focus on external resources** that the static analyzer cannot detect

SEARCH ONLY FOR REFERENCED FILES:
- **String literals** found in code: "config/database.yml", "templates/email.html", "scripts/setup.lua"
- **Dynamic file loading** patterns: Files.readString(), getResourceAsStream(), Properties.load()
- **Configuration references** that are loaded at runtime
- **Scripts/templates** called dynamically by the code

SEARCH STRATEGY:
1. **searchCode for file references**: Look for specific file paths mentioned in string literals
   * searchCode('"config/') → find config file references
   * searchCode('\\.lua"') → find script references
   * searchCode('getResourceAsStream') → find resource loading patterns

2. **findFiles for exact references**: ONLY search for files found in Step 1
   * If code mentions "config/leaderboard.lua" → findFiles("config/leaderboard.lua")
   * If code loads ".properties" → findFiles("**/*.properties") 
   * NEVER search for files not mentioned in the code

3. **listFiles sparingly**: ONLY if specific directory referenced in code
   * If code mentions "config/" directory → listFiles("config/")
   * ONLY for directories with confirmed file references

STRICT FILTERING:
✅ Code contains 'Files.readString(Path.of("config/leaderboard.lua"))' → search for this exact file
✅ Code contains 'getResourceAsStream("templates/")' → explore templates directory
❌ Code doesn't mention .yml files → DON'T search for *.yml
❌ No resource loading in code → DON'T explore resources/
❌ No script references → DON'T look for .lua files

Remember: You are NOT doing general project exploration. You are ONLY finding external files that the code explicitly references but static analysis missed.
  
EXPLORATION PRIORITIES:
1. Files referenced by string literals in the analyzed code
2. Configuration directories (config/, resources/, etc.)
3. Test directories related to the class under test
4. Script/template directories if code loads dynamic content

AVOID:
- Broad searches like "listFiles('.')" or "findFiles('*.java')" 
- Exploring irrelevant directories (build/, .git/, node_modules/)
- Recursive listing of large directory trees
- General project exploration without specific purpose

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
     * Get the chat memory for UI integration
     */
    @NotNull
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
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
            return processToolResult("analyzeClass", filePathOrClassName, result);
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
            
            Strategy Guidelines:
            - Level 0 for large directories (src/, build/, target/)
            - Level 1-2 for config/resource directories  
            - Level 3+ only for deeply nested structures
            - Always specify level explicitly - forces strategic thinking
            """)
        public String listFiles(String directoryPath, int recursiveLevel) {
            notifyTool("listFiles", directoryPath + " (level=" + recursiveLevel + ")");
            return listFilesTool.listFiles(directoryPath, recursiveLevel);
        }

        @Tool("Find files matching a pattern in the project")
        public String findFiles(String pattern) {
            notifyTool("findFiles", pattern);
            return findFilesTool.findFiles(pattern);
        }

        @Tool("""
            Search for code patterns, method calls, or text across the project using grep-like functionality with regex support.
            CANNOT be used to search for files by their names.
            
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
            
            // Store in readFiles map (use normalized path)
            String normalizedPath = normalizeFilePath(filePath);
            readFiles.put(normalizedPath, result);
            
            return processToolResult("readFile", filePath, result, toolResult.isSuccess());
        }
        
        /**
         * Normalize any input (FQN, file path, class name) to canonical file path.
         */
        private String normalizeFilePath(String input) {
            if (input == null || input.trim().isEmpty()) return input;
            
            // If it's a tool name, don't process it - this shouldn't happen
            if (input.equals("analyzeClass") || input.equals("readFile") || input.equals("searchCode")) {
                LOG.warn("Tool name passed as file path: " + input);
                return input;
            }
            
            // If it's already a valid file path (contains slashes), normalize separators
            if (input.contains("/") || input.contains("\\")) {
                return input.replace('\\', '/');
            }
            
            // If it's a FQN (com.example.MyClass), try to convert to file path
            if (input.contains(".") && !input.endsWith(".java") && !input.endsWith(".kt")) {
                // Try to find the actual file for this class
                try {
                    String classPath = input.replace('.', '/') + ".java";
                    return classPath;
                } catch (Exception e) {
                    // Fallback to original input
                    return input;
                }
            }
            
            return input;
        }
        
        /**
         * Validate if content is meaningful (not empty, not just error messages).
         * Only fails on actual tool error messages, not legitimate file content that mentions errors.
         */
        private boolean hasValidContent(String content) {
            if (content == null || content.trim().isEmpty()) return false;
            
            // Check for specific tool error patterns (not general content that mentions errors)
            String trimmed = content.trim();
            
            // These are actual ReadFileTool error messages, not file content
            if (trimmed.startsWith("❌") ||
                trimmed.startsWith("No files found with name:") ||
                trimmed.startsWith("Multiple files found with name") ||
                trimmed.startsWith("Error reading file:") ||
                trimmed.startsWith("File not found:") ||
                trimmed.startsWith("Permission denied:") ||
                (trimmed.contains("not found") && trimmed.length() < 100)) { // Short "not found" messages are likely errors
                return false;
            }
            
            // Require at least some meaningful content
            return trimmed.length() > 10;
        }

        private ContextDisplayData createContextDisplayData(String inputPath, String analysisResult) {
            return createContextDisplayData(inputPath, analysisResult, true); // Default to success for backward compatibility
        }
        
        private ContextDisplayData createContextDisplayData(String inputPath, String analysisResult, boolean toolSuccess) {
            // Normalize path to prevent duplicates
            String normalizedPath = normalizeFilePath(inputPath);
            
            // Extract clean file name
            String fileName = normalizedPath.substring(
                Math.max(normalizedPath.lastIndexOf('/'), normalizedPath.lastIndexOf('\\')) + 1
            );
            
            // Set status based on tool success and content validation
            ContextDisplayData.AnalysisStatus status;
            String summary;
            boolean isValid;
            
            if (!toolSuccess) {
                // Tool itself failed, definitely an error
                status = ContextDisplayData.AnalysisStatus.ERROR;
                summary = "Tool execution failed";
                isValid = false;
            } else {
                // Tool succeeded, check if content looks good
                isValid = hasValidContent(analysisResult);
                status = isValid ? ContextDisplayData.AnalysisStatus.COMPLETED : ContextDisplayData.AnalysisStatus.ERROR;
                summary = isValid ? 
                    (analysisResult.length() > 100 ? analysisResult.substring(0, 100) + "..." : analysisResult) :
                    "No valid content";
            }
            
            return new ContextDisplayData(
                normalizedPath,  // Use normalized path as key
                fileName,
                status,
                summary,
                isValid ? analysisResult : null, // Only set fullAnalysis if content is valid
                extractClasses(analysisResult),
                extractMethods(analysisResult),
                Collections.emptyList(),
                System.currentTimeMillis()
            );
        }
        
        /**
         * Common logic for processing tool results and updating UI.
         */
        private String processToolResult(String toolName, String input, String result) {
            return processToolResult(toolName, input, result, true); // Default to success for backward compatibility
        }
        
        /**
         * Common logic for processing tool results and updating UI with success status.
         */
        private String processToolResult(String toolName, String input, String result, boolean isSuccess) {
            // Create context data with normalized path and send to UI
            if (contextAgent != null && result != null && !result.isEmpty()) {
                ContextDisplayData contextData = createContextDisplayData(input, result, isSuccess);
                contextAgent.sendContextFileAnalyzed(contextData);
            }
            
            notifyContextUpdate();
            return result;
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