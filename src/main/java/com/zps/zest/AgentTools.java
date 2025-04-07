package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilBase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * Reads the content of a file by name or path.
     *
     * @param filePath The name or path of the file to read
     * @return The content of the file or an error message
     */
    public String readFile(String filePath) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                // First try to find by exact path (supporting both absolute and relative paths)
                VirtualFile fileByPath = findFileByPath(filePath);
                if (fileByPath != null) {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(fileByPath);
                    if (psiFile != null) {
                        return psiFile.getText();
                    }
                }

                // If not found by path, try to find by filename
                String fileName = new File(filePath).getName();
                PsiFile[] files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));

                if (files.length == 0) {
                    return "File not found: " + filePath;
                }

                // If multiple files with the same name exist, try to find the best match
                if (files.length > 1 && filePath.contains("/")) {
                    for (PsiFile file : files) {
                        String fullPath = file.getVirtualFile().getPath();
                        if (fullPath.endsWith(filePath) || fullPath.contains(filePath)) {
                            return file.getText();
                        }
                    }
                }

                // Return first matching file
                return files[0].getText();
            });
        } catch (Exception e) {
            LOG.error("Error reading file: " + filePath, e);
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Finds a file by its path, supporting both absolute and relative paths.
     *
     * @param filePath The path to the file
     * @return The VirtualFile if found, null otherwise
     */
    private VirtualFile findFileByPath(String filePath) {
        // Check if it's an absolute path
        File file = new File(filePath);
        if (file.isAbsolute()) {
            return LocalFileSystem.getInstance().findFileByPath(filePath);
        }

        // Try as a relative path from project root
        String projectBasePath = project.getBasePath();
        if (projectBasePath != null) {
            String absolutePath = projectBasePath + "/" + filePath;
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vFile != null && vFile.exists()) {
                return vFile;
            }
        }

        // Try as a relative path from source roots
        for (VirtualFile sourceRoot : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            String rootPath = sourceRoot.getPath();
            String absolutePath = rootPath + "/" + filePath;
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vFile != null && vFile.exists()) {
                return vFile;
            }
        }

        return null;
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
                    if (count >= 100) {
                        result.append("... and ").append(packages.size() - 100).append(" more packages\n");
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

    /**
     * Creates a new file with the specified path and content.
     *
     * @param fileInfo The file information in the format "path:content"
     * @return Success message or error message
     */
    public String createFile(String fileInfo) {
        try {
            // Parse the file information
            int firstColonIndex = fileInfo.indexOf(":");
            if (firstColonIndex <= 0) {
                return "Invalid file format. Expected 'path:content'.";
            }

            // Extract file path and content
            String filePath = fileInfo.substring(0, firstColonIndex).trim();
            String content = fileInfo.substring(firstColonIndex + 1).trim();

            // Make sure we have both path and content
            if (filePath.isEmpty()) {
                return "File path cannot be empty.";
            }

            String finalFilePath = filePath;
            return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                // Get project base path
                String basePath = project.getBasePath();
                if (basePath == null) {
                    return "No base directory found for the project.";
                }

                // Handle relative or absolute path
                Path fullPath;
                if (new File(finalFilePath).isAbsolute()) {
                    fullPath = Paths.get(finalFilePath);
                } else {
                    fullPath = Paths.get(basePath, finalFilePath);
                }

                // Ensure parent directories exist
                File parentDir = fullPath.getParent().toFile();
                if (!parentDir.exists() && !parentDir.mkdirs()) {
                    return "Failed to create parent directories for: " + finalFilePath;
                }

                // Create or update the file in VFS
                String fullPathStr = fullPath.toString();
                VirtualFile parentVFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentDir.getAbsolutePath());
                if (parentVFile == null) {
                    return "Failed to find parent directory in VFS: " + parentDir.getAbsolutePath();
                }

                try {
                    // Check if file exists
                    String fileName = fullPath.getFileName().toString();
                    VirtualFile existingFile = parentVFile.findChild(fileName);

                    if (existingFile != null) {
                        // Update existing file
                        existingFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                        return "File updated successfully: " + finalFilePath;
                    } else {
                        // Create new file
                        VirtualFile newFile = parentVFile.createChildData(this, fileName);
                        newFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));

                        // Open the file in editor
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FileEditorManager.getInstance(project).openFile(newFile, true);
                        });

                        return "File created successfully: " + finalFilePath;
                    }
                } catch (Exception e) {
                    LOG.error("Error creating/updating file: " + finalFilePath, e);
                    return "Error creating/updating file: " + e.getMessage();
                }
            });
        } catch (Exception e) {
            LOG.error("Error processing file creation request", e);
            return "Error processing file creation request: " + e.getMessage();
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

    /**
     * Lists all files in a directory or with a specific extension.
     *
     * @param pathOrExtension Directory path or file extension (e.g., "java", "src/main")
     * @return List of matching files
     */
    public String listFiles(String pathOrExtension) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();

                // Check if it's an extension
                if (!pathOrExtension.contains("/") && !pathOrExtension.contains("\\")) {
                    // Assume it's a file extension
                    Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(
                            project, pathOrExtension, GlobalSearchScope.projectScope(project));

                    result.append("Files with extension '.").append(pathOrExtension).append("':\n");
                    int count = 0;
                    for (VirtualFile file : files) {
                        result.append("- ").append(file.getPath()).append("\n");
                        count++;
                        if (count >= 50) {
                            result.append("... and ").append(files.size() - 50).append(" more files\n");
                            break;
                        }
                    }

                    result.append("\nTotal: ").append(files.size()).append(" files\n");
                } else {
                    // Assume it's a directory path
                    VirtualFile dir = findFileByPath(pathOrExtension);
                    if (dir == null || !dir.isDirectory()) {
                        return "Directory not found: " + pathOrExtension;
                    }

                    result.append("Files in directory '").append(pathOrExtension).append("':\n");
                    listFilesRecursively(dir, result, 0, 3); // Max depth of 3
                }

                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error listing files", e);
            return "Error listing files: " + e.getMessage();
        }
    }

    /**
     * Recursively lists files in a directory.
     *
     * @param dir The directory to list
     * @param result The StringBuilder to append results to
     * @param depth Current depth
     * @param maxDepth Maximum depth to recurse
     */
    private void listFilesRecursively(VirtualFile dir, StringBuilder result, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return;
        }

        String indent = "  ".repeat(depth);
        for (VirtualFile child : dir.getChildren()) {
            result.append(indent).append("- ").append(child.getName());
            if (child.isDirectory()) {
                result.append("/");
            }
            result.append("\n");

            if (child.isDirectory()) {
                listFilesRecursively(child, result, depth + 1, maxDepth);
            }
        }
    }

    // Add these methods to your existing AgentTools.java class

    /**
     * Analyzes code problems in the specified scope.
     *
     * @param parameters The analysis parameters in the format "scope:path[:filter]"
     *                  - scope: "project", "current_file", or "directory"
     *                  - path: Path to file or directory (for current_file or directory scope)
     *                  - filter (optional): Text filter for problems, prefixed with severity filter
     *                    (e.g., "error:" or "warning:" or "all:")
     * @return A formatted report of code problems
     */
    public String analyzeCodeProblems(String parameters) {
        try {
            // Parse parameters
            String[] parts = parameters.split(":", 3);
            if (parts.length < 1) {
                return "Invalid parameters. Format: scope:path[:filter]";
            }

            String scope = parts[0].trim();
            String path = parts.length > 1 ? parts[1].trim() : "";
            String filter = parts.length > 2 ? parts[2].trim() : "";

            // Create analyzer and apply filters
            CodeProblemsAnalyzer analyzer = new CodeProblemsAnalyzer(project);


            // Apply text filter if remaining
            if (!filter.isEmpty()) {
                analyzer.setTextFilter(filter);
            }

            // Analyze code and return results
            return analyzer.analyzeCode(scope, path);
        } catch (Exception e) {
            return "Error analyzing code problems: " + e.getMessage();
        }
    }

    /**
     * Quick analyze of the current file for code problems.
     * This is a shorthand method for analyzeCodeProblems with scope="current_file"
     *
     * @return A formatted report of code problems in the current file
     */
    public String quickAnalyzeCurrentFile() {
        try {
            // Get current editor
            com.intellij.openapi.editor.Editor editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return "No file is currently open.";
            }

            // Get current file path
            com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file == null) {
                return "Cannot determine current file.";
            }

            String filePath = file.getPath();

            // Create analyzer
            CodeProblemsAnalyzer analyzer = new CodeProblemsAnalyzer(project);

            // Analyze current file
            return analyzer.analyzeCode("current_file", filePath);
        } catch (Exception e) {
            return "Error analyzing current file: " + e.getMessage();
        }
    }


}