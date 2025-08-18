package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic code chunker that uses language-aware patterns to identify 
 * meaningful code boundaries (functions, classes, methods) without 
 * complex AST parsing dependencies.
 */
public class SemanticChunker {
    private static final Logger LOG = Logger.getInstance(SemanticChunker.class);
    
    private static final int DEFAULT_MAX_CHUNK_SIZE = 2000; // characters
    private static final int MIN_CHUNK_SIZE = 100;
    
    // Language-specific patterns for semantic boundaries
    private static final Map<String, List<Pattern>> LANGUAGE_PATTERNS = new HashMap<>();
    
    static {
        // Java/Kotlin patterns
        List<Pattern> javaPatterns = Arrays.asList(
            Pattern.compile("^\\s*(?:public|private|protected)?\\s*(?:static)?\\s*(?:final)?\\s*class\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:public|private|protected)?\\s*(?:static)?\\s*(?:synchronized)?\\s*(?:\\w+\\s+)?\\w+\\s*\\([^)]*\\)\\s*(?:throws\\s+[^{]+)?\\s*\\{", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:public|private|protected)?\\s*interface\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:public|private|protected)?\\s*enum\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s*fun\\s+\\w+\\s*\\([^)]*\\)", Pattern.MULTILINE) // Kotlin
        );
        LANGUAGE_PATTERNS.put("java", javaPatterns);
        LANGUAGE_PATTERNS.put("kt", javaPatterns);
        
        // JavaScript/TypeScript patterns
        List<Pattern> jsPatterns = Arrays.asList(
            Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:const|let|var)\\s+\\w+\\s*=\\s*(?:async\\s+)?\\([^)]*\\)\\s*=>", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:const|let|var)\\s+\\w+\\s*=\\s*function", Pattern.MULTILINE),
            Pattern.compile("^\\s*(?:export\\s+)?interface\\s+\\w+", Pattern.MULTILINE), // TypeScript
            Pattern.compile("^\\s*(?:export\\s+)?type\\s+\\w+", Pattern.MULTILINE) // TypeScript
        );
        LANGUAGE_PATTERNS.put("js", jsPatterns);
        LANGUAGE_PATTERNS.put("jsx", jsPatterns);
        LANGUAGE_PATTERNS.put("ts", jsPatterns);
        LANGUAGE_PATTERNS.put("tsx", jsPatterns);
        
        // Python patterns
        List<Pattern> pythonPatterns = Arrays.asList(
            Pattern.compile("^\\s*def\\s+\\w+\\s*\\([^)]*\\):", Pattern.MULTILINE),
            Pattern.compile("^\\s*class\\s+\\w+(?:\\([^)]*\\))?:", Pattern.MULTILINE),
            Pattern.compile("^\\s*@\\w+", Pattern.MULTILINE) // decorators
        );
        LANGUAGE_PATTERNS.put("py", pythonPatterns);
    }
    
    private final int maxChunkSize;
    
    public SemanticChunker() {
        this(DEFAULT_MAX_CHUNK_SIZE);
    }
    
    public SemanticChunker(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }
    
    /**
     * Chunk code using semantic boundaries
     */
    public List<CodeChunk> chunkCode(String content, String filePath) {
        try {
            String fileExtension = getFileExtension(filePath);
            
            if (!LANGUAGE_PATTERNS.containsKey(fileExtension)) {
                LOG.debug("No semantic patterns for " + fileExtension + ", using simple chunking");
                return simpleChunk(content, filePath);
            }
            
            return performSemanticChunking(content, filePath, fileExtension);
            
        } catch (Exception e) {
            LOG.error("Semantic chunking failed for " + filePath, e);
            return simpleChunk(content, filePath);
        }
    }
    
    /**
     * Perform semantic chunking using language patterns
     */
    private List<CodeChunk> performSemanticChunking(String content, String filePath, String fileExtension) {
        List<CodeChunk> chunks = new ArrayList<>();
        List<SemanticBoundary> boundaries = findSemanticBoundaries(content, fileExtension);
        
        if (boundaries.isEmpty()) {
            return simpleChunk(content, filePath);
        }
        
        // Sort boundaries by position
        boundaries.sort(Comparator.comparingInt(b -> b.startIndex));
        
        int lastEnd = 0;
        String[] lines = content.split("\n");
        
        for (SemanticBoundary boundary : boundaries) {
            // Find the end of this semantic unit (next boundary or end of file)
            int nextBoundaryStart = content.length();
            int currentBoundaryIndex = boundaries.indexOf(boundary);
            
            if (currentBoundaryIndex + 1 < boundaries.size()) {
                nextBoundaryStart = boundaries.get(currentBoundaryIndex + 1).startIndex;
            }
            
            // Extract content for this semantic unit
            String semanticContent = content.substring(boundary.startIndex, nextBoundaryStart).trim();
            
            if (semanticContent.length() >= MIN_CHUNK_SIZE) {
                if (semanticContent.length() <= maxChunkSize) {
                    // Perfect size chunk
                    chunks.add(new CodeChunk(
                        semanticContent,
                        filePath,
                        boundary.lineNumber,
                        getLineNumber(content, nextBoundaryStart),
                        boundary.type,
                        semanticContent.length()
                    ));
                } else {
                    // Too large, split further
                    chunks.addAll(splitLargeSemanticUnit(semanticContent, filePath, boundary));
                }
            }
            
            lastEnd = nextBoundaryStart;
        }
        
        // Handle any remaining content
        if (lastEnd < content.length()) {
            String remaining = content.substring(lastEnd).trim();
            if (remaining.length() >= MIN_CHUNK_SIZE) {
                chunks.add(new CodeChunk(
                    remaining,
                    filePath,
                    getLineNumber(content, lastEnd),
                    getLineNumber(content, content.length()),
                    "remainder",
                    remaining.length()
                ));
            }
        }
        
        LOG.debug("Semantic chunking created " + chunks.size() + " chunks for " + filePath);
        return chunks;
    }
    
    /**
     * Find semantic boundaries in the code
     */
    private List<SemanticBoundary> findSemanticBoundaries(String content, String fileExtension) {
        List<SemanticBoundary> boundaries = new ArrayList<>();
        List<Pattern> patterns = LANGUAGE_PATTERNS.get(fileExtension);
        
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                int lineNumber = getLineNumber(content, matcher.start());
                String matchText = matcher.group();
                String type = inferSemanticType(matchText, fileExtension);
                
                boundaries.add(new SemanticBoundary(matcher.start(), lineNumber, type, matchText));
            }
        }
        
        return boundaries;
    }
    
    /**
     * Infer the type of semantic unit from the matched text
     */
    private String inferSemanticType(String matchText, String fileExtension) {
        String lower = matchText.toLowerCase().trim();
        
        if (lower.contains("class ")) return "class_declaration";
        if (lower.contains("interface ")) return "interface_declaration";
        if (lower.contains("enum ")) return "enum_declaration";
        if (lower.contains("function ") || lower.contains("def ") || lower.contains("fun ")) return "function_declaration";
        if (lower.contains("=>")) return "arrow_function";
        if (lower.contains("@")) return "decorated_definition";
        
        return "code_block";
    }
    
    /**
     * Split large semantic units into smaller chunks
     */
    private List<CodeChunk> splitLargeSemanticUnit(String content, String filePath, SemanticBoundary boundary) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        
        StringBuilder currentChunk = new StringBuilder();
        int currentStartLine = boundary.lineNumber;
        int currentLineNum = 0;
        
        for (String line : lines) {
            if (currentChunk.length() + line.length() > maxChunkSize && currentChunk.length() > MIN_CHUNK_SIZE) {
                // Create chunk from accumulated content
                chunks.add(new CodeChunk(
                    currentChunk.toString().trim(),
                    filePath,
                    currentStartLine,
                    currentStartLine + currentLineNum,
                    boundary.type + "_split",
                    currentChunk.length()
                ));
                
                // Start new chunk
                currentChunk = new StringBuilder();
                currentStartLine = boundary.lineNumber + currentLineNum + 1;
            }
            
            currentChunk.append(line).append("\n");
            currentLineNum++;
        }
        
        // Add remaining content
        if (currentChunk.length() > MIN_CHUNK_SIZE) {
            chunks.add(new CodeChunk(
                currentChunk.toString().trim(),
                filePath,
                currentStartLine,
                boundary.lineNumber + lines.length,
                boundary.type + "_split",
                currentChunk.length()
            ));
        }
        
        return chunks;
    }
    
    /**
     * Simple fallback chunking
     */
    private List<CodeChunk> simpleChunk(String content, String filePath) {
        List<CodeChunk> chunks = new ArrayList<>();
        int chunkSize = maxChunkSize;
        
        for (int i = 0; i < content.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, content.length());
            String chunk = content.substring(i, end);
            
            if (chunk.trim().length() >= MIN_CHUNK_SIZE) {
                chunks.add(new CodeChunk(
                    chunk,
                    filePath,
                    getLineNumber(content, i),
                    getLineNumber(content, end),
                    "simple_chunk",
                    chunk.length()
                ));
            }
        }
        
        return chunks;
    }
    
    /**
     * Get line number for character position
     */
    private int getLineNumber(String content, int charPosition) {
        if (charPosition >= content.length()) {
            charPosition = content.length() - 1;
        }
        if (charPosition < 0) {
            return 1;
        }
        
        String upToPosition = content.substring(0, charPosition);
        return upToPosition.split("\n").length;
    }
    
    /**
     * Extract file extension
     */
    private String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        return lastDot > 0 ? filePath.substring(lastDot + 1).toLowerCase() : "";
    }
    
    /**
     * Represents a semantic boundary in code
     */
    private static class SemanticBoundary {
        final int startIndex;
        final int lineNumber;
        final String type;
        final String matchText;
        
        SemanticBoundary(int startIndex, int lineNumber, String type, String matchText) {
            this.startIndex = startIndex;
            this.lineNumber = lineNumber;
            this.type = type;
            this.matchText = matchText;
        }
    }
    
    /**
     * Data class for code chunks
     */
    public static class CodeChunk {
        private final String content;
        private final String filePath;
        private final int startLine;
        private final int endLine;
        private final String nodeType;
        private final int size;
        
        public CodeChunk(String content, String filePath, int startLine, int endLine, String nodeType, int size) {
            this.content = content;
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.nodeType = nodeType;
            this.size = size;
        }
        
        // Getters
        public String getContent() { return content; }
        public String getFilePath() { return filePath; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public String getNodeType() { return nodeType; }
        public int getSize() { return size; }
        
        @Override
        public String toString() {
            return String.format("CodeChunk[%s:%d-%d, type=%s, size=%d]", 
                filePath, startLine, endLine, nodeType, size);
        }
    }
}