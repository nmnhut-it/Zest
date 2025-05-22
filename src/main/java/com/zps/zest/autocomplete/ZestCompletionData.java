package com.zps.zest.autocomplete;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.util.TextRange;

/**
 * Enhanced completion data structures following Tabby ML patterns.
 */
public class ZestCompletionData {

    /**
     * Represents a completion item with replace range support.
     */
    public static class CompletionItem {
        private final String insertText;
        private final Range replaceRange;
        private final String eventId;
        private final double confidence;
        
        public CompletionItem(String insertText, Range replaceRange, String eventId, double confidence) {
            this.insertText = insertText;
            this.replaceRange = replaceRange;
            this.eventId = eventId;
            this.confidence = confidence;
        }
        
        public CompletionItem(String insertText, int cursorOffset) {
            this.insertText = insertText;
            this.replaceRange = new Range(cursorOffset, cursorOffset);
            this.eventId = null;
            this.confidence = 1.0;
        }
        
        public String getInsertText() { return insertText; }
        public Range getReplaceRange() { return replaceRange; }
        public String getEventId() { return eventId; }
        public double getConfidence() { return confidence; }
        
        /**
         * Gets the text that should be visible (after accounting for replace range).
         */
        public String getVisibleText(int currentOffset) {
            int prefixLength = currentOffset - replaceRange.start;
            if (prefixLength >= insertText.length()) {
                return "";
            }
            if (prefixLength <= 0) {
                return insertText;
            }
            return insertText.substring(prefixLength);
        }
        
        /**
         * Checks if this completion is still valid at the given offset.
         */
        public boolean isValidAt(int offset) {
            return offset >= replaceRange.start && offset <= replaceRange.end;
        }
        
        /**
         * Checks if this is a multi-line completion.
         */
        public boolean isMultiLine() {
            return insertText.contains("\n");
        }
        
        /**
         * Gets the first line of the completion.
         */
        public String getFirstLine() {
            int newlineIndex = insertText.indexOf('\n');
            return newlineIndex == -1 ? insertText : insertText.substring(0, newlineIndex);
        }
        
        /**
         * Gets the number of lines in this completion.
         */
        public int getLineCount() {
            if (insertText.isEmpty()) return 0;
            return insertText.split("\n").length;
        }
    }
    
    /**
     * Represents a text range for replacements.
     */
    public static class Range {
        private final int start;
        private final int end;
        
        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        public int getStart() { return start; }
        public int getEnd() { return end; }
        public int getLength() { return end - start; }
        
        public TextRange toTextRange() {
            return new TextRange(start, end);
        }
        
        @Override
        public String toString() {
            return String.format("Range{%d-%d}", start, end);
        }
    }
    
    /**
     * Enhanced pending completion with replace range support.
     */
    public static class PendingCompletion {
        private final CompletionItem item;
        private final Editor editor;
        private final long timestamp;
        private final String originalContext;
        
        private Inlay<?> inlay;
        private boolean isAccepted;
        private boolean isRejected;
        
        public PendingCompletion(CompletionItem item, Editor editor, String originalContext) {
            this.item = item;
            this.editor = editor;
            this.originalContext = originalContext;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public CompletionItem getItem() { return item; }
        public Editor getEditor() { return editor; }
        public long getTimestamp() { return timestamp; }
        public String getOriginalContext() { return originalContext; }
        public Inlay<?> getInlay() { return inlay; }
        
        public void setInlay(Inlay<?> inlay) { this.inlay = inlay; }
        
        // State management
        public boolean isAccepted() { return isAccepted; }
        public boolean isRejected() { return isRejected; }
        public boolean isActive() { return !isAccepted && !isRejected; }
        
        public void accept() { this.isAccepted = true; }
        public void reject() { this.isRejected = true; }
        
        /**
         * Checks if this completion is still valid at the current cursor position.
         */
        public boolean isValidAtCurrentPosition() {
            int currentOffset = editor.getCaretModel().getOffset();
            return item.isValidAt(currentOffset);
        }
        
        /**
         * Gets the text that should be displayed at the current cursor position.
         */
        public String getDisplayText() {
            int currentOffset = editor.getCaretModel().getOffset();
            return item.getVisibleText(currentOffset);
        }
        
        /**
         * Gets the text range that would be replaced if this completion is accepted.
         */
        public TextRange getReplaceTextRange() {
            return item.getReplaceRange().toTextRange();
        }
        
        /**
         * Disposes of any resources associated with this completion.
         */
        public void dispose() {
            if (inlay != null && inlay.isValid()) {
                inlay.dispose();
            }
        }
        
        @Override
        public String toString() {
            return String.format("PendingCompletion{range=%s, text='%s', active=%s}", 
                               item.getReplaceRange(),
                               item.getInsertText().length() > 50 
                                   ? item.getInsertText().substring(0, 50) + "..." 
                                   : item.getInsertText(), 
                               isActive());
        }
    }
    
    /**
     * Represents a list of completion items.
     */
    public static class CompletionList {
        private final java.util.List<CompletionItem> items;
        private final boolean isIncomplete;
        
        public CompletionList(java.util.List<CompletionItem> items, boolean isIncomplete) {
            this.items = items;
            this.isIncomplete = isIncomplete;
        }
        
        public java.util.List<CompletionItem> getItems() { return items; }
        public boolean isIncomplete() { return isIncomplete; }
        public boolean isEmpty() { return items.isEmpty(); }
        public int size() { return items.size(); }
        
        /**
         * Gets the best completion item (highest confidence).
         */
        public CompletionItem getBest() {
            return items.stream()
                .max((a, b) -> Double.compare(a.getConfidence(), b.getConfidence()))
                .orElse(null);
        }
        
        /**
         * Filters items that are valid at the given offset.
         */
        public CompletionList filterValidAt(int offset) {
            java.util.List<CompletionItem> validItems = items.stream()
                .filter(item -> item.isValidAt(offset))
                .collect(java.util.stream.Collectors.toList());
            
            return new CompletionList(validItems, isIncomplete);
        }
    }
}