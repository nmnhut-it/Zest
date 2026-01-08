package com.zps.zest.mcp;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase;
import com.intellij.codeInspection.reference.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.RefactoringFactory;
import com.intellij.refactoring.RenameRefactoring;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor;
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor;
import com.intellij.refactoring.extractMethod.ExtractMethodHandler;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import com.intellij.refactoring.introduceField.IntroduceConstantHandler;
import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor;
import com.intellij.refactoring.move.moveClassesOrPackages.SingleSourceRootMoveDestination;
import com.intellij.refactoring.PackageWrapper;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Document;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Service providing PSI-based code navigation and refactoring tools for MCP.
 * Exposes IntelliJ's powerful code analysis capabilities to AI agents.
 */
public class PsiToolsService {
    private static final Logger LOG = Logger.getInstance(PsiToolsService.class);
    private static final int MAX_RESULTS = 50;

    private final Project project;

    public PsiToolsService(@NotNull Project project) {
        this.project = project;
    }

    // ========== Code Navigation Tools ==========

    /**
     * Find all usages of a symbol (class, method, field).
     */
    @NotNull
    public UsagesResult findUsages(@NotNull String className, @Nullable String memberName) {
        return ApplicationManager.getApplication().runReadAction((Computable<UsagesResult>) () -> {
            try {
                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    return UsagesResult.error(buildClassNotFoundError(className));
                }

                PsiElement target;
                if (memberName != null && !memberName.isEmpty()) {
                    // Find method or field
                    PsiMethod[] methods = psiClass.findMethodsByName(memberName, false);
                    if (methods.length > 0) {
                        target = methods[0];
                    } else {
                        PsiField field = psiClass.findFieldByName(memberName, false);
                        if (field != null) {
                            target = field;
                        } else {
                            return UsagesResult.error("Member not found: " + memberName);
                        }
                    }
                } else {
                    target = psiClass;
                }

                List<UsageLocation> usages = new ArrayList<>();
                Collection<PsiReference> refs = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll();

                for (PsiReference ref : refs) {
                    if (usages.size() >= MAX_RESULTS) break;

                    PsiElement element = ref.getElement();
                    PsiFile file = element.getContainingFile();
                    if (file == null) continue;

                    int offset = element.getTextOffset();
                    int line = getLineNumber(file, offset);
                    String context = getContextSnippet(element);

                    usages.add(new UsageLocation(
                            file.getVirtualFile().getPath(),
                            line,
                            context,
                            getUsageType(element)
                    ));
                }

                String targetName = memberName != null ? className + "." + memberName : className;
                return UsagesResult.success(targetName, usages);

            } catch (Exception e) {
                LOG.error("Error finding usages", e);
                return UsagesResult.error("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Find implementations of an interface or abstract method.
     */
    @NotNull
    public ImplementationsResult findImplementations(@NotNull String className, @Nullable String methodName) {
        return ApplicationManager.getApplication().runReadAction((Computable<ImplementationsResult>) () -> {
            try {
                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    return ImplementationsResult.error(buildClassNotFoundError(className));
                }

                List<ImplementationInfo> implementations = new ArrayList<>();

                if (methodName != null && !methodName.isEmpty()) {
                    // Find method implementations
                    PsiMethod[] methods = psiClass.findMethodsByName(methodName, false);
                    if (methods.length == 0) {
                        return ImplementationsResult.error("Method not found: " + methodName);
                    }

                    PsiMethod targetMethod = methods[0];
                    for (PsiMethod impl : targetMethod.findDeepestSuperMethods()) {
                        // Get overriding methods
                    }

                    // Search for overriding methods in subclasses
                    Collection<PsiClass> inheritors = ClassInheritorsSearch.search(psiClass, GlobalSearchScope.projectScope(project), true).findAll();
                    for (PsiClass inheritor : inheritors) {
                        if (implementations.size() >= MAX_RESULTS) break;

                        PsiMethod overridingMethod = inheritor.findMethodBySignature(targetMethod, false);
                        if (overridingMethod != null) {
                            PsiFile file = inheritor.getContainingFile();
                            implementations.add(new ImplementationInfo(
                                    inheritor.getQualifiedName(),
                                    file != null ? file.getVirtualFile().getPath() : "unknown",
                                    getMethodSignature(overridingMethod)
                            ));
                        }
                    }
                } else {
                    // Find class implementations
                    Collection<PsiClass> inheritors = ClassInheritorsSearch.search(psiClass, GlobalSearchScope.projectScope(project), true).findAll();
                    for (PsiClass inheritor : inheritors) {
                        if (implementations.size() >= MAX_RESULTS) break;

                        PsiFile file = inheritor.getContainingFile();
                        implementations.add(new ImplementationInfo(
                                inheritor.getQualifiedName(),
                                file != null ? file.getVirtualFile().getPath() : "unknown",
                                inheritor.isInterface() ? "interface" : "class"
                        ));
                    }
                }

                String targetName = methodName != null ? className + "." + methodName : className;
                return ImplementationsResult.success(targetName, implementations);

            } catch (Exception e) {
                LOG.error("Error finding implementations", e);
                return ImplementationsResult.error("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Get type hierarchy for a class.
     */
    @NotNull
    public TypeHierarchyResult getTypeHierarchy(@NotNull String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<TypeHierarchyResult>) () -> {
            try {
                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    return TypeHierarchyResult.error(buildClassNotFoundError(className));
                }

                // Get superclasses
                List<String> superClasses = new ArrayList<>();
                PsiClass current = psiClass.getSuperClass();
                while (current != null && !current.getQualifiedName().equals("java.lang.Object")) {
                    superClasses.add(current.getQualifiedName());
                    current = current.getSuperClass();
                }

                // Get interfaces
                List<String> interfaces = Arrays.stream(psiClass.getInterfaces())
                        .map(PsiClass::getQualifiedName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                // Get direct subclasses
                List<String> subClasses = ClassInheritorsSearch.search(psiClass, GlobalSearchScope.projectScope(project), false)
                        .findAll().stream()
                        .limit(MAX_RESULTS)
                        .map(PsiClass::getQualifiedName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                return TypeHierarchyResult.success(className, superClasses, interfaces, subClasses);

            } catch (Exception e) {
                LOG.error("Error getting type hierarchy", e);
                return TypeHierarchyResult.error("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Get call hierarchy for a method.
     */
    @NotNull
    public CallHierarchyResult getCallHierarchy(@NotNull String className, @NotNull String methodName, boolean callers) {
        return ApplicationManager.getApplication().runReadAction((Computable<CallHierarchyResult>) () -> {
            try {
                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    return CallHierarchyResult.error(buildClassNotFoundError(className));
                }

                PsiMethod[] methods = psiClass.findMethodsByName(methodName, false);
                if (methods.length == 0) {
                    return CallHierarchyResult.error("Method not found: " + methodName);
                }

                PsiMethod targetMethod = methods[0];
                List<CallInfo> calls = new ArrayList<>();

                if (callers) {
                    // Find methods that call this method
                    Collection<PsiReference> refs = MethodReferencesSearch.search(targetMethod, GlobalSearchScope.projectScope(project), true).findAll();
                    for (PsiReference ref : refs) {
                        if (calls.size() >= MAX_RESULTS) break;

                        PsiElement element = ref.getElement();
                        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                        if (containingMethod != null) {
                            PsiClass containingClass = containingMethod.getContainingClass();
                            calls.add(new CallInfo(
                                    containingClass != null ? containingClass.getQualifiedName() : "unknown",
                                    containingMethod.getName(),
                                    getLineNumber(element.getContainingFile(), element.getTextOffset())
                            ));
                        }
                    }
                } else {
                    // Find methods called by this method
                    PsiCodeBlock body = targetMethod.getBody();
                    if (body != null) {
                        body.accept(new JavaRecursiveElementVisitor() {
                            @Override
                            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                                if (calls.size() >= MAX_RESULTS) return;

                                PsiMethod resolved = expression.resolveMethod();
                                if (resolved != null) {
                                    PsiClass containingClass = resolved.getContainingClass();
                                    calls.add(new CallInfo(
                                            containingClass != null ? containingClass.getQualifiedName() : "unknown",
                                            resolved.getName(),
                                            getLineNumber(expression.getContainingFile(), expression.getTextOffset())
                                    ));
                                }
                                super.visitMethodCallExpression(expression);
                            }
                        });
                    }
                }

                return CallHierarchyResult.success(className + "." + methodName, callers, calls);

            } catch (Exception e) {
                LOG.error("Error getting call hierarchy", e);
                return CallHierarchyResult.error("Error: " + e.getMessage());
            }
        });
    }

    // ========== Refactoring Tools ==========

    /**
     * Rename a symbol using IntelliJ's native RenameProcessor.
     * Supports class, method, field renaming with full reference updates.
     */
    @NotNull
    public RefactoringResult rename(@NotNull String className, @Nullable String memberName, @NotNull String newName) {
        try {
            // Phase 1: Find target in read action
            class RenameInfo {
                PsiElement target;
                String oldName;
                int usageCount;
                String error;
            }

            RenameInfo info = ApplicationManager.getApplication().runReadAction((Computable<RenameInfo>) () -> {
                RenameInfo result = new RenameInfo();

                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    result.error = buildClassNotFoundError(className);
                    return result;
                }

                if (memberName != null && !memberName.isEmpty()) {
                    PsiMethod[] methods = psiClass.findMethodsByName(memberName, false);
                    if (methods.length > 0) {
                        result.target = methods[0];
                        result.oldName = memberName;
                    } else {
                        PsiField field = psiClass.findFieldByName(memberName, false);
                        if (field != null) {
                            result.target = field;
                            result.oldName = memberName;
                        } else {
                            result.error = buildMemberNotFoundError(psiClass, memberName);
                            return result;
                        }
                    }
                } else {
                    result.target = psiClass;
                    result.oldName = psiClass.getName();
                }

                // Count usages
                Collection<PsiReference> refs = ReferencesSearch.search(result.target, GlobalSearchScope.projectScope(project)).findAll();
                result.usageCount = refs.size();

                return result;
            });

            if (info.error != null) {
                return RefactoringResult.error(info.error);
            }

            // Phase 2: Use IntelliJ's RenameProcessor
            final String oldName = info.oldName;
            final int usageCount = info.usageCount;

            WriteCommandAction.runWriteCommandAction(project, () -> {
                RenameProcessor processor = new RenameProcessor(
                    project,
                    info.target,
                    newName,
                    true,  // searchInComments
                    true   // searchTextOccurrences
                );
                processor.run();
            });

            return RefactoringResult.success("rename", oldName, newName, usageCount);

        } catch (Exception e) {
            LOG.error("Error renaming", e);
            return RefactoringResult.error("Error: " + e.getMessage());
        }
    }

    /**
     * Get method body for potential extraction.
     */
    @NotNull
    public MethodBodyResult getMethodBody(@NotNull String className, @NotNull String methodName) {
        return ApplicationManager.getApplication().runReadAction((Computable<MethodBodyResult>) () -> {
            try {
                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    return MethodBodyResult.error(buildClassNotFoundError(className));
                }

                PsiMethod[] methods = psiClass.findMethodsByName(methodName, false);
                if (methods.length == 0) {
                    return MethodBodyResult.error("Method not found: " + methodName);
                }

                PsiMethod method = methods[0];
                PsiCodeBlock body = method.getBody();
                if (body == null) {
                    return MethodBodyResult.error("Method has no body (abstract or interface)");
                }

                // Analyze the method body
                List<CodeBlock> blocks = new ArrayList<>();
                int blockIndex = 0;

                for (PsiStatement statement : body.getStatements()) {
                    int startLine = getLineNumber(method.getContainingFile(), statement.getTextOffset());
                    String text = statement.getText();
                    String type = statement.getClass().getSimpleName().replace("Psi", "").replace("Impl", "");

                    blocks.add(new CodeBlock(blockIndex++, startLine, type, text));
                }

                return MethodBodyResult.success(
                        className + "." + methodName,
                        getMethodSignature(method),
                        body.getText(),
                        blocks
                );

            } catch (Exception e) {
                LOG.error("Error getting method body", e);
                return MethodBodyResult.error("Error: " + e.getMessage());
            }
        });
    }

    // ========== New Refactoring Tools ==========

    /**
     * Programmatically extract a literal expression to a static final constant.
     * Fully automated - no user interaction required.
     *
     * If targetValue is provided, extracts that value from within a string literal
     * and creates a concatenation (e.g., "hello 6 world" with targetValue="6" becomes
     * "hello " + CONSTANT + " world").
     *
     * If targetValue is null, extracts the entire first literal on the line.
     */
    @NotNull
    public RefactoringResult extractConstant(@NotNull String className, @NotNull String methodName,
                                              int lineNumber, @NotNull String constantName,
                                              @Nullable String targetValue) {
        try {
            // Phase 1: Gather info in read action
            class ExtractionInfo {
                PsiClass psiClass;
                PsiLiteralExpression targetLiteral;
                String originalText;
                String typeText;
                String constantValue; // The value to extract (whole literal or part of string)
                boolean isStringExtraction; // True if extracting from within a string
                List<PsiLiteralExpression> allOccurrences = new ArrayList<>();
                String error;
            }

            ExtractionInfo info = ApplicationManager.getApplication().runReadAction((Computable<ExtractionInfo>) () -> {
                ExtractionInfo result = new ExtractionInfo();

                result.psiClass = findClass(className);
                if (result.psiClass == null) {
                    result.error = buildClassNotFoundError(className);
                    return result;
                }

                PsiMethod[] methods = result.psiClass.findMethodsByName(methodName, false);
                if (methods.length == 0) {
                    result.error = buildMethodNotFoundError(result.psiClass, methodName);
                    return result;
                }

                PsiMethod method = methods[0];
                PsiFile file = method.getContainingFile();
                LOG.info("Found method " + methodName + " in file: " +
                        (file != null ? file.getName() : "null"));
                if (file == null) {
                    result.error = "Cannot find containing file";
                    return result;
                }

                // Log file path for debugging
                String filePath = file.getVirtualFile() != null ? file.getVirtualFile().getPath() : "unknown";
                LOG.info("Searching for literal at line " + lineNumber + " in: " + filePath);

                // Find expression at line
                PsiElement elementAtLine = findElementAtLine(file, lineNumber);
                if (elementAtLine == null) {
                    // Get the actual content at that line for debugging
                    String lineContent = getLineContent(file, lineNumber);
                    result.error = "No element found at line " + lineNumber +
                            " in " + file.getName() + ". Line content: " + lineContent;
                    return result;
                }

                // Find literal expression - check if elementAtLine is already a literal
                if (elementAtLine instanceof PsiLiteralExpression) {
                    result.targetLiteral = (PsiLiteralExpression) elementAtLine;
                } else {
                    result.targetLiteral = PsiTreeUtil.getParentOfType(elementAtLine, PsiLiteralExpression.class, false);
                }

                if (result.targetLiteral == null) {
                    result.error = "No literal expression found at line " + lineNumber +
                            ". Found element type: " + elementAtLine.getClass().getSimpleName();
                    return result;
                }

                result.originalText = result.targetLiteral.getText();
                Object value = result.targetLiteral.getValue();

                // Check if we're extracting from within a string
                if (targetValue != null && value instanceof String) {
                    String strValue = (String) value;
                    if (!strValue.contains(targetValue)) {
                        result.error = "Target value '" + targetValue + "' not found in string: " + result.originalText;
                        return result;
                    }
                    result.isStringExtraction = true;
                    result.constantValue = targetValue;
                    // Determine type: if targetValue is numeric, use int/long; otherwise String
                    try {
                        Integer.parseInt(targetValue);
                        result.typeText = "int";
                    } catch (NumberFormatException e1) {
                        try {
                            Long.parseLong(targetValue);
                            result.typeText = "long";
                        } catch (NumberFormatException e2) {
                            result.typeText = "String";
                        }
                    }
                } else {
                    // Extract entire literal
                    result.isStringExtraction = false;
                    result.constantValue = result.originalText;
                    PsiType type = result.targetLiteral.getType();
                    if (type == null) {
                        result.error = "Cannot determine type of expression";
                        return result;
                    }
                    result.typeText = type.getCanonicalText();
                }

                // Find all occurrences of this literal in the class
                final String searchText = result.originalText;
                result.psiClass.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitLiteralExpression(PsiLiteralExpression expression) {
                        if (expression.getText().equals(searchText)) {
                            result.allOccurrences.add(expression);
                        }
                        super.visitLiteralExpression(expression);
                    }
                });

                return result;
            });

            if (info.error != null) {
                return RefactoringResult.error(info.error);
            }

            // Phase 2: Perform extraction in write action
            final String originalText = info.originalText;
            final int occurrenceCount = info.allOccurrences.size();
            final String extractedValue = info.constantValue;

            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                // Create constant field
                String constantValueText;
                if (info.isStringExtraction && "String".equals(info.typeText)) {
                    constantValueText = "\"" + info.constantValue + "\"";
                } else if (info.isStringExtraction) {
                    constantValueText = info.constantValue; // numeric value
                } else {
                    constantValueText = info.originalText;
                }

                String fieldDeclaration = String.format(
                    "private static final %s %s = %s;",
                    info.typeText, constantName, constantValueText
                );
                PsiField constantField = factory.createFieldFromText(fieldDeclaration, info.psiClass);

                // Add field to class
                PsiField[] existingFields = info.psiClass.getFields();
                PsiElement anchor = null;
                for (PsiField f : existingFields) {
                    if (f.hasModifierProperty(PsiModifier.STATIC) && f.hasModifierProperty(PsiModifier.FINAL)) {
                        anchor = f;
                    }
                }
                if (anchor != null) {
                    info.psiClass.addAfter(constantField, anchor);
                } else {
                    info.psiClass.addAfter(constantField, info.psiClass.getLBrace());
                }

                // Replace occurrences
                for (PsiLiteralExpression literal : info.allOccurrences) {
                    if (!literal.isValid()) continue;

                    if (info.isStringExtraction) {
                        // Split string and create concatenation
                        String strValue = (String) literal.getValue();
                        int idx = strValue.indexOf(info.constantValue);
                        String before = strValue.substring(0, idx);
                        String after = strValue.substring(idx + info.constantValue.length());

                        // Build replacement expression
                        StringBuilder replacement = new StringBuilder();
                        if (!before.isEmpty()) {
                            replacement.append("\"").append(escapeString(before)).append("\" + ");
                        }
                        replacement.append(constantName);
                        if (!after.isEmpty()) {
                            replacement.append(" + \"").append(escapeString(after)).append("\"");
                        }

                        PsiExpression newExpr = factory.createExpressionFromText(replacement.toString(), literal);
                        literal.replace(newExpr);
                    } else {
                        // Simple replacement
                        PsiExpression replacement = factory.createExpressionFromText(constantName, literal);
                        literal.replace(replacement);
                    }
                }

                JavaCodeStyleManager.getInstance(project).shortenClassReferences(info.psiClass);
            });

            String resultDesc = info.isStringExtraction
                ? "Extracted '" + extractedValue + "' from " + originalText
                : originalText;
            return RefactoringResult.success("extractConstant", resultDesc, constantName, occurrenceCount);

        } catch (Exception e) {
            LOG.error("Error extracting constant", e);
            return RefactoringResult.error("Error: " + e.getMessage());
        }
    }

    /**
     * Escape special characters in a string for Java string literals.
     */
    private String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Extract code into a new method using IntelliJ's ExtractMethodProcessor.
     * Uses the same approach as IntelliJ's native Extract Method refactoring.
     * Fully automated with proper handling of:
     * - Return values (auto-detects if extracted code produces a value needed later)
     * - Parameters (auto-detects variables from outer scope)
     * - Exceptions (preserves throws declarations)
     */
    @NotNull
    public RefactoringResult extractMethod(@NotNull String className, @NotNull String sourceMethodName,
                                            int startLine, int endLine, @NotNull String newMethodName) {
        try {
            // Phase 1: Gather info and find elements using CodeInsightUtil (like IntelliJ does)
            class ExtractionInfo {
                PsiFile file;
                VirtualFile virtualFile;
                int startOffset;
                int endOffset;
                String error;
            }

            ExtractionInfo info = ApplicationManager.getApplication().runReadAction((Computable<ExtractionInfo>) () -> {
                ExtractionInfo result = new ExtractionInfo();

                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    result.error = buildClassNotFoundError(className);
                    return result;
                }

                PsiMethod[] methods = psiClass.findMethodsByName(sourceMethodName, false);
                if (methods.length == 0) {
                    result.error = buildMethodNotFoundError(psiClass, sourceMethodName);
                    return result;
                }

                PsiMethod sourceMethod = methods[0];
                PsiCodeBlock body = sourceMethod.getBody();
                if (body == null) {
                    result.error = "Method has no body";
                    return result;
                }

                result.file = sourceMethod.getContainingFile();
                if (result.file == null || result.file.getVirtualFile() == null) {
                    result.error = "Cannot find containing file";
                    return result;
                }
                result.virtualFile = result.file.getVirtualFile();

                // Find statements in range to get offsets
                List<PsiStatement> statements = new ArrayList<>();
                for (PsiStatement statement : body.getStatements()) {
                    int line = getLineNumber(result.file, statement.getTextOffset());
                    if (line >= startLine && line <= endLine) {
                        statements.add(statement);
                    }
                }

                if (statements.isEmpty()) {
                    result.error = "No statements found between lines " + startLine + " and " + endLine;
                    return result;
                }

                result.startOffset = statements.get(0).getTextRange().getStartOffset();
                result.endOffset = statements.get(statements.size() - 1).getTextRange().getEndOffset();

                return result;
            });

            if (info.error != null) {
                return RefactoringResult.error(info.error);
            }

            // Phase 2: Use IntelliJ's ExtractMethodProcessor with proper patterns
            final String[] resultMessage = {null};
            final boolean[] success = {false};
            final int[] elementCount = {0};

            // Must run on EDT for editor operations
            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    // Open file and get editor
                    OpenFileDescriptor descriptor = new OpenFileDescriptor(project, info.virtualFile, info.startOffset);
                    Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);

                    if (editor == null) {
                        resultMessage[0] = "Could not open editor for file";
                        return;
                    }

                    // Set selection (like user would select code)
                    editor.getSelectionModel().setSelection(info.startOffset, info.endOffset);

                    // Use CodeInsightUtil to find elements (same as IntelliJ's internal approach)
                    PsiElement[] elements = ApplicationManager.getApplication().runReadAction((Computable<PsiElement[]>) () -> {
                        // Try to find expression first
                        PsiExpression expr = CodeInsightUtil.findExpressionInRange(info.file, info.startOffset, info.endOffset);
                        if (expr != null) {
                            return new PsiElement[]{expr};
                        }
                        // Fall back to statements
                        return CodeInsightUtil.findStatementsInRange(info.file, info.startOffset, info.endOffset);
                    });

                    if (elements == null || elements.length == 0) {
                        resultMessage[0] = "No extractable code found in selection";
                        return;
                    }

                    elementCount[0] = elements.length;

                    // Create and run ExtractMethodProcessor
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        try {
                            ExtractMethodProcessor processor = new ExtractMethodProcessor(
                                project, editor, elements,
                                null,  // forcedReturnType - let it auto-detect
                                "Extract Method",
                                newMethodName,
                                null   // helpId
                            );

                            // Disable error dialogs for programmatic use
                            processor.setShowErrorDialogs(false);

                            // Prepare analyzes the code
                            if (processor.prepare()) {
                                // Configure visibility
                                processor.setMethodVisibility(PsiModifier.PRIVATE);

                                // Use ExtractMethodHandler to perform extraction (IntelliJ's way)
                                ExtractMethodHandler.extractMethod(project, processor);

                                success[0] = true;
                                resultMessage[0] = "Created method: " + newMethodName;
                            } else {
                                resultMessage[0] = "Cannot extract: code analysis failed (may have multiple exit points or other issues)";
                            }
                        } catch (PrepareFailedException e) {
                            resultMessage[0] = "Cannot extract: " + e.getMessage();
                        } catch (Exception e) {
                            resultMessage[0] = "Extraction failed: " + e.getMessage();
                            LOG.error("Extract method failed", e);
                        }
                    });

                } catch (Exception e) {
                    resultMessage[0] = "Error: " + e.getMessage();
                    LOG.error("Extract method error", e);
                }
            });

            if (success[0]) {
                return RefactoringResult.success("extractMethod",
                    sourceMethodName + " (lines " + startLine + "-" + endLine + ")",
                    newMethodName, elementCount[0]);
            } else {
                return RefactoringResult.error(resultMessage[0] != null ? resultMessage[0] : "Unknown error");
            }

        } catch (Exception e) {
            LOG.error("Error extracting method", e);
            return RefactoringResult.error("Error: " + e.getMessage());
        }
    }

    /**
     * Safely delete a class member using IntelliJ's SafeDeleteProcessor.
     * Checks for usages and only deletes if safe.
     */
    @NotNull
    public SafeDeleteResult safeDelete(@NotNull String className, @Nullable String memberName) {
        try {
            // Phase 1: Find target and check usages in read action
            class DeleteInfo {
                PsiElement target;
                String targetName;
                int usageCount;
                List<String> usageLocations = new ArrayList<>();
                String error;
            }

            DeleteInfo info = ApplicationManager.getApplication().runReadAction((Computable<DeleteInfo>) () -> {
                DeleteInfo result = new DeleteInfo();

                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    result.error = buildClassNotFoundError(className);
                    return result;
                }

                if (memberName != null && !memberName.isEmpty()) {
                    PsiMethod[] methods = psiClass.findMethodsByName(memberName, false);
                    if (methods.length > 0) {
                        result.target = methods[0];
                        result.targetName = className + "." + memberName + "()";
                    } else {
                        PsiField field = psiClass.findFieldByName(memberName, false);
                        if (field != null) {
                            result.target = field;
                            result.targetName = className + "." + memberName;
                        } else {
                            result.error = buildMemberNotFoundError(psiClass, memberName);
                            return result;
                        }
                    }
                } else {
                    result.target = psiClass;
                    result.targetName = className;
                }

                // Check for usages
                Collection<PsiReference> refs = ReferencesSearch.search(result.target, GlobalSearchScope.projectScope(project)).findAll();
                result.usageCount = refs.size();

                for (PsiReference ref : refs) {
                    if (result.usageLocations.size() >= 10) {
                        result.usageLocations.add("... and " + (result.usageCount - 10) + " more");
                        break;
                    }
                    PsiElement element = ref.getElement();
                    PsiFile file = element.getContainingFile();
                    if (file != null) {
                        int line = getLineNumber(file, element.getTextOffset());
                        result.usageLocations.add(file.getName() + ":" + line);
                    }
                }

                return result;
            });

            if (info.error != null) {
                return SafeDeleteResult.error(info.error);
            }

            if (info.usageCount > 0) {
                return SafeDeleteResult.unsafe(info.targetName, info.usageCount, info.usageLocations);
            }

            // Phase 2: Use IntelliJ's SafeDeleteProcessor
            WriteCommandAction.runWriteCommandAction(project, () -> {
                SafeDeleteProcessor processor = SafeDeleteProcessor.createInstance(
                    project,
                    null,  // runnable to execute after
                    new PsiElement[]{info.target},
                    true,  // searchInComments
                    true,  // searchNonJava
                    true   // safe delete overriding methods
                );
                processor.run();
            });

            return SafeDeleteResult.deleted(info.targetName);

        } catch (Exception e) {
            LOG.error("Error in safe delete", e);
            return SafeDeleteResult.error("Error: " + e.getMessage());
        }
    }

    /**
     * Find dead code (unused declarations) in a class or project.
     */
    @NotNull
    public DeadCodeResult findDeadCode(@Nullable String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<DeadCodeResult>) () -> {
            try {
                List<DeadCodeItem> deadCode = new ArrayList<>();

                if (className != null) {
                    // Analyze single class
                    PsiClass psiClass = findClass(className);
                    if (psiClass == null) {
                        return DeadCodeResult.error(buildClassNotFoundError(className));
                    }

                    // Check each method
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (method.isConstructor()) continue;
                        if (isPublicApi(method)) continue; // Skip public API

                        Collection<PsiReference> refs = MethodReferencesSearch.search(method, GlobalSearchScope.projectScope(project), true).findAll();
                        if (refs.isEmpty()) {
                            PsiFile file = method.getContainingFile();
                            deadCode.add(new DeadCodeItem(
                                    "method",
                                    method.getName(),
                                    file != null ? file.getName() : "unknown",
                                    getLineNumber(file, method.getTextOffset())
                            ));
                        }
                    }

                    // Check each field
                    for (PsiField field : psiClass.getFields()) {
                        if (isPublicApi(field)) continue;

                        Collection<PsiReference> refs = ReferencesSearch.search(field, GlobalSearchScope.projectScope(project)).findAll();
                        if (refs.isEmpty()) {
                            PsiFile file = field.getContainingFile();
                            deadCode.add(new DeadCodeItem(
                                    "field",
                                    field.getName(),
                                    file != null ? file.getName() : "unknown",
                                    getLineNumber(file, field.getTextOffset())
                            ));
                        }
                    }

                    return DeadCodeResult.success(className, deadCode);

                } else {
                    // Scan whole project - just report classes with no usages
                    // This is a simplified scan - full project scan would be expensive
                    return DeadCodeResult.error("Please specify a className for targeted analysis");
                }

            } catch (Exception e) {
                LOG.error("Error finding dead code", e);
                return DeadCodeResult.error("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Move a class to a different package.
     * Uses IntelliJ's MoveClassesOrPackagesProcessor for proper refactoring.
     */
    @NotNull
    public MoveClassResult moveClass(@NotNull String className, @NotNull String targetPackage) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<MoveClassResult>) () -> {
                PsiClass psiClass = findClass(className);
                if (psiClass == null) {
                    return MoveClassResult.error(buildClassNotFoundError(className));
                }

                PsiFile containingFile = psiClass.getContainingFile();
                if (containingFile == null) {
                    return MoveClassResult.error("Cannot find containing file for class");
                }

                // Find target package
                PsiPackage targetPsiPackage = JavaPsiFacade.getInstance(project).findPackage(targetPackage);
                PsiDirectory targetDirectory = null;

                if (targetPsiPackage != null) {
                    PsiDirectory[] directories = targetPsiPackage.getDirectories();
                    if (directories.length > 0) {
                        targetDirectory = directories[0];
                    }
                }

                if (targetDirectory == null) {
                    // Try to create the package directory
                    VirtualFile[] sourceRoots = ProjectRootManager.getInstance(project).getContentSourceRoots();
                    if (sourceRoots.length == 0) {
                        return MoveClassResult.error("No source roots found in project");
                    }

                    PsiManager psiManager = PsiManager.getInstance(project);
                    PsiDirectory sourceRoot = psiManager.findDirectory(sourceRoots[0]);
                    if (sourceRoot == null) {
                        return MoveClassResult.error("Cannot access source root");
                    }

                    // Create package directories
                    final PsiDirectory finalSourceRoot = sourceRoot;
                    final String[] packageParts = targetPackage.split("\\.");

                    try {
                        targetDirectory = WriteCommandAction.writeCommandAction(project).compute(() -> {
                            PsiDirectory current = finalSourceRoot;
                            for (String part : packageParts) {
                                PsiDirectory subDir = current.findSubdirectory(part);
                                if (subDir == null) {
                                    subDir = current.createSubdirectory(part);
                                }
                                current = subDir;
                            }
                            return current;
                        });
                    } catch (Exception e) {
                        return MoveClassResult.error("Failed to create target package: " + e.getMessage());
                    }
                }

                // Count references before move
                Collection<PsiReference> refs = ReferencesSearch.search(psiClass, GlobalSearchScope.projectScope(project)).findAll();
                int referenceCount = refs.size();

                // Perform the move using IntelliJ's processor
                final PsiDirectory finalTargetDir = targetDirectory;
                final PsiClass[] classesToMove = new PsiClass[]{psiClass};
                final String originalPackage = psiClass.getQualifiedName();

                try {
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        // Use MoveClassesOrPackagesProcessor for proper refactoring
                        MoveClassesOrPackagesProcessor processor = new MoveClassesOrPackagesProcessor(
                                project,
                                classesToMove,
                                new SingleSourceRootMoveDestination(
                                        PackageWrapper.create(JavaDirectoryService.getInstance().getPackage(finalTargetDir)),
                                        finalTargetDir
                                ),
                                true,  // searchInComments
                                true,  // searchInNonJavaFiles
                                null   // moveCallback
                        );
                        processor.run();
                    });
                } catch (Exception e) {
                    return MoveClassResult.error("Move failed: " + e.getMessage());
                }

                return MoveClassResult.success(originalPackage, targetPackage + "." + psiClass.getName(), referenceCount);
            });

        } catch (Exception e) {
            LOG.error("Error moving class", e);
            return MoveClassResult.error("Error: " + e.getMessage());
        }
    }

    private boolean isPublicApi(PsiModifierListOwner element) {
        return element.hasModifierProperty(PsiModifier.PUBLIC);
    }

    /**
     * Find a literal expression at the specified line number.
     * Scans the line and adjacent lines (±1) for any PsiLiteralExpression.
     * This is lenient to handle off-by-one line number differences.
     */
    private PsiElement findElementAtLine(PsiFile file, int lineNumber) {
        // Try exact line first, then adjacent lines
        for (int lineOffset : new int[]{0, -1, 1}) {
            int targetLine = lineNumber + lineOffset;
            if (targetLine < 1) continue;

            PsiLiteralExpression literal = findLiteralAtExactLine(file, targetLine);
            if (literal != null) {
                if (lineOffset != 0) {
                    LOG.info("Found literal at adjacent line " + targetLine +
                            " (requested line " + lineNumber + ")");
                }
                return literal;
            }
        }

        // Fall back to finding any element at the exact line
        return findAnyElementAtLine(file, lineNumber);
    }

    /**
     * Find a literal expression at the exact specified line.
     */
    private PsiLiteralExpression findLiteralAtExactLine(PsiFile file, int lineNumber) {
        int[] offsets = getLineOffsets(file, lineNumber);
        if (offsets == null) return null;

        int lineStartOffset = offsets[0];
        int lineEndOffset = offsets[1];

        // Scan for any literal expression on this line
        Set<PsiLiteralExpression> foundLiterals = new LinkedHashSet<>();
        for (int i = lineStartOffset; i < lineEndOffset; i++) {
            PsiElement e = file.findElementAt(i);
            if (e != null) {
                PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(e, PsiLiteralExpression.class, false);
                if (literal != null) {
                    foundLiterals.add(literal);
                }
            }
        }

        if (!foundLiterals.isEmpty()) {
            PsiLiteralExpression firstLiteral = foundLiterals.iterator().next();
            LOG.info("Found " + foundLiterals.size() + " literals at line " + lineNumber +
                    ": " + firstLiteral.getText());
            return firstLiteral;
        }
        return null;
    }

    /**
     * Find any meaningful element at the specified line (fallback).
     */
    private PsiElement findAnyElementAtLine(PsiFile file, int lineNumber) {
        int[] offsets = getLineOffsets(file, lineNumber);
        if (offsets == null) return null;

        String text = file.getText();
        for (int i = offsets[0]; i < offsets[1]; i++) {
            if (Character.isWhitespace(text.charAt(i))) continue;
            PsiElement e = file.findElementAt(i);
            if (e != null && !(e instanceof PsiWhiteSpace)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Get start and end offsets for a line number.
     * Returns null if line not found.
     */
    private int[] getLineOffsets(PsiFile file, int lineNumber) {
        String text = file.getText();
        int currentLine = 1;
        int lineStartOffset = 0;

        for (int i = 0; i < text.length(); i++) {
            if (currentLine == lineNumber) {
                lineStartOffset = i;
                // Find end of this line
                int lineEndOffset = text.length();
                for (int j = i; j < text.length(); j++) {
                    if (text.charAt(j) == '\n') {
                        lineEndOffset = j;
                        break;
                    }
                }
                return new int[]{lineStartOffset, lineEndOffset};
            }
            if (text.charAt(i) == '\n') {
                currentLine++;
            }
        }

        LOG.warn("Line " + lineNumber + " not found (file has " + currentLine + " lines)");
        return null;
    }

    // ========== Helper Methods ==========

    /**
     * Find a class by name with smart resolution.
     * First tries fully qualified name, then searches for simple name matches.
     * Returns the class if found, or null with detailed logging.
     */
    @Nullable
    private PsiClass findClass(String className) {
        // Try exact fully qualified name first
        PsiClass result = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project));
        if (result != null) {
            return result;
        }

        // Try to find by simple name if not fully qualified
        if (!className.contains(".")) {
            return findClassBySimpleName(className);
        }

        // Try project scope only (maybe the class is in project but not indexed globally)
        result = JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.projectScope(project));
        if (result != null) {
            return result;
        }

        LOG.warn("Class not found: " + className + ". Tried fully qualified and project scopes.");
        return null;
    }

    /**
     * Find a class by simple name (without package).
     * Searches project scope and returns the best match.
     */
    @Nullable
    private PsiClass findClassBySimpleName(String simpleName) {
        LOG.info("Searching for class by simple name: " + simpleName);

        // Search in project scope for classes matching the simple name
        List<PsiClass> matches = new ArrayList<>();

        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(simpleName, projectScope);

        if (classes.length == 1) {
            LOG.info("Found unique class: " + classes[0].getQualifiedName());
            return classes[0];
        } else if (classes.length > 1) {
            // Multiple matches - log them for debugging
            StringBuilder sb = new StringBuilder("Found " + classes.length + " classes matching '" + simpleName + "':\n");
            for (PsiClass cls : classes) {
                sb.append("  - ").append(cls.getQualifiedName()).append("\n");
                matches.add(cls);
            }
            LOG.info(sb.toString());

            // Return the first project-level match (prioritize non-library classes)
            for (PsiClass cls : classes) {
                PsiFile file = cls.getContainingFile();
                if (file != null && file.getVirtualFile() != null) {
                    String path = file.getVirtualFile().getPath();
                    if (path.contains("/src/main/") || path.contains("\\src\\main\\")) {
                        LOG.info("Selecting main source class: " + cls.getQualifiedName());
                        return cls;
                    }
                }
            }
            // Return first match if no main source found
            return classes[0];
        }

        LOG.warn("No class found with simple name: " + simpleName);
        return null;
    }

    /**
     * Build a helpful error message when a class is not found.
     * Suggests similar class names that exist in the project.
     */
    private String buildClassNotFoundError(String className) {
        StringBuilder error = new StringBuilder("Class not found: " + className);

        // Try to find similar names
        String simpleName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;

        // Search for classes with similar names
        List<String> suggestions = new ArrayList<>();
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);

        // Try exact simple name first
        PsiClass[] exactMatches = PsiShortNamesCache.getInstance(project).getClassesByName(simpleName, projectScope);
        for (PsiClass cls : exactMatches) {
            if (suggestions.size() < 5) {
                suggestions.add(cls.getQualifiedName());
            }
        }

        // If no exact matches, try prefix match
        if (suggestions.isEmpty() && simpleName.length() >= 3) {
            String[] allClassNames = PsiShortNamesCache.getInstance(project).getAllClassNames();
            for (String name : allClassNames) {
                if (name.toLowerCase().contains(simpleName.toLowerCase()) && suggestions.size() < 5) {
                    PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(name, projectScope);
                    for (PsiClass cls : classes) {
                        if (suggestions.size() < 5) {
                            suggestions.add(cls.getQualifiedName());
                        }
                    }
                }
            }
        }

        if (!suggestions.isEmpty()) {
            error.append("\n\nDid you mean one of these? (use fully qualified name):");
            for (String suggestion : suggestions) {
                error.append("\n  - ").append(suggestion);
            }
        } else {
            error.append("\n\nHint: Use the fully qualified class name (e.g., com.example.MyClass)");
        }

        return error.toString();
    }

    /**
     * Build a helpful error message when a method is not found in a class.
     * Lists available methods to help LLM correct its call.
     */
    private String buildMethodNotFoundError(PsiClass psiClass, String methodName) {
        StringBuilder error = new StringBuilder("Method not found: " + methodName);

        PsiMethod[] methods = psiClass.getMethods();
        if (methods.length > 0) {
            error.append("\n\nAvailable methods in ").append(psiClass.getName()).append(":");
            int count = 0;
            for (PsiMethod m : methods) {
                if (count >= 15) {
                    error.append("\n  ... and ").append(methods.length - 15).append(" more");
                    break;
                }
                error.append("\n  - ").append(m.getName()).append("()");
                count++;
            }
        }

        return error.toString();
    }

    /**
     * Build a helpful error message when a member is not found in a class.
     * Lists available methods and fields to help LLM correct its call.
     */
    private String buildMemberNotFoundError(PsiClass psiClass, String memberName) {
        StringBuilder error = new StringBuilder("Member not found: " + memberName);
        error.append("\nNote: This tool only works on CLASS-LEVEL members (fields, methods), not local variables.");

        // List available methods
        PsiMethod[] methods = psiClass.getMethods();
        if (methods.length > 0) {
            error.append("\n\nAvailable methods:");
            int count = 0;
            for (PsiMethod m : methods) {
                if (count >= 10) {
                    error.append("\n  ... and ").append(methods.length - 10).append(" more");
                    break;
                }
                error.append("\n  - ").append(m.getName()).append("()");
                count++;
            }
        }

        // List available fields
        PsiField[] fields = psiClass.getFields();
        if (fields.length > 0) {
            error.append("\n\nAvailable fields:");
            int count = 0;
            for (PsiField f : fields) {
                if (count >= 10) {
                    error.append("\n  ... and ").append(fields.length - 10).append(" more");
                    break;
                }
                error.append("\n  - ").append(f.getName());
                count++;
            }
        }

        return error.toString();
    }

    private int getLineNumber(PsiFile file, int offset) {
        if (file == null) return 0;
        String text = file.getText();
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Get the content of a specific line in the file (for debugging).
     */
    private String getLineContent(PsiFile file, int lineNumber) {
        if (file == null) return "(null file)";
        String text = file.getText();
        String[] lines = text.split("\n", -1);
        if (lineNumber < 1 || lineNumber > lines.length) {
            return "(line " + lineNumber + " out of range, file has " + lines.length + " lines)";
        }
        String content = lines[lineNumber - 1];
        if (content.length() > 100) {
            content = content.substring(0, 100) + "...";
        }
        return "'" + content.trim() + "'";
    }

    private String getContextSnippet(PsiElement element) {
        PsiElement context = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (context == null) {
            context = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        }
        if (context == null) {
            context = element.getParent();
        }
        if (context != null) {
            String text = context.getText();
            if (text.length() > 100) {
                text = text.substring(0, 100) + "...";
            }
            return text.replace("\n", " ").trim();
        }
        return element.getText();
    }

    private String getUsageType(PsiElement element) {
        PsiElement parent = element.getParent();
        if (parent instanceof PsiMethodCallExpression) return "method_call";
        if (parent instanceof PsiNewExpression) return "instantiation";
        if (parent instanceof PsiReferenceExpression) {
            PsiElement grandParent = parent.getParent();
            if (grandParent instanceof PsiAssignmentExpression) return "assignment";
            return "reference";
        }
        if (parent instanceof PsiTypeElement) return "type_usage";
        if (parent instanceof PsiImportStatement) return "import";
        return "other";
    }

    private String getMethodSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getPresentableText());
            sb.append(" ").append(params[i].getName());
        }
        sb.append(")");
        if (method.getReturnType() != null) {
            sb.append(": ").append(method.getReturnType().getPresentableText());
        }
        return sb.toString();
    }

    // ========== Result Classes ==========

    public static class UsagesResult {
        private final boolean success;
        private final String targetName;
        private final List<UsageLocation> usages;
        private final String error;

        private UsagesResult(boolean success, String targetName, List<UsageLocation> usages, String error) {
            this.success = success;
            this.targetName = targetName;
            this.usages = usages;
            this.error = error;
        }

        public static UsagesResult success(String targetName, List<UsageLocation> usages) {
            return new UsagesResult(true, targetName, usages, null);
        }

        public static UsagesResult error(String message) {
            return new UsagesResult(false, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getTargetName() { return targetName; }
        public List<UsageLocation> getUsages() { return usages; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# Usages of `").append(targetName).append("`\n\n");
            sb.append("Found **").append(usages.size()).append("** usages\n\n");

            Map<String, List<UsageLocation>> byFile = usages.stream()
                    .collect(Collectors.groupingBy(UsageLocation::getFilePath));

            for (Map.Entry<String, List<UsageLocation>> entry : byFile.entrySet()) {
                sb.append("## ").append(entry.getKey()).append("\n\n");
                for (UsageLocation usage : entry.getValue()) {
                    sb.append("- Line ").append(usage.getLine())
                            .append(" (").append(usage.getType()).append("): `")
                            .append(truncate(usage.getContext(), 60)).append("`\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        }

        private String truncate(String s, int max) {
            return s.length() > max ? s.substring(0, max) + "..." : s;
        }
    }

    public static class UsageLocation {
        private final String filePath;
        private final int line;
        private final String context;
        private final String type;

        public UsageLocation(String filePath, int line, String context, String type) {
            this.filePath = filePath;
            this.line = line;
            this.context = context;
            this.type = type;
        }

        public String getFilePath() { return filePath; }
        public int getLine() { return line; }
        public String getContext() { return context; }
        public String getType() { return type; }
    }

    public static class ImplementationsResult {
        private final boolean success;
        private final String targetName;
        private final List<ImplementationInfo> implementations;
        private final String error;

        private ImplementationsResult(boolean success, String targetName, List<ImplementationInfo> implementations, String error) {
            this.success = success;
            this.targetName = targetName;
            this.implementations = implementations;
            this.error = error;
        }

        public static ImplementationsResult success(String targetName, List<ImplementationInfo> implementations) {
            return new ImplementationsResult(true, targetName, implementations, null);
        }

        public static ImplementationsResult error(String message) {
            return new ImplementationsResult(false, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getTargetName() { return targetName; }
        public List<ImplementationInfo> getImplementations() { return implementations; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# Implementations of `").append(targetName).append("`\n\n");
            sb.append("Found **").append(implementations.size()).append("** implementations\n\n");

            for (ImplementationInfo impl : implementations) {
                sb.append("- **").append(impl.getClassName()).append("**\n");
                sb.append("  - File: `").append(impl.getFilePath()).append("`\n");
                sb.append("  - ").append(impl.getSignature()).append("\n");
            }

            return sb.toString();
        }
    }

    public static class ImplementationInfo {
        private final String className;
        private final String filePath;
        private final String signature;

        public ImplementationInfo(String className, String filePath, String signature) {
            this.className = className;
            this.filePath = filePath;
            this.signature = signature;
        }

        public String getClassName() { return className; }
        public String getFilePath() { return filePath; }
        public String getSignature() { return signature; }
    }

    public static class TypeHierarchyResult {
        private final boolean success;
        private final String className;
        private final List<String> superClasses;
        private final List<String> interfaces;
        private final List<String> subClasses;
        private final String error;

        private TypeHierarchyResult(boolean success, String className, List<String> superClasses,
                                    List<String> interfaces, List<String> subClasses, String error) {
            this.success = success;
            this.className = className;
            this.superClasses = superClasses;
            this.interfaces = interfaces;
            this.subClasses = subClasses;
            this.error = error;
        }

        public static TypeHierarchyResult success(String className, List<String> superClasses,
                                                   List<String> interfaces, List<String> subClasses) {
            return new TypeHierarchyResult(true, className, superClasses, interfaces, subClasses, null);
        }

        public static TypeHierarchyResult error(String message) {
            return new TypeHierarchyResult(false, null, null, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# Type Hierarchy: `").append(className).append("`\n\n");

            sb.append("## Superclasses\n");
            if (superClasses.isEmpty()) {
                sb.append("- (none)\n");
            } else {
                for (String s : superClasses) {
                    sb.append("- ").append(s).append("\n");
                }
            }

            sb.append("\n## Interfaces\n");
            if (interfaces.isEmpty()) {
                sb.append("- (none)\n");
            } else {
                for (String i : interfaces) {
                    sb.append("- ").append(i).append("\n");
                }
            }

            sb.append("\n## Subclasses\n");
            if (subClasses.isEmpty()) {
                sb.append("- (none)\n");
            } else {
                for (String s : subClasses) {
                    sb.append("- ").append(s).append("\n");
                }
            }

            return sb.toString();
        }
    }

    public static class CallHierarchyResult {
        private final boolean success;
        private final String methodName;
        private final boolean callers;
        private final List<CallInfo> calls;
        private final String error;

        private CallHierarchyResult(boolean success, String methodName, boolean callers,
                                    List<CallInfo> calls, String error) {
            this.success = success;
            this.methodName = methodName;
            this.callers = callers;
            this.calls = calls;
            this.error = error;
        }

        public static CallHierarchyResult success(String methodName, boolean callers, List<CallInfo> calls) {
            return new CallHierarchyResult(true, methodName, callers, calls, null);
        }

        public static CallHierarchyResult error(String message) {
            return new CallHierarchyResult(false, null, false, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(callers ? "Callers of" : "Calls from").append(" `").append(methodName).append("`\n\n");
            sb.append("Found **").append(calls.size()).append("** ").append(callers ? "callers" : "callees").append("\n\n");

            for (CallInfo call : calls) {
                sb.append("- `").append(call.getClassName()).append(".").append(call.getMethodName())
                        .append("()` (line ").append(call.getLine()).append(")\n");
            }

            return sb.toString();
        }
    }

    public static class CallInfo {
        private final String className;
        private final String methodName;
        private final int line;

        public CallInfo(String className, String methodName, int line) {
            this.className = className;
            this.methodName = methodName;
            this.line = line;
        }

        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public int getLine() { return line; }
    }

    public static class RefactoringResult {
        private final boolean success;
        private final String operation;
        private final String oldName;
        private final String newName;
        private final int affectedCount;
        private final String error;

        private RefactoringResult(boolean success, String operation, String oldName, String newName,
                                   int affectedCount, String error) {
            this.success = success;
            this.operation = operation;
            this.oldName = oldName;
            this.newName = newName;
            this.affectedCount = affectedCount;
            this.error = error;
        }

        public static RefactoringResult success(String operation, String oldName, String newName, int affectedCount) {
            return new RefactoringResult(true, operation, oldName, newName, affectedCount, null);
        }

        public static RefactoringResult error(String message) {
            return new RefactoringResult(false, null, null, null, 0, message);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            return String.format("# Refactoring: %s\n\n- **From:** `%s`\n- **To:** `%s`\n- **Affected locations:** %d\n",
                    operation, oldName, newName, affectedCount);
        }
    }

    public static class MethodBodyResult {
        private final boolean success;
        private final String methodName;
        private final String signature;
        private final String body;
        private final List<CodeBlock> blocks;
        private final String error;

        private MethodBodyResult(boolean success, String methodName, String signature, String body,
                                  List<CodeBlock> blocks, String error) {
            this.success = success;
            this.methodName = methodName;
            this.signature = signature;
            this.body = body;
            this.blocks = blocks;
            this.error = error;
        }

        public static MethodBodyResult success(String methodName, String signature, String body, List<CodeBlock> blocks) {
            return new MethodBodyResult(true, methodName, signature, body, blocks, null);
        }

        public static MethodBodyResult error(String message) {
            return new MethodBodyResult(false, null, null, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public String getBody() { return body; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# Method: `").append(methodName).append("`\n\n");
            sb.append("**Signature:** `").append(signature).append("`\n\n");
            sb.append("## Code Blocks\n\n");

            for (CodeBlock block : blocks) {
                sb.append("### Block ").append(block.getIndex()).append(" (line ")
                        .append(block.getStartLine()).append(") - ").append(block.getType()).append("\n");
                sb.append("```java\n").append(block.getCode()).append("\n```\n\n");
            }

            return sb.toString();
        }
    }

    public static class CodeBlock {
        private final int index;
        private final int startLine;
        private final String type;
        private final String code;

        public CodeBlock(int index, int startLine, String type, String code) {
            this.index = index;
            this.startLine = startLine;
            this.type = type;
            this.code = code;
        }

        public int getIndex() { return index; }
        public int getStartLine() { return startLine; }
        public String getType() { return type; }
        public String getCode() { return code; }
    }

    public static class SafeDeleteResult {
        private final boolean success;
        private final boolean deleted;
        private final String targetName;
        private final int usageCount;
        private final List<String> usageLocations;
        private final String error;

        private SafeDeleteResult(boolean success, boolean deleted, String targetName,
                                  int usageCount, List<String> usageLocations, String error) {
            this.success = success;
            this.deleted = deleted;
            this.targetName = targetName;
            this.usageCount = usageCount;
            this.usageLocations = usageLocations;
            this.error = error;
        }

        public static SafeDeleteResult deleted(String targetName) {
            return new SafeDeleteResult(true, true, targetName, 0, List.of(), null);
        }

        public static SafeDeleteResult unsafe(String targetName, int usageCount, List<String> locations) {
            return new SafeDeleteResult(true, false, targetName, usageCount, locations, null);
        }

        public static SafeDeleteResult error(String message) {
            return new SafeDeleteResult(false, false, null, 0, null, message);
        }

        public boolean isSuccess() { return success; }
        public boolean isDeleted() { return deleted; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            if (deleted) {
                return String.format("# Safe Delete: `%s`\n\n✅ **Deleted successfully** - no usages found.\n", targetName);
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("# Safe Delete: `").append(targetName).append("`\n\n");
                sb.append("⚠️ **Cannot delete** - found **").append(usageCount).append("** usages:\n\n");
                for (String loc : usageLocations) {
                    sb.append("- `").append(loc).append("`\n");
                }
                return sb.toString();
            }
        }
    }

    public static class DeadCodeResult {
        private final boolean success;
        private final String className;
        private final List<DeadCodeItem> deadCode;
        private final String error;

        private DeadCodeResult(boolean success, String className, List<DeadCodeItem> deadCode, String error) {
            this.success = success;
            this.className = className;
            this.deadCode = deadCode;
            this.error = error;
        }

        public static DeadCodeResult success(String className, List<DeadCodeItem> deadCode) {
            return new DeadCodeResult(true, className, deadCode, null);
        }

        public static DeadCodeResult error(String message) {
            return new DeadCodeResult(false, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public List<DeadCodeItem> getDeadCode() { return deadCode; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# Dead Code Analysis: `").append(className).append("`\n\n");

            if (deadCode.isEmpty()) {
                sb.append("✅ **No dead code found** - all non-public members are used.\n");
            } else {
                sb.append("Found **").append(deadCode.size()).append("** unused declarations:\n\n");

                Map<String, List<DeadCodeItem>> byType = deadCode.stream()
                        .collect(Collectors.groupingBy(DeadCodeItem::getType));

                for (Map.Entry<String, List<DeadCodeItem>> entry : byType.entrySet()) {
                    sb.append("## Unused ").append(entry.getKey()).append("s\n\n");
                    for (DeadCodeItem item : entry.getValue()) {
                        sb.append("- `").append(item.getName()).append("` (")
                                .append(item.getFile()).append(":").append(item.getLine()).append(")\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        }
    }

    public static class DeadCodeItem {
        private final String type;
        private final String name;
        private final String file;
        private final int line;

        public DeadCodeItem(String type, String name, String file, int line) {
            this.type = type;
            this.name = name;
            this.file = file;
            this.line = line;
        }

        public String getType() { return type; }
        public String getName() { return name; }
        public String getFile() { return file; }
        public int getLine() { return line; }
    }

    public static class MoveClassResult {
        private final boolean success;
        private final String originalClass;
        private final String newClass;
        private final int updatedReferences;
        private final String error;

        private MoveClassResult(boolean success, String originalClass, String newClass,
                                 int updatedReferences, String error) {
            this.success = success;
            this.originalClass = originalClass;
            this.newClass = newClass;
            this.updatedReferences = updatedReferences;
            this.error = error;
        }

        public static MoveClassResult success(String originalClass, String newClass, int updatedReferences) {
            return new MoveClassResult(true, originalClass, newClass, updatedReferences, null);
        }

        public static MoveClassResult error(String message) {
            return new MoveClassResult(false, null, null, 0, message);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            return String.format("""
                    # Move Class Refactoring

                    - **From:** `%s`
                    - **To:** `%s`
                    - **References updated:** %d

                    ✅ Class moved successfully. All imports and references have been updated.
                    """, originalClass, newClass, updatedReferences);
        }
    }

    // ========== Project Dependencies Tool ==========

    /**
     * Get project dependencies from build files (build.gradle, pom.xml, .iml).
     * Helps LLMs understand what libraries are available before writing tests.
     */
    @NotNull
    public ProjectDependenciesResult getProjectDependencies() {
        return ApplicationManager.getApplication().runReadAction((Computable<ProjectDependenciesResult>) () -> {
            try {
                VirtualFile projectDir = project.getBaseDir();
                if (projectDir == null) {
                    return ProjectDependenciesResult.error("Cannot determine project directory");
                }

                List<DependencyInfo> dependencies = new ArrayList<>();
                String buildSystem = "unknown";
                String buildFile = null;

                // Check for Gradle (build.gradle or build.gradle.kts)
                VirtualFile gradleFile = projectDir.findChild("build.gradle");
                VirtualFile gradleKtsFile = projectDir.findChild("build.gradle.kts");
                VirtualFile pomFile = projectDir.findChild("pom.xml");

                if (gradleKtsFile != null && gradleKtsFile.exists()) {
                    buildSystem = "gradle-kotlin";
                    buildFile = gradleKtsFile.getPath();
                    dependencies = parseGradleDependencies(gradleKtsFile);
                } else if (gradleFile != null && gradleFile.exists()) {
                    buildSystem = "gradle-groovy";
                    buildFile = gradleFile.getPath();
                    dependencies = parseGradleDependencies(gradleFile);
                } else if (pomFile != null && pomFile.exists()) {
                    buildSystem = "maven";
                    buildFile = pomFile.getPath();
                    dependencies = parseMavenDependencies(pomFile);
                }

                // Fallback: parse .idea/libraries if no build file or to supplement
                VirtualFile ideaDir = projectDir.findChild(".idea");
                if (ideaDir != null) {
                    VirtualFile librariesDir = ideaDir.findChild("libraries");
                    if (librariesDir != null && librariesDir.isDirectory()) {
                        List<DependencyInfo> ideaDeps = parseIdeaLibraries(librariesDir);
                        if (buildSystem.equals("unknown") && !ideaDeps.isEmpty()) {
                            buildSystem = "idea-libraries";
                            buildFile = librariesDir.getPath();
                            dependencies = ideaDeps;
                        } else if (!ideaDeps.isEmpty()) {
                            // Merge: add JARs not already in dependencies
                            Set<String> existingArtifacts = dependencies.stream()
                                    .map(d -> extractArtifactName(d.coordinate))
                                    .collect(java.util.stream.Collectors.toSet());
                            for (DependencyInfo ideaDep : ideaDeps) {
                                String artifact = extractArtifactName(ideaDep.coordinate);
                                if (!existingArtifacts.contains(artifact)) {
                                    dependencies.add(ideaDep);
                                }
                            }
                        }
                    }
                }

                // Categorize test libraries
                TestLibraries testLibs = categorizeTestLibraries(dependencies);

                return ProjectDependenciesResult.success(buildSystem, buildFile, dependencies, testLibs);
            } catch (Exception e) {
                LOG.warn("Error getting project dependencies", e);
                return ProjectDependenciesResult.error("Failed to read dependencies: " + e.getMessage());
            }
        });
    }

    private List<DependencyInfo> parseGradleDependencies(VirtualFile gradleFile) {
        List<DependencyInfo> deps = new ArrayList<>();
        try {
            String content = new String(gradleFile.contentsToByteArray());

            // Match common dependency patterns:
            // implementation 'group:artifact:version'
            // testImplementation("group:artifact:version")
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(implementation|testImplementation|api|testApi|compileOnly|testCompileOnly|runtimeOnly|testRuntimeOnly)" +
                "\\s*[('\"]([^'\"]+)['\")]"
            );
            java.util.regex.Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                String scope = matcher.group(1);
                String coordinate = matcher.group(2);
                boolean isTest = scope.startsWith("test");
                deps.add(new DependencyInfo(coordinate, scope, isTest));
            }
        } catch (Exception e) {
            LOG.warn("Error parsing gradle file", e);
        }
        return deps;
    }

    private List<DependencyInfo> parseMavenDependencies(VirtualFile pomFile) {
        List<DependencyInfo> deps = new ArrayList<>();
        try {
            String content = new String(pomFile.contentsToByteArray());

            // Simple regex for Maven dependencies
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<dependency>\\s*" +
                "<groupId>([^<]+)</groupId>\\s*" +
                "<artifactId>([^<]+)</artifactId>\\s*" +
                "(?:<version>([^<]+)</version>\\s*)?" +
                "(?:<scope>([^<]+)</scope>)?",
                java.util.regex.Pattern.DOTALL
            );
            java.util.regex.Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                String groupId = matcher.group(1);
                String artifactId = matcher.group(2);
                String version = matcher.group(3) != null ? matcher.group(3) : "";
                String scope = matcher.group(4) != null ? matcher.group(4) : "compile";

                String coordinate = groupId + ":" + artifactId + (version.isEmpty() ? "" : ":" + version);
                boolean isTest = "test".equals(scope);
                deps.add(new DependencyInfo(coordinate, scope, isTest));
            }
        } catch (Exception e) {
            LOG.warn("Error parsing pom file", e);
        }
        return deps;
    }

    /**
     * Parse .idea/libraries/*.xml files for JAR dependencies.
     * Useful for projects without build.gradle or pom.xml.
     */
    private List<DependencyInfo> parseIdeaLibraries(VirtualFile librariesDir) {
        List<DependencyInfo> deps = new ArrayList<>();
        try {
            for (VirtualFile xmlFile : librariesDir.getChildren()) {
                if (!xmlFile.getName().endsWith(".xml")) continue;

                String content = new String(xmlFile.contentsToByteArray());
                // Match: <root url="jar://$PROJECT_DIR$/path/to/name-version.jar!/" />
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<root url=\"jar://[^\"]*?/([^/\"]+\\.jar)!/\""
                );
                java.util.regex.Matcher matcher = pattern.matcher(content);

                while (matcher.find()) {
                    String jarName = matcher.group(1);
                    // Extract artifact name and version from JAR name
                    String coordinate = jarNameToCoordinate(jarName);
                    boolean isTest = jarName.toLowerCase().contains("test") ||
                                     jarName.toLowerCase().contains("junit");
                    deps.add(new DependencyInfo(coordinate, "compile", isTest));
                }
            }
        } catch (Exception e) {
            LOG.warn("Error parsing .idea/libraries", e);
        }
        return deps;
    }

    /**
     * Convert JAR filename to Maven-style coordinate.
     * Example: "junit-4.8.2.jar" -> "junit:4.8.2"
     */
    private String jarNameToCoordinate(String jarName) {
        // Remove .jar extension
        String name = jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName;

        // Try to split name-version pattern (e.g., "gson-2.8.6" -> "gson:2.8.6")
        java.util.regex.Pattern versionPattern = java.util.regex.Pattern.compile(
            "^(.+?)-(\\d+\\.\\d+[^-]*)$"
        );
        java.util.regex.Matcher matcher = versionPattern.matcher(name);

        if (matcher.matches()) {
            return matcher.group(1) + ":" + matcher.group(2);
        }
        return name;  // No version found, return as-is
    }

    /**
     * Extract artifact name from coordinate for deduplication.
     */
    private String extractArtifactName(String coordinate) {
        // Handle "group:artifact:version" or "artifact:version" or just "artifact"
        String[] parts = coordinate.split(":");
        if (parts.length >= 2) {
            // Return artifact (second part for group:artifact:version, first for artifact:version)
            return parts.length >= 3 ? parts[1] : parts[0];
        }
        return coordinate.toLowerCase();
    }

    private TestLibraries categorizeTestLibraries(List<DependencyInfo> dependencies) {
        boolean hasJunit5 = false;
        boolean hasJunit4 = false;
        boolean hasTestcontainers = false;
        boolean hasWiremock = false;
        boolean hasAssertj = false;
        boolean hasMockito = false;
        boolean hasSpringTest = false;
        List<String> testcontainersModules = new ArrayList<>();

        for (DependencyInfo dep : dependencies) {
            String coord = dep.coordinate.toLowerCase();

            if (coord.contains("junit-jupiter") || coord.contains("junit:junit:5")) {
                hasJunit5 = true;
            }
            if (coord.contains("junit:junit:4") || coord.contains("junit:junit:") && !coord.contains("jupiter")) {
                hasJunit4 = true;
            }
            if (coord.contains("testcontainers")) {
                hasTestcontainers = true;
                // Extract module name
                if (coord.contains(":postgresql")) testcontainersModules.add("postgresql");
                else if (coord.contains(":mysql")) testcontainersModules.add("mysql");
                else if (coord.contains(":kafka")) testcontainersModules.add("kafka");
                else if (coord.contains(":mongodb")) testcontainersModules.add("mongodb");
                else if (coord.contains(":redis")) testcontainersModules.add("redis");
                else if (coord.contains(":elasticsearch")) testcontainersModules.add("elasticsearch");
            }
            if (coord.contains("wiremock")) {
                hasWiremock = true;
            }
            if (coord.contains("assertj")) {
                hasAssertj = true;
            }
            if (coord.contains("mockito")) {
                hasMockito = true;
            }
            if (coord.contains("spring-boot-starter-test") || coord.contains("spring-test")) {
                hasSpringTest = true;
            }
        }

        return new TestLibraries(hasJunit5, hasJunit4, hasTestcontainers, testcontainersModules,
                hasWiremock, hasAssertj, hasMockito, hasSpringTest);
    }

    /**
     * Information about a single dependency.
     */
    public record DependencyInfo(String coordinate, String scope, boolean isTest) {}

    /**
     * Summary of available test libraries.
     */
    public record TestLibraries(
            boolean hasJunit5,
            boolean hasJunit4,
            boolean hasTestcontainers,
            List<String> testcontainersModules,
            boolean hasWiremock,
            boolean hasAssertj,
            boolean hasMockito,
            boolean hasSpringTest
    ) {}

    /**
     * Result of getProjectDependencies.
     */
    public static class ProjectDependenciesResult {
        private final boolean success;
        private final String buildSystem;
        private final String buildFile;
        private final List<DependencyInfo> dependencies;
        private final TestLibraries testLibraries;
        private final String error;

        private ProjectDependenciesResult(boolean success, String buildSystem, String buildFile,
                                         List<DependencyInfo> dependencies, TestLibraries testLibraries, String error) {
            this.success = success;
            this.buildSystem = buildSystem;
            this.buildFile = buildFile;
            this.dependencies = dependencies != null ? dependencies : List.of();
            this.testLibraries = testLibraries;
            this.error = error;
        }

        public static ProjectDependenciesResult success(String buildSystem, String buildFile,
                                                        List<DependencyInfo> dependencies, TestLibraries testLibraries) {
            return new ProjectDependenciesResult(true, buildSystem, buildFile, dependencies, testLibraries, null);
        }

        public static ProjectDependenciesResult error(String message) {
            return new ProjectDependenciesResult(false, null, null, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public String getBuildSystem() { return buildSystem; }
        public TestLibraries getTestLibraries() { return testLibraries; }

        public String toMarkdown() {
            if (!success) return "Error: " + error;

            StringBuilder sb = new StringBuilder();
            sb.append("# Project Dependencies\n\n");
            sb.append("- **Build System:** ").append(buildSystem).append("\n");
            sb.append("- **Build File:** `").append(buildFile).append("`\n\n");

            // Test Libraries Summary
            sb.append("## Test Libraries Available\n\n");
            if (testLibraries != null) {
                sb.append("| Library | Available | Notes |\n");
                sb.append("|---------|-----------|-------|\n");
                sb.append("| JUnit 5 | ").append(testLibraries.hasJunit5 ? "✅" : "❌").append(" | |\n");
                sb.append("| JUnit 4 | ").append(testLibraries.hasJunit4 ? "✅" : "❌").append(" | |\n");
                sb.append("| Testcontainers | ").append(testLibraries.hasTestcontainers ? "✅" : "❌");
                if (!testLibraries.testcontainersModules.isEmpty()) {
                    sb.append(" | Modules: ").append(String.join(", ", testLibraries.testcontainersModules));
                }
                sb.append(" |\n");
                sb.append("| WireMock | ").append(testLibraries.hasWiremock ? "✅" : "❌").append(" | |\n");
                sb.append("| AssertJ | ").append(testLibraries.hasAssertj ? "✅" : "❌").append(" | |\n");
                sb.append("| Mockito | ").append(testLibraries.hasMockito ? "✅" : "❌").append(" | |\n");
                sb.append("| Spring Test | ").append(testLibraries.hasSpringTest ? "✅" : "❌").append(" | |\n");
            }

            // Missing libraries warning
            sb.append("\n## ⚠️ Missing Libraries\n\n");
            List<String> missing = new ArrayList<>();
            if (testLibraries != null) {
                if (!testLibraries.hasJunit5 && !testLibraries.hasJunit4) {
                    missing.add("**JUnit** - No test framework detected! Add JUnit 5.");
                }
                if (!testLibraries.hasAssertj) {
                    missing.add("**AssertJ** - Recommended for fluent assertions.");
                }
            }
            if (missing.isEmpty()) {
                sb.append("None - basic test libraries are available.\n");
            } else {
                for (String m : missing) {
                    sb.append("- ").append(m).append("\n");
                }
            }

            // Dependency snippets for missing libs
            if (testLibraries != null && (!testLibraries.hasJunit5 || !testLibraries.hasTestcontainers)) {
                sb.append("\n## 📦 Add Dependencies\n\n");
                if ("gradle-kotlin".equals(buildSystem) || "gradle-groovy".equals(buildSystem)) {
                    sb.append("```kotlin\n// Add to build.gradle.kts\n");
                    if (!testLibraries.hasJunit5) {
                        sb.append("testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.1\")\n");
                    }
                    if (!testLibraries.hasAssertj) {
                        sb.append("testImplementation(\"org.assertj:assertj-core:3.24.2\")\n");
                    }
                    sb.append("// For integration tests:\n");
                    if (!testLibraries.hasTestcontainers) {
                        sb.append("testImplementation(\"org.testcontainers:testcontainers:1.19.3\")\n");
                        sb.append("testImplementation(\"org.testcontainers:junit-jupiter:1.19.3\")\n");
                    }
                    if (!testLibraries.hasWiremock) {
                        sb.append("testImplementation(\"org.wiremock:wiremock:3.3.1\")\n");
                    }
                    sb.append("```\n");
                } else if ("maven".equals(buildSystem)) {
                    sb.append("```xml\n<!-- Add to pom.xml -->\n");
                    if (!testLibraries.hasJunit5) {
                        sb.append("<dependency>\n  <groupId>org.junit.jupiter</groupId>\n  <artifactId>junit-jupiter</artifactId>\n  <version>5.10.1</version>\n  <scope>test</scope>\n</dependency>\n");
                    }
                    sb.append("```\n");
                }
            }

            // All dependencies list
            sb.append("\n## All Dependencies (").append(dependencies.size()).append(")\n\n");
            long testCount = dependencies.stream().filter(DependencyInfo::isTest).count();
            sb.append("- **Test dependencies:** ").append(testCount).append("\n");
            sb.append("- **Compile dependencies:** ").append(dependencies.size() - testCount).append("\n\n");

            if (!dependencies.isEmpty()) {
                sb.append("<details><summary>Full list</summary>\n\n");
                for (DependencyInfo dep : dependencies) {
                    sb.append("- `").append(dep.coordinate).append("` (").append(dep.scope).append(")\n");
                }
                sb.append("</details>\n");
            }

            return sb.toString();
        }
    }

}
