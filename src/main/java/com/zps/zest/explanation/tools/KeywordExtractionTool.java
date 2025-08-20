package com.zps.zest.explanation.tools;

import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool for extracting relevant keywords from code using LLM and pattern-based approaches.
 * Supports all programming languages by identifying common code patterns.
 */
public class KeywordExtractionTool {
    private final Project project;
    private final ZestLangChain4jService langChainService;
    private final List<String> extractedKeywords;

    public KeywordExtractionTool(@NotNull Project project,
                                @NotNull ZestLangChain4jService langChainService, 
                                @NotNull List<String> extractedKeywords) {
        this.project = project;
        this.langChainService = langChainService;
        this.extractedKeywords = extractedKeywords;
    }

    /**
     * Extract keywords from code using both LLM and pattern-based approaches
     */
    public String extractKeywords(String code, @Nullable String language) {
        try {
            // First, use pattern-based extraction as fallback
            Set<String> patternKeywords = extractKeywordsWithPatterns(code, language);
            
            // Then use LLM for intelligent extraction
            Set<String> llmKeywords = extractKeywordsWithLLM(code, language);
            
            // Combine and deduplicate
            Set<String> allKeywords = new HashSet<>();
            allKeywords.addAll(patternKeywords);
            allKeywords.addAll(llmKeywords);
            
            // Filter and sort by relevance
            List<String> filteredKeywords = filterAndRankKeywords(allKeywords, code);
            
            // Store in shared list
            extractedKeywords.clear();
            extractedKeywords.addAll(filteredKeywords);
            
            return formatKeywordResults(filteredKeywords, patternKeywords.size(), llmKeywords.size());
            
        } catch (Exception e) {
            // Fallback to pattern-only extraction if LLM fails
            Set<String> keywords = extractKeywordsWithPatterns(code, language);
            extractedKeywords.clear();
            extractedKeywords.addAll(keywords);
            
            return String.format("Extracted %d keywords using pattern matching (LLM unavailable): %s",
                                keywords.size(), String.join(", ", keywords));
        }
    }

    /**
     * Extract keywords using LLM for intelligent analysis
     */
    private Set<String> extractKeywordsWithLLM(String code, @Nullable String language) {
        try {
            // Get LLMService from project
            com.zps.zest.langchain4j.util.LLMService llmService = project
                .getService(com.zps.zest.langchain4j.util.LLMService.class);
            
            if (llmService == null) {
                return new HashSet<>();
            }
            
            String prompt = buildKeywordExtractionPrompt(code, language);
            
            String response = llmService.query(prompt, com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.INLINE_COMPLETION);
            
            // Parse the LLM response to extract keywords
            return parseKeywordsFromLLMResponse(response);
            
        } catch (Exception e) {
            // Return empty set if LLM extraction fails
            return new HashSet<>();
        }
    }

    /**
     * Build prompt for LLM keyword extraction
     */
    private String buildKeywordExtractionPrompt(String code, @Nullable String language) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Extract important keywords from the following code that would be useful for finding related code in a codebase. ");
        prompt.append("Focus on identifiers that other code might reference or depend on.\n\n");
        
        if (language != null) {
            prompt.append("Language: ").append(language).append("\n");
        }
        
        prompt.append("Code:\n```\n");
        // Limit code length for token efficiency
        String limitedCode = code.length() > 2000 ? code.substring(0, 2000) + "\n... [truncated]" : code;
        prompt.append(limitedCode);
        prompt.append("\n```\n\n");
        
        prompt.append("Extract these types of keywords:\n");
        prompt.append("1. Class names, interface names, enum names\n");
        prompt.append("2. Method names, function names\n");
        prompt.append("3. Important variable names and constants\n");
        prompt.append("4. Package/module names and imports\n");
        prompt.append("5. Annotation names\n");
        prompt.append("6. Type names and generics\n");
        prompt.append("7. Configuration keys or resource names\n");
        prompt.append("8. API endpoints or service names\n\n");
        
        prompt.append("Return only the keywords, one per line, without explanations or formatting. ");
        prompt.append("Exclude common language keywords like 'public', 'private', 'if', 'for', etc.");
        
        return prompt.toString();
    }

    /**
     * Parse keywords from LLM response
     */
    private Set<String> parseKeywordsFromLLMResponse(String response) {
        Set<String> keywords = new HashSet<>();
        
        // Split by lines and extract meaningful keywords
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // Remove common prefixes/suffixes from LLM response
            line = line.replaceAll("^[-*•]\\s*", ""); // Remove bullet points
            line = line.replaceAll("^\\d+\\.\\s*", ""); // Remove numbered lists
            line = line.replaceAll("[\"'`]", ""); // Remove quotes
            
            // Split by comma or semicolon if multiple keywords per line
            String[] parts = line.split("[,;]");
            for (String part : parts) {
                part = part.trim();
                if (isValidKeyword(part)) {
                    keywords.add(part);
                }
            }
        }
        
        return keywords;
    }

    /**
     * Extract keywords using pattern-based analysis (language-agnostic)
     */
    private Set<String> extractKeywordsWithPatterns(String code, @Nullable String language) {
        Set<String> keywords = new HashSet<>();
        
        // Common patterns across languages
        extractIdentifierPatterns(code, keywords);
        extractImportPatterns(code, keywords);
        extractAnnotationPatterns(code, keywords);
        extractStringLiterals(code, keywords);
        
        // Language-specific patterns
        if (language != null) {
            String lang = language.toLowerCase();
            switch (lang) {
                case "java":
                case "kotlin":
                    extractJavaKotlinPatterns(code, keywords);
                    break;
                case "javascript":
                case "typescript":
                    extractJavaScriptPatterns(code, keywords);
                    break;
                case "python":
                    extractPythonPatterns(code, keywords);
                    break;
                default:
                    // Use general patterns
                    break;
            }
        }
        
        return keywords;
    }

    /**
     * Extract common identifier patterns (camelCase, PascalCase, snake_case)
     */
    private void extractIdentifierPatterns(String code, Set<String> keywords) {
        // Class/interface names (PascalCase starting with capital)
        Pattern classPattern = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b");
        extractMatches(code, classPattern, keywords, 2); // Min length 2
        
        // Method/variable names (camelCase or snake_case)
        Pattern methodPattern = Pattern.compile("\\b[a-z][a-zA-Z0-9_]*\\b");
        extractMatches(code, methodPattern, keywords, 3); // Min length 3
        
        // Constants (UPPER_CASE)
        Pattern constantPattern = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");
        extractMatches(code, constantPattern, keywords, 2);
    }

    /**
     * Extract import/require patterns
     */
    private void extractImportPatterns(String code, Set<String> keywords) {
        // Java/Kotlin imports
        Pattern javaImport = Pattern.compile("import\\s+([a-zA-Z0-9_.]+)");
        extractGroupMatches(code, javaImport, keywords, 1);
        
        // JavaScript/TypeScript imports  
        Pattern jsImport = Pattern.compile("import.*from\\s+['\"]([^'\"]+)['\"]");
        extractGroupMatches(code, jsImport, keywords, 1);
        
        Pattern jsRequire = Pattern.compile("require\\(['\"]([^'\"]+)['\"]\\)");
        extractGroupMatches(code, jsRequire, keywords, 1);
        
        // Python imports
        Pattern pythonImport = Pattern.compile("(?:from\\s+([a-zA-Z0-9_.]+)\\s+)?import\\s+([a-zA-Z0-9_.]+)");
        Matcher matcher = pythonImport.matcher(code);
        while (matcher.find()) {
            if (matcher.group(1) != null) keywords.add(matcher.group(1));
            if (matcher.group(2) != null) keywords.add(matcher.group(2));
        }
    }

    /**
     * Extract annotation patterns (@Annotation)
     */
    private void extractAnnotationPatterns(String code, Set<String> keywords) {
        Pattern annotationPattern = Pattern.compile("@([A-Za-z][A-Za-z0-9_]*)");
        extractGroupMatches(code, annotationPattern, keywords, 1);
    }

    /**
     * Extract meaningful string literals (config keys, file names, etc.)
     */
    private void extractStringLiterals(String code, Set<String> keywords) {
        Pattern stringPattern = Pattern.compile("['\"]([^'\"]{3,30})['\"]");
        Matcher matcher = stringPattern.matcher(code);
        while (matcher.find()) {
            String literal = matcher.group(1);
            // Only include strings that look like identifiers or config keys
            if (literal.matches("[a-zA-Z][a-zA-Z0-9_./-]*") || 
                literal.matches("[a-zA-Z][a-zA-Z0-9_.]*\\.[a-zA-Z0-9]+")) {
                keywords.add(literal);
            }
        }
    }

    /**
     * Extract Java/Kotlin specific patterns
     */
    private void extractJavaKotlinPatterns(String code, Set<String> keywords) {
        // Package declarations
        Pattern packagePattern = Pattern.compile("package\\s+([a-zA-Z0-9_.]+)");
        extractGroupMatches(code, packagePattern, keywords, 1);
        
        // Generic types
        Pattern genericPattern = Pattern.compile("<([A-Z][a-zA-Z0-9_]*)>");
        extractGroupMatches(code, genericPattern, keywords, 1);
        
        // Spring annotations (common in Java projects)
        Pattern springPattern = Pattern.compile("@(Service|Component|Controller|Repository|Autowired|Value)");
        extractGroupMatches(code, springPattern, keywords, 1);
    }

    /**
     * Extract JavaScript/TypeScript specific patterns
     */
    private void extractJavaScriptPatterns(String code, Set<String> keywords) {
        // Function declarations
        Pattern funcPattern = Pattern.compile("function\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        extractGroupMatches(code, funcPattern, keywords, 1);
        
        // Arrow functions assigned to variables
        Pattern arrowPattern = Pattern.compile("const\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=.*=>");
        extractGroupMatches(code, arrowPattern, keywords, 1);
        
        // Object properties
        Pattern propPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*):");
        extractGroupMatches(code, propPattern, keywords, 1);
    }

    /**
     * Extract Python specific patterns
     */
    private void extractPythonPatterns(String code, Set<String> keywords) {
        // Function definitions
        Pattern funcPattern = Pattern.compile("def\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
        extractGroupMatches(code, funcPattern, keywords, 1);
        
        // Class definitions
        Pattern classPattern = Pattern.compile("class\\s+([A-Z][a-zA-Z0-9_]*)");
        extractGroupMatches(code, classPattern, keywords, 1);
        
        // Decorators
        Pattern decoratorPattern = Pattern.compile("@([a-zA-Z_][a-zA-Z0-9_]*)");
        extractGroupMatches(code, decoratorPattern, keywords, 1);
    }

    /**
     * Extract matches from a pattern
     */
    private void extractMatches(String code, Pattern pattern, Set<String> keywords, int minLength) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String match = matcher.group();
            if (isValidKeyword(match) && match.length() >= minLength) {
                keywords.add(match);
            }
        }
    }

    /**
     * Extract group matches from a pattern
     */
    private void extractGroupMatches(String code, Pattern pattern, Set<String> keywords, int group) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String match = matcher.group(group);
            if (match != null && isValidKeyword(match)) {
                keywords.add(match);
            }
        }
    }

    /**
     * Check if a keyword is valid and worth including
     */
    private boolean isValidKeyword(String keyword) {
        if (keyword == null || keyword.length() < 2) return false;
        
        // Exclude common language keywords and operators
        Set<String> commonKeywords = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "class", "interface",
            "extends", "implements", "import", "package", "return", "void", "int", "String",
            "boolean", "double", "float", "long", "char", "byte", "short", "if", "else", "for",
            "while", "do", "switch", "case", "break", "continue", "try", "catch", "finally",
            "throw", "throws", "new", "this", "super", "null", "true", "false", "const", "let",
            "var", "function", "async", "await", "export", "default", "from", "as", "def",
            "lambda", "pass", "with", "as", "global", "nonlocal", "yield", "and", "or", "not",
            "is", "in", "del", "assert", "raise", "exec", "eval"
        );
        
        return !commonKeywords.contains(keyword.toLowerCase());
    }

    /**
     * Filter and rank keywords by relevance
     */
    private List<String> filterAndRankKeywords(Set<String> keywords, String originalCode) {
        List<String> filtered = new ArrayList<>();
        
        // Score keywords by various factors
        Map<String, Integer> scores = new HashMap<>();
        for (String keyword : keywords) {
            int score = calculateKeywordScore(keyword, originalCode);
            if (score > 0) {
                scores.put(keyword, score);
                filtered.add(keyword);
            }
        }
        
        // Sort by score (descending) and limit to top keywords
        filtered.sort((a, b) -> scores.getOrDefault(b, 0).compareTo(scores.getOrDefault(a, 0)));
        
        // Return top 15 most relevant keywords
        return filtered.subList(0, Math.min(15, filtered.size()));
    }

    /**
     * Calculate relevance score for a keyword
     */
    private int calculateKeywordScore(String keyword, String code) {
        int score = 1; // Base score
        
        // Higher score for longer, more specific keywords
        if (keyword.length() > 5) score += 2;
        if (keyword.length() > 10) score += 2;
        
        // Higher score for CamelCase/PascalCase (likely class/method names)
        if (keyword.matches(".*[A-Z].*[a-z].*")) score += 3;
        
        // Higher score for keywords that appear multiple times
        long frequency = code.split(Pattern.quote(keyword), -1).length - 1;
        if (frequency > 1) score += (int) Math.min(frequency, 5);
        
        // Lower score for very common patterns
        if (keyword.matches("^[a-z]{1,3}$")) score -= 1; // Short lowercase
        if (keyword.matches("^test.*|.*[Tt]est$")) score -= 1; // Test-related
        
        return Math.max(0, score);
    }

    /**
     * Format the keyword extraction results
     */
    private String formatKeywordResults(List<String> keywords, int patternCount, int llmCount) {
        StringBuilder result = new StringBuilder();
        
        result.append(String.format("Extracted %d relevant keywords (Pattern: %d, LLM: %d):\n\n",
                                  keywords.size(), patternCount, llmCount));
        
        // Group keywords by type for better readability
        List<String> classes = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        List<String> others = new ArrayList<>();
        
        for (String keyword : keywords) {
            if (keyword.matches("^[A-Z].*")) {
                classes.add(keyword);
            } else if (keyword.matches("^[a-z].*[A-Z].*") || keyword.contains("_")) {
                methods.add(keyword);
            } else {
                others.add(keyword);
            }
        }
        
        if (!classes.isEmpty()) {
            result.append("🏛️ Classes/Types: ").append(String.join(", ", classes)).append("\n");
        }
        if (!methods.isEmpty()) {
            result.append("⚙️ Methods/Functions: ").append(String.join(", ", methods)).append("\n");
        }
        if (!others.isEmpty()) {
            result.append("🔧 Other Keywords: ").append(String.join(", ", others)).append("\n");
        }
        
        result.append("\nThese keywords will be used to search for related code components.");
        
        return result.toString();
    }
}