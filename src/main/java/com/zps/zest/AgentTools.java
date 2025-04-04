package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilBase;

import java.util.*;

/**
 * A collection of tools that the AI agent can use to gather information.
 */
public class AgentTools {
    private static final Logger LOG = Logger.getInstance(AgentTools.class);
    private final Project project;

    public AgentTools(Project project) {
        this.project = project;
    }

    /**
     * Reads the content of a file by name.
     *
     * @param fileName The name of the file to read
     * @return The content of the file or an error message
     */
    public String readFile(String fileName) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                PsiFile[] files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));
                if (files.length == 0) {
                    return "File not found: " + fileName;
                }
                
                // Return first matching file
                return files[0].getText();
            });
        } catch (Exception e) {
            LOG.error("Error reading file: " + fileName, e);
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Finds methods in the current file that match a search term.
     *
     * @param searchTerm The term to search for
     * @return A list of matching method signatures
     */
    public List<String> findMethods(String searchTerm) {
        List<String> results = new ArrayList<>();
        
        try {
            ApplicationManager.getApplication().runReadAction(() -> {
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    results.add("No active editor");
                    return;
                }
                
                PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
                if (!(psiFile instanceof PsiJavaFile)) {
                    results.add("Not a Java file");
                    return;
                }
                
                PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                for (PsiClass psiClass : classes) {
                    PsiMethod[] methods = psiClass.getMethods();
                    for (PsiMethod method : methods) {
                        if (method.getName().toLowerCase().contains(searchTerm.toLowerCase()) ||
                                method.getText().toLowerCase().contains(searchTerm.toLowerCase())) {
                            results.add(method.getText());
                        }
                    }
                }
            });
        } catch (Exception e) {
            LOG.error("Error finding methods", e);
            results.add("Error finding methods: " + e.getMessage());
        }
        
        return results;
    }

    /**
     * Searches for classes related to a search term.
     *
     * @param searchTerm The term to search for
     * @return Information about matching classes
     */
    public String searchClasses(String searchTerm) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                
                // Find classes by name
                PsiClass[] classes = JavaPsiFacade.getInstance(project)
                        .findClasses(searchTerm, GlobalSearchScope.projectScope(project));
                
                if (classes.length == 0) {
                    // Try a more flexible search
                    PsiClass[] allClasses = JavaPsiFacade.getInstance(project)
                            .findClasses("*", GlobalSearchScope.projectScope(project));
                    
                    for (PsiClass cls : allClasses) {
                        if (cls.getName() != null && cls.getName().toLowerCase()
                                .contains(searchTerm.toLowerCase())) {
                            result.append("Class: ").append(cls.getQualifiedName()).append("\n");
                            result.append("  Methods:\n");
                            
                            for (PsiMethod method : cls.getMethods()) {
                                result.append("    - ").append(method.getName())
                                        .append(method.getParameterList().getText()).append("\n");
                            }
                            
                            result.append("\n");
                        }
                    }
                } else {
                    // Process exact matches
                    for (PsiClass cls : classes) {
                        result.append("Class: ").append(cls.getQualifiedName()).append("\n");
                        result.append("  Methods:\n");
                        
                        for (PsiMethod method : cls.getMethods()) {
                            result.append("    - ").append(method.getName())
                                    .append(method.getParameterList().getText()).append("\n");
                        }
                        
                        result.append("\n");
                    }
                }
                
                return result.length() > 0 ? result.toString() : "No classes found matching: " + searchTerm;
            });
        } catch (Exception e) {
            LOG.error("Error searching classes", e);
            return "Error searching classes: " + e.getMessage();
        }
    }

    /**
     * Gets information about the current project structure.
     *
     * @return A summary of the project structure
     */
    public String getProjectStructure() {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                result.append("Project Structure for ").append(project.getName()).append(":\n\n");
                
                // Get project directories
                VirtualFile[] contentRoots = project.getBaseDir().getChildren();
                
                // List source directories
                result.append("Source Directories:\n");
                for (VirtualFile root : contentRoots) {
                    if (root.isDirectory()) {
                        if (root.getName().equals("src") || root.getName().equals("java") || 
                                root.getName().equals("kotlin") || root.getName().equals("resources")) {
                            result.append("- ").append(root.getPath()).append("\n");
                        }
                    }
                }
                
                // Count Java files
                int javaFileCount = FilenameIndex.getAllFilesByExt(project, "java", 
                        GlobalSearchScope.projectScope(project)).size();
                result.append("\nJava Files: ").append(javaFileCount).append("\n");
                
                // List key packages
                result.append("\nKey Packages:\n");
                Set<String> packages = new HashSet<>();
                
                PsiManager psiManager = PsiManager.getInstance(project);
                for (VirtualFile vFile : FilenameIndex.getAllFilesByExt(project, "java", 
                        GlobalSearchScope.projectScope(project))) {
                    PsiFile psiFile = psiManager.findFile(vFile);
                    if (psiFile instanceof PsiJavaFile) {
                        String packageName = ((PsiJavaFile) psiFile).getPackageName();
                        if (!packageName.isEmpty()) {
                            packages.add(packageName);
                        }
                    }
                }
                
                // List up to 20 packages
                int count = 0;
                for (String pkg : packages) {
                    result.append("- ").append(pkg).append("\n");
                    count++;
                    if (count >= 20) {
                        result.append("... and ").append(packages.size() - 20).append(" more packages\n");
                        break;
                    }
                }
                
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error getting project structure", e);
            return "Error getting project structure: " + e.getMessage();
        }
    }

    /**
     * Retrieves information about the current class in the editor.
     *
     * @return Information about the current class
     */
    public String getCurrentClassInfo() {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    return "No active editor";
                }
                
                PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
                if (!(psiFile instanceof PsiJavaFile)) {
                    return "Not a Java file";
                }
                
                StringBuilder result = new StringBuilder();
                PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                
                if (classes.length == 0) {
                    return "No classes found in the current file";
                }
                
                for (PsiClass psiClass : classes) {
                    result.append("Class: ").append(psiClass.getQualifiedName()).append("\n\n");
                    
                    // Super class
                    if (psiClass.getSuperClass() != null && !psiClass.getSuperClass().getName().equals("Object")) {
                        result.append("Extends: ").append(psiClass.getSuperClass().getQualifiedName()).append("\n");
                    }
                    
                    // Interfaces
                    PsiClassType[] interfaces = psiClass.getImplementsListTypes();
                    if (interfaces.length > 0) {
                        result.append("Implements: ");
                        for (int i = 0; i < interfaces.length; i++) {
                            result.append(interfaces[i].getClassName());
                            if (i < interfaces.length - 1) {
                                result.append(", ");
                            }
                        }
                        result.append("\n");
                    }
                    
                    // Fields
                    result.append("\nFields:\n");
                    for (PsiField field : psiClass.getFields()) {
                        result.append("- ").append(field.getType().getPresentableText())
                                .append(" ").append(field.getName()).append("\n");
                    }
                    
                    // Methods
                    result.append("\nMethods:\n");
                    for (PsiMethod method : psiClass.getMethods()) {
                        result.append("- ").append(method.getName())
                                .append(method.getParameterList().getText());
                        
                        if (method.getReturnType() != null) {
                            result.append(" : ").append(method.getReturnType().getPresentableText());
                        }
                        
                        result.append("\n");
                    }
                    
                    result.append("\n");
                }
                
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error getting current class info", e);
            return "Error getting current class info: " + e.getMessage();
        }
    }

// Add this method inside the AgentTools class
    /**
     * Creates a new file with the specified name and content.
     *
     * @return Success message or error message
     */
    public String createFile(String response) {
        String fileName = "Un parsed" ;

        try {
            // Split the response into parts using colon as the delimiter
            String[] parts = response.split(":");

            // Check if we have at least two parts: path and content
            if (parts.length < 2) {
                return "Invalid response format. Expected 'path:content'.";
            }

            // Extract file path and content
            String filePath = parts[0].trim();
            fileName = filePath;
            String content = String.join(":", Arrays.copyOfRange(parts, 1, parts.length)).trim();

            String finalFileName = fileName;
            return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                VirtualFile baseDir = project.getBaseDir();
                if (baseDir == null) {
                    return "No base directory found for the project.";
                }

                // Check if the file already exists
                VirtualFile existingFile = baseDir.findChild(finalFileName);
                if (existingFile != null) {
                    return "File already exists: " + finalFileName;
                }

                // Create the new file
                try {
                    VirtualFile newFile = baseDir.createChildData(this, finalFileName);
                    newFile.setBinaryContent(content.getBytes());
                    return "File created successfully: " + finalFileName;
                } catch (Exception e) {
                    LOG.error("Error creating file: " + finalFileName, e);
                    return "Error creating file: " + e.getMessage();
                }
            });
        } catch (Exception e) {
            LOG.error("Error creating file: " + fileName, e);
            return "Error creating file: " + e.getMessage();
        }
    }

    /**
     * Gets references to a symbol (where it's used).
     *
     * @param symbolName The name of the symbol to find references for
     * @return Information about where the symbol is referenced
     */
    public String findReferences(String symbolName) {
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
                    references = PsiTreeUtil.getChildrenOfType(psiFile, PsiReferenceExpression.class);
                }
                
                if (references != null) {
                    int count = 0;
                    for (PsiReference reference : references) {
                        if (reference.isReferenceTo(symbol)) {
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
    
    /**
     * Gets the line number for a PSI element.
     *
     * @param element The PSI element
     * @return The line number
     */
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