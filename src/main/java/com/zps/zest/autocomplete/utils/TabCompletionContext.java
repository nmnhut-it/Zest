package com.zps.zest.autocomplete.utils;

import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Context-aware detection for TAB completion with ZEST PRIORITY.
 * Zest completion takes precedence over IntelliJ when available.
 */
public class TabCompletionContext {
    private static final Logger LOG = Logger.getInstance(TabCompletionContext.class);
    
    public enum ContextType {
        ZEST_COMPLETION_ACTIVE,     // HIGHEST PRIORITY: Zest has active completion
        LIVE_TEMPLATE_ACTIVE,       // Live template expansion (can't interrupt)
        BRACKET_NAVIGATION,         // Cursor between brackets/parentheses  
        PARAMETER_HINTS_ACTIVE,     // Method parameter hints showing
        STRING_LITERAL,             // Cursor inside string literal
        COMMENT_CONTEXT,            // Cursor inside comment
        ZEST_OPPORTUNITY,           // Zest could provide completion here
        INTELLIJ_POPUP_ACTIVE,      // LOWER PRIORITY: IntelliJ completion popup
        NORMAL_TYPING              // Normal code typing context
    }
    
    /**
     * Detects the current TAB completion context with ZEST PRIORITY.
     */
    @NotNull
    public static ContextType detectContext(@NotNull Editor editor, @Nullable DataContext dataContext) {
        try {
            // Handle null dataContext gracefully
            if (dataContext == null) {
                LOG.debug("DataContext is null, creating minimal context for detection");
                dataContext = new com.intellij.openapi.actionSystem.DataContext() {
                    @Override
                    public Object getData(String dataId) {
                        if (com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.is(dataId)) {
                            return editor;
                        }
                        if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.is(dataId)) {
                            return editor.getProject();
                        }
                        return null;
                    }
                };
            }
            // PRIORITY 1: Active Zest completion (HIGHEST)
            if (hasActiveZestCompletion(editor)) {
                LOG.debug("Context: ZEST completion active (PRIORITY)");
                return ContextType.ZEST_COMPLETION_ACTIVE;
            }
            
            // PRIORITY 2: Live template (can't be interrupted safely)
            if (isLiveTemplateActive(editor)) {
                LOG.debug("Context: Live template active");
                return ContextType.LIVE_TEMPLATE_ACTIVE;
            }
            
            // PRIORITY 3: Special navigation contexts
            if (isBracketNavigationContext(editor)) {
                LOG.debug("Context: Bracket navigation");
                return ContextType.BRACKET_NAVIGATION;
            }
            
            if (isParameterHintsActive(editor)) {
                LOG.debug("Context: Parameter hints active");
                return ContextType.PARAMETER_HINTS_ACTIVE;
            }
            
            // PRIORITY 4: Special text contexts (where completion doesn't make sense)
            if (isInStringLiteral(editor)) {
                LOG.debug("Context: String literal");
                return ContextType.STRING_LITERAL;
            }
            
            if (isInComment(editor)) {
                LOG.debug("Context: Comment");
                return ContextType.COMMENT_CONTEXT;
            }
            
            // PRIORITY 5: Zest opportunity (trigger new Zest completion)
            if (canZestProvideCompletion(editor)) {
                LOG.debug("Context: ZEST opportunity (can provide completion)");
                return ContextType.ZEST_OPPORTUNITY;
            }
            
            // PRIORITY 6: IntelliJ popup (LOWER PRIORITY than Zest)
            if (isIntelliJPopupActive(editor)) {
                LOG.debug("Context: IntelliJ popup active (lower priority)");
                return ContextType.INTELLIJ_POPUP_ACTIVE;
            }
            
            LOG.debug("Context: Normal typing");
            return ContextType.NORMAL_TYPING;
            
        } catch (Exception e) {
            LOG.warn("Error detecting TAB context", e);
            return ContextType.NORMAL_TYPING;
        }
    }
    
    /**
     * Checks if Zest currently has an active completion.
     * ENHANCED: More robust checking with detailed logging.
     */
    private static boolean hasActiveZestCompletion(@NotNull Editor editor) {
        try {
            if (editor.getProject() == null) {
                LOG.debug("No project for editor - no active Zest completion");
                return false;
            }
            
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(editor.getProject());
            boolean hasCompletion = service.hasActiveCompletion(editor);
            
            LOG.debug("Zest completion check: hasCompletion={}", hasCompletion);
            
            // Additional validation - check if completion is actually visible/valid
            if (hasCompletion) {
                try {
                    com.zps.zest.autocomplete.ZestCompletionData.PendingCompletion completion = service.getActiveCompletion(editor);
                    if (completion == null || !completion.isActive()) {
                        LOG.debug("Completion exists but is inactive - clearing state");
                        service.clearCompletion(editor);
                        return false;
                    }
                    
                    // Check if completion has display text
                    String displayText = completion.getDisplayText();
                    if (displayText == null || displayText.trim().isEmpty()) {
                        LOG.debug("Completion has no display text - clearing state");
                        service.clearCompletion(editor);
                        return false;
                    }
                    
                    LOG.debug("Active Zest completion confirmed: {}", 
                        displayText.substring(0, Math.min(30, displayText.length())));
                    return true;
                    
                } catch (Exception e) {
                    LOG.warn("Error validating completion state", e);
                    // Clear potentially corrupted state
                    service.clearCompletion(editor);
                    return false;
                }
            }
            
            return false;
        } catch (Exception e) {
            LOG.warn("Error checking Zest completion state", e);
            return false;
        }
    }
    
    /**
     * Determines if Zest can provide completion at the current position.
     * This is where we suppress IntelliJ to give Zest a chance.
     */
    private static boolean canZestProvideCompletion(@NotNull Editor editor) {
        try {
            // Basic heuristics for when Zest might be useful
            Document document = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            
            if (offset < 2) {
                return false; // Need some context
            }
            
            // Get current line context
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String beforeCursor = document.getText(TextRange.from(lineStart, offset - lineStart));
            
            // Zest is good at completing method calls and variable assignments
            String trimmed = beforeCursor.trim();
            
            // Method call patterns: obj.method, variable.
            if (trimmed.matches(".*\\w+\\.\\w*$")) {
                LOG.debug("Zest opportunity: method call pattern detected");
                return true;
            }
            
            // Assignment patterns: variable = 
            if (trimmed.matches(".*\\w+\\s*=\\s*\\w*$")) {
                LOG.debug("Zest opportunity: assignment pattern detected");
                return true;
            }
            
            // Variable declaration patterns: Type variable
            if (trimmed.matches(".*\\b(int|String|boolean|byte|short|long|double|float|char)\\s+\\w*$")) {
                LOG.debug("Zest opportunity: variable declaration pattern detected");
                return true;
            }
            
            // Object creation patterns: new Something
            if (trimmed.matches(".*\\bnew\\s+\\w*$")) {
                LOG.debug("Zest opportunity: object creation pattern detected");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            LOG.warn("Error checking Zest completion opportunity", e);
            return false;
        }
    }
    
    /**
     * Detects if IntelliJ's completion popup is currently active.
     */
    private static boolean isIntelliJPopupActive(@NotNull Editor editor) {
        try {
            if (editor.getProject() == null) {
                return false;
            }
            
            LookupManager lookupManager = LookupManager.getInstance(editor.getProject());
            if (lookupManager != null) {
                var activeLookup = lookupManager.getActiveLookup();
                return activeLookup != null && activeLookup.isCompletion();
            }
            
            return false;
        } catch (Exception e) {
            LOG.warn("Error checking IntelliJ popup state", e);
            return false;
        }
    }
    
    /**
     * Detects if a live template is currently being expanded.
     */
    private static boolean isLiveTemplateActive(@NotNull Editor editor) {
        try {
            if (editor.getProject() == null) {
                return false;
            }
            
            TemplateManager templateManager = TemplateManager.getInstance(editor.getProject());
            return templateManager != null && templateManager.getActiveTemplate(editor) != null;
        } catch (Exception e) {
            LOG.warn("Error checking live template state", e);
            return false;
        }
    }
    
    /**
     * Detects if cursor is in a bracket navigation context.
     */
    private static boolean isBracketNavigationContext(@NotNull Editor editor) {
        try {
            Document document = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            
            if (offset >= document.getTextLength()) {
                return false;
            }
            
            // Check if we're between matching brackets
            char currentChar = offset < document.getTextLength() ? 
                document.getCharsSequence().charAt(offset) : ' ';
            char prevChar = offset > 0 ? 
                document.getCharsSequence().charAt(offset - 1) : ' ';
            
            // Common bracket navigation scenarios
            return (prevChar == '(' && currentChar == ')') ||
                   (prevChar == '[' && currentChar == ']') ||
                   (prevChar == '{' && currentChar == '}') ||
                   (prevChar == '<' && currentChar == '>');
                   
        } catch (Exception e) {
            LOG.warn("Error checking bracket navigation context", e);
            return false;
        }
    }
    
    /**
     * Detects if parameter hints are active.
     */
    private static boolean isParameterHintsActive(@NotNull Editor editor) {
        try {
            // Check if we're in a method call with parameter hints
            Document document = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            
            if (offset < 10) return false;
            
            // Look for recent opening parenthesis suggesting parameter context
            String recentText = document.getText(TextRange.from(Math.max(0, offset - 10), Math.min(10, offset)));
            return recentText.contains("(") && !recentText.contains(")");
            
        } catch (Exception e) {
            LOG.warn("Error checking parameter hints context", e);
            return false;
        }
    }
    
    /**
     * Detects if cursor is inside a string literal.
     */
    private static boolean isInStringLiteral(@NotNull Editor editor) {
        try {
            Document document = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            
            // Get current line
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            int lineEnd = document.getLineEndOffset(lineNumber);
            String lineText = document.getText(TextRange.from(lineStart, lineEnd - lineStart));
            
            // Count quotes before cursor
            int relativeOffset = offset - lineStart;
            int quoteCount = 0;
            for (int i = 0; i < relativeOffset && i < lineText.length(); i++) {
                if (lineText.charAt(i) == '"' && (i == 0 || lineText.charAt(i - 1) != '\\')) {
                    quoteCount++;
                }
            }
            
            // Odd number of quotes means we're inside a string
            return quoteCount % 2 == 1;
            
        } catch (Exception e) {
            LOG.warn("Error checking string literal context", e);
            return false;
        }
    }
    
    /**
     * Detects if cursor is inside a comment.
     */
    private static boolean isInComment(@NotNull Editor editor) {
        try {
            Document document = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            
            // Get current line
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String beforeCursor = document.getText(TextRange.from(lineStart, offset - lineStart));
            
            // Check for line comment
            if (beforeCursor.trim().startsWith("//")) {
                return true;
            }
            
            // Check for block comment (simple heuristic)
            return beforeCursor.contains("/*") && !beforeCursor.contains("*/");
            
        } catch (Exception e) {
            LOG.warn("Error checking comment context", e);
            return false;
        }
    }
}
