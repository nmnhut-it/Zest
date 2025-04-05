package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilBase;

import java.util.ArrayList;
import java.util.List;

public class FindReferencesTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(FindReferencesTool.class);
    private final Project project;

    public FindReferencesTool(Project project) {
        super("find_references", "Gets references to a symbol (where it's used)");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String symbolName = getStringParam(params, "symbolName", "");
        if (symbolName.isEmpty()) {
            return "Error: Symbol name is required";
        }
        return findReferences(symbolName);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("symbolName", "exampleSymbol");
        return params;
    }

    private String findReferences(String symbolName) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                result.append("References to '").append(symbolName).append("':\n\n");
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    return "No active editor";
                }
                PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
                if (!(psiFile instanceof PsiJavaFile)) {
                    return "Not a Java file";
                }
                // Find the symbol
                PsiElement symbol = null;
                PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                for (PsiClass psiClass : classes) {
                    // Check fields
                    for (PsiField field : psiClass.getFields()) {
                        if (field.getName().equals(symbolName)) {
                            symbol = field;
                            break;
                        }
                    }
                    if (symbol != null) break;
                    // Check methods
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (method.getName().equals(symbolName)) {
                            symbol = method;
                            break;
                        }
                        // Check parameters
                        for (PsiParameter param : method.getParameterList().getParameters()) {
                            if (param.getName().equals(symbolName)) {
                                symbol = param;
                                break;
                            }
                        }
                        if (symbol != null) break;
                        // Check local variables
                        PsiCodeBlock body = method.getBody();
                        if (body != null) {
                            PsiStatement[] statements = body.getStatements();
                            for (PsiStatement statement : statements) {
                                // Look for declarations
                                if (statement instanceof PsiDeclarationStatement) {
                                    PsiElement[] elements = ((PsiDeclarationStatement) statement).getDeclaredElements();
                                    for (PsiElement element : elements) {
                                        if (element instanceof PsiLocalVariable) {
                                            if (((PsiLocalVariable) element).getName().equals(symbolName)) {
                                                symbol = element;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (symbol != null) break;
                            }
                        }
                    }
                }
                if (symbol == null) {
                    return "Symbol '" + symbolName + "' not found in the current file";
                }
                // Find all references to the symbol in the current file
                PsiReference[] references = null;
                // For fields and methods, we can use findReferences
                if (symbol instanceof PsiField || symbol instanceof PsiMethod ||
                    symbol instanceof PsiClass || symbol instanceof PsiParameter) {
                    references = symbol.getReferences();
                }
                if (references != null) {
                    int count = 0;
                    for (PsiReference reference : references) {
                        PsiElement refElement = reference.getElement();
                        PsiFile refFile = refElement.getContainingFile();
                        int lineNumber = getLineNumber(refElement);
                        result.append("- ").append(refFile.getName())
                                .append(":").append(lineNumber)
                                .append(" in ");
                        // Find containing method or class
                        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
                        if (containingMethod != null) {
                            result.append("method '").append(containingMethod.getName()).append("'");
                        } else {
                            PsiClass containingClass = PsiTreeUtil.getParentOfType(refElement, PsiClass.class);
                            if (containingClass != null) {
                                result.append("class '").append(containingClass.getName()).append("'");
                            }
                        }
                        result.append("\n");
                        count++;
                    }
                    if (count == 0) {
                        result.append("No references found in the current file\n");
                    } else {
                        result.append("\nTotal references: ").append(count).append("\n");
                    }
                }
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error finding references", e);
            return "Error finding references: " + e.getMessage();
        }
    }

    private int getLineNumber(PsiElement element) {
        if (element == null) {
            return -1;
        }
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return -1;
        }
        String text = file.getText();
        int offset = element.getTextOffset();
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}