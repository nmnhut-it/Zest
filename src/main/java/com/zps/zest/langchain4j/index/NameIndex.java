package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Name-based index optimized for identifier matching with camelCase awareness.
 * Uses in-memory structures for efficient text search with support for:
 * - CamelCase splitting (addScore -> add score)
 * - Snake_case handling
 * - Fuzzy matching
 * - Prefix/suffix search
 */
public class NameIndex {
    private static final Logger LOG = Logger.getInstance(NameIndex.class);
    
    // Main index: maps tokens to set of element IDs
    private final Map<String, Set<String>> tokenIndex = new ConcurrentHashMap<>();
    
    // Element storage: maps element ID to its data
    private final Map<String, IndexedElement> elementStorage = new ConcurrentHashMap<>();
    
    // Patterns for identifier parsing
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern SNAKE_CASE_PATTERN = Pattern.compile("_");
    private static final Pattern DOT_PATTERN = Pattern.compile("\\.");
    
    public NameIndex() throws IOException {
        // No initialization needed for in-memory index
    }
    
    /**
     * Indexes a code element with its identifier information.
     */
    public void indexElement(String id, String signature, String type, String filePath, Map<String, String> additionalFields) throws IOException {
        // Store the element
        IndexedElement element = new IndexedElement(id, signature, type, filePath, additionalFields);
        elementStorage.put(id, element);
        
        // Extract and index all searchable tokens
        Set<String> tokens = new HashSet<>();
        
        // Extract simple name and its variations
        String simpleName = extractSimpleName(id);
        tokens.add(simpleName.toLowerCase());
        
        // Add camelCase split tokens
        String spacedName = splitIdentifier(simpleName);
        tokens.addAll(Arrays.asList(spacedName.split("\\s+")));
        
        // Add individual tokens from the identifier
        tokens.addAll(tokenizeIdentifier(simpleName));
        
        // Extract package/class context tokens
        String packageName = extractPackage(id);
        if (!packageName.isEmpty()) {
            tokens.addAll(Arrays.asList(packageName.split("\\.")));
        }
        
        String className = extractClassName(id);
        if (!className.isEmpty()) {
            tokens.add(className.toLowerCase());
            tokens.addAll(tokenizeIdentifier(className));
        }
        
        // Extract method/field specific tokens
        if (id.contains("#")) {
            String memberName = id.substring(id.lastIndexOf("#") + 1);
            tokens.add(memberName.toLowerCase());
            tokens.addAll(tokenizeIdentifier(memberName));
        }
        
        // Add tokens from signature
        tokens.addAll(tokenizeSignature(signature));
        
        // Index all tokens
        for (String token : tokens) {
            if (token.length() > 1) { // Skip single character tokens
                tokenIndex.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(id);
            }
        }
        
        // Also index prefixes for prefix search
        for (String token : new HashSet<>(tokens)) {
            if (token.length() > 3) {
                for (int i = 2; i < Math.min(token.length(), 8); i++) {
                    String prefix = token.substring(0, i);
                    tokenIndex.computeIfAbsent(prefix, k -> ConcurrentHashMap.newKeySet()).add(id);
                }
            }
        }
    }
    
