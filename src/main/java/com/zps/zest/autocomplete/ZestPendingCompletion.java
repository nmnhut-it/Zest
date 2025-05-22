package com.zps.zest.autocomplete;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.util.TextRange;

/**
 * Represents a pending autocomplete suggestion that can be displayed inline in the editor.
 * This class holds the completion text, editor context, and associated inline elements.
 */
public class ZestPendingCompletion {
    private final String completionText;
    private final String originalText;
    private final int offset;
    private final Editor editor;
    private final long timestamp;
    private Inlay<?> inlay;
    private boolean isAccepted;
    private boolean isRejected;
    
    /**
     * Creates a new pending completion.
     * 
     * @param completionText The text to suggest as completion
     * @param originalText The original text that triggered this completion
     * @param offset The offset in the document where completion should be inserted
     * @param editor The editor instance
     */
    public ZestPendingCompletion(String completionText, String originalText, int offset, Editor editor) {
        this.completionText = completionText;
        this.originalText = originalText;
        this.offset = offset;
        this.editor = editor;
        this.timestamp = System.currentTimeMillis();
        this.isAccepted = false;
        this.isRejected = false;
    }
    
    /**
     * Gets the completion text to be suggested.
     */
    public String getCompletionText() {
        return completionText;
    }
    
    /**
     * Gets the original text that triggered this completion.
     */
    public String getOriginalText() {
        return originalText;
    }
    
    /**
     * Gets the offset where the completion should be inserted.
     */
    public int getOffset() {
        return offset;
    }
    
    /**
     * Gets the editor associated with this completion.
     */
    public Editor getEditor() {
        return editor;
    }
    
    /**
     * Gets the timestamp when this completion was created.
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Gets the inlay element used to display this completion.
     */
    public Inlay<?> getInlay() {
        return inlay;
    }
    
    /**
     * Sets the inlay element for this completion.
     */
    public void setInlay(Inlay<?> inlay) {
        this.inlay = inlay;
    }
    
    /**
     * Checks if this completion has been accepted.
     */
    public boolean isAccepted() {
        return isAccepted;
    }
    
    /**
     * Marks this completion as accepted.
     */
    public void accept() {
        this.isAccepted = true;
    }
    
    /**
     * Checks if this completion has been rejected.
     */
    public boolean isRejected() {
        return isRejected;
    }
    
    /**
     * Marks this completion as rejected.
     */
    public void reject() {
        this.isRejected = true;
    }
    
    /**
     * Checks if this completion is still active (not accepted or rejected).
     */
    public boolean isActive() {
        return !isAccepted && !isRejected;
    }
    
    /**
     * Gets the text range that this completion would occupy if accepted.
     */
    public TextRange getTextRange() {
        return new TextRange(offset, offset + completionText.length());
    }
    
    /**
     * Checks if this completion is for a multi-line suggestion.
     */
    public boolean isMultiLine() {
        return completionText.contains("\n");
    }
    
    /**
     * Gets the first line of the completion text.
     */
    public String getFirstLine() {
        int newlineIndex = completionText.indexOf('\n');
        return newlineIndex == -1 ? completionText : completionText.substring(0, newlineIndex);
    }
    
    /**
     * Gets the number of lines in the completion.
     */
    public int getLineCount() {
        if (completionText.isEmpty()) {
            return 0;
        }
        return completionText.split("\n").length;
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
        return String.format("ZestPendingCompletion{offset=%d, text='%s', active=%s}", 
                             offset, 
                             completionText.length() > 50 
                                 ? completionText.substring(0, 50) + "..." 
                                 : completionText, 
                             isActive());
    }
}
