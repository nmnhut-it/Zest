package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Programming-specific tokenizer that understands code naming conventions.
 * Handles camelCase, snake_case, kebab-case, and common programming patterns.
 */
public class CodeAwareTokenizer {
    private static final Logger LOG = Logger.getInstance(CodeAwareTokenizer.class);
    
    // Patterns for different naming conventions
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern SNAKE_CASE_PATTERN = Pattern.compile("_");
    private static final Pattern KEBAB_CASE_PATTERN = Pattern.compile("-");
    private static final Pattern DOT_NOTATION_PATTERN = Pattern.compile("\\.");
    
    // Common programming abbreviations and their expansions
    private static final Map<String, List<String>> ABBREVIATIONS = new HashMap<>();
    static {
        ABBREVIATIONS.put("impl", Arrays.asList("implementation", "implement"));
        ABBREVIATIONS.put("repo", Arrays.asList("repository"));
        ABBREVIATIONS.put("ctrl", Arrays.asList("controller", "control"));
        ABBREVIATIONS.put("mgr", Arrays.asList("manager", "manage"));
        ABBREVIATIONS.put("svc", Arrays.asList("service"));
        ABBREVIATIONS.put("util", Arrays.asList("utility", "utilities"));
        ABBREVIATIONS.put("config", Arrays.asList("configuration", "configure"));
        ABBREVIATIONS.put("auth", Arrays.asList("authentication", "authorization", "authenticate", "authorize"));
        ABBREVIATIONS.put("db", Arrays.asList("database"));
        ABBREVIATIONS.put("conn", Arrays.asList("connection", "connect"));
        ABBREVIATIONS.put("msg", Arrays.asList("message"));
        ABBREVIATIONS.put("btn", Arrays.asList("button"));
        ABBREVIATIONS.put("val", Arrays.asList("value", "validation", "validate"));
        ABBREVIATIONS.put("max", Arrays.asList("maximum"));
        ABBREVIATIONS.put("min", Arrays.asList("minimum"));
        ABBREVIATIONS.put("avg", Arrays.asList("average"));
        ABBREVIATIONS.put("cnt", Arrays.asList("count", "counter"));
        ABBREVIATIONS.put("idx", Arrays.asList("index"));
        ABBREVIATIONS.put("ref", Arrays.asList("reference"));
        ABBREVIATIONS.put("req", Arrays.asList("request", "require", "required"));
        ABBREVIATIONS.put("res", Arrays.asList("response", "result", "resource"));
        ABBREVIATIONS.put("err", Arrays.asList("error"));
        ABBREVIATIONS.put("exc", Arrays.asList("exception"));
        ABBREVIATIONS.put("init", Arrays.asList("initialize", "initial"));
        ABBREVIATIONS.put("calc", Arrays.asList("calculate", "calculation"));
        ABBREVIATIONS.put("exec", Arrays.asList("execute", "execution"));
        ABBREVIATIONS.put("proc", Arrays.asList("process", "procedure"));
        ABBREVIATIONS.put("async", Arrays.asList("asynchronous"));
        ABBREVIATIONS.put("sync", Arrays.asList("synchronous", "synchronize"));
    }
    
    // Common programming verbs and their variations
    private static final Map<String, List<String>> VERB_VARIATIONS = new HashMap<>();
    static {
        VERB_VARIATIONS.put("get", Arrays.asList("fetch", "retrieve", "find", "load", "read", "obtain"));
        VERB_VARIATIONS.put("set", Arrays.asList("update", "assign", "store", "save", "write", "put"));
        VERB_VARIATIONS.put("add", Arrays.asList("insert", "append", "push", "create", "plus", "increment"));
        VERB_VARIATIONS.put("remove", Arrays.asList("delete", "drop", "pop", "clear", "minus", "decrement"));
        VERB_VARIATIONS.put("check", Arrays.asList("verify", "validate", "test", "ensure", "confirm"));
        VERB_VARIATIONS.put("init", Arrays.asList("initialize", "setup", "create", "start", "begin"));
        VERB_VARIATIONS.put("calc", Arrays.asList("calculate", "compute", "derive", "determine"));
        VERB_VARIATIONS.put("send", Arrays.asList("dispatch", "emit", "publish", "broadcast", "transmit"));
        VERB_VARIATIONS.put("receive", Arrays.asList("accept", "handle", "consume", "listen", "subscribe"));
        VERB_VARIATIONS.put("convert", Arrays.asList("transform", "parse", "format", "serialize", "map"));
    }
    
    /**
     * Tokenizes code identifiers and queries into searchable tokens.
     * 
     * @param text The text to tokenize (can be identifier or query)
     * @param isQuery Whether this is a search query (affects tokenization strategy)
     * @return List of tokens with variations
     */
    public List<String> tokenize(String text, boolean isQuery) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        Set<String> tokens = new LinkedHashSet<>();
        
        // Original text (normalized)
        tokens.add(text.toLowerCase().trim());
        
        // Split by common separators
        List<String> parts = splitByConventions(text);
        tokens.addAll(parts.stream().map(String::toLowerCase).collect(Collectors.toList()));
        
        // Add variations for each part
        for (String part : parts) {
            String lowerPart = part.toLowerCase();
            
            // Add abbreviation expansions
            if (ABBREVIATIONS.containsKey(lowerPart)) {
                tokens.addAll(ABBREVIATIONS.get(lowerPart));
            }
            
            // Add verb variations
            if (VERB_VARIATIONS.containsKey(lowerPart)) {
                tokens.addAll(VERB_VARIATIONS.get(lowerPart));
            }
            
            // Add stemmed version (simple stemming)
            tokens.add(simpleStem(lowerPart));
        }
        
        // For queries, add combined variations
        if (isQuery && parts.size() > 1) {
            tokens.addAll(generateCombinedVariations(parts));
        }
        
        // Remove empty strings and duplicates
        return tokens.stream()
            .filter(t -> !t.isEmpty())
            .distinct()
            .collect(Collectors.toList());
    }
    
    /**
     * Generates query variations for multi-word queries.
     * E.g., "add score" -> ["addScore", "add_score", "scoreAdd", etc.]
     */
    public List<String> generateQueryVariations(String query) {
        List<String> variations = new ArrayList<>();
        
        // Original query
        variations.add(query);
        
        // Split by spaces
        String[] words = query.toLowerCase().split("\\s+");
        if (words.length > 1) {
            // CamelCase variation
            variations.add(toCamelCase(words));
            
            // PascalCase variation  
            variations.add(toPascalCase(words));
            
            // snake_case variation
            variations.add(String.join("_", words));
            
            // Reversed order
            List<String> reversed = new ArrayList<>(Arrays.asList(words));
            Collections.reverse(reversed);
            variations.add(toCamelCase(reversed.toArray(new String[0])));
            
            // With common prefixes/suffixes
            if (words.length == 2) {
                String first = words[0];
                String second = words[1];
                
                // Common patterns
                variations.add(first + "To" + capitalize(second));      // addToScore
                variations.add(first + capitalize(second) + "s");       // addScores
                variations.add(first + "ing" + capitalize(second));     // addingScore
                variations.add(second + capitalize(first) + "er");      // scoreAdder
            }
        }
        
        return variations.stream().distinct().collect(Collectors.toList());
    }
    
    /**
     * Splits text by common programming naming conventions.
     */
    private List<String> splitByConventions(String text) {
        List<String> parts = new ArrayList<>();
        
        // First, split by dots (package/class notation)
        String[] dotParts = DOT_NOTATION_PATTERN.split(text);
        
        for (String dotPart : dotParts) {
            // Then split by underscores
            String[] snakeParts = SNAKE_CASE_PATTERN.split(dotPart);
            
            for (String snakePart : snakeParts) {
                // Then split by hyphens
                String[] kebabParts = KEBAB_CASE_PATTERN.split(snakePart);
                
                for (String kebabPart : kebabParts) {
                    // Finally split by camelCase
                    String[] camelParts = CAMEL_CASE_PATTERN.split(kebabPart);
                    
                    for (String camelPart : camelParts) {
                        if (!camelPart.isEmpty()) {
                            parts.add(camelPart);
                        }
                    }
                }
            }
        }
        
        // If no splits occurred, add the original
        if (parts.isEmpty()) {
            parts.add(text);
        }
        
        return parts;
    }
    
    /**
     * Simple stemming for common programming terms.
     */
    private String simpleStem(String word) {
        if (word.length() <= 3) return word;
        
        // Remove common suffixes
        if (word.endsWith("ing") && word.length() > 5) {
            return word.substring(0, word.length() - 3);
        }
        if (word.endsWith("ed") && word.length() > 4) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("er") && word.length() > 4) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && word.length() > 3 && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        
        return word;
    }
    
    /**
     * Generates combined variations for multi-part identifiers.
     */
    private List<String> generateCombinedVariations(List<String> parts) {
        List<String> variations = new ArrayList<>();
        
        if (parts.size() == 2) {
            String first = parts.get(0).toLowerCase();
            String second = parts.get(1).toLowerCase();
            
            // Direct combinations
            variations.add(first + second);
            variations.add(first + "_" + second);
            variations.add(second + first);
            variations.add(second + "_" + first);
        } else if (parts.size() > 2) {
            // For longer combinations, just do camelCase and snake_case
            variations.add(parts.stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining()));
            variations.add(parts.stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining("_")));
        }
        
        return variations;
    }
    
    /**
     * Converts words to camelCase.
     */
    private String toCamelCase(String[] words) {
        if (words.length == 0) return "";
        
        StringBuilder sb = new StringBuilder(words[0].toLowerCase());
        for (int i = 1; i < words.length; i++) {
            sb.append(capitalize(words[i].toLowerCase()));
        }
        return sb.toString();
    }
    
    /**
     * Converts words to PascalCase.
     */
    private String toPascalCase(String[] words) {
        return Arrays.stream(words)
            .map(w -> capitalize(w.toLowerCase()))
            .collect(Collectors.joining());
    }
    
    /**
     * Capitalizes the first letter of a string.
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