    /**
     * Searches the name index with various strategies.
     */
    public List<SearchResult> search(String queryStr, int maxResults) throws IOException {
        if (queryStr == null || queryStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        Map<String, Float> scores = new HashMap<>();
        String query = queryStr.toLowerCase().trim();
        
        // Strategy 1: Exact match on simple name
        for (Map.Entry<String, IndexedElement> entry : elementStorage.entrySet()) {
            String id = entry.getKey();
            String simpleName = extractSimpleName(id).toLowerCase();
            
            if (simpleName.equals(query)) {
                scores.put(id, scores.getOrDefault(id, 0f) + 10.0f);
            } else if (simpleName.startsWith(query)) {
                scores.put(id, scores.getOrDefault(id, 0f) + 5.0f);
            } else if (simpleName.contains(query)) {
                scores.put(id, scores.getOrDefault(id, 0f) + 3.0f);
            }
        }
        
        // Strategy 2: Token-based search
        String[] queryTokens = query.split("\\s+");
        Set<String> candidateIds = new HashSet<>();
        
        // Find all elements that contain at least one query token
        for (String token : queryTokens) {
            Set<String> ids = tokenIndex.get(token);
            if (ids != null) {
                candidateIds.addAll(ids);
            }
        }
        
        // Score candidates based on how many tokens they match
        for (String id : candidateIds) {
            float score = 0;
            IndexedElement element = elementStorage.get(id);
            if (element == null) continue;
            
            // Count matching tokens
            String elementText = (element.id + " " + element.signature).toLowerCase();
            for (String token : queryTokens) {
                if (elementText.contains(token)) {
                    score += 2.0f;
                }
            }
            
            // Boost if all tokens match
            boolean allMatch = true;
            for (String token : queryTokens) {
                if (!elementText.contains(token)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch && queryTokens.length > 1) {
                score += 5.0f;
            }
            
            scores.put(id, scores.getOrDefault(id, 0f) + score);
        }
        
        // Strategy 3: Fuzzy matching for typos
        if (query.length() >= 3) {
            for (Map.Entry<String, IndexedElement> entry : elementStorage.entrySet()) {
                String id = entry.getKey();
                String simpleName = extractSimpleName(id).toLowerCase();
                
                // Simple edit distance check
                if (Math.abs(simpleName.length() - query.length()) <= 2) {
                    int distance = calculateSimpleEditDistance(simpleName, query);
                    if (distance <= 2 && distance > 0) {
                        scores.put(id, scores.getOrDefault(id, 0f) + (3.0f - distance));
                    }
                }
            }
        }
        
        // Convert scores to results and sort
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Float> entry : scores.entrySet()) {
            IndexedElement element = elementStorage.get(entry.getKey());
            if (element != null) {
                results.add(new SearchResult(
                    element.id,
                    element.signature,
                    element.type,
                    element.filePath,
                    entry.getValue()
                ));
            }
        }
        
        // Sort by score descending
        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        
        // Limit results
        return results.stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    /**
     * Simple edit distance calculation for fuzzy matching.
     */
    private int calculateSimpleEditDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        
        if (Math.abs(len1 - len2) > 2) return Integer.MAX_VALUE;
        
        int[][] dp = new int[len1 + 1][len2 + 1];
        
        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], 
                                   Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        
        return dp[len1][len2];
    }
    
    /**
     * Tokenizes a signature for indexing.
     */
    protected Set<String> tokenizeSignature(String signature) {
        Set<String> tokens = new HashSet<>();
        // Extract identifiers from the signature
        String[] parts = signature.split("[^a-zA-Z0-9_]+");
        for (String part : parts) {
            if (part.length() > 1) {
                tokens.add(part.toLowerCase());
                tokens.addAll(tokenizeIdentifier(part));
            }
        }
        return tokens;
    }
    
    /**
     * Commits changes to the index.
     */
    public void commit() throws IOException {
        // No-op for in-memory index
    }
    
    /**
     * Extracts simple name from fully qualified identifier.
     */
    protected String extractSimpleName(String id) {
        // Handle method names (com.example.Class#method)
        if (id.contains("#")) {
            return id.substring(id.lastIndexOf("#") + 1);
        }
        
        // Handle class names (com.example.Class)
        if (id.contains(".")) {
            String lastPart = id.substring(id.lastIndexOf(".") + 1);
            // Check if it's likely a field (starts with lowercase)
            if (!lastPart.isEmpty() && Character.isLowerCase(lastPart.charAt(0))) {
                return lastPart;
            }
            // Otherwise, return the class name
            int secondLastDot = id.lastIndexOf(".", id.lastIndexOf(".") - 1);
            if (secondLastDot >= 0 && Character.isUpperCase(id.charAt(secondLastDot + 1))) {
                return id.substring(secondLastDot + 1);
            }
            return lastPart;
        }
        
        return id;
    }
    
    /**
     * Splits identifier into space-separated words.
     */
    protected String splitIdentifier(String identifier) {
        // Handle camelCase
        String result = CAMEL_CASE_PATTERN.matcher(identifier).replaceAll(" ");
        
        // Handle snake_case
        result = SNAKE_CASE_PATTERN.matcher(result).replaceAll(" ");
        
        // Handle dots
        result = DOT_PATTERN.matcher(result).replaceAll(" ");
        
        return result.toLowerCase().trim();
    }
    
    /**
     * Tokenizes identifier into individual words.
     */
    protected List<String> tokenizeIdentifier(String identifier) {
        String split = splitIdentifier(identifier);
        return Arrays.stream(split.split("\\s+"))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
    
    /**
     * Extracts package name from qualified identifier.
     */
    protected String extractPackage(String id) {
        int lastDot = id.lastIndexOf(".");
        int hashIndex = id.indexOf("#");
        
        if (hashIndex > 0) {
            // Method: extract package from class part
            String classPart = id.substring(0, hashIndex);
            lastDot = classPart.lastIndexOf(".");
            if (lastDot > 0) {
                return classPart.substring(0, lastDot);
            }
        } else if (lastDot > 0) {
            // Could be class or field
            String possiblePackage = id.substring(0, lastDot);
            // Check if last part starts with uppercase (class) or lowercase (field)
            String lastPart = id.substring(lastDot + 1);
            if (!lastPart.isEmpty() && Character.isUpperCase(lastPart.charAt(0))) {
                // It's a class, return package
                return possiblePackage;
            } else {
                // It's a field, need to extract package from class
                int secondLastDot = possiblePackage.lastIndexOf(".");
                if (secondLastDot > 0) {
                    return possiblePackage.substring(0, secondLastDot);
                }
            }
        }
        
        return "";
    }
    
    /**
     * Extracts class name from qualified identifier.
     */
    protected String extractClassName(String id) {
        if (id.contains("#")) {
            // Method: extract class name
            String classPart = id.substring(0, id.indexOf("#"));
            return classPart.substring(classPart.lastIndexOf(".") + 1);
        } else {
            // Could be class or field
            int lastDot = id.lastIndexOf(".");
            if (lastDot > 0) {
                String lastPart = id.substring(lastDot + 1);
                if (!lastPart.isEmpty() && Character.isUpperCase(lastPart.charAt(0))) {
                    // It's a class
                    return lastPart;
                } else {
                    // It's a field, extract class name
                    String beforeLast = id.substring(0, lastDot);
                    int secondLastDot = beforeLast.lastIndexOf(".");
                    if (secondLastDot >= 0) {
                        return beforeLast.substring(secondLastDot + 1);
                    }
                    return beforeLast;
                }
            }
        }
        return "";
    }
    
    /**
     * Closes the index and releases resources.
     */
    public void close() throws IOException {
        tokenIndex.clear();
        elementStorage.clear();
    }
    
    /**
     * Removes an element from the index.
     */
    public boolean removeElement(String id) {
        elementStorage.remove(id);
        
        // Remove from token index
        for (Map.Entry<String, Set<String>> entry : tokenIndex.entrySet()) {
            entry.getValue().remove(id);
        }
        
        return true;
    }
    
    /**
     * Storage class for indexed elements.
     */
    protected static class IndexedElement {
        final String id;
        final String signature;
        final String type;
        final String filePath;
        final Map<String, String> additionalFields;
        
        IndexedElement(String id, String signature, String type, String filePath, Map<String, String> additionalFields) {
            this.id = id;
            this.signature = signature;
            this.type = type;
            this.filePath = filePath;
            this.additionalFields = additionalFields;
        }
    }
    
    /**
     * Search result from name index.
     */
    public static class SearchResult {
        private final String id;
        private final String signature;
        private final String type;
        private final String filePath;
        private final float score;
        
        public SearchResult(String id, String signature, String type, String filePath, float score) {
            this.id = id;
            this.signature = signature;
            this.type = type;
            this.filePath = filePath;
            this.score = score;
        }
        
        // Getters
        public String getId() { return id; }
        public String getSignature() { return signature; }
        public String getType() { return type; }
        public String getFilePath() { return filePath; }
        public float getScore() { return score; }
    }
}
