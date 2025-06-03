package com.zps.zest.langchain4j.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zps.zest.rag.CodeSignature;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for common operations in code search functionality.
 * Consolidates duplicated logic across search services.
 */
public final class CodeSearchUtils {
    
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "what", "where",
        "how", "when", "which", "who", "why", "are", "was", "were", "been"
    );
    
    private CodeSearchUtils() {
        // Utility class, prevent instantiation
    }
    
    /**
     * Extracts the type from a CodeSignature's metadata.
     * 
     * @param signature The code signature
     * @return The type string or "unknown" if not found
     */
    public static String extractSignatureType(CodeSignature signature) {
        JsonObject metadata = parseSignatureMetadata(signature);
        return metadata != null && metadata.has("type") 
            ? metadata.get("type").getAsString() 
            : "unknown";
    }
    
    /**
     * Safely parses the metadata JSON from a CodeSignature.
     * 
     * @param signature The code signature
     * @return The parsed JsonObject or null if parsing fails
     */
    public static JsonObject parseSignatureMetadata(CodeSignature signature) {
        if (signature == null || signature.getMetadata() == null) {
            return null;
        }
        
        try {
            return JsonParser.parseString(signature.getMetadata()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extracts metadata fields from a CodeSignature.
     * 
     * @param signature The code signature
     * @return A map of metadata fields
     */
    public static Map<String, String> extractSignatureMetadata(CodeSignature signature) {
        Map<String, String> result = new HashMap<>();
        JsonObject metadata = parseSignatureMetadata(signature);
        
        if (metadata != null) {
            // Extract common fields
            addIfPresent(metadata, result, "type");
            addIfPresent(metadata, result, "javadoc");
            addIfPresent(metadata, result, "package", "package_name");
            addIfPresent(metadata, result, "class", "class_name");
            addIfPresent(metadata, result, "name", "element_name");
            addIfPresent(metadata, result, "qualifiedName", "qualified_name");
        }
        
        return result;
    }
    
    /**
     * Builds a text representation of a CodeSignature for indexing.
     * 
     * @param signature The code signature
     * @return A formatted string representation
     */
    public static String buildSignatureContent(CodeSignature signature) {
        StringBuilder sb = new StringBuilder();
        sb.append(signature.getSignature()).append("\n");
        sb.append("ID: ").append(signature.getId()).append("\n");
        
        JsonObject metadata = parseSignatureMetadata(signature);
        if (metadata != null && metadata.has("javadoc")) {
            String javadoc = metadata.get("javadoc").getAsString();
            if (javadoc != null && !javadoc.isEmpty()) {
                sb.append("\n").append(javadoc);
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Extracts keywords from a search query.
     * 
     * @param query The search query
     * @return List of keywords (excluding stop words)
     */
    public static List<String> extractKeywords(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        
        return Arrays.stream(query.toLowerCase().split("\\s+"))
            .filter(word -> word.length() > 2)
            .filter(word -> !isStopWord(word))
            .distinct()
            .collect(Collectors.toList());
    }
    
    /**
     * Checks if a word is a stop word.
     * 
     * @param word The word to check
     * @return true if it's a stop word
     */
    public static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase());
    }
    
    /**
     * Creates a metadata map for vector storage from a CodeSignature.
     * 
     * @param signature The code signature
     * @param additionalMetadata Additional metadata to include
     * @return Combined metadata map
     */
    public static Map<String, Object> createVectorMetadata(CodeSignature signature, 
                                                           Map<String, Object> additionalMetadata) {
        Map<String, Object> metadata = new HashMap<>();
        
        // Add signature fields
        metadata.put("signature_id", signature.getId());
        metadata.put("file_path", signature.getFilePath());
        metadata.put("type", extractSignatureType(signature));
        
        // Add extracted metadata
        Map<String, String> extractedMetadata = extractSignatureMetadata(signature);
        metadata.putAll(extractedMetadata);
        
        // Add additional metadata
        if (additionalMetadata != null) {
            metadata.putAll(additionalMetadata);
        }
        
        return metadata;
    }
    
    /**
     * Validates a CodeSignature before indexing.
     * 
     * @param signature The signature to validate
     * @return true if valid for indexing
     */
    public static boolean isValidForIndexing(CodeSignature signature) {
        return signature != null 
            && signature.getId() != null 
            && !signature.getId().isEmpty()
            && signature.getSignature() != null;
    }
    
    /**
     * Detects if a query suggests structural relationships.
     * 
     * @param query The search query
     * @return true if structural search should be performed
     */
    public static boolean suggestsStructuralSearch(String query) {
        if (query == null) return false;
        
        String lower = query.toLowerCase();
        return lower.contains("call") || lower.contains("use") || 
               lower.contains("extend") || lower.contains("implement") ||
               lower.contains("override") || lower.contains("inherit") ||
               lower.contains("depend");
    }
    
    /**
     * Normalizes a file path for consistent indexing.
     * 
     * @param path The file path
     * @return Normalized path
     */
    public static String normalizeFilePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/');
    }
    
    // Helper method to add metadata field with optional rename
    private static void addIfPresent(JsonObject metadata, Map<String, String> result, 
                                    String jsonKey, String... mapKey) {
        if (metadata.has(jsonKey) && !metadata.get(jsonKey).isJsonNull()) {
            String key = mapKey.length > 0 ? mapKey[0] : jsonKey;
            result.put(key, metadata.get(jsonKey).getAsString());
        }
    }
}
