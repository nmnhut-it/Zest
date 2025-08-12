package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import org.treesitter.*;


import java.util.*;

/**
 * Tree-sitter based AST code chunker for semantic code splitting.
 * Respects function, class, and method boundaries across multiple languages.
 */
public class ASTChunker {
    private static final Logger LOG = Logger.getInstance(ASTChunker.class);
    
    // Language parsers cache
    private static final Map<String, TSLanguage> LANGUAGE_CACHE = new HashMap<>();
    
    // Chunk size limits (in characters)
    private static final int DEFAULT_MAX_CHUNK_SIZE = 2000;
    private static final int MIN_CHUNK_SIZE = 100;
    
    // Semantic node types that make good chunk boundaries
    private static final Set<String> JAVA_CHUNK_BOUNDARIES = Set.of(
        "method_declaration", "constructor_declaration", "class_declaration", 
        "interface_declaration", "enum_declaration", "annotation_type_declaration"
    );
    
    private static final Set<String> JAVASCRIPT_CHUNK_BOUNDARIES = Set.of(
        "function_declaration", "function_expression", "arrow_function", 
        "class_declaration", "method_definition", "export_statement"
    );
    
    private static final Set<String> KOTLIN_CHUNK_BOUNDARIES = Set.of(
        "function_declaration", "class_declaration", "object_declaration",
        "interface_declaration", "property_declaration"
    );
    
    private static final Set<String> PYTHON_CHUNK_BOUNDARIES = Set.of(
        "function_definition", "class_definition", "decorated_definition"
    );
    
    private final int maxChunkSize;
    
    public ASTChunker() {
        this(DEFAULT_MAX_CHUNK_SIZE);
    }
    
    public ASTChunker(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }
    
    /**
     * Chunk code using AST-aware semantic boundaries
     */
    public List<CodeChunk> chunkCode(String content, String filePath) {
        try {
            String fileExtension = getFileExtension(filePath);
            TSLanguage language = getLanguageForExtension(fileExtension);
            
            if (language == null) {
                LOG.warn("Unsupported language for file: " + filePath + ", falling back to line-based chunking");
                return fallbackLineBasedChunking(content, filePath);
            }
            
            return performASTChunking(content, filePath, language, fileExtension);
            
        } catch (Exception e) {
            LOG.error("AST chunking failed for " + filePath + ", falling back to line-based chunking", e);
            return fallbackLineBasedChunking(content, filePath);
        }
    }
    
    /**
     * Perform AST-based chunking using tree-sitter
     */
    private List<CodeChunk> performASTChunking(String content, String filePath, TSLanguage language, String fileExtension) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        
        TSTree tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
                
        // Extract semantic chunks
        extractSemanticChunks(rootNode, content, filePath, fileExtension, chunks, 0);
        
        LOG.info("AST chunking completed for " + filePath + ": " + chunks.size() + " chunks");
        
