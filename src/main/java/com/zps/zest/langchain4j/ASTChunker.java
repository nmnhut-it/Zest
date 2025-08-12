package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.chunking.*;
import org.treesitter.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tree-sitter based AST code chunker for semantic code splitting.
 * Respects function, class, and method boundaries across multiple languages.
 */
public class ASTChunker implements CodeChunker {
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
        ChunkingOptions options = ChunkingOptions.builder()
            .maxChunkSize(maxChunkSize)
            .minChunkSize(MIN_CHUNK_SIZE)
            .preserveSemanticBoundaries(true)
            .extractMetadata(true)
            .build();
            
        // Get new format chunks and convert to legacy format
        List<com.zps.zest.chunking.CodeChunk> newChunks = chunk(content, filePath, options);
        return newChunks.stream()
            .map(this::convertToLegacyChunk)
            .collect(Collectors.toList());
    }
    
    private CodeChunk convertToLegacyChunk(com.zps.zest.chunking.CodeChunk newChunk) {
        String nodeType = (String) newChunk.getMetadata().get("astNodeType");
        if (nodeType == null) {
            nodeType = newChunk.getType().name().toLowerCase();
        }
        
        return new CodeChunk(
            newChunk.getContent(),
            newChunk.getFilePath(),
            newChunk.getStartLine(),
            newChunk.getEndLine(),
            nodeType,
            newChunk.getContent().length()
        );
    }
    
    @Override
    public List<com.zps.zest.chunking.CodeChunk> chunk(String content, String filePath, ChunkingOptions options) {
        try {
            String fileExtension = getFileExtension(filePath);
            TSLanguage language = getLanguageForExtension(fileExtension);
            
            if (language == null) {
                LOG.warn("Unsupported language for file: " + filePath + ", falling back to line-based chunking");
                return fallbackLineBasedChunking(content, filePath, options);
            }
            
            return performASTChunking(content, filePath, language, fileExtension, options);
            
        } catch (Exception e) {
            LOG.error("AST chunking failed for " + filePath + ", falling back to line-based chunking", e);
            return fallbackLineBasedChunking(content, filePath, options);
        }
    }
    
    @Override
    public int getOptimalChunkSize() {
        return maxChunkSize;
    }
    
    @Override
    public boolean supports(String filePath) {
        String ext = getFileExtension(filePath).toLowerCase();
        return Set.of("java", "kt", "js", "jsx", "ts", "tsx").contains(ext);
    }
    
    /**
     * Perform AST-based chunking using tree-sitter
     */
    private List<com.zps.zest.chunking.CodeChunk> performASTChunking(String content, String filePath, 
                                                                     TSLanguage language, String fileExtension,
                                                                     ChunkingOptions options) {
        List<com.zps.zest.chunking.CodeChunk> chunks = new ArrayList<>();
        
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        
        TSTree tree = parser.parseString(null, content);
        TSNode rootNode = tree.getRootNode();
                
        // Extract semantic chunks
        extractSemanticChunks(rootNode, content, filePath, fileExtension, chunks, options, 0);
        
        LOG.info("AST chunking completed for " + filePath + ": " + chunks.size() + " chunks");
        
        // Validate chunks
        return validateAndMergeChunks(chunks, options);
    }
    
    /**
     * Recursively extract semantic chunks from AST nodes
     */
    private void extractSemanticChunks(TSNode node, String content, String filePath, 
                                     String fileExtension, List<com.zps.zest.chunking.CodeChunk> chunks,
                                     ChunkingOptions options, int depth) {
        
        String nodeType = node.getType();
        String nodeText = getNodeText(node, content);
        
        // Skip empty or whitespace-only nodes
        if (nodeText.trim().isEmpty()) {
            return;
        }
        
        Set<String> boundaries = getChunkBoundariesForLanguage(fileExtension);
        
        // Check if this is a good chunk boundary
        if (boundaries.contains(nodeType) && nodeText.length() >= MIN_CHUNK_SIZE) {
            
            if (nodeText.length() <= options.getMaxChunkSize()) {
                // Perfect size chunk
                chunks.add(createCodeChunk(nodeText, filePath, nodeType, 
                    getNodeStartLine(node, content), getNodeEndLine(node, content), options));
                return;
                
            } else if (depth < 5) { // Prevent infinite recursion
                // Too large, try to break it down further
                boolean hasChildren = false;
                for (int i = 0; i < node.getChildCount(); i++) {
                    TSNode child = node.getChild(i);
                    extractSemanticChunks(child, content, filePath, fileExtension, chunks, options, depth + 1);
                    hasChildren = true;
                }
                
                // If we couldn't break it down, take it as is but split manually
                if (!hasChildren) {
                    chunks.addAll(splitLargeChunk(nodeText, filePath, nodeType, 
                        getNodeStartLine(node, content), options));
                }
                return;
            }
        }
        
        // Continue recursively for non-boundary nodes or if we need more granularity
        if (node.getChildCount() > 0) {
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                extractSemanticChunks(child, content, filePath, fileExtension, chunks, options, depth);
            }
        } else if (nodeText.length() >= options.getMinChunkSize()) {
            // Leaf node with enough content
            chunks.add(createCodeChunk(nodeText, filePath, nodeType,
                getNodeStartLine(node, content), getNodeEndLine(node, content), options));
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
    private com.zps.zest.chunking.CodeChunk createCodeChunk(String content, String filePath, String nodeType, 
                                    int startLine, int endLine, ChunkingOptions options) {
        Map<String, Object> metadata = new HashMap<>();
        if (options.isExtractMetadata()) {
            metadata.put("astNodeType", nodeType);
        }
        
        com.zps.zest.chunking.CodeChunk.ChunkType type = mapNodeTypeToChunkType(nodeType);
        
        return new com.zps.zest.chunking.CodeChunk(
            content,
            filePath,
            startLine,
            endLine,
            type,
            metadata
        );
    }
    
    private com.zps.zest.chunking.CodeChunk.ChunkType mapNodeTypeToChunkType(String nodeType) {
        if (nodeType.contains("method")) return com.zps.zest.chunking.CodeChunk.ChunkType.METHOD;
        if (nodeType.contains("function")) return com.zps.zest.chunking.CodeChunk.ChunkType.FUNCTION;
        if (nodeType.contains("constructor")) return com.zps.zest.chunking.CodeChunk.ChunkType.CONSTRUCTOR;
        if (nodeType.contains("class")) return com.zps.zest.chunking.CodeChunk.ChunkType.CLASS;
        if (nodeType.contains("interface")) return com.zps.zest.chunking.CodeChunk.ChunkType.INTERFACE;
        if (nodeType.contains("import")) return com.zps.zest.chunking.CodeChunk.ChunkType.IMPORT_BLOCK;
        if (nodeType.contains("field") || nodeType.contains("property")) return com.zps.zest.chunking.CodeChunk.ChunkType.FIELD_BLOCK;
        if (nodeType.contains("module")) return com.zps.zest.chunking.CodeChunk.ChunkType.MODULE;
        return com.zps.zest.chunking.CodeChunk.ChunkType.UNKNOWN;
    }
    
    /**
     * Split large chunks that exceed maxChunkSize
     */
    private List<com.zps.zest.chunking.CodeChunk> splitLargeChunk(String content, String filePath, 
                                                                   String nodeType, int startLine,
                                                                   ChunkingOptions options) {
        List<com.zps.zest.chunking.CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int currentStart = 0;
        StringBuilder currentChunk = new StringBuilder();
        int currentLineNum = startLine;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            if (currentChunk.length() + line.length() > options.getMaxChunkSize() && 
                currentChunk.length() > options.getMinChunkSize()) {
                // Create chunk from accumulated lines
                chunks.add(createCodeChunk(currentChunk.toString().trim(), filePath, 
                    nodeType + "_split", currentLineNum, currentLineNum + (i - currentStart), options));
                
                // Start new chunk
                currentChunk = new StringBuilder();
                currentStart = i;
                currentLineNum = startLine + i;
            }
            
            currentChunk.append(line).append("\n");
        }
        
        // Add remaining content
        if (currentChunk.length() > options.getMinChunkSize()) {
            chunks.add(createCodeChunk(currentChunk.toString().trim(), filePath,
                nodeType + "_split", currentLineNum, startLine + lines.length, options));
        }
        
        return chunks;
    }
    
    /**
     * Validate and merge small adjacent chunks
     */
    private List<com.zps.zest.chunking.CodeChunk> validateAndMergeChunks(List<com.zps.zest.chunking.CodeChunk> chunks, 
                                                                         ChunkingOptions options) {
        List<com.zps.zest.chunking.CodeChunk> optimized = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            com.zps.zest.chunking.CodeChunk current = chunks.get(i);
            
            // If chunk is too small and can be merged with next
            if (current.getContent().length() < options.getMinChunkSize() && i < chunks.size() - 1) {
                com.zps.zest.chunking.CodeChunk next = chunks.get(i + 1);
                
                if (current.getContent().length() + next.getContent().length() <= options.getMaxChunkSize()) {
                    // Merge chunks
                    Map<String, Object> mergedMetadata = new HashMap<>();
                    mergedMetadata.putAll(current.getMetadata());
                    mergedMetadata.put("merged", true);
                    
                    com.zps.zest.chunking.CodeChunk merged = new com.zps.zest.chunking.CodeChunk(
                        current.getContent() + "\n" + next.getContent(),
                        current.getFilePath(),
                        current.getStartLine(),
                        next.getEndLine(),
                        current.getType(),
                        mergedMetadata
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
     * @deprecated Use TreeSitterChunker instead
     */
    @Deprecated
    private TSLanguage getLanguageForExtension(String extension) {
        return LANGUAGE_CACHE.computeIfAbsent(extension, ext -> {
            try {
                return switch (ext.toLowerCase()) {
                    case "java" -> new TreeSitterJava();
                    case "kt" -> new TreeSitterKotlin(); 
                    case "js", "jsx" -> new TreeSitterJavascript();
                    case "ts", "tsx" -> new TreeSitterTypescript();
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
    private List<com.zps.zest.chunking.CodeChunk> fallbackLineBasedChunking(String content, String filePath,
                                                                            ChunkingOptions options) {
        List<com.zps.zest.chunking.CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int linesPerChunk = Math.max(10, options.getMaxChunkSize() / 50); // Estimate lines per chunk
        
        for (int i = 0; i < lines.length; i += linesPerChunk) {
            int endLine = Math.min(i + linesPerChunk, lines.length);
            
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < endLine; j++) {
                chunk.append(lines[j]).append("\n");
            }
            
            if (chunk.length() > options.getMinChunkSize()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("astNodeType", "line_based");
                
                chunks.add(new com.zps.zest.chunking.CodeChunk(
                    chunk.toString().trim(),
                    filePath,
                    i + 1,
                    endLine,
                    com.zps.zest.chunking.CodeChunk.ChunkType.UNKNOWN,
                    metadata
                ));
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