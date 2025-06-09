package com.zps.zest.langchain4j.index;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Markdown-aware document splitter that preserves document structure.
 * Splits documents by headers while maintaining context and hierarchy.
 */
public class MarkdownDocumentSplitter implements DocumentSplitter {
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([^\\n]*)\\n([^`]+)```", Pattern.DOTALL);
    
    private final int maxSegmentSize;
    private final int overlapSize;
    
    public MarkdownDocumentSplitter(int maxSegmentSize, int overlapSize) {
        this.maxSegmentSize = maxSegmentSize;
        this.overlapSize = overlapSize;
    }
    
    @Override
    public List<TextSegment> split(Document document) {
        String content = document.text();
        Metadata baseMetadata = document.metadata();
        
        List<TextSegment> segments = new ArrayList<>();
        
        // Extract and replace code blocks temporarily
        Map<String, CodeBlock> codeBlocks = extractCodeBlocks(content);
        String contentWithoutCode = content;
        for (String placeholder : codeBlocks.keySet()) {
            contentWithoutCode = contentWithoutCode.replace(codeBlocks.get(placeholder).originalText, placeholder);
        }
        
        // Split by headers
        List<Section> sections = splitByHeaders(contentWithoutCode);
        
        // Create segments with context
        for (Section section : sections) {
            // Restore code blocks in section content
            String restoredContent = section.content;
            for (Map.Entry<String, CodeBlock> entry : codeBlocks.entrySet()) {
                restoredContent = restoredContent.replace(entry.getKey(), entry.getValue().originalText);
            }
            section.content = restoredContent;
            
            // Add header hierarchy to metadata
            Metadata metadata = new Metadata(baseMetadata.asMap());
            metadata.add("section_level", String.valueOf(section.level));
            metadata.add("section_header", section.header);
            metadata.add("parent_headers", String.join(" > ", section.parentHeaders));
            metadata.add("type", "documentation");
            
            // Include parent context in content for better search
            String contextualContent = buildContextualContent(section);
            
            // If content is too long, split further
            if (contextualContent.length() > maxSegmentSize) {
                segments.addAll(splitLongSection(contextualContent, metadata));
            } else {
                segments.add(TextSegment.from(contextualContent, metadata));
            }
        }
        
        // Add code blocks as separate segments
        for (CodeBlock codeBlock : codeBlocks.values()) {
            Metadata codeMetadata = new Metadata(baseMetadata.asMap());
            codeMetadata.add("type", "code_block");
            codeMetadata.add("language", codeBlock.language);
            codeMetadata.add("parent_section", codeBlock.parentSection);
            segments.add(TextSegment.from(codeBlock.code, codeMetadata));
        }
        
        return segments;
    }
    
    private Map<String, CodeBlock> extractCodeBlocks(String content) {
        Map<String, CodeBlock> blocks = new LinkedHashMap<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        int blockIndex = 0;
        
        while (matcher.find()) {
            String language = matcher.group(1).trim();
            String code = matcher.group(2);
            String placeholder = "[[CODE_BLOCK_" + blockIndex + "]]";
            
            // Find parent section
            String beforeCode = content.substring(0, matcher.start());
            String parentSection = findLastHeader(beforeCode);
            
            blocks.put(placeholder, new CodeBlock(
                code, 
                language.isEmpty() ? "text" : language, 
                parentSection,
                matcher.group(0)
            ));
            blockIndex++;
        }
        
        return blocks;
    }
    
    private String findLastHeader(String content) {
        Matcher matcher = HEADER_PATTERN.matcher(content);
        String lastHeader = "Document";
        while (matcher.find()) {
            lastHeader = matcher.group(2);
        }
        return lastHeader;
    }
    
    private List<Section> splitByHeaders(String content) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADER_PATTERN.matcher(content);
        
        List<HeaderMatch> headers = new ArrayList<>();
        while (matcher.find()) {
            headers.add(new HeaderMatch(
                matcher.start(),
                matcher.end(),
                matcher.group(1).length(),
                matcher.group(2)
            ));
        }
        
        // Add document start if no header at beginning
        if (headers.isEmpty() || headers.get(0).start > 0) {
            Section introSection = new Section();
            introSection.header = "Introduction";
            introSection.level = 1;
            introSection.parentHeaders = new ArrayList<>();
            introSection.content = headers.isEmpty() ? 
                content : content.substring(0, headers.get(0).start).trim();
            if (!introSection.content.isEmpty()) {
                sections.add(introSection);
            }
        }
        
        // Process each header
        for (int i = 0; i < headers.size(); i++) {
            HeaderMatch current = headers.get(i);
            Section section = new Section();
            section.header = current.text;
            section.level = current.level;
            section.parentHeaders = findParentHeaders(headers, i);
            
            // Extract content until next header or end
            int contentStart = current.end;
            int contentEnd = (i + 1 < headers.size()) ? 
                headers.get(i + 1).start : content.length();
            
            section.content = content.substring(contentStart, contentEnd).trim();
            
            if (!section.content.isEmpty() || !section.header.isEmpty()) {
                sections.add(section);
            }
        }
        
        return sections;
    }
    
    private List<String> findParentHeaders(List<HeaderMatch> headers, int currentIndex) {
        List<String> parents = new ArrayList<>();
        HeaderMatch current = headers.get(currentIndex);
        
        // Look backwards for headers with lower level (higher in hierarchy)
        for (int i = currentIndex - 1; i >= 0; i--) {
            HeaderMatch candidate = headers.get(i);
            if (candidate.level < current.level) {
                // This is a parent
                parents.add(0, candidate.text);
                current = candidate; // Update current to find grandparents
            }
        }
        
        return parents;
    }
    
    private String buildContextualContent(Section section) {
        StringBuilder content = new StringBuilder();
        
        // Add breadcrumb-style context
        if (!section.parentHeaders.isEmpty()) {
            content.append(String.join(" > ", section.parentHeaders))
                   .append(" > ")
                   .append(section.header)
                   .append("\n\n");
        } else {
            content.append(section.header).append("\n\n");
        }
        
        // Add content
        content.append(section.content);
        
        return content.toString();
    }
    
    private List<TextSegment> splitLongSection(String content, Metadata metadata) {
        List<TextSegment> segments = new ArrayList<>();
        
        // Simple paragraph-based splitting for long sections
        String[] paragraphs = content.split("\n\n");
        StringBuilder currentSegment = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            if (currentSegment.length() + paragraph.length() > maxSegmentSize && 
                currentSegment.length() > 0) {
                // Save current segment
                segments.add(TextSegment.from(currentSegment.toString().trim(), metadata));
                
                // Start new segment with overlap
                if (overlapSize > 0 && currentSegment.length() > overlapSize) {
                    String overlap = currentSegment.substring(
                        currentSegment.length() - overlapSize
                    );
                    currentSegment = new StringBuilder(overlap).append("\n\n");
                } else {
                    currentSegment = new StringBuilder();
                }
            }
            
            currentSegment.append(paragraph).append("\n\n");
        }
        
        // Add remaining content
        if (currentSegment.length() > 0) {
            segments.add(TextSegment.from(currentSegment.toString().trim(), metadata));
        }
        
        return segments;
    }
    
    private static class Section {
        String header;
        String content;
        int level;
        List<String> parentHeaders;
    }
    
    private static class HeaderMatch {
        final int start;
        final int end;
        final int level;
        final String text;
        
        HeaderMatch(int start, int end, int level, String text) {
            this.start = start;
            this.end = end;
            this.level = level;
            this.text = text;
        }
    }
    
    private static class CodeBlock {
        final String code;
        final String language;
        final String parentSection;
        final String originalText;
        
        CodeBlock(String code, String language, String parentSection, String originalText) {
            this.code = code;
            this.language = language;
            this.parentSection = parentSection;
            this.originalText = originalText;
        }
    }
}
