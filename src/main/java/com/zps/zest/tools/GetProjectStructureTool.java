package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.HashSet;
import java.util.Set;

public class GetProjectStructureTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(GetProjectStructureTool.class);
    private final Project project;

    public GetProjectStructureTool(Project project) {
        super("get_project_structure", "Gets information about the current project structure");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        return getProjectStructure();
    }

    @Override
    public JsonObject getExampleParams() {
        return new JsonObject(); // No parameters required
    }

    private String getProjectStructure() {
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
}