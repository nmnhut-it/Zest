package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        return ContextGathererForAgent.gatherCodeContext(project, editor);
    }

    /**
     * Builds the prompt for keyword generation with code context.
     */
    /**
     * Builds the prompt for keyword generation with code context.
     */
    /**
     * Builds the prompt for keyword generation with code context.
     */
    /**
     * Builds the prompt for keyword generation with code context.
     */
    private String buildKeywordPromptWithContext(String userQuery, Map<String, String> context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Generate keywords to search for code in a project. ");
        prompt.append("These keywords will be used to find relevant functions, classes, and code snippets.\n\n");

        prompt.append("Examples:\n");
        prompt.append("- 'user authentication' → authenticate, login, user, password, token, auth\n");
        prompt.append("- 'handle button clicks' → click, button, handle, event, listener, press\n");
        prompt.append("- 'database connection' → database, connection, connect, query, pool, db\n\n");

        // Keep your existing context information
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
                }

                // Add selected text or cursor context
                if ("true".equals(context.get("hasSelection"))) {
                    String selectedText = context.get("selectedText");
                    if (selectedText != null && selectedText.length() < 500) {
                        prompt.append("- Selected code:\n```\n");
                        prompt.append(selectedText).append("\n```\n");
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

                // Add sample file paths to understand project structure
                String sourceFiles = context.get("sourceRootFiles");
                if (sourceFiles != null && !sourceFiles.isEmpty()) {
                    String[] files = sourceFiles.split("\n");
                    if (files.length > 0) {
                        prompt.append("- Sample files in project:\n");
                        for (int i = 0; i < Math.min(300, files.length); i++) {
                            prompt.append("  ").append(files[i]).append("\n");
                        }
                    }
                }
            }
        }

        prompt.append("\nQuery: ").append(userQuery).append("\n\n");
        prompt.append("Generate 10-15 SIMPLE keywords to search for this query.\n");
        prompt.append("Rules:\n");
        prompt.append("- Use single words, not phrases (e.g., 'user' not 'addUser')\n");
        prompt.append("- Break compound words into parts (e.g., 'UserService' → 'user', 'service')\n");
        prompt.append("- Include root words that appear in function/class names\n");
        prompt.append("- One keyword per line:\n");

        return prompt.toString();
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
                }
            }

            // Extract from selected text
            if ("true".equals(context.get("hasSelection"))) {
                String selectedText = context.get("selectedText");
                if (selectedText != null && selectedText.length() < 200) {
                    // Look for function/method names in selection
                    extractIdentifiersFromCode(selectedText, keywords);
                }
            }
        }

        // Add common patterns based on file extension
        String extension = context.get("currentFileExtension");
        if (extension != null) {
            addLanguageSpecificKeywords(extension, userQuery, keywords);
        }

        List<String> result = keywords.stream()
                .distinct()
                .limit(10)
                .toList();

        LOG.info("Generated " + result.size() + " context-aware fallback keywords: " + result);
        return result;
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
                if (lowerQuery.contains("test")) keywords.add("@Test");
                if (lowerQuery.contains("spring")) keywords.add("@Component");
                if (lowerQuery.contains("rest")) keywords.add("@RestController");
                break;
            case "js":
            case "jsx":
            case "ts":
            case "tsx":
                if (lowerQuery.contains("react")) {
                    keywords.add("useState");
                    keywords.add("useEffect");
                }
                if (lowerQuery.contains("component")) keywords.add("Component");
                break;
            case "py":
                if (lowerQuery.contains("test")) keywords.add("pytest");
                if (lowerQuery.contains("class")) keywords.add("__init__");
                break;
        }
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