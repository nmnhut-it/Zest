package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Tool for getting information about the current project structure.
 */
public class GetProjectStructureTool extends ThreadSafeCodeExplorationTool {

    public GetProjectStructureTool(@NotNull Project project) {
        super(project, "get_project_structure",
                "Gets information about the current project structure including source directories, " +
                "file counts, and key packages. No parameters required. " +
                "Example: get_project_structure({})");
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }

    @Override
    protected boolean requiresReadAction() {
        return true; // Reading PSI structure requires read action
    }

    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        StringBuilder result = new StringBuilder();
        JsonObject metadata = createMetadata();

        result.append("Project Structure for ").append(project.getName()).append(":\n\n");

        // Get project base directory
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return ToolResult.error("Could not access project base directory");
        }

        // Get project directories
        VirtualFile[] contentRoots = baseDir.getChildren();

        // List source directories
        result.append("Source Directories:\n");
        JsonArray sourceDirs = new JsonArray();
        for (VirtualFile root : contentRoots) {
            if (root.isDirectory()) {
                String name = root.getName();
                if (name.equals("src") || name.equals("java") ||
                        name.equals("kotlin") || name.equals("resources")) {
                    result.append("- ").append(root.getPath()).append("\n");
                    sourceDirs.add(root.getPath());
                }
            }
        }
        metadata.add("sourceDirs", sourceDirs);

        // Count Java files
        int javaFileCount = FilenameIndex.getAllFilesByExt(project, "java",
                GlobalSearchScope.projectScope(project)).size();
        result.append("\nJava Files: ").append(javaFileCount).append("\n");
        metadata.addProperty("javaFileCount", javaFileCount);

        // Count Kotlin files
        int kotlinFileCount = FilenameIndex.getAllFilesByExt(project, "kt",
                GlobalSearchScope.projectScope(project)).size();
        if (kotlinFileCount > 0) {
            result.append("Kotlin Files: ").append(kotlinFileCount).append("\n");
            metadata.addProperty("kotlinFileCount", kotlinFileCount);
        }

        // Count XML files (often configuration files)
        int xmlFileCount = FilenameIndex.getAllFilesByExt(project, "xml",
                GlobalSearchScope.projectScope(project)).size();
        if (xmlFileCount > 0) {
            result.append("XML Files: ").append(xmlFileCount).append("\n");
            metadata.addProperty("xmlFileCount", xmlFileCount);
        }

        // List key packages
        result.append("\nKey Packages:\n");
        Set<String> packages = new HashSet<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        JsonArray packagesArray = new JsonArray();

        // Collect packages from Java files
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

        // List up to 100 packages
        int count = 0;
        for (String pkg : packages) {
            result.append("- ").append(pkg).append("\n");
            packagesArray.add(pkg);
            count++;
            if (count >= 100) {
                result.append("... and ").append(packages.size() - 100).append(" more packages\n");
                break;
            }
        }
        metadata.add("packages", packagesArray);
        metadata.addProperty("totalPackageCount", packages.size());

        // Add project path
        metadata.addProperty("projectPath", project.getBasePath());

        return ToolResult.success(result.toString(), metadata);
    }
}
