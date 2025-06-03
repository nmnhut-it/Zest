package com.zps.zest.langchain4j.agent;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.langchain4j.CodeSearchUtility;
import com.zps.zest.langchain4j.HybridIndexManager;
import com.zps.zest.langchain4j.index.NameIndex;
import com.zps.zest.langchain4j.index.SemanticIndex;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Query Augmentation Agent that intelligently finds relevant code context
 * based on patterns like -handler, -controller, -cmd, etc.
 * <p>
 * This agent is designed to be called from the JavaScript interceptor to enhance
 * user queries with relevant project context before sending to the LLM.
 */
@Service(Service.Level.PROJECT)
public final class QueryAugmentationAgent {

    private static final Logger LOG = Logger.getInstance(QueryAugmentationAgent.class);

    private final Project project;
    private final HybridIndexManager indexManager;
    private final CodeSearchUtility searchUtility;
    private final LLMService llmService;

    // Common code patterns to look for
    private static final Map<String, List<String>> PATTERN_KEYWORDS = Map.of(
            "controller", Arrays.asList("Controller", "RestController", "endpoint", "mapping", "api", "http", "rest"),
            "service", Arrays.asList("Service", "ServiceImpl", "business", "logic", "process"),
            "handler", Arrays.asList("Handler", "EventHandler", "handle", "process", "listener", "event"),
            "command", Arrays.asList("Command", "Cmd", "execute", "action", "operation"),
            "repository", Arrays.asList("Repository", "Dao", "persistence", "database", "storage", "data"),
            "dto", Arrays.asList("DTO", "Request", "Response", "payload", "model", "transfer"),
            "config", Arrays.asList("Config", "Configuration", "properties", "settings", "setup"),
            "util", Arrays.asList("Util", "Utils", "Helper", "utility", "common", "shared"),
            "test", Arrays.asList("Test", "Spec", "test", "verify", "assert", "junit")
    );

    // Pattern detection regex for class names
    private static final Map<String, String> PATTERN_SUFFIXES = Map.of(
            "controller", ".*[Cc]ontroller$",
            "service", ".*[Ss]ervice(Impl)?$",
            "handler", ".*[Hh]andler$",
            "command", ".*[Cc](ommand|md)$",
            "repository", ".*(Repository|Dao)$",
            "dto", ".*(DTO|Dto|Request|Response)$",
            "config", ".*[Cc]onfig(uration)?$",
            "util", ".*(Util|Utils|Helper)$",
            "test", ".*Test$"
    );

    // Action keywords that suggest what the user wants to do
    private static final Map<String, Set<String>> ACTION_PATTERNS = Map.of(
            "implement", Set.of("implement", "create", "add", "build", "make", "develop"),
            "fix", Set.of("fix", "repair", "debug", "solve", "resolve", "correct"),
            "refactor", Set.of("refactor", "improve", "enhance", "optimize", "clean"),
            "test", Set.of("test", "verify", "check", "validate", "assert"),
            "understand", Set.of("explain", "show", "what", "how", "understand", "describe")
    );

    public QueryAugmentationAgent(@NotNull Project project) {
        this.project = project;
        this.indexManager = project.getService(HybridIndexManager.class);
        this.searchUtility = project.getService(CodeSearchUtility.class);
        this.llmService = project.getService(LLMService.class);
        LOG.info("Initialized QueryAugmentationAgent for project: " + project.getName());
    }

