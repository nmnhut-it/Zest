package com.zps.zest.autocompletion2.context;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.ClassAnalyzer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Enhanced context gathering system that provides semantic understanding of Java code.
 * Uses PSI analysis to create rich, structured context for better completions.
 */
public class SemanticContextGatherer {
    private static final Logger LOG = Logger.getInstance(SemanticContextGatherer.class);
    
    private static final int MAX_CONTEXT_LINES = 15;
    private static final int MAX_LINE_LENGTH = 120;
    
    /**
     * Gathers comprehensive semantic context for the given editor position.
     */
    public static CompletionContext gatherContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        if (!(psiFile instanceof PsiJavaFile)) {
            return createFallbackContext(editor);
        }
        
        return ReadAction.compute(() -> {
            try {
                return gatherJavaContext(editor, (PsiJavaFile) psiFile);
            } catch (Exception e) {
                LOG.warn("Failed to gather semantic context, falling back", e);
                return createFallbackContext(editor);
            }
        });
    }
    
    /**
     * Gathers rich Java-specific context using PSI analysis.
     */
    private static CompletionContext gatherJavaContext(@NotNull Editor editor, @NotNull PsiJavaFile psiFile) {
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        
        // Basic cursor position info
        CursorPosition cursorPos = analyzeCursorPosition(document, offset);
        
        // Find containing elements
        PsiElement elementAtCursor = psiFile.findElementAt(offset);
        ContainingElements containers = findContainingElements(elementAtCursor);
        
        // Gather semantic context
        SemanticInfo semantics = gatherSemanticInfo(psiFile, containers, cursorPos);
        
        // Determine completion type
        CompletionType completionType = determineCompletionType(cursorPos, containers, elementAtCursor);
        
        return new CompletionContext(
            cursorPos,
            containers, 
            semantics,
            completionType,
            gatherLocalContext(document, offset),
            detectLanguageLevel(psiFile)
        );
    }
    
    /**
     * Analyzes the cursor position within the document.
     */
    private static CursorPosition analyzeCursorPosition(Document document, int offset) {
        int lineNumber = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(lineNumber);
        int lineEnd = document.getLineEndOffset(lineNumber);
        
        String lineText = document.getText(new TextRange(lineStart, lineEnd));
        int columnInLine = offset - lineStart;
        
        String prefix = lineText.substring(0, columnInLine);
        String suffix = lineText.substring(columnInLine);
        String indentation = extractIndentation(lineText);
        
        return new CursorPosition(
            offset, lineNumber, columnInLine,
            prefix, suffix, lineText, indentation
        );
    }
    
    /**
     * Finds containing PSI elements (method, class, etc.).
     */
    private static ContainingElements findContainingElements(@Nullable PsiElement elementAtCursor) {
        if (elementAtCursor == null) {
            return new ContainingElements(null, null, null, null);
        }
        
        PsiMethod method = PsiTreeUtil.getParentOfType(elementAtCursor, PsiMethod.class);
        PsiClass containingClass = PsiTreeUtil.getParentOfType(elementAtCursor, PsiClass.class);
        PsiCodeBlock codeBlock = PsiTreeUtil.getParentOfType(elementAtCursor, PsiCodeBlock.class);
        PsiStatement statement = PsiTreeUtil.getParentOfType(elementAtCursor, PsiStatement.class);
        
        return new ContainingElements(method, containingClass, codeBlock, statement);
    }
    
    /**
     * Gathers rich semantic information using ClassAnalyzer.
     */
    private static SemanticInfo gatherSemanticInfo(PsiJavaFile psiFile, ContainingElements containers, CursorPosition cursorPos) {
        // File-level info
        String packageName = psiFile.getPackageName();
        List<String> imports = extractImports(psiFile);
        
        // Class context
        ClassContext classContext = null;
        if (containers.containingClass != null) {
            classContext = analyzeClassContext(containers.containingClass);
        }
        
        // Method context  
        MethodContext methodContext = null;
        if (containers.method != null) {
            methodContext = analyzeMethodContext(containers.method);
        }
        
        // Local scope analysis
        LocalScope localScope = analyzeLocalScope(containers, cursorPos);
        
        return new SemanticInfo(packageName, imports, classContext, methodContext, localScope);
    }
    
    /**
     * Analyzes class context using ClassAnalyzer capabilities.
     */
    private static ClassContext analyzeClassContext(PsiClass psiClass) {
        // Basic class info
        String className = psiClass.getName();
        boolean isInterface = psiClass.isInterface();
        boolean isAbstract = psiClass.hasModifierProperty(PsiModifier.ABSTRACT);
        
        // Inheritance info
        String superClass = null;
        PsiClass superClassPsi = psiClass.getSuperClass();
        if (superClassPsi != null && !"Object".equals(superClassPsi.getName())) {
            superClass = superClassPsi.getName();
        }
        
        List<String> interfaces = new ArrayList<>();
        for (PsiClassType interfaceType : psiClass.getImplementsListTypes()) {
            PsiClass interfacePsi = interfaceType.resolve();
            if (interfacePsi != null) {
                interfaces.add(interfacePsi.getName());
            }
        }
        
        // Fields and methods (structure only)
        List<FieldInfo> fields = extractFieldInfo(psiClass);
        List<MethodSignature> methods = extractMethodSignatures(psiClass);
        
        // Related classes using ClassAnalyzer
        Set<PsiClass> relatedClasses = new HashSet<>();
        ClassAnalyzer.collectRelatedClasses(psiClass, relatedClasses);
        
        List<String> relatedClassNames = relatedClasses.stream()
            .map(PsiClass::getName)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toList());
        
        return new ClassContext(
            className, isInterface, isAbstract,
            superClass, interfaces,
            fields, methods, relatedClassNames
        );
    }
    
    /**
     * Analyzes method context.
     */
    private static MethodContext analyzeMethodContext(PsiMethod method) {
        String methodName = method.getName();
        boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);
        boolean isPrivate = method.hasModifierProperty(PsiModifier.PRIVATE);
        
        // Return type
        String returnType = null;
        PsiType returnTypePsi = method.getReturnType();
        if (returnTypePsi != null) {
            returnType = returnTypePsi.getPresentableText();
        }
        
        // Parameters
        List<ParameterInfo> parameters = new ArrayList<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            parameters.add(new ParameterInfo(
                param.getName(),
                param.getType().getPresentableText()
            ));
        }
        
        // Local variables in scope
        List<VariableInfo> localVars = extractLocalVariables(method);
        
        return new MethodContext(methodName, isStatic, isPrivate, returnType, parameters, localVars);
    }
    
    /**
     * Analyzes local scope for available variables and types.
     */
    private static LocalScope analyzeLocalScope(ContainingElements containers, CursorPosition cursorPos) {
        List<VariableInfo> availableVars = new ArrayList<>();
        List<String> availableTypes = new ArrayList<>();
        
        // Add method parameters if in method
        if (containers.method != null) {
            for (PsiParameter param : containers.method.getParameterList().getParameters()) {
                availableVars.add(new VariableInfo(
                    param.getName(),
                    param.getType().getPresentableText(),
                    VariableInfo.Scope.PARAMETER
                ));
                availableTypes.add(param.getType().getPresentableText());
            }
            
            // Add local variables declared before cursor
            // This is a simplified version - could be enhanced with more sophisticated scope analysis
            if (containers.codeBlock != null) {
                extractVariablesFromCodeBlock(containers.codeBlock, availableVars, availableTypes);
            }
        }
        
        // Add class fields if in instance context
        if (containers.containingClass != null && containers.method != null && 
            !containers.method.hasModifierProperty(PsiModifier.STATIC)) {
            for (PsiField field : containers.containingClass.getFields()) {
                availableVars.add(new VariableInfo(
                    field.getName(),
                    field.getType().getPresentableText(),
                    VariableInfo.Scope.FIELD
                ));
            }
        }
        
        return new LocalScope(availableVars, availableTypes);
    }
    
    /**
     * Determines the type of completion needed based on context.
     */
    private static CompletionType determineCompletionType(CursorPosition cursorPos, ContainingElements containers, PsiElement elementAtCursor) {
        String linePrefix = cursorPos.linePrefix.trim();
        
        // Check for specific patterns
        if (linePrefix.endsWith(".")) {
            return CompletionType.MEMBER_ACCESS;
        }
        
        if (linePrefix.contains("new ") && !linePrefix.contains(";")) {
            return CompletionType.CONSTRUCTOR_CALL;
        }
        
        if (containers.method == null && containers.containingClass != null) {
            return CompletionType.CLASS_MEMBER_DECLARATION;
        }
        
        if (containers.method != null) {
            if (linePrefix.isEmpty() || Character.isWhitespace(linePrefix.charAt(linePrefix.length() - 1))) {
                return CompletionType.STATEMENT_START;
            }
            return CompletionType.EXPRESSION;
        }
        
        return CompletionType.GENERAL;
    }
    
    // Helper methods
    private static String extractIndentation(String lineText) {
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }
    
    private static List<String> extractImports(PsiJavaFile psiFile) {
        List<String> imports = new ArrayList<>();
        PsiImportList importList = psiFile.getImportList();
        if (importList != null) {
            for (PsiImportStatement importStmt : importList.getImportStatements()) {
                if (importStmt.getQualifiedName() != null) {
                    imports.add(importStmt.getQualifiedName());
                }
            }
        }
        return imports;
    }
    
    private static List<FieldInfo> extractFieldInfo(PsiClass psiClass) {
        List<FieldInfo> fields = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            fields.add(new FieldInfo(
                field.getName(),
                field.getType().getPresentableText(),
                field.hasModifierProperty(PsiModifier.STATIC),
                field.hasModifierProperty(PsiModifier.FINAL)
            ));
        }
        return fields;
    }
    
    private static List<MethodSignature> extractMethodSignatures(PsiClass psiClass) {
        List<MethodSignature> signatures = new ArrayList<>();
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getContainingClass() == psiClass) { // Skip inherited methods
                List<String> paramTypes = new ArrayList<>();
                for (PsiParameter param : method.getParameterList().getParameters()) {
                    paramTypes.add(param.getType().getPresentableText());
                }
                
                String returnType = method.getReturnType() != null ? 
                    method.getReturnType().getPresentableText() : "void";
                
                signatures.add(new MethodSignature(
                    method.getName(),
                    returnType,
                    paramTypes,
                    method.hasModifierProperty(PsiModifier.STATIC)
                ));
            }
        }
        return signatures;
    }
    
    private static List<VariableInfo> extractLocalVariables(PsiMethod method) {
        List<VariableInfo> vars = new ArrayList<>();
        // This could be enhanced with proper scope analysis
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitLocalVariable(PsiLocalVariable variable) {
                vars.add(new VariableInfo(
                    variable.getName(),
                    variable.getType().getPresentableText(),
                    VariableInfo.Scope.LOCAL
                ));
            }
        });
        return vars;
    }
    
    private static void extractVariablesFromCodeBlock(PsiCodeBlock codeBlock, List<VariableInfo> vars, List<String> types) {
        // Simple implementation - could be enhanced
        codeBlock.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitLocalVariable(PsiLocalVariable variable) {
                vars.add(new VariableInfo(
                    variable.getName(),
                    variable.getType().getPresentableText(),
                    VariableInfo.Scope.LOCAL
                ));
                types.add(variable.getType().getPresentableText());
            }
        });
    }
    
    private static LocalContext gatherLocalContext(Document document, int offset) {
        // Gather surrounding text context
        int lineNumber = document.getLineNumber(offset);
        int startLine = Math.max(0, lineNumber - MAX_CONTEXT_LINES / 2);
        int endLine = Math.min(document.getLineCount() - 1, lineNumber + MAX_CONTEXT_LINES / 2);
        
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        
        // Lines before cursor
        for (int i = startLine; i < lineNumber; i++) {
            String line = getLineText(document, i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "...";
            }
            before.append(line).append("\n");
        }
        
        // Current line before cursor
        int lineStart = document.getLineStartOffset(lineNumber);
        String currentLinePrefix = document.getText(new TextRange(lineStart, offset));
        before.append(currentLinePrefix);
        
        // Current line after cursor + following lines
        int lineEnd = document.getLineEndOffset(lineNumber);
        String currentLineSuffix = document.getText(new TextRange(offset, lineEnd));
        after.append(currentLineSuffix);
        
        for (int i = lineNumber + 1; i <= endLine; i++) {
            String line = getLineText(document, i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "...";
            }
            after.append("\n").append(line);
        }
        
        return new LocalContext(before.toString(), after.toString());
    }
    
    private static String getLineText(Document document, int lineNumber) {
        int start = document.getLineStartOffset(lineNumber);
        int end = document.getLineEndOffset(lineNumber);
        return document.getText(new TextRange(start, end));
    }
    
    private static String detectLanguageLevel(PsiJavaFile psiFile) {
        // Could be enhanced to detect actual language level
        return "java";
    }
    
    /**
     * Creates fallback context for non-Java files or when PSI analysis fails.
     */
    private static CompletionContext createFallbackContext(Editor editor) {
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        
        CursorPosition cursorPos = analyzeCursorPosition(document, offset);
        LocalContext localContext = gatherLocalContext(document, offset);
        
        return new CompletionContext(
            cursorPos,
            new ContainingElements(null, null, null, null),
            new SemanticInfo("", Collections.emptyList(), null, null,
                new LocalScope(Collections.emptyList(), Collections.emptyList())),
            CompletionType.GENERAL,
            localContext,
            "java"
        );
    }
}
