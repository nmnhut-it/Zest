package com.zps.zest.testgen.util;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Utility for locating test source roots in IntelliJ projects.
 * Searches module configuration first, then falls back to conventional paths.
 */
public final class TestSourceRootUtil {

    private TestSourceRootUtil() {
    }

    /**
     * Find the best test source root from project modules.
     *
     * @param project The IntelliJ project
     * @return Absolute path to test source root, never null (defaults to src/test/java)
     */
    @NotNull
    public static String findBestTestSourceRoot(@NotNull Project project) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (ContentEntry contentEntry : rootManager.getContentEntries()) {
                for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                    if (sourceFolder.isTestSource() && sourceFolder.getFile() != null) {
                        return sourceFolder.getFile().getPath();
                    }
                }
            }
        }

        String basePath = project.getBasePath();
        if (basePath == null) {
            return "src/test/java";
        }

        File baseDir = new File(basePath);

        File srcTestJava = new File(baseDir, "src/test/java");
        if (srcTestJava.exists()) {
            return srcTestJava.getAbsolutePath();
        }

        File srcTestKotlin = new File(baseDir, "src/test/kotlin");
        if (srcTestKotlin.exists()) {
            return srcTestKotlin.getAbsolutePath();
        }

        File testJava = new File(baseDir, "test/java");
        if (testJava.exists()) {
            return testJava.getAbsolutePath();
        }

        File test = new File(baseDir, "test");
        if (test.exists()) {
            return test.getAbsolutePath();
        }

        return new File(baseDir, "src/test/java").getAbsolutePath();
    }

    /**
     * Find the best test source root as a VirtualFile for proper classpath context.
     *
     * @param project The IntelliJ project
     * @return VirtualFile of test source root, or null if not found
     */
    @Nullable
    public static VirtualFile findBestTestSourceRootVirtualFile(@NotNull Project project) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (ContentEntry contentEntry : rootManager.getContentEntries()) {
                for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
                    if (sourceFolder.isTestSource() && sourceFolder.getFile() != null) {
                        return sourceFolder.getFile();
                    }
                }
            }
        }

        String testRootPath = findBestTestSourceRoot(project);
        return LocalFileSystem.getInstance().findFileByPath(testRootPath);
    }
}