        // Validate chunks
        return validateAndMergeChunks(chunks, content);
    }
    
    /**
     * Recursively extract semantic chunks from AST nodes
     */
    private void extractSemanticChunks(TSNode node, String content, String filePath, 
                                     String fileExtension, List<CodeChunk> chunks, int depth) {
        
        String nodeType = node.getType();
        String nodeText = getNodeText(node, content);
        
        // Skip empty or whitespace-only nodes
        if (nodeText.trim().isEmpty()) {
            return;
        }
        
        Set<String> boundaries = getChunkBoundariesForLanguage(fileExtension);
        
        // Check if this is a good chunk boundary
        if (boundaries.contains(nodeType) && nodeText.length() >= MIN_CHUNK_SIZE) {
            
            if (nodeText.length() <= maxChunkSize) {
                // Perfect size chunk
                chunks.add(createCodeChunk(nodeText, filePath, nodeType, 
                    getNodeStartLine(node, content), getNodeEndLine(node, content)));
                return;
                
            } else if (depth < 5) { // Prevent infinite recursion
                // Too large, try to break it down further
                boolean hasChildren = false;
                for (int i = 0; i < node.getChildCount(); i++) {
                    TSNode child = node.getChild(i);
                    extractSemanticChunks(child, content, filePath, fileExtension, chunks, depth + 1);
                    hasChildren = true;
                }
                
                // If we couldn't break it down, take it as is but split manually
                if (!hasChildren) {
                    chunks.addAll(splitLargeChunk(nodeText, filePath, nodeType, 
                        getNodeStartLine(node, content)));
                }
                return;
            }
        }
        
        // Continue recursively for non-boundary nodes or if we need more granularity
        if (node.getChildCount() > 0) {
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                extractSemanticChunks(child, content, filePath, fileExtension, chunks, depth);
            }
        } else if (nodeText.length() >= MIN_CHUNK_SIZE) {
            // Leaf node with enough content
            chunks.add(createCodeChunk(nodeText, filePath, nodeType,
                getNodeStartLine(node, content), getNodeEndLine(node, content)));
        }
    }
    
    /**
     * Get text content for a tree-sitter node
     */
    private String getNodeText(TSNode node, String content) {
        int startByte = node.getStartByte();
        int endByte = node.getEndByte();
        
        // Ensure bounds are valid
        if (startByte < 0 || endByte > content.length() || startByte >= endByte) {
            return "";
        }
        
        return content.substring(startByte, endByte);
    }
    
    /**
     * Get line number for node start
     */
    private int getNodeStartLine(TSNode node, String content) {
        TSPoint startPoint = node.getStartPoint();
        return startPoint.getRow() + 1; // Convert to 1-based line numbers
    }
    
    /**
     * Get line number for node end
     */
    private int getNodeEndLine(TSNode node, String content) {
        TSPoint endPoint = node.getEndPoint();
        return endPoint.getRow() + 1; // Convert to 1-based line numbers
    }
    
    /**
     * Create a CodeChunk with metadata
     */
    private CodeChunk createCodeChunk(String content, String filePath, String nodeType, 
                                    int startLine, int endLine) {
        return new CodeChunk(
            content,
            filePath,
            startLine,
            endLine,
            nodeType,
            content.length()
        );
    }
    
    /**
     * Split large chunks that exceed maxChunkSize
     */
    private List<CodeChunk> splitLargeChunk(String content, String filePath, String nodeType, int startLine) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int currentStart = 0;
        StringBuilder currentChunk = new StringBuilder();
        int currentLineNum = startLine;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            if (currentChunk.length() + line.length() > maxChunkSize && currentChunk.length() > MIN_CHUNK_SIZE) {
                // Create chunk from accumulated lines
                chunks.add(createCodeChunk(currentChunk.toString().trim(), filePath, 
                    nodeType + "_split", currentLineNum, currentLineNum + (i - currentStart)));
                
                // Start new chunk
                currentChunk = new StringBuilder();
                currentStart = i;
                currentLineNum = startLine + i;
            }
            
            currentChunk.append(line).append("\n");
        }
        
        // Add remaining content
        if (currentChunk.length() > MIN_CHUNK_SIZE) {
            chunks.add(createCodeChunk(currentChunk.toString().trim(), filePath,
                nodeType + "_split", currentLineNum, startLine + lines.length));
        }
        
        return chunks;
    }
    
    /**
     * Validate and merge small adjacent chunks
     */
    private List<CodeChunk> validateAndMergeChunks(List<CodeChunk> chunks, String originalContent) {
        List<CodeChunk> optimized = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk current = chunks.get(i);
            
            // If chunk is too small and can be merged with next
            if (current.getContent().length() < MIN_CHUNK_SIZE && i < chunks.size() - 1) {
                CodeChunk next = chunks.get(i + 1);
                
                if (current.getContent().length() + next.getContent().length() <= maxChunkSize) {
                    // Merge chunks
                    CodeChunk merged = new CodeChunk(
                        current.getContent() + "\n" + next.getContent(),
                        current.getFilePath(),
                        current.getStartLine(),
                        next.getEndLine(),
                        current.getNodeType() + "_merged",
                        current.getContent().length() + next.getContent().length()
                    );
                    optimized.add(merged);
                    i++; // Skip next chunk since we merged it
                    continue;
                }
            }
            
            optimized.add(current);
        }
        
        return optimized;
    }
    
    /**
     * Get tree-sitter language for file extension
     */
    private TSLanguage getLanguageForExtension(String extension) {
        return LANGUAGE_CACHE.computeIfAbsent(extension, ext -> {
            try {
                return switch (ext.toLowerCase()) {
                    case "java" -> new TreeSitterJava();
                    case "kt" -> new TreeSitterKotlin(); 
                    case "js", "jsx" -> new TreeSitterJavascript();
                    case "ts", "tsx" -> new TreeSitterTypescript();
                    // Note: TreeSitterPython not available, skip for now
                    default -> null;
                };
            } catch (Exception e) {
                LOG.warn("Failed to load tree-sitter language for: " + ext, e);
                return null;
            }
        });
    }
    
    /**
     * Get chunk boundaries for specific language
     */
    private Set<String> getChunkBoundariesForLanguage(String extension) {
        return switch (extension.toLowerCase()) {
            case "java" -> JAVA_CHUNK_BOUNDARIES;
            case "js", "jsx", "ts", "tsx" -> JAVASCRIPT_CHUNK_BOUNDARIES;
            case "kt" -> KOTLIN_CHUNK_BOUNDARIES;
            case "py" -> PYTHON_CHUNK_BOUNDARIES;
            default -> Set.of();
        };
    }
    
    /**
     * Fallback to line-based chunking when AST parsing fails
     */
    private List<CodeChunk> fallbackLineBasedChunking(String content, String filePath) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int linesPerChunk = Math.max(10, maxChunkSize / 50); // Estimate lines per chunk
        
        for (int i = 0; i < lines.length; i += linesPerChunk) {
            int endLine = Math.min(i + linesPerChunk, lines.length);
            
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < endLine; j++) {
                chunk.append(lines[j]).append("\n");
            }
            
            if (chunk.length() > MIN_CHUNK_SIZE) {
                chunks.add(createCodeChunk(chunk.toString().trim(), filePath, 
                    "line_based", i + 1, endLine));
            }
        }
        
        return chunks;
    }
    
    /**
     * Extract file extension from path
     */
    private String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        return lastDot > 0 ? filePath.substring(lastDot + 1) : "";
    }
    
    /**
     * Data class for code chunks with metadata
     */
    public static class CodeChunk {
        private final String content;
        private final String filePath;
        private final int startLine;
        private final int endLine;
        private final String nodeType;
        private final int size;
        
        public CodeChunk(String content, String filePath, int startLine, int endLine, 
                        String nodeType, int size) {
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