package com.zps.zest.autocomplete.context;

import com.intellij.psi.*;
import java.util.List;

/**
 * Complete context information for code completion.
 */
public class CompletionContext {
    public final CursorPosition cursorPosition;
    public final ContainingElements containingElements;
    public final SemanticInfo semanticInfo;
    public final CompletionType completionType;
    public final LocalContext localContext;
    public final String languageLevel;
    
    public CompletionContext(CursorPosition cursorPosition, ContainingElements containingElements,
                           SemanticInfo semanticInfo, CompletionType completionType,
                           LocalContext localContext, String languageLevel) {
        this.cursorPosition = cursorPosition;
        this.containingElements = containingElements;
        this.semanticInfo = semanticInfo;
        this.completionType = completionType;
        this.localContext = localContext;
        this.languageLevel = languageLevel;
    }
}