    /**
     * Main augmentation method that analyzes the query and returns relevant code context.
     * This is designed to be called from JavaScriptBridgeActions.
     *
     * @param userQuery The user's original query
     * @return Augmented context in a format suitable for the LLM
     */
    public String augmentQuery(String userQuery) {
        try {
            LOG.info("Augmenting query: " + userQuery);

            StringBuilder augmented = new StringBuilder();

            // 1. Analyze query and determine if we need clarification
            QueryAnalysis analysis = analyzeQuery(userQuery);

            // 2. Get current IDE context (synchronous, wrapped in ReadAction)
            String ideContext = ReadAction.compute(this::getCurrentContext);
            if (!ideContext.isEmpty()) {
                augmented.append("### Current IDE Context ###\n")
                        .append(ideContext)
                        .append("\n\n");
            }

            // 3. Find relevant code based on analysis (this includes embedding search)
            // IMPORTANT: We wait for this to complete before any LLM calls
            String codeContext = findRelevantCode(userQuery, analysis);
            if (!codeContext.isEmpty()) {
                augmented.append(codeContext);
            }

            // 4. Now that all embedding/search operations are complete, we can safely call LLM
            // This ensures no thread context switching issues with embeddings
            
            // If query is ambiguous, use LLM to generate better questions
            if (isAmbiguous(analysis)) {
                String clarifiedQuestions = generateSmartQuestions(userQuery, analysis);
                if (!clarifiedQuestions.isEmpty()) {
                    // Prepend to the beginning of augmented context
                    augmented.insert(0, "### Agent Analysis ###\n" + clarifiedQuestions + "\n\n");
                }
            }

            // 5. If enabled, perform autonomous exploration (LLM call)
            if (shouldPerformAutonomousExploration(analysis)) {
                String exploration = performQuickExploration(userQuery, codeContext);
                if (!exploration.isEmpty()) {
                    augmented.append("\n### Agent Exploration ###\n");
                    augmented.append(exploration);
                    augmented.append("\n");
                }
            }

            // 6. Add exploration suggestions (no LLM, just rules)
            List<String> explorationSuggestions = generateExplorationSuggestions(analysis);
            if (!explorationSuggestions.isEmpty()) {
                augmented.append("\n### Exploration Suggestions ###\n");
                augmented.append("Based on the code structure, you might also want to explore:\n");
                for (String suggestion : explorationSuggestions) {
                    augmented.append("- ").append(suggestion).append("\n");
                }
            }

            // 7. Add pattern-specific guidance (no LLM, just rules)
            String guidance = generateGuidance(analysis);
            if (!guidance.isEmpty()) {
                augmented.append("\n### Pattern-Specific Guidance ###\n")
                        .append(guidance)
                        .append("\n");
            }

            return augmented.toString();

        } catch (Exception e) {
            LOG.error("Error augmenting query", e);
            return ""; // Return empty string on error to not break the flow
        }
    }

    /**
     * Uses LLM to generate smarter clarifying questions.
     */
    private String generateSmartQuestions(String userQuery, QueryAnalysis analysis) {
        try {
            if (!llmService.isConfigured()) {
                // Fallback to rule-based questions if no LLM configured
                return formatReflectiveQuestions(generateReflectiveQuestions(userQuery, analysis));
            }

            // Build prompt for LLM
            String prompt = String.format("""
                            You are a code exploration assistant analyzing a user's query about their codebase.
                            
                            User Query: "%s"
                            
                            Detected Patterns: %s
                            Detected Actions: %s
                            Ambiguity Reasons: %s
                            
                            Generate 2-3 clarifying questions that would help understand exactly what the user needs.
                            Focus on:
                            1. Specific components or files they're interested in
                            2. The scope of their request (single file, module, entire project)
                            3. Their end goal (understanding, modification, debugging)
                            
                            Format each question on a new line starting with "- "
                            Keep questions concise and directly relevant to their query.
                            """,
                    userQuery,
                    analysis.detectedPatterns,
                    analysis.detectedActions,
                    getAmbiguityReasons(userQuery, analysis)
            );

            // Call LLM using the new service
            String response = llmService.query(prompt);
            
            if (response != null && !response.isEmpty()) {
                return "The agent needs clarification:\n" + response;
            }

        } catch (Exception e) {
            LOG.warn("Failed to generate smart questions, falling back to rules", e);
        }

        // Fallback to rule-based
        return formatReflectiveQuestions(generateReflectiveQuestions(userQuery, analysis));
    }

