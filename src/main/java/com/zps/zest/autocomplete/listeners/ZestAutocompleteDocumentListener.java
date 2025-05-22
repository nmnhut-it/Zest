package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.events.ZestDocumentEventListener;
import com.zps.zest.autocomplete.events.ZestCompletionEventPublisher;
import org.jetbrains.annotations.NotNull;

/**
 * Enhanced document listener following Tabby ML patterns.
 * Implements intelligent triggering with message bus integration and sophisticated context analysis.
 */
public class ZestAutocompleteDocumentListener implements DocumentListener {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteDocumentListener.class);
    
    // Enhanced triggering thresholds
    private static final int MIN_INSERTION_LENGTH = 1;  // Reduced for more responsive triggering
    private static final int MIN_CONTEXT_OFFSET = 5;    // Reduced minimum context requirement
    private static final int CONTEXT_ANALYSIS_WINDOW = 200; // Characters to analyze for context
    
    private final ZestAutocompleteService autocompleteService;
    private final Editor editor;
    private final Project project;
    private final MessageBus messageBus;
    
    // Typing pattern detection
    private long lastTypingTime = 0;
    private int consecutiveTypingEvents = 0;
    private static final long TYPING_SESSION_TIMEOUT = 500; // ms
    
    public ZestAutocompleteDocumentListener(ZestAutocompleteService autocompleteService, Editor editor) {
        this.autocompleteService = autocompleteService;
        this.editor = editor;
        this.project = editor.getProject();
        this.messageBus = project != null ? project.getMessageBus() : null;
        LOG.debug("Enhanced ZestAutocompleteDocumentListener created for editor");
    }
    
    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        LOG.debug("Document changed: " + event);
        
        updateTypingPattern();
        
        // Analyze the type of change
        ChangeAnalysis analysis = analyzeDocumentChange(event);
        
        // Publish document change events via message bus
        publishDocumentChangeEvents(event, analysis);
        
        // Handle the change based on analysis
        handleDocumentChange(event, analysis);
    }
    
    /**
     * Enhanced document change analysis following Tabby ML patterns.
     */
    private ChangeAnalysis analyzeDocumentChange(DocumentEvent event) {
        ChangeType changeType = determineChangeType(event);
        boolean shouldTrigger = false;
        boolean shouldInvalidate = false;
        TriggerReason triggerReason = TriggerReason.NONE;
        
        switch (changeType) {
            case INSERTION:
                var insertionAnalysis = analyzeInsertion(event);
                shouldTrigger = insertionAnalysis.shouldTrigger;
                triggerReason = insertionAnalysis.reason;
                break;
                
            case DELETION:
                shouldInvalidate = true;
                break;
                
            case REPLACEMENT:
                shouldInvalidate = true;
                // Might trigger after replacement if it looks like continuation typing
                if (looksLikeContinuationTyping(event)) {
                    shouldTrigger = true;
                    triggerReason = TriggerReason.CONTINUATION_TYPING;
                }
                break;
                
            case BULK_CHANGE:
                shouldInvalidate = true;
                break;
        }
        
        return new ChangeAnalysis(changeType, shouldTrigger, shouldInvalidate, triggerReason);
    }
    
    /**
     * Analyzes text insertion to determine if autocomplete should trigger.
     */
    private InsertionAnalysis analyzeInsertion(DocumentEvent event) {
        try {
            String insertedText = event.getNewFragment().toString();
            
            // Quick checks for obvious non-trigger cases
            if (insertedText.length() < MIN_INSERTION_LENGTH) {
                return new InsertionAnalysis(false, TriggerReason.TOO_SHORT);
            }
            
            // Don't trigger if completion is already active
            if (autocompleteService.hasActiveCompletion(editor)) {
                return new InsertionAnalysis(false, TriggerReason.COMPLETION_ACTIVE);
            }
            
            // Enhanced context validation
            ContextValidation contextValidation = validateContext(event);
            if (!contextValidation.isValid) {
                return new InsertionAnalysis(false, contextValidation.reason);
            }
            
            // Analyze typing patterns for smart triggering
            TypingPattern pattern = analyzeTypingPattern(insertedText);
            TriggerReason triggerReason = decideTriggerReason(pattern, insertedText);
            
            boolean shouldTrigger = triggerReason != TriggerReason.NONE;
            
            LOG.debug("Insertion analysis: text='{}', pattern={}, reason={}, trigger={}", 
                     insertedText, pattern, triggerReason, shouldTrigger);
            
            return new InsertionAnalysis(shouldTrigger, triggerReason);
            
        } catch (Exception e) {
            LOG.warn("Error analyzing insertion", e);
            return new InsertionAnalysis(false, TriggerReason.ERROR);
        }
    }
    
    /**
     * Enhanced context validation with better heuristics.
     */
    private ContextValidation validateContext(DocumentEvent event) {
        Document document = event.getDocument();
        int offset = event.getOffset() + event.getNewLength();
        
        // Minimum offset check
        if (offset < MIN_CONTEXT_OFFSET) {
            return new ContextValidation(false, TriggerReason.INSUFFICIENT_CONTEXT);
        }
        
        // String literal check (improved)
        if (isInStringLiteral(document, offset)) {
            return new ContextValidation(false, TriggerReason.IN_STRING_LITERAL);
        }
        
        // Comment check (can be enabled as needed)
        if (isInComment(document, offset)) {
            return new ContextValidation(false, TriggerReason.IN_COMMENT);
        }
        
        // Language-specific context checks
        if (!isInCodeContext(document, offset)) {
            return new ContextValidation(false, TriggerReason.NOT_CODE_CONTEXT);
        }
        
        return new ContextValidation(true, TriggerReason.NONE);
    }
    
    /**
     * Analyzes typing patterns for intelligent triggering.
     */
    private TypingPattern analyzeTypingPattern(String insertedText) {
        // Check for common coding patterns
        if (insertedText.matches("\\w+")) {
            return TypingPattern.IDENTIFIER_TYPING;
        }
        
        if (insertedText.contains(".")) {
            return TypingPattern.DOT_COMPLETION;
        }
        
        if (insertedText.contains("(")) {
            return TypingPattern.METHOD_CALL;
        }
        
        if (insertedText.matches("\\s+")) {
            return TypingPattern.WHITESPACE;
        }
        
        if (insertedText.length() == 1 && Character.isJavaIdentifierPart(insertedText.charAt(0))) {
            return TypingPattern.SINGLE_CHAR_IDENTIFIER;
        }
        
        return TypingPattern.OTHER;
    }
    
    /**
     * Decides trigger reason based on typing pattern and content.
     */
    private TriggerReason decideTriggerReason(TypingPattern pattern, String insertedText) {
        switch (pattern) {
            case IDENTIFIER_TYPING:
                return insertedText.length() >= 2 ? TriggerReason.IDENTIFIER_COMPLETION : TriggerReason.NONE;
                
            case DOT_COMPLETION:
                return TriggerReason.DOT_COMPLETION;
                
            case METHOD_CALL:
                return TriggerReason.METHOD_COMPLETION;
                
            case SINGLE_CHAR_IDENTIFIER:
                // Only trigger single char if we're in a good typing session
                return consecutiveTypingEvents >= 2 ? TriggerReason.CONTINUED_TYPING : TriggerReason.NONE;
                
            case WHITESPACE:
                return TriggerReason.NONE;
                
            default:
                return TriggerReason.NONE;
        }
    }
    
    /**
     * Updates typing pattern tracking.
     */
    private void updateTypingPattern() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastTypingTime < TYPING_SESSION_TIMEOUT) {
            consecutiveTypingEvents++;
        } else {
            consecutiveTypingEvents = 1;
        }
        
        lastTypingTime = currentTime;
    }
    
    /**
     * Publishes document change events via message bus.
     */
    private void publishDocumentChangeEvents(DocumentEvent event, ChangeAnalysis analysis) {
        if (messageBus == null) return;
        
        var publisher = messageBus.syncPublisher(ZestDocumentEventListener.TOPIC);
        
        if (analysis.shouldTrigger) {
            publisher.documentChangedForCompletion(event.getDocument(), editor, event, true);
        }
        
        if (analysis.shouldInvalidate) {
            publisher.documentChangedInvalidateCompletion(event.getDocument(), editor, event);
        }
        
        if (analysis.changeType == ChangeType.BULK_CHANGE) {
            publisher.bulkDocumentChangeStarted(event.getDocument(), editor);
        }
    }
    
    /**
     * Handles the document change based on analysis.
     */
    private void handleDocumentChange(DocumentEvent event, ChangeAnalysis analysis) {
        if (analysis.shouldInvalidate) {
            LOG.debug("Invalidating completion due to: " + analysis.changeType);
            autocompleteService.clearCompletion(editor);
        }
        
        if (analysis.shouldTrigger) {
            LOG.info("Triggering autocomplete due to: " + analysis.triggerReason);
            autocompleteService.triggerAutocomplete(editor);
        }
    }
    
    /**
     * Determines the type of document change.
     */
    private ChangeType determineChangeType(DocumentEvent event) {
        int oldLength = event.getOldLength();
        int newLength = event.getNewLength();
        
        if (oldLength == 0 && newLength > 0) {
            return ChangeType.INSERTION;
        } else if (oldLength > 0 && newLength == 0) {
            return ChangeType.DELETION;
        } else if (oldLength > 0 && newLength > 0) {
            return ChangeType.REPLACEMENT;
        } else if (oldLength > 100 || newLength > 100) {
            return ChangeType.BULK_CHANGE;
        }
        
        return ChangeType.OTHER;
    }
    
    /**
     * Checks if change looks like continuation typing after a replacement.
     */
    private boolean looksLikeContinuationTyping(DocumentEvent event) {
        String newText = event.getNewFragment().toString();
        return newText.length() <= 3 && newText.matches("\\w+");
    }
    
    /**
     * Enhanced check for code context (not in strings, comments, etc.).
     */
    private boolean isInCodeContext(Document document, int offset) {
        try {
            // Get surrounding context
            int start = Math.max(0, offset - CONTEXT_ANALYSIS_WINDOW);
            int end = Math.min(document.getTextLength(), offset + CONTEXT_ANALYSIS_WINDOW);
            String context = document.getText().substring(start, end);
            
            // Simple heuristics for code context
            // This could be enhanced with PSI analysis
            return context.contains("{") || context.contains(";") || context.contains("class") || context.contains("public");
            
        } catch (Exception e) {
            return true; // Default to allowing completion
        }
    }
    
    /**
     * Enhanced string literal detection.
     */
    private boolean isInStringLiteral(Document document, int offset) {
        try {
            String text = document.getText();
            if (offset >= text.length()) {
                return false;
            }
            
            // Analyze current line for string context
            int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
            String lineText = text.substring(lineStart, offset);
            
            // Count unescaped quotes (both single and double)
            int doubleQuoteCount = 0;
            int singleQuoteCount = 0;
            boolean inEscape = false;
            
            for (char c : lineText.toCharArray()) {
                if (inEscape) {
                    inEscape = false;
                    continue;
                }
                
                if (c == '\\') {
                    inEscape = true;
                } else if (c == '"') {
                    doubleQuoteCount++;
                } else if (c == '\'') {
                    singleQuoteCount++;
                }
            }
            
            // If odd number of quotes, we're inside a string
            return (doubleQuoteCount % 2 == 1) || (singleQuoteCount % 2 == 1);
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Enhanced comment detection.
     */
    private boolean isInComment(Document document, int offset) {
        try {
            String text = document.getText();
            if (offset >= text.length()) {
                return false;
            }
            
            // Check for single-line comments
            int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
            int lineEnd = document.getLineEndOffset(document.getLineNumber(offset));
            String lineText = text.substring(lineStart, Math.min(lineEnd, text.length()));
            
            if (lineText.contains("//")) {
                int commentStart = lineStart + lineText.indexOf("//");
                if (offset >= commentStart) {
                    return true;
                }
            }
            
            // Check for multi-line comments
            String beforeCursor = text.substring(Math.max(0, offset - CONTEXT_ANALYSIS_WINDOW), offset);
            int lastCommentStart = beforeCursor.lastIndexOf("/*");
            int lastCommentEnd = beforeCursor.lastIndexOf("*/");
            
            return lastCommentStart > lastCommentEnd;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    // Data classes for analysis results
    private static class ChangeAnalysis {
        final ChangeType changeType;
        final boolean shouldTrigger;
        final boolean shouldInvalidate;
        final TriggerReason triggerReason;
        
        ChangeAnalysis(ChangeType changeType, boolean shouldTrigger, boolean shouldInvalidate, TriggerReason triggerReason) {
            this.changeType = changeType;
            this.shouldTrigger = shouldTrigger;
            this.shouldInvalidate = shouldInvalidate;
            this.triggerReason = triggerReason;
        }
    }
    
    private static class InsertionAnalysis {
        final boolean shouldTrigger;
        final TriggerReason reason;
        
        InsertionAnalysis(boolean shouldTrigger, TriggerReason reason) {
            this.shouldTrigger = shouldTrigger;
            this.reason = reason;
        }
    }
    
    private static class ContextValidation {
        final boolean isValid;
        final TriggerReason reason;
        
        ContextValidation(boolean isValid, TriggerReason reason) {
            this.isValid = isValid;
            this.reason = reason;
        }
    }
    
    // Enums for analysis
    private enum ChangeType {
        INSERTION, DELETION, REPLACEMENT, BULK_CHANGE, OTHER
    }
    
    private enum TypingPattern {
        IDENTIFIER_TYPING, DOT_COMPLETION, METHOD_CALL, SINGLE_CHAR_IDENTIFIER, WHITESPACE, OTHER
    }
    
    private enum TriggerReason {
        NONE, IDENTIFIER_COMPLETION, DOT_COMPLETION, METHOD_COMPLETION, 
        CONTINUED_TYPING, CONTINUATION_TYPING, TOO_SHORT, COMPLETION_ACTIVE, 
        INSUFFICIENT_CONTEXT, IN_STRING_LITERAL, IN_COMMENT, NOT_CODE_CONTEXT, ERROR
    }
}
