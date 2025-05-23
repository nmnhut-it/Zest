package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.zps.zest.autocomplete.context.CompletionContext;
import com.zps.zest.autocomplete.context.SemanticContextGatherer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Simplified context gatherer that delegates to the enhanced semantic system.
 * Maintains backward compatibility while leveraging the new architecture.
 */
public class ContextGatherer {
    
    /**
     * Enhanced cursor context gathering using semantic analysis.
     * Preferred method for new code.
     */
    public static CursorContext gatherEnhancedCursorContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        try {
            // Use the new semantic context gatherer
            CompletionContext semanticContext = SemanticContextGatherer.gatherContext(editor, psiFile);
            
            // Convert to legacy format for backward compatibility
            return new CursorContext(
                semanticContext.localContext.beforeCursor,
                semanticContext.localContext.afterCursor,
                semanticContext.cursorPosition.linePrefix,
                semanticContext.cursorPosition.lineSuffix,
                semanticContext.cursorPosition.indentation,
                semanticContext.cursorPosition.offset,
                semanticContext.cursorPosition.lineNumber
            );
        } catch (Exception e) {
            // Fall back to simple context gathering
            return gatherSimpleCursorContext(editor);
        }
    }
    
    /**
     * Simple cursor context gathering for fallback scenarios.
     */
    private static CursorContext gatherSimpleCursorContext(@NotNull Editor editor) {
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        
        int lineNumber = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(lineNumber);
        int lineEnd = document.getLineEndOffset(lineNumber);
        
        String lineText = document.getText(new TextRange(lineStart, lineEnd));
        int positionInLine = offset - lineStart;
        
        String currentLinePrefix = lineText.substring(0, positionInLine);
        String currentLineSuffix = lineText.substring(positionInLine);
        String indentation = extractIndentation(lineText);
        
        // Simple prefix/suffix context
        String prefixContext = gatherSimplePrefixContext(document, lineNumber, currentLinePrefix);
        String suffixContext = gatherSimpleSuffixContext(document, lineNumber, currentLineSuffix);
        
        return new CursorContext(
            prefixContext,
            suffixContext,
            currentLinePrefix,
            currentLineSuffix,
            indentation,
            offset,
            lineNumber
        );
    }
    
    /**
     * Legacy method for backward compatibility.
     */
    public static String gatherCursorContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        CursorContext context = gatherEnhancedCursorContext(editor, psiFile);
        return context.getPrefixContext() + "<CURSOR>" + context.getSuffixContext();
    }
    
    /**
     * Enhanced file context gathering using semantic analysis.
     */
    public static String gatherFileContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        try {
            CompletionContext semanticContext = SemanticContextGatherer.gatherContext(editor, psiFile);
            
            // Build structured file context from semantic info
            StringBuilder fileContext = new StringBuilder();
            
            // Package
            if (!semanticContext.semanticInfo.packageName.isEmpty()) {
                fileContext.append("package ").append(semanticContext.semanticInfo.packageName).append(";\n\n");
            }
            
            // Key imports
            if (!semanticContext.semanticInfo.imports.isEmpty()) {
                for (String imp : semanticContext.semanticInfo.imports) {
                    if (!imp.startsWith("java.lang.")) {
                        fileContext.append("import ").append(imp).append(";\n");
                    }
                }
                fileContext.append("\n");
            }
            
            // Class structure
            if (semanticContext.semanticInfo.classContext != null) {
                var classCtx = semanticContext.semanticInfo.classContext;
                fileContext.append("public ");
                if (classCtx.isInterface) {
                    fileContext.append("interface ");
                } else {
                    fileContext.append("class ");
                }
                fileContext.append(classCtx.className);
                
                if (classCtx.superClass != null) {
                    fileContext.append(" extends ").append(classCtx.superClass);
                }
                
                if (!classCtx.interfaces.isEmpty()) {
                    fileContext.append(" implements ").append(String.join(", ", classCtx.interfaces));
                }
                
                fileContext.append(" {\n");
                
                // Fields
                for (var field : classCtx.fields) {
                    fileContext.append("    ");
                    if (field.isStatic) fileContext.append("static ");
                    if (field.isFinal) fileContext.append("final ");
                    fileContext.append(field.type).append(" ").append(field.name).append(";\n");
                }
                
                // Method signatures
                for (var method : classCtx.methods) {
                    fileContext.append("    ");
                    if (method.isStatic) fileContext.append("static ");
                    fileContext.append(method.returnType).append(" ").append(method.name).append("(");
                    fileContext.append(String.join(", ", method.parameterTypes));
                    fileContext.append(");\n");
                }
                
                fileContext.append("}\n");
            }
            
            return fileContext.toString();
            
        } catch (Exception e) {
            // Fall back to simple file context
            return gatherSimpleFileContext(editor);
        }
    }
    
    /**
     * Simple file context gathering for fallback.
     */
    private static String gatherSimpleFileContext(@NotNull Editor editor) {
        Document document = editor.getDocument();
        String fullText = document.getText();
        
        if (fullText.length() > 3000) {
            return extractSimpleStructuralContext(fullText);
        }
        
        return fullText;
    }
    
    /**
     * Simple structural context extraction.
     */
    private static String extractSimpleStructuralContext(String fullText) {
        StringBuilder context = new StringBuilder();
        String[] lines = fullText.split("\n");
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") ||
                    trimmed.startsWith("import ") ||
                    trimmed.contains("class ") ||
                    trimmed.contains("interface ") ||
                    (trimmed.contains("(") && (trimmed.contains("public ") ||
                            trimmed.contains("private ") || trimmed.contains("protected ")))) {
                context.append(line).append("\n");
            }
        }
        
        return context.toString();
    }
    
    /**
     * Simple prefix context gathering.
     */
    private static String gatherSimplePrefixContext(Document document, int currentLine, String currentLinePrefix) {
        StringBuilder prefix = new StringBuilder();
        int startLine = Math.max(0, currentLine - 10);
        
        for (int line = startLine; line < currentLine; line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText(new TextRange(lineStart, lineEnd));
            
            if (lineText.trim().isEmpty() && prefix.length() == 0) {
                continue;
            }
            
            if (lineText.length() > 120) {
                lineText = lineText.substring(0, 120) + "...";
            }
            
            prefix.append(lineText).append("\n");
        }
        
        prefix.append(currentLinePrefix);
        return prefix.toString();
    }
    
    /**
     * Simple suffix context gathering.
     */
    private static String gatherSimpleSuffixContext(Document document, int currentLine, String currentLineSuffix) {
        StringBuilder suffix = new StringBuilder();
        
        if (!currentLineSuffix.trim().isEmpty()) {
            suffix.append(currentLineSuffix);
        }
        
        int totalLines = document.getLineCount();
        int endLine = Math.min(totalLines, currentLine + 6);
        
        for (int line = currentLine + 1; line < endLine; line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText(new TextRange(lineStart, lineEnd));
            
            if (lineText.length() > 120) {
                lineText = lineText.substring(0, 120) + "...";
            }
            
            suffix.append("\n").append(lineText);
        }
        
        return suffix.toString();
    }
    
    /**
     * Helper method to extract indentation.
     */
    private static String extractIndentation(String lineText) {
        StringBuilder indentation = new StringBuilder();
        for (char ch : lineText.toCharArray()) {
            if (ch == ' ' || ch == '\t') {
                indentation.append(ch);
            } else {
                break;
            }
        }
        return indentation.toString();
    }
    
    /**
     * Legacy cursor context class for backward compatibility.
     */
    public static class CursorContext {
        private final String prefixContext;
        private final String suffixContext;
        private final String currentLinePrefix;
        private final String currentLineSuffix;
        private final String indentation;
        private final int offset;
        private final int lineNumber;

        public CursorContext(String prefixContext, String suffixContext,
                             String currentLinePrefix, String currentLineSuffix,
                             String indentation, int offset, int lineNumber) {
            this.prefixContext = prefixContext;
            this.suffixContext = suffixContext;
            this.currentLinePrefix = currentLinePrefix;
            this.currentLineSuffix = currentLineSuffix;
            this.indentation = indentation;
            this.offset = offset;
            this.lineNumber = lineNumber;
        }

        public String getPrefixContext() { return prefixContext; }
        public String getSuffixContext() { return suffixContext; }
        public String getCurrentLinePrefix() { return currentLinePrefix; }
        public String getCurrentLineSuffix() { return currentLineSuffix; }
        public String getIndentation() { return indentation; }
        public int getOffset() { return offset; }
        public int getLineNumber() { return lineNumber; }

        public boolean isAtLineStart() {
            return currentLinePrefix.trim().isEmpty();
        }

        public boolean isInMethodBody() {
            return prefixContext.contains("{") &&
                    prefixContext.lastIndexOf("{") > prefixContext.lastIndexOf("}");
        }

        public String getNextLineIndentation() {
            if (currentLinePrefix.trim().endsWith("{")) {
                return indentation + (indentation.contains("\t") ? "\t" : "    ");
            }
            return indentation;
        }
    }
}