    /**
     * Performs a quick autonomous exploration using LLM.
     */
    private String performQuickExploration(String userQuery, String codeContext) {
        try {
            if (!llmService.isConfigured()) {
                return ""; // No LLM available
            }

            // Build exploration prompt
            String prompt = String.format("""
                            You are exploring a codebase to help answer a user's question.
                            
                            User Query: "%s"
                            
                            Code Context Found:
                            %s
                            
                            Based on this context, provide:
                            1. A brief answer to what the user is looking for
                            2. One follow-up question you would ask to explore deeper
                            3. One insight about the code structure or patterns
                            
                            Keep your response concise (max 5 lines).
                            """,
                    userQuery,
                    codeContext.length() > 1000 ? codeContext.substring(0, 1000) + "..." : codeContext
            );

            // Call LLM using the new service
            String response = llmService.query(prompt);
            
            if (response != null && !response.isEmpty()) {
                return response;
            }

        } catch (Exception e) {
            LOG.warn("Failed to perform autonomous exploration", e);
        }

        return "";
    }

    /**
     * Determines if the query is ambiguous enough to need clarification.
     */
    private boolean isAmbiguous(QueryAnalysis analysis) {
        int ambiguityScore = 0;

        // Multiple patterns without clear focus
        if (analysis.detectedPatterns.size() > 2) {
            ambiguityScore += 3;
        }

        // No specific identifiers
        if (analysis.potentialIdentifiers.isEmpty()) {
            ambiguityScore += 2;
        }

        // Multiple or no actions
        if (analysis.detectedActions.isEmpty() || analysis.detectedActions.size() > 2) {
            ambiguityScore += 2;
        }

        return ambiguityScore >= 4;
    }

    /**
     * Determines if autonomous exploration should be performed.
     */
    private boolean shouldPerformAutonomousExploration(QueryAnalysis analysis) {
        // Only explore for understanding/investigation queries
        return analysis.detectedActions.contains("understand") ||
                analysis.detectedActions.isEmpty() ||
                analysis.detectedPatterns.size() > 1;
    }

    /**
     * Gets ambiguity reasons for the LLM prompt.
     */
    private String getAmbiguityReasons(String query, QueryAnalysis analysis) {
        List<String> reasons = new ArrayList<>();

        if (analysis.detectedPatterns.size() > 2) {
            reasons.add("Multiple component types mentioned");
        }
        if (analysis.potentialIdentifiers.isEmpty()) {
            reasons.add("No specific class/method names");
        }
        if (analysis.detectedActions.isEmpty()) {
            reasons.add("Unclear what action is needed");
        }

        String[] vagueTerms = {"something", "stuff", "thing", "it"};
        for (String term : vagueTerms) {
            if (query.toLowerCase().contains(term)) {
                reasons.add("Contains vague term: '" + term + "'");
                break;
            }
        }

        return String.join(", ", reasons);
    }

    /**
     * Formats reflective questions for display.
     */
    private String formatReflectiveQuestions(List<String> questions) {
        if (questions.isEmpty()) return "";

        StringBuilder formatted = new StringBuilder("To better understand your request:\n");
        for (String question : questions) {
            formatted.append("- ").append(question).append("\n");
        }
        return formatted.toString();
    }

    /**
     * Generates reflective questions to help clarify ambiguous queries.
     */
    private List<String> generateReflectiveQuestions(String query, QueryAnalysis analysis) {
        List<String> questions = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        // Check for ambiguous patterns
        if (analysis.detectedPatterns.size() > 2) {
            questions.add("Are you looking for a specific type of component (" +
                    String.join(", ", analysis.detectedPatterns) + "), or all of them?");
        }

        // Check for missing context
        if (analysis.detectedActions.contains("implement") && analysis.potentialIdentifiers.isEmpty()) {
            questions.add("What specific feature or component would you like to implement?");
            questions.add("Do you have a particular naming convention or pattern to follow?");
        }

        if (analysis.detectedActions.contains("fix") && !lowerQuery.contains("error") &&
                !lowerQuery.contains("bug") && !lowerQuery.contains("issue")) {
            questions.add("What specific issue or error are you trying to fix?");
            questions.add("Do you have error messages or logs that could help identify the problem?");
        }

        // Check for scope ambiguity
        if (lowerQuery.contains("all") || lowerQuery.contains("every")) {
            questions.add("Should I include test files and configuration files in the search?");
            questions.add("Are you interested in a specific package or module?");
        }

        // Check for relationship queries without clear direction
        if (lowerQuery.contains("related") || lowerQuery.contains("connection") ||
                lowerQuery.contains("relationship")) {
            questions.add("What type of relationship are you interested in (calls, extends, implements)?");
            questions.add("Do you want to see incoming or outgoing relationships?");
        }

        // Pattern-specific questions
        if (analysis.detectedPatterns.contains("controller") && !lowerQuery.contains("rest") &&
                !lowerQuery.contains("api") && !lowerQuery.contains("endpoint")) {
            questions.add("Are you looking for REST controllers specifically, or all types of controllers?");
        }

        if (analysis.detectedPatterns.contains("service") &&
                analysis.detectedPatterns.contains("repository")) {
            questions.add("Are you interested in how services interact with repositories?");
        }

        return questions.stream().limit(3).collect(Collectors.toList()); // Limit to 3 questions
    }

    /**
     * Generates exploration suggestions based on the found code.
     */
    private List<String> generateExplorationSuggestions(QueryAnalysis analysis) {
        List<String> suggestions = new ArrayList<>();

        // Based on detected patterns
        if (analysis.detectedPatterns.contains("controller")) {
            suggestions.add("Check the corresponding service layer implementations");
            suggestions.add("Review the request/response DTOs used by these controllers");
            suggestions.add("Examine the API documentation or Swagger annotations");
        }

        if (analysis.detectedPatterns.contains("service")) {
            suggestions.add("Look at the repository/DAO layer for data access patterns");
            suggestions.add("Check if there are any service interfaces vs implementations");
            suggestions.add("Review transaction boundaries and error handling");
        }

        if (analysis.detectedPatterns.contains("repository")) {
            suggestions.add("Examine the entity models and database mappings");
            suggestions.add("Check for custom query methods or specifications");
            suggestions.add("Review the database configuration and connection settings");
        }

        // Based on actions
        if (analysis.detectedActions.contains("test")) {
            suggestions.add("Look for existing test utilities or base test classes");
            suggestions.add("Check the test configuration and mock setups");
            suggestions.add("Review the project's testing conventions and patterns");
        }

        if (analysis.detectedActions.contains("refactor")) {
            suggestions.add("Identify code duplication across similar components");
            suggestions.add("Check for design patterns that could simplify the structure");
            suggestions.add("Look for interfaces that could improve testability");
        }

        return suggestions.stream().limit(4).collect(Collectors.toList());
    }

    @Tool("Search for relevant code context based on query intent")
    public String findRelevantCode(String query, QueryAnalysis analysis) {
        try {
            // Perform hybrid search
            CompletableFuture<List<CodeSearchUtility.EnrichedSearchResult>> searchFuture =
                    searchUtility.searchRelatedCode(query, 20);

            List<CodeSearchUtility.EnrichedSearchResult> results =
                    searchFuture.get(50, TimeUnit.SECONDS);

            // If patterns detected, boost pattern-matching results
            if (!analysis.detectedPatterns.isEmpty()) {
                results = boostPatternResults(results, analysis.detectedPatterns);
            }

            // Group and format results
            return formatCodeContext(results, analysis);

        } catch (Exception e) {
            LOG.error("Error finding relevant code", e);
            return "";
        }
    }

    @Tool("Get current IDE context including open files and current location")
    public String getCurrentContext() {
        try {
            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            Editor editor = editorManager.getSelectedTextEditor();

            StringBuilder context = new StringBuilder();

            // Current file and position
            if (editor != null) {
                VirtualFile virtualFile = editorManager.getSelectedFiles()[0];
                context.append("Current file: ").append(virtualFile.getPath()).append("\n");

                PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                if (psiFile != null) {
                    // Get current element at cursor
                    int offset = editor.getCaretModel().getOffset();
                    PsiElement element = psiFile.findElementAt(offset);

                    PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                    if (method != null) {
                        context.append("Current method: ").append(method.getName()).append("\n");
                    }

                    PsiClass clazz = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                    if (clazz != null) {
                        context.append("Current class: ").append(clazz.getQualifiedName()).append("\n");
                    }
                }
            }

            // Open files
            VirtualFile[] openFiles = editorManager.getOpenFiles();
            if (openFiles.length > 0) {
                context.append("Open files: ");
                context.append(Arrays.stream(openFiles)
                        .map(VirtualFile::getName)
                        .limit(5)
                        .collect(Collectors.joining(", ")));
                if (openFiles.length > 5) {
                    context.append(" and ").append(openFiles.length - 5).append(" more");
                }
                context.append("\n");
            }

            return context.toString();
        } catch (Exception e) {
            LOG.error("Error getting current context", e);
            return "";
        }
    }

    /**
     * Analyzes the query to detect patterns and intent.
     */
    private QueryAnalysis analyzeQuery(String query) {
        QueryAnalysis analysis = new QueryAnalysis();
        String lowerQuery = query.toLowerCase();

        // Detect code patterns
        for (Map.Entry<String, List<String>> entry : PATTERN_KEYWORDS.entrySet()) {
            String pattern = entry.getKey();
            List<String> keywords = entry.getValue();

            // Check if query mentions the pattern or its keywords
            if (lowerQuery.contains(pattern) ||
                    keywords.stream().anyMatch(k -> lowerQuery.contains(k.toLowerCase()))) {
                analysis.detectedPatterns.add(pattern);
            }
        }

        // Detect action intent
        for (Map.Entry<String, Set<String>> entry : ACTION_PATTERNS.entrySet()) {
            String action = entry.getKey();
            Set<String> keywords = entry.getValue();

            if (keywords.stream().anyMatch(k -> lowerQuery.contains(k))) {
                analysis.detectedActions.add(action);
            }
        }

        // Extract potential class/method names (camelCase or PascalCase)
        String[] words = query.split("\\s+");
        for (String word : words) {
            if (word.matches("[A-Z][a-zA-Z0-9]*") || word.matches("[a-z]+[A-Z][a-zA-Z0-9]*")) {
                analysis.potentialIdentifiers.add(word);
            }
        }

        return analysis;
    }

    /**
     * Boost results that match detected patterns.
     */
    private List<CodeSearchUtility.EnrichedSearchResult> boostPatternResults(
            List<CodeSearchUtility.EnrichedSearchResult> results,
            Set<String> patterns) {

        // Create a map to track boosted scores
        Map<String, Double> boostedScores = new HashMap<>();

        for (CodeSearchUtility.EnrichedSearchResult result : results) {
            double score = result.getScore();
            String id = result.getId();

            // Check if result matches any pattern
            for (String pattern : patterns) {
                String regex = PATTERN_SUFFIXES.get(pattern);
                if (regex != null && id.matches(".*" + regex)) {
                    score *= 1.5; // Boost by 50%
                }
            }

            boostedScores.put(id, score);
        }

        // Sort by boosted scores
        return results.stream()
                .sorted((a, b) -> Double.compare(
                        boostedScores.getOrDefault(b.getId(), b.getScore()),
                        boostedScores.getOrDefault(a.getId(), a.getScore())
                ))
                .collect(Collectors.toList());
    }

    /**
     * Formats code results as context for the LLM.
     */
    private String formatCodeContext(List<CodeSearchUtility.EnrichedSearchResult> results,
                                     QueryAnalysis analysis) {
        if (results.isEmpty()) return "";

        StringBuilder context = new StringBuilder("### Relevant Code Found ###\n\n");

        // Group by category
        Map<String, List<CodeSearchUtility.EnrichedSearchResult>> grouped =
                groupResultsByCategory(results);

        // Format each category
        for (Map.Entry<String, List<CodeSearchUtility.EnrichedSearchResult>> entry : grouped.entrySet()) {
            String category = entry.getKey();
            List<CodeSearchUtility.EnrichedSearchResult> categoryResults = entry.getValue();

            context.append("#### ").append(category).append(" ####\n");

            for (CodeSearchUtility.EnrichedSearchResult result : categoryResults.stream().limit(3).toList()) {
                context.append("- **").append(result.getId()).append("**");

                // Add file path if available
                if (result.getFilePath() != null && !result.getFilePath().isEmpty()) {
                    context.append(" (").append(result.getFilePath()).append(")");
                }
                context.append("\n");

                // Add brief description
                String content = result.getContent();
                if (content.length() > 150) {
                    content = content.substring(0, 150) + "...";
                }
                context.append("  ").append(content.replace("\n", " ")).append("\n");

                // Add relationships if action suggests understanding
                if (analysis.detectedActions.contains("understand")) {
                    addRelationships(context, result);
                }

                context.append("\n");
            }
        }

        return context.toString();
    }

    /**
     * Groups results by their category/type.
     */
    private Map<String, List<CodeSearchUtility.EnrichedSearchResult>> groupResultsByCategory(
            List<CodeSearchUtility.EnrichedSearchResult> results) {

        Map<String, List<CodeSearchUtility.EnrichedSearchResult>> grouped = new LinkedHashMap<>();

        for (CodeSearchUtility.EnrichedSearchResult result : results) {
            String category = categorizeResult(result);
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(result);
        }

        return grouped;
    }

    /**
     * Categorizes a result based on its ID and content.
     */
    private String categorizeResult(CodeSearchUtility.EnrichedSearchResult result) {
        String id = result.getId().toLowerCase();

        for (Map.Entry<String, String> entry : PATTERN_SUFFIXES.entrySet()) {
            if (id.matches(".*" + entry.getValue().toLowerCase())) {
                return capitalize(entry.getKey()) + "s";
            }
        }

        // Check metadata if available
        Object type = result.getContent();
        if (type != null && type.toString().contains("interface")) {
            return "Interfaces";
        }

        return "Other Components";
    }

    /**
     * Adds relationship information to the context.
     */
    private void addRelationships(StringBuilder context, CodeSearchUtility.EnrichedSearchResult result) {
        Map<StructuralIndex.RelationType, List<String>> relationships =
                result.getStructuralRelationships();

        if (relationships != null && !relationships.isEmpty()) {
            context.append("  Relationships:\n");

            for (Map.Entry<StructuralIndex.RelationType, List<String>> entry : relationships.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    context.append("    - ").append(entry.getKey()).append(": ")
                            .append(String.join(", ", entry.getValue().stream().limit(3).toList()))
                            .append("\n");
                }
            }
        }
    }

    /**
     * Generates guidance based on the query analysis.
     */
    private String generateGuidance(QueryAnalysis analysis) {
        StringBuilder guidance = new StringBuilder();

        // Pattern-specific guidance
        if (analysis.detectedPatterns.contains("controller")) {
            guidance.append("- Controllers handle HTTP requests. Check @RequestMapping annotations\n");
            guidance.append("- Look for @PathVariable, @RequestParam, and @RequestBody usage\n");
        }
        if (analysis.detectedPatterns.contains("service")) {
            guidance.append("- Services contain business logic. Look for @Service annotations\n");
            guidance.append("- Check for @Transactional boundaries and rollback conditions\n");
        }
        if (analysis.detectedPatterns.contains("repository")) {
            guidance.append("- Repositories handle data persistence. Check for @Repository annotations\n");
            guidance.append("- Look for custom @Query methods and JPA specifications\n");
        }

        // Action-specific guidance
        if (analysis.detectedActions.contains("implement")) {
            guidance.append("- Consider existing patterns in the codebase\n");
            guidance.append("- Follow the project's naming conventions\n");
            guidance.append("- Check for interfaces that need to be implemented\n");
        }
        if (analysis.detectedActions.contains("fix")) {
            guidance.append("- Check error logs and stack traces\n");
            guidance.append("- Verify related tests are passing\n");
            guidance.append("- Look for recent changes that might have introduced the issue\n");
        }
        if (analysis.detectedActions.contains("refactor")) {
            guidance.append("- Identify code smells and duplication\n");
            guidance.append("- Consider SOLID principles\n");
            guidance.append("- Ensure backward compatibility\n");
        }

        return guidance.toString();
    }

    /**
     * Determines if the query needs more clarification based on ambiguity score.
     */
    @Tool("Analyze query ambiguity and suggest clarifications")
    public String analyzeQueryAmbiguity(String query) {
        QueryAnalysis analysis = analyzeQuery(query);

        int ambiguityScore = 0;
        List<String> ambiguityReasons = new ArrayList<>();

        // Check for vague terms
        String[] vagueTerms = {"something", "stuff", "thing", "it", "that", "this", "some"};
        for (String term : vagueTerms) {
            if (query.toLowerCase().contains(term)) {
                ambiguityScore += 2;
                ambiguityReasons.add("Contains vague term: '" + term + "'");
            }
        }

        // Multiple patterns without clear focus
        if (analysis.detectedPatterns.size() > 3) {
            ambiguityScore += 3;
            ambiguityReasons.add("References multiple component types without clear focus");
        }

        // No specific identifiers
        if (analysis.potentialIdentifiers.isEmpty() && query.split("\\s+").length < 5) {
            ambiguityScore += 2;
            ambiguityReasons.add("No specific class or method names mentioned");
        }

        // Conflicting actions
        if (analysis.detectedActions.size() > 2) {
            ambiguityScore += 2;
            ambiguityReasons.add("Multiple conflicting actions detected");
        }

        StringBuilder result = new StringBuilder();
        result.append("Ambiguity Score: ").append(ambiguityScore).append("/10\n");

        if (ambiguityScore > 5) {
            result.append("\nThis query is quite ambiguous. Consider clarifying:\n");
            for (String reason : ambiguityReasons) {
                result.append("- ").append(reason).append("\n");
            }
        } else if (ambiguityScore > 2) {
            result.append("\nThis query could be clearer. Minor clarifications might help.\n");
        } else {
            result.append("\nThis query is clear and specific.\n");
        }

        return result.toString();
    }

    /**
     * Suggests follow-up queries based on the current context.
     */
    @Tool("Generate follow-up query suggestions")
    public List<String> suggestFollowUpQueries(String originalQuery, List<String> foundComponents) {
        List<String> suggestions = new ArrayList<>();
        QueryAnalysis analysis = analyzeQuery(originalQuery);

        // Based on what was found
        if (!foundComponents.isEmpty()) {
            String firstComponent = foundComponents.get(0);

            if (analysis.detectedPatterns.contains("controller")) {
                suggestions.add("What services does " + firstComponent + " use?");
                suggestions.add("Show me the request mappings in " + firstComponent);
                suggestions.add("Find DTOs used by " + firstComponent);
            }

            if (analysis.detectedPatterns.contains("service")) {
                suggestions.add("What repositories does " + firstComponent + " depend on?");
                suggestions.add("Show me all methods in " + firstComponent);
                suggestions.add("Find where " + firstComponent + " is used");
            }

            if (analysis.detectedPatterns.contains("repository")) {
                suggestions.add("What entities does " + firstComponent + " manage?");
                suggestions.add("Show custom queries in " + firstComponent);
                suggestions.add("Find services using " + firstComponent);
            }
        }

        // General follow-ups based on action
        if (analysis.detectedActions.contains("implement")) {
            suggestions.add("Show me similar implementations in the codebase");
            suggestions.add("What interfaces should I implement?");
            suggestions.add("Find test examples for similar components");
        }

        if (analysis.detectedActions.contains("fix")) {
            suggestions.add("Show me recent changes to these files");
            suggestions.add("Find related error handling code");
            suggestions.add("What tests cover this functionality?");
        }

        return suggestions.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * Capitalizes the first letter of a string.
     */
    private String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Container for query analysis results.
     */
    private static class QueryAnalysis {
        Set<String> detectedPatterns = new HashSet<>();
        Set<String> detectedActions = new HashSet<>();
        Set<String> potentialIdentifiers = new HashSet<>();
    }
}
