package com.zps.zest.mcp.refactor;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Detects build tools and Java SDK configuration for a project.
 * Provides accurate paths for running build commands with coverage.
 */
public class BuildToolDetector {
    private static final Logger LOG = Logger.getInstance(BuildToolDetector.class);

    /**
     * Detect build tool and return comprehensive information.
     */
    @NotNull
    public static JsonObject detectBuildInfo(@NotNull Project project) {
        JsonObject info = new JsonObject();

        String projectPath = project.getBasePath();
        if (projectPath == null) {
            info.addProperty("error", "Project path not found");
            return info;
        }

        // Detect build system
        String buildSystem = detectBuildSystem(projectPath);
        info.addProperty("buildSystem", buildSystem);

        // Detect Java SDK
        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (projectSdk != null) {
            info.addProperty("sdkName", projectSdk.getName());
            info.addProperty("sdkVersion", projectSdk.getVersionString());
            String sdkHomePath = projectSdk.getHomePath();
            if (sdkHomePath != null) {
                info.addProperty("javaHome", sdkHomePath);

                // Find java executable
                String javaExecutable = findJavaExecutable(sdkHomePath);
                if (javaExecutable != null) {
                    info.addProperty("javaExecutable", javaExecutable);
                }
            }
        } else {
            // Try to find from modules
            Module[] modules = ModuleManager.getInstance(project).getModules();
            for (Module module : modules) {
                Sdk moduleSdk = ModuleRootManager.getInstance(module).getSdk();
                if (moduleSdk != null && moduleSdk.getHomePath() != null) {
                    info.addProperty("javaHome", moduleSdk.getHomePath());
                    info.addProperty("sdkName", moduleSdk.getName());
                    info.addProperty("sdkVersion", moduleSdk.getVersionString());

                    String javaExecutable = findJavaExecutable(moduleSdk.getHomePath());
                    if (javaExecutable != null) {
                        info.addProperty("javaExecutable", javaExecutable);
                    }
                    break;
                }
            }
        }

        // Find build tool executable
        if ("gradle".equals(buildSystem)) {
            String gradleWrapper = findGradleWrapper(projectPath);
            if (gradleWrapper != null) {
                info.addProperty("buildExecutable", gradleWrapper);
                info.addProperty("testCommand", gradleWrapper + " test");
                info.addProperty("coverageCommand", gradleWrapper + " test jacocoTestReport");
            } else {
                info.addProperty("buildExecutable", "gradle");
                info.addProperty("testCommand", "gradle test");
                info.addProperty("coverageCommand", "gradle test jacocoTestReport");
            }
            info.addProperty("coverageReportPath", "build/reports/jacoco/test/jacocoTestReport.xml");
        } else if ("maven".equals(buildSystem)) {
            String mavenWrapper = findMavenWrapper(projectPath);
            if (mavenWrapper != null) {
                info.addProperty("buildExecutable", mavenWrapper);
                info.addProperty("testCommand", mavenWrapper + " test");
                info.addProperty("coverageCommand", mavenWrapper + " test jacoco:report");
            } else {
                info.addProperty("buildExecutable", "mvn");
                info.addProperty("testCommand", "mvn test");
                info.addProperty("coverageCommand", "mvn test jacoco:report");
            }
            info.addProperty("coverageReportPath", "target/site/jacoco/jacoco.xml");
        }

        return info;
    }

    /**
     * Detect build system by looking for build files.
     */
    @NotNull
    public static String detectBuildSystem(@NotNull String projectPath) {
        if (Files.exists(Paths.get(projectPath, "build.gradle")) ||
            Files.exists(Paths.get(projectPath, "build.gradle.kts"))) {
            return "gradle";
        }
        if (Files.exists(Paths.get(projectPath, "pom.xml"))) {
            return "maven";
        }
        if (Files.exists(Paths.get(projectPath, "build.xml"))) {
            return "ant";
        }
        return "unknown";
    }

    /**
     * Find Gradle wrapper executable.
     */
    @Nullable
    private static String findGradleWrapper(@NotNull String projectPath) {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.startsWith("windows");

        String wrapperName = isWindows ? "gradlew.bat" : "gradlew";
        Path wrapperPath = Paths.get(projectPath, wrapperName);

        if (Files.exists(wrapperPath)) {
            // Return relative path for better portability
            return isWindows ? ".\\gradlew.bat" : "./gradlew";
        }

        return null;
    }

    /**
     * Find Maven wrapper executable.
     */
    @Nullable
    private static String findMavenWrapper(@NotNull String projectPath) {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.startsWith("windows");

        String wrapperName = isWindows ? "mvnw.cmd" : "mvnw";
        Path wrapperPath = Paths.get(projectPath, wrapperName);

        if (Files.exists(wrapperPath)) {
            // Return relative path for better portability
            return isWindows ? ".\\mvnw.cmd" : "./mvnw";
        }

        return null;
    }

    /**
     * Find Java executable from JDK home.
     */
    @Nullable
    private static String findJavaExecutable(@NotNull String jdkHome) {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.startsWith("windows");

        String javaExeName = isWindows ? "java.exe" : "java";
        Path javaPath = Paths.get(jdkHome, "bin", javaExeName);

        if (Files.exists(javaPath)) {
            return javaPath.toString();
        }

        return null;
    }

    /**
     * Check if JaCoCo appears to be configured in build files.
     */
    public static boolean isJaCoCoConfigured(@NotNull String projectPath, @NotNull String buildSystem) {
        try {
            if ("gradle".equals(buildSystem)) {
                // Check build.gradle or build.gradle.kts for jacoco plugin
                Path buildGradle = Paths.get(projectPath, "build.gradle");
                Path buildGradleKts = Paths.get(projectPath, "build.gradle.kts");

                if (Files.exists(buildGradle)) {
                    String content = Files.readString(buildGradle);
                    if (content.contains("jacoco") || content.contains("JaCoCo")) {
                        return true;
                    }
                }

                if (Files.exists(buildGradleKts)) {
                    String content = Files.readString(buildGradleKts);
                    if (content.contains("jacoco") || content.contains("JaCoCo")) {
                        return true;
                    }
                }
            } else if ("maven".equals(buildSystem)) {
                // Check pom.xml for jacoco-maven-plugin
                Path pomXml = Paths.get(projectPath, "pom.xml");
                if (Files.exists(pomXml)) {
                    String content = Files.readString(pomXml);
                    if (content.contains("jacoco-maven-plugin")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error checking for JaCoCo configuration", e);
        }

        return false;
    }
}
