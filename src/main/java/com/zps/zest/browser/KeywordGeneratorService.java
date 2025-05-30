package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.ContextGathererForAgent;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Service to generate keywords from user queries using LLM with code context awareness.
 */
public class KeywordGeneratorService {
    private static final Logger LOG = Logger.getInstance(KeywordGeneratorService.class);
    private static final Gson GSON = new Gson();

    private final Project project;
    private final ConfigurationManager configManager;
    private final HttpClient httpClient;

    public KeywordGeneratorService(@NotNull Project project) {
        this.project = project;
        this.configManager = ConfigurationManager.getInstance(project);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Generates keywords from a user query using the LLM with code context.
     * Returns up to 10 keywords that represent functions, patterns, or concepts to search for.
     */
    public CompletableFuture<List<String>> generateKeywords(String userQuery) {
        LOG.info("=== Keyword Generation Started ===");
        LOG.info("Query: " + userQuery);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Gather code context
                Map<String, String> codeContext = gatherCodeContext();

                // Build prompt for keyword generation with context
                String prompt = buildKeywordPromptWithContext(userQuery, codeContext);
                LOG.info("Keyword prompt length: " + prompt.length() + " chars");

                // Call LLM API
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", configManager.getLiteModel());
                requestBody.addProperty("temperature", 0.3); // Lower temperature for more focused results

                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", prompt);
                messages.add(message);
                requestBody.add("messages", messages);

                String apiUrl = configManager.getApiUrl();
                LOG.info("Calling LLM API at: " + apiUrl);
                LOG.info("Using model: " + configManager.getLiteModel());

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + configManager.getAuthToken())
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                        .build();

                long startTime = System.currentTimeMillis();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - startTime;

                LOG.info("LLM API response received in " + duration + "ms, status: " + response.statusCode());

                if (response.statusCode() == 200) {
                    List<String> keywords = parseKeywordsFromResponse(response.body());
                    LOG.info("Successfully generated " + keywords.size() + " keywords: " + keywords);
                    return keywords;
                } else {
                    LOG.error("LLM API error: " + response.statusCode() + " - " + response.body());
                    LOG.info("Falling back to context-aware keyword extraction");
                    return getContextAwareFallbackKeywords(userQuery, codeContext);
                }

            } catch (Exception e) {
                LOG.error("Error generating keywords", e);
                LOG.info("Falling back to simple keyword extraction due to error");
                return getFallbackKeywords(userQuery);
            }
        });
    }

    /**
     * Gathers code context for keyword generation.
     */
    private Map<String, String> gatherCodeContext() {
        return ReadAction.compute(()->{
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            return ContextGathererForAgent.gatherCodeContext(project, editor);
        });

    }

    private String buildKeywordPromptWithContext(String userQuery, Map<String, String> context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are analyzing a software project to generate search keywords.\n");
        prompt.append("Your goal is to find ALL relevant code including frameworks, utilities, and patterns.\n\n");

        prompt.append("THINK LIKE A DEVELOPER exploring this codebase:\n");
        prompt.append("- What frameworks might be in use?\n");
        prompt.append("- What utility/helper classes would exist?\n");
        prompt.append("- What design patterns are likely used?\n");
        prompt.append("- What would the file/class naming conventions be?\n\n");

        prompt.append("Examples of good keyword generation:\n");
        prompt.append("Query: 'user authentication'\n");
        prompt.append("Keywords: auth, user, login, password, token, session, security, authenticate, authorize, ");
        prompt.append("AuthService, UserService, AuthController, SecurityConfig, AuthUtils, PasswordEncoder, ");
        prompt.append("JWT, OAuth, LDAP, filter, interceptor, guard, middleware\n\n");

        prompt.append("Query: 'handle button clicks'\n");
        prompt.append("Keywords: click, button, handle, event, listener, onClick, press, tap, ");
        prompt.append("EventHandler, ButtonComponent, ClickListener, EventManager, UIUtils, ");
        prompt.append("addEventListener, preventDefault, propagation, delegate\n\n");

        // Add context information
        prompt.append("PROJECT CONTEXT:\n");

        if ("true".equals(context.get("hasProject"))) {
            prompt.append("- Project: ").append(context.get("projectName")).append("\n");

            // Add current file information
            if ("true".equals(context.get("hasEditor"))) {
                String currentFile = context.get("currentFileName");
                String extension = context.get("currentFileExtension");
                if (currentFile != null) {
                    prompt.append("- Current file: ").append(currentFile).append("\n");
                    prompt.append("- File type: ").append(extension != null ? extension : "unknown").append("\n");
                    
                    // Infer framework from file patterns
                    inferFrameworkFromFile(currentFile, extension, prompt);
                }

                // Add selected text or cursor context
                if ("true".equals(context.get("hasSelection"))) {
                    String selectedText = context.get("selectedText");
                    if (selectedText != null && selectedText.length() < 500) {
                        prompt.append("- Selected code:\n```\n");
                        prompt.append(selectedText).append("\n```\n");
                        
                        // Analyze code for framework indicators
                        analyzeCodeForFrameworks(selectedText, prompt);
                    }
                } else {
                    // Add a small code snippet around cursor if available
                    String fileContent = context.get("currentFileContent");
                    String cursorOffset = context.get("cursorOffset");
                    if (fileContent != null && cursorOffset != null) {
                        try {
                            int offset = Integer.parseInt(cursorOffset);
                            int start = Math.max(0, offset - 200);
                            int end = Math.min(fileContent.length(), offset + 200);
                            String snippet = fileContent.substring(start, end);
                            prompt.append("- Code near cursor:\n```\n");
                            prompt.append(snippet).append("\n```\n");
                            
                            analyzeCodeForFrameworks(snippet, prompt);
                        } catch (Exception e) {
                            // Ignore parsing errors
                        }
                    }
                }
            }

            // Add source root info
            String sourceRoot = context.get("sourceRoot");
            if (sourceRoot != null) {
                prompt.append("- Source root: ").append(sourceRoot).append("\n");

                // Analyze project structure
                String sourceFiles = context.get("sourceRootFiles");
                if (sourceFiles != null && !sourceFiles.isEmpty()) {
                    analyzeProjectStructure(sourceFiles, prompt);
                }
            }
        }

        prompt.append("\nUSER QUERY: ").append(userQuery).append("\n\n");
        
        prompt.append("GENERATE COMPREHENSIVE KEYWORDS:\n");
        prompt.append("1. Core terms from the query (break into parts)\n");
        prompt.append("2. Common class/method names for this functionality\n");
        prompt.append("3. Framework-specific terms (if framework detected)\n");
        prompt.append("4. Utility/helper class names\n");
        prompt.append("5. Common patterns (Factory, Builder, Manager, etc.)\n");
        prompt.append("6. Related configuration terms\n");
        prompt.append("7. Test-related terms\n\n");
        
        prompt.append("Rules:\n");
        prompt.append("- Include both generic terms (e.g., 'user') and specific patterns (e.g., 'UserService')\n");
        prompt.append("- Consider multiple naming conventions (camelCase, snake_case, PascalCase)\n");
        prompt.append("- Think about what else would be in the same module/package\n");
        prompt.append("- Generate 15-20 keywords total\n");
        prompt.append("- One keyword per line\n");

        return prompt.toString();
    }
    
    /**
     * Infers framework from file name and extension.
     */
    private void inferFrameworkFromFile(String fileName, String extension, StringBuilder prompt) {
        String lowerFileName = fileName.toLowerCase();
        
        if (lowerFileName.contains("controller") || lowerFileName.contains("service") || 
            lowerFileName.contains("repository")) {
            prompt.append("  * Likely Spring/MVC framework detected\n");
        }
        if (lowerFileName.contains("component") || lowerFileName.contains("hook") || 
            extension != null && (extension.equals("jsx") || extension.equals("tsx"))) {
            prompt.append("  * Likely React framework detected\n");
        }
        if (lowerFileName.contains("spec") || lowerFileName.contains("test")) {
            prompt.append("  * Test file detected\n");
        }
    }
    
    /**
     * Analyzes code snippet for framework indicators.
     */
    private void analyzeCodeForFrameworks(String code, StringBuilder prompt) {
        // Spring indicators
        if (code.contains("@Service") || code.contains("@Controller") || 
            code.contains("@Repository") || code.contains("@Autowired")) {
            prompt.append("  * Spring framework annotations detected\n");
        }
        
        // React indicators
        if (code.contains("useState") || code.contains("useEffect") || 
            code.contains("React.") || code.contains("props.")) {
            prompt.append("  * React framework detected\n");
        }
        
        // Angular indicators
        if (code.contains("@Component") || code.contains("@Injectable") || 
            code.contains("ngOnInit")) {
            prompt.append("  * Angular framework detected\n");
        }
        
        // Testing frameworks
        if (code.contains("@Test") || code.contains("describe(") || 
            code.contains("it(") || code.contains("expect(")) {
            prompt.append("  * Testing framework detected\n");
        }
    }
    
    /**
     * Analyzes project structure from file list.
     */
    private void analyzeProjectStructure(String sourceFiles, StringBuilder prompt) {
        String[] files = sourceFiles.split("\n");
        Set<String> commonPatterns = new HashSet<>();
        Set<String> frameworks = new HashSet<>();
        
        // Analyze first 100 files for patterns
        for (int i = 0; i < Math.min(100, files.length); i++) {
            String file = files[i].toLowerCase();
            
            // Detect common patterns
            if (file.contains("utils")) commonPatterns.add("Utils classes");
            if (file.contains("helper")) commonPatterns.add("Helper classes");
            if (file.contains("service")) commonPatterns.add("Service layer");
            if (file.contains("controller")) commonPatterns.add("Controller layer");
            if (file.contains("repository")) commonPatterns.add("Repository layer");
            if (file.contains("config")) commonPatterns.add("Configuration");
            if (file.contains("constant")) commonPatterns.add("Constants");
            
            // Detect frameworks
            if (file.contains("pom.xml") || file.contains("application.properties")) {
                frameworks.add("Spring/Spring Boot");
            }
            if (file.contains("package.json")) {
                frameworks.add("Node.js/JavaScript");
            }
            if (file.contains(".tsx") || file.contains(".jsx")) {
                frameworks.add("React");
            }
            if (file.contains("angular.json")) {
                frameworks.add("Angular");
            }
        }
        
        if (!commonPatterns.isEmpty()) {
            prompt.append("- Common patterns detected: ").append(String.join(", ", commonPatterns)).append("\n");
        }
        if (!frameworks.isEmpty()) {
            prompt.append("- Likely frameworks: ").append(String.join(", ", frameworks)).append("\n");
        }
        
        // Show sample structure
        prompt.append("- Sample project files:\n");
        for (int i = 0; i < Math.min(20, files.length); i++) {
            prompt.append("  ").append(files[i]).append("\n");
        }
    }


    /**
     * Parses keywords from LLM response.
     */
    private List<String> parseKeywordsFromResponse(String responseBody) {
        LOG.info("Parsing keywords from LLM response");
        List<String> keywords = new ArrayList<>();

        try {
            JsonObject response = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = response.getAsJsonArray("choices");

            if (choices != null && choices.size() > 0) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                String content = message.get("content").getAsString();

                LOG.info("LLM response content length: " + content.length());

                // Parse keywords from content (one per line)
                String[] lines = content.trim().split("\n");
                for (String line : lines) {
                    String keyword = line.trim();
                    if (!keyword.isEmpty() && keyword.length() > 2) {
                        keywords.add(keyword);
                        if (keywords.size() >= 10) break;
                    }
                }

                LOG.info("Parsed " + keywords.size() + " keywords from LLM response");
            } else {
                LOG.warn("No choices found in LLM response");
            }
        } catch (Exception e) {
            LOG.warn("Error parsing LLM response", e);
        }

        if (keywords.isEmpty()) {
            LOG.info("No keywords parsed, using fallback");
            return getFallbackKeywords("");
        }

        return keywords;
    }

    /**
     * Provides context-aware fallback keywords when LLM is unavailable.
     */
    private List<String> getContextAwareFallbackKeywords(String userQuery, Map<String, String> context) {
        LOG.info("Generating context-aware fallback keywords");

        List<String> keywords = new ArrayList<>();

        // Extract from query
        keywords.addAll(getFallbackKeywords(userQuery));

        // Add keywords based on current file
        if ("true".equals(context.get("hasEditor"))) {
            String fileName = context.get("currentFileName");
            if (fileName != null) {
                // Extract class/component name from file name
                String nameWithoutExt = fileName.replaceAll("\\.[^.]+$", "");
                if (nameWithoutExt.length() > 3) {
                    keywords.add(nameWithoutExt);

                    // Add variations (camelCase to snake_case, etc.)
                    keywords.add(camelToSnake(nameWithoutExt));
                    keywords.add(nameWithoutExt.toLowerCase());
                    
                    // Add common patterns based on file name
                    if (nameWithoutExt.endsWith("Service")) {
                        keywords.add(nameWithoutExt.replace("Service", "Repository"));
                        keywords.add(nameWithoutExt.replace("Service", "Controller"));
                        keywords.add(nameWithoutExt.replace("Service", "Utils"));
                    } else if (nameWithoutExt.endsWith("Controller")) {
                        keywords.add(nameWithoutExt.replace("Controller", "Service"));
                        keywords.add(nameWithoutExt.replace("Controller", "Repository"));
                    } else if (nameWithoutExt.endsWith("Component")) {
                        keywords.add(nameWithoutExt.replace("Component", "Service"));
                        keywords.add(nameWithoutExt.replace("Component", "Container"));
                        keywords.add("Provider");
                    }
                }
            }

            // Extract from selected text
            if ("true".equals(context.get("hasSelection"))) {
                String selectedText = context.get("selectedText");
                if (selectedText != null && selectedText.length() < 200) {
                    // Look for function/method names in selection
                    extractIdentifiersFromCode(selectedText, keywords);
                    // Look for framework patterns
                    extractFrameworkPatterns(selectedText, keywords);
                }
            }
        }

        // Add common patterns based on file extension
        String extension = context.get("currentFileExtension");
        if (extension != null) {
            addLanguageSpecificKeywords(extension, userQuery, keywords);
        }
        
        // Add exploration keywords
        addExploratoryKeywords(userQuery, keywords);

        List<String> result = keywords.stream()
                .distinct()
                .limit(20) // Allow more keywords for exploration
                .toList();

        LOG.info("Generated " + result.size() + " context-aware fallback keywords: " + result);
        return result;
    }
    
    /**
     * Extracts framework-specific patterns from code.
     */
    private void extractFrameworkPatterns(String code, List<String> keywords) {
        // Spring patterns
        if (code.contains("@")) {
            java.util.regex.Pattern annotationPattern = java.util.regex.Pattern.compile("@(\\w+)");
            java.util.regex.Matcher m = annotationPattern.matcher(code);
            while (m.find()) {
                String annotation = m.group(1);
                keywords.add(annotation);
                
                // Add related Spring terms
                if (annotation.equals("Service")) {
                    keywords.add("Repository");
                    keywords.add("Controller");
                } else if (annotation.equals("Component")) {
                    keywords.add("Bean");
                    keywords.add("Configuration");
                }
            }
        }
        
        // React patterns
        if (code.contains("use") || code.contains("State") || code.contains("Effect")) {
            keywords.add("hook");
            keywords.add("component");
            keywords.add("props");
            keywords.add("context");
        }
    }
    
    /**
     * Adds exploratory keywords based on the query.
     */
    private void addExploratoryKeywords(String query, List<String> keywords) {
        String lowerQuery = query.toLowerCase();
        
        // Add utility/helper patterns
        keywords.add("Utils");
        keywords.add("Helper");
        keywords.add("Manager");
        keywords.add("Factory");
        
        // Add common architectural patterns
        if (lowerQuery.contains("service") || lowerQuery.contains("business")) {
            keywords.add("Service");
            keywords.add("Repository");
            keywords.add("DAO");
            keywords.add("Model");
        }
        
        // Add test-related terms
        if (lowerQuery.contains("test") || lowerQuery.contains("mock")) {
            keywords.add("Test");
            keywords.add("Spec");
            keywords.add("Mock");
            keywords.add("Stub");
            keywords.add("Fixture");
        }
        
        // Add configuration terms
        if (lowerQuery.contains("config") || lowerQuery.contains("setup")) {
            keywords.add("Config");
            keywords.add("Configuration");
            keywords.add("Settings");
            keywords.add("Properties");
            keywords.add("Constants");
        }
    }

    /**
     * Extracts identifiers from code snippet.
     */
    private void extractIdentifiersFromCode(String code, List<String> keywords) {
        // Simple regex to find potential function/method names
        String[] patterns = {
                "\\b(function|def|public|private|protected)\\s+(\\w+)",
                "\\b(const|let|var)\\s+(\\w+)\\s*=\\s*\\(",
                "\\b(class|interface)\\s+(\\w+)",
                "\\b(\\w+)\\s*\\([^)]*\\)\\s*\\{"
        };

        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(code);
            while (m.find() && keywords.size() < 15) {
                String identifier = m.group(m.groupCount());
                if (identifier != null && identifier.length() > 3) {
                    keywords.add(identifier);
                }
            }
        }
    }

    /**
     * Adds language-specific keywords based on file extension.
     */
    private void addLanguageSpecificKeywords(String extension, String query, List<String> keywords) {
        String lowerQuery = query.toLowerCase();

        switch (extension.toLowerCase()) {
            case "java":
                // Framework annotations
                keywords.add("@Service");
                keywords.add("@Repository");
                keywords.add("@Controller");
                keywords.add("@Component");
                keywords.add("@Configuration");
                
                // Common patterns
                keywords.add("Interface");
                keywords.add("Abstract");
                keywords.add("Factory");
                keywords.add("Builder");
                
                // Testing
                if (lowerQuery.contains("test")) {
                    keywords.add("@Test");
                    keywords.add("@Mock");
                    keywords.add("@BeforeEach");
                    keywords.add("TestUtils");
                }
                
                // Spring specific
                if (lowerQuery.contains("spring") || lowerQuery.contains("boot")) {
                    keywords.add("@Autowired");
                    keywords.add("@Bean");
                    keywords.add("ApplicationContext");
                }
                break;
                
            case "js":
            case "jsx":
            case "ts":
            case "tsx":
                // React patterns
                keywords.add("Component");
                keywords.add("useState");
                keywords.add("useEffect");
                keywords.add("useContext");
                keywords.add("Provider");
                keywords.add("props");
                
                // Common patterns
                keywords.add("Utils");
                keywords.add("Helper");
                keywords.add("Service");
                keywords.add("Manager");
                
                // Event handling
                if (lowerQuery.contains("event") || lowerQuery.contains("click")) {
                    keywords.add("addEventListener");
                    keywords.add("onClick");
                    keywords.add("handler");
                }
                
                // Testing
                if (lowerQuery.contains("test")) {
                    keywords.add("describe");
                    keywords.add("beforeEach");
                    keywords.add("jest");
                    keywords.add("enzyme");
                }
                break;
                
            case "py":
                // Common patterns
                keywords.add("class");
                keywords.add("def");
                keywords.add("__init__");
                keywords.add("self");
                
                // Framework specific
                keywords.add("django");
                keywords.add("flask");
                keywords.add("model");
                keywords.add("view");
                
                // Testing
                if (lowerQuery.contains("test")) {
                    keywords.add("pytest");
                    keywords.add("unittest");
                    keywords.add("mock");
                    keywords.add("fixture");
                }
                break;
                
            case "cpp":
            case "c":
            case "h":
            case "hpp":
                // Common patterns
                keywords.add("class");
                keywords.add("namespace");
                keywords.add("template");
                keywords.add("virtual");
                keywords.add("interface");
                
                // Utilities
                keywords.add("Utils");
                keywords.add("Helper");
                keywords.add("Manager");
                break;
        }
        
        // Add general utility patterns for all languages
        keywords.add("Config");
        keywords.add("Constants");
        keywords.add("Error");
        keywords.add("Exception");
    }

    /**
     * Converts camelCase to snake_case.
     */
    private String camelToSnake(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    /**
     * Provides fallback keywords when LLM is unavailable.
     */
    private List<String> getFallbackKeywords(String userQuery) {
        LOG.info("Generating fallback keywords for: " + userQuery);

        // Simple fallback: extract potential function/class names
        List<String> keywords = new ArrayList<>();

        String[] words = userQuery.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s_]", " ")
                .split("\\s+");

        for (String word : words) {
            // Look for camelCase or snake_case patterns
            if (word.length() > 3 &&
                    (word.contains("_") || !word.equals(word.toLowerCase()))) {
                keywords.add(word);
            }
        }

        // Add some common patterns based on query words
        if (userQuery.toLowerCase().contains("button")) {
            keywords.add("onClick");
            keywords.add("handleClick");
        }
        if (userQuery.toLowerCase().contains("form")) {
            keywords.add("onSubmit");
            keywords.add("handleSubmit");
        }
        if (userQuery.toLowerCase().contains("api") || userQuery.toLowerCase().contains("fetch")) {
            keywords.add("fetch");
            keywords.add("axios");
            keywords.add("request");
        }

        List<String> result = keywords.stream().distinct().limit(10).toList();
        LOG.info("Generated " + result.size() + " fallback keywords: " + result);
        return result;
    }
}