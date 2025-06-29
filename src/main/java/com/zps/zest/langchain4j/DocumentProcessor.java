package com.zps.zest.langchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Processes documents for embedding, handling various file types and chunking strategies.
 */
public class DocumentProcessor {
    private static final Logger LOG = Logger.getInstance(DocumentProcessor.class);
    
    private final DocumentSplitter defaultSplitter;
    private final DocumentSplitter codeSplitter;
    
    // Chunking parameters
    private static final int DEFAULT_CHUNK_SIZE = 300;
    private static final int DEFAULT_CHUNK_OVERLAP = 50;
    private static final int CODE_CHUNK_SIZE = 400;
    private static final int CODE_CHUNK_OVERLAP = 100;
    
    public DocumentProcessor() {
        // Default splitter for general documents
        this.defaultSplitter = DocumentSplitters.recursive(
            DEFAULT_CHUNK_SIZE,
            DEFAULT_CHUNK_OVERLAP
        );
        
        // Code-specific splitter with larger chunks
        this.codeSplitter = DocumentSplitters.recursive(
            CODE_CHUNK_SIZE,
            CODE_CHUNK_OVERLAP
        );
    }
    
    /**
     * Processes a VirtualFile into text segments ready for embedding.
     * 
     * @param file The file to process
     * @return List of text segments
     */
    public List<TextSegment> processFile(VirtualFile file) {
        try {
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            Document document = Document.from(content, createMetadata(file));
            
            // Choose appropriate splitter based on file type
            DocumentSplitter splitter = isCodeFile(file) ? codeSplitter : defaultSplitter;
            
            return splitter.split(document);
        } catch (IOException e) {
            LOG.error("Failed to process file: " + file.getPath(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Processes a PsiFile with code-aware chunking.
     * 
     * @param psiFile The PSI file to process
     * @param signatures Pre-extracted code signatures for enrichment
     * @return List of enriched text segments
     */
    public List<TextSegment> processPsiFile(PsiFile psiFile, List<com.zps.zest.rag.CodeSignature> signatures) {
        List<TextSegment> segments = new ArrayList<>();
        
        // Add file overview segment
        StringBuilder overview = new StringBuilder();
        overview.append("File: ").append(psiFile.getName()).append("\n");
        overview.append("Path: ").append(psiFile.getVirtualFile().getPath()).append("\n\n");
        
        if (!signatures.isEmpty()) {
            overview.append("Contains:\n");
            for (com.zps.zest.rag.CodeSignature sig : signatures) {
                overview.append("- ").append(sig.getSignature()).append("\n");
            }
        }
        
        segments.add(TextSegment.from(
            overview.toString(),
            createMetadata(psiFile.getVirtualFile(), "overview")
        ));
        
        // Add the full file content in chunks
        String content = psiFile.getText();
        Document document = Document.from(content, createMetadata(psiFile.getVirtualFile(), "code"));
        segments.addAll(codeSplitter.split(document));
        
        return segments;
    }
    
    /**
     * Processes a markdown document with special handling for code blocks.
     * 
     * @param content The markdown content
     * @param fileName The file name for metadata
     * @return List of text segments
     */
    public List<TextSegment> processMarkdown(String content, String fileName) {
        Document document = Document.from(content, createMetadata(fileName, "markdown"));
        
        // Use smaller chunks for markdown to preserve structure
        DocumentSplitter markdownSplitter = DocumentSplitters.recursive(
            250, 
            40
        );
        
        return markdownSplitter.split(document);
    }
    
    /**
     * Processes arbitrary document types using Apache Tika.
     * 
     * @param inputStream Document input stream
     * @param fileName File name
     * @param mimeType MIME type if known
     * @return List of text segments
     */
    public List<TextSegment> processDocument(InputStream inputStream, String fileName, String mimeType) {
        try {
            DocumentParser parser = new ApacheTikaDocumentParser();
            Document document = parser.parse(inputStream);
            
            // Add filename to metadata
            document.metadata().add("filename", fileName);
            if (mimeType != null) {
                document.metadata().add("mime_type", mimeType);
            }
            
            return defaultSplitter.split(document);
        } catch (Exception e) {
            LOG.error("Failed to parse document: " + fileName, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Creates a single text segment from a string without splitting.
     * Useful for short texts like method signatures.
     * 
     * @param text The text content
     * @param metadata Additional metadata
     * @return A single text segment
     */
    public TextSegment createSegment(String text, java.util.Map<String, String> metadata) {
        Metadata md = new Metadata();
        for (java.util.Map.Entry<String, String> entry : metadata.entrySet()) {
            md.add(entry.getKey(), entry.getValue());
        }
        return TextSegment.from(text, md);
    }
    
    /**
     * Estimates the number of tokens in a text.
     * Simple approximation: ~4 characters per token
     * 
     * @param text The text to analyze
     * @return Estimated token count
     */
    public int estimateTokens(String text) {
        // Simple estimation: average 4 characters per token
        return text.length() / 4;
    }
    
    private Metadata createMetadata(VirtualFile file) {
        return createMetadata(file, detectFileType(file));
    }
    
    private Metadata createMetadata(VirtualFile file, String type) {
        Metadata metadata = new Metadata();
        metadata.add("filename", file.getName());
        metadata.add("path", file.getPath());
        metadata.add("type", type);
        metadata.add("extension", file.getExtension() != null ? file.getExtension() : "");
        return metadata;
    }
    
    private Metadata createMetadata(String fileName, String type) {
        Metadata metadata = new Metadata();
        metadata.add("filename", fileName);
        metadata.add("type", type);
        return metadata;
    }
    
    private boolean isCodeFile(VirtualFile file) {
        String extension = file.getExtension();
        return extension != null && (
            extension.equals("java") ||
            extension.equals("kt") ||
            extension.equals("kts") ||
            extension.equals("py") ||
            extension.equals("js") ||
            extension.equals("ts") ||
            extension.equals("cpp") ||
            extension.equals("c") ||
            extension.equals("h") ||
            extension.equals("cs") ||
            extension.equals("go") ||
            extension.equals("rs") ||
            extension.equals("rb") ||
            extension.equals("php")
        );
    }
    
    private String detectFileType(VirtualFile file) {
        if (isCodeFile(file)) return "code";
        
        String extension = file.getExtension();
        if (extension == null) return "text";
        
        switch (extension.toLowerCase()) {
            case "md":
            case "markdown":
                return "markdown";
            case "pdf":
                return "pdf";
            case "doc":
            case "docx":
                return "document";
            case "txt":
                return "text";
            case "xml":
            case "json":
            case "yaml":
            case "yml":
                return "config";
            default:
                return "unknown";
        }
    }
}
