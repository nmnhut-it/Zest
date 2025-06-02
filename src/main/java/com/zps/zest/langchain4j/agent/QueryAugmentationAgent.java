package com.zps.zest.langchain4j.agent;

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
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Query Augmentation Agent that intelligently finds relevant code context
 * based on patterns like -handler, -controller, -cmd, etc.
 * 
 * This agent is designed to be called from the JavaScript interceptor to enhance
 * user queries with relevant project context before sending to the LLM.
 */
@Service(Service.Level.PROJECT)
public final class QueryAugmentationAgent {
    
    private static final Logger LOG = Logger.getInstance(QueryAugmentationAgent.class);
    
    private final Project project;
    private final HybridIndexManager indexManager;
    private final CodeSearchUtility searchUtility;
    
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
            
            // 1. Get current IDE context
            String ideContext = getCurrentContext();
            if (!ideContext.isEmpty()) {
                augmented.append("### Current IDE Context ###\n")
                        .append(ideContext)
                        .append("\n\n");
            }
            
            // 2. Analyze query intent and patterns
            QueryAnalysis analysis = analyzeQuery(userQuery);
            
            // 3. Find relevant code based on analysis
            String codeContext = findRelevantCode(userQuery, analysis);
            if (!codeContext.isEmpty()) {
                augmented.append(codeContext);
            }
            
            // 4. Add pattern-specific guidance
            String guidance = generateGuidance(analysis);
            if (!guidance.isEmpty()) {
                augmented.append("\n### Guidance ###\n")
                        .append(guidance)
                        .append("\n");
            }
            
            return augmented.toString();
            
        } catch (Exception e) {
            LOG.error("Error augmenting query", e);
            return ""; // Return empty string on error to not break the flow
        }
    }
    
    @Tool("Search for relevant code context based on query intent")
    public String findRelevantCode(String query, QueryAnalysis analysis) {
        try {
            // Perform hybrid search
            CompletableFuture<List<CodeSearchUtility.EnrichedSearchResult>> searchFuture = 
                searchUtility.searchRelatedCode(query, 20);
            
            List<CodeSearchUtility.EnrichedSearchResult> results = 
                searchFuture.get(5, TimeUnit.SECONDS);
            
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
        }
        if (analysis.detectedPatterns.contains("service")) {
            guidance.append("- Services contain business logic. Look for @Service annotations\n");
        }
        if (analysis.detectedPatterns.contains("repository")) {
            guidance.append("- Repositories handle data persistence. Check for @Repository annotations\n");
        }
        
        // Action-specific guidance
        if (analysis.detectedActions.contains("implement")) {
            guidance.append("- Consider existing patterns in the codebase\n");
            guidance.append("- Follow the project's naming conventions\n");
        }
        if (analysis.detectedActions.contains("fix")) {
            guidance.append("- Check error logs and stack traces\n");
            guidance.append("- Verify related tests are passing\n");
        }
        
        return guidance.toString();
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
