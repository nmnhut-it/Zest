package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Static utility methods for detecting and working with test frameworks.
 */
public class TestFrameworkUtils {

    /**
     * Detects the JUnit version available in the project.
     * 
     * @param project The project to check
     * @return JUnit version string or "JUnit 5" as default
     */
    public static String detectJUnitVersion(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // Check for JUnit 5
                if (isClassAvailable(project, "org.junit.jupiter.api.Test")) {
                    return "JUnit 5";
                }
                // Check for JUnit 4
                else if (isClassAvailable(project, "org.junit.Test")) {
                    return "JUnit 4";
                }
                // Default fallback
                return "JUnit 5";
            } catch (Exception e) {
                return "JUnit 5";
            }
        });
    }

    /**
     * Checks if Mockito is available in the project.
     * 
     * @param project The project to check
     * @return true if Mockito is available
     */
    public static boolean isMockitoAvailable(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> {
            try {
                return isClassAvailable(project, "org.mockito.Mockito");
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Gets the Mockito version information.
     * 
     * @param project The project to check
     * @return Mockito version string or null if not available
     */
    @Nullable
    public static String detectMockitoVersion(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                if (!isClassAvailable(project, "org.mockito.Mockito")) {
                    return null;
                }
                
                // Check for Mockito version indicators
                if (isClassAvailable(project, "org.mockito.junit.jupiter.MockitoExtension")) {
                    return "Mockito 3.x+";
                } else if (isClassAvailable(project, "org.mockito.junit.MockitoJUnitRunner")) {
                    return "Mockito 2.x+";
                } else {
                    return "Mockito 1.x+";
                }
            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * Checks if AssertJ is available in the project.
     * 
     * @param project The project to check
     * @return true if AssertJ is available
     */
    public static boolean isAssertJAvailable(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> {
            try {
                return isClassAvailable(project, "org.assertj.core.api.Assertions");
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Checks if Hamcrest is available in the project.
     * 
     * @param project The project to check
     * @return true if Hamcrest is available
     */
    public static boolean isHamcrestAvailable(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> {
            try {
                return isClassAvailable(project, "org.hamcrest.MatcherAssert");
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Checks if Spring Test is available in the project.
     * 
     * @param project The project to check
     * @return true if Spring Test is available
     */
    public static boolean isSpringTestAvailable(@NotNull Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> {
            try {
                return isClassAvailable(project, "org.springframework.test.context.junit.jupiter.SpringJUnitConfig") ||
                       isClassAvailable(project, "org.springframework.boot.test.context.SpringBootTest");
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Detects the build tool being used in the project.
     * 
     * @param project The project to check
     * @return Build tool name (Maven, Gradle, SBT, or Unknown)
     */
    public static String detectBuildTool(@NotNull Project project) {
        try {
            if (project.getBaseDir().findChild("pom.xml") != null) {
                return "Maven";
            } else if (project.getBaseDir().findChild("build.gradle") != null || 
                       project.getBaseDir().findChild("build.gradle.kts") != null) {
                return "Gradle";
            } else if (project.getBaseDir().findChild("build.sbt") != null) {
                return "SBT";
            } else {
                return "Unknown";
            }
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Gets a list of available testing frameworks in the project.
     * 
     * @param project The project to check
     * @return List of available framework names
     */
    public static List<String> getAvailableFrameworks(@NotNull Project project) {
        List<String> frameworks = new ArrayList<>();
        
        String junitVersion = detectJUnitVersion(project);
        if (junitVersion != null) {
            frameworks.add(junitVersion);
        }
        
        String mockitoVersion = detectMockitoVersion(project);
        if (mockitoVersion != null) {
            frameworks.add(mockitoVersion);
        }
        
        if (isAssertJAvailable(project)) {
            frameworks.add("AssertJ");
        }
        
        if (isHamcrestAvailable(project)) {
            frameworks.add("Hamcrest");
        }
        
        if (isSpringTestAvailable(project)) {
            frameworks.add("Spring Test");
        }
        
        return frameworks;
    }

    /**
     * Gets a formatted string of available frameworks for templates.
     * 
     * @param project The project to check
     * @return Formatted string of available frameworks
     */
    public static String getFrameworksSummary(@NotNull Project project) {
        List<String> frameworks = getAvailableFrameworks(project);
        if (frameworks.isEmpty()) {
            return "No testing frameworks detected";
        }
        return String.join(", ", frameworks);
    }

    /**
     * Gets the recommended assertion style based on available frameworks.
     * 
     * @param project The project to check
     * @return Recommended assertion style
     */
    public static String getRecommendedAssertionStyle(@NotNull Project project) {
        if (isAssertJAvailable(project)) {
            return "AssertJ";
        } else if (isHamcrestAvailable(project)) {
            return "Hamcrest";
        } else {
            String junitVersion = detectJUnitVersion(project);
            if ("JUnit 5".equals(junitVersion)) {
                return "JUnit 5 Assertions";
            } else if ("JUnit 4".equals(junitVersion)) {
                return "JUnit 4 Assert";
            } else {
                return "Standard Assertions";
            }
        }
    }

    /**
     * Checks if advanced mocking capabilities are available.
     * 
     * @param project The project to check
     * @return true if advanced mocking is available
     */
    public static boolean hasAdvancedMockingCapabilities(@NotNull Project project) {
        return isMockitoAvailable(project) || isSpringTestAvailable(project);
    }

    /**
     * Gets environment information as a formatted string.
     * 
     * @return Formatted environment information
     */
    public static String getEnvironmentInfo() {
        String osName = System.getProperty("os.name", "Unknown");
        String javaVersion = System.getProperty("java.version", "Unknown");
        String terminalType = getTerminalType(osName);
        
        return String.format("OS: %s | Java: %s | Terminal: %s", osName, javaVersion, terminalType);
    }

    /**
     * Gets terminal type based on operating system.
     * 
     * @param osName The operating system name
     * @return Terminal type description
     */
    public static String getTerminalType(String osName) {
        if (osName == null) {
            return "Unknown terminal";
        }
        
        String os = osName.toLowerCase();
        if (os.contains("windows")) {
            return "Command Prompt/PowerShell";
        } else if (os.contains("mac")) {
            return "Terminal (bash/zsh)";
        } else if (os.contains("linux")) {
            return "Terminal (bash)";
        } else {
            return "Unknown terminal";
        }
    }

    /**
     * Gets complete framework and environment information for templates.
     * 
     * @param project The project to check
     * @return Formatted framework and environment information
     */
    public static String getCompleteFrameworkInfo(@NotNull Project project) {
        StringBuilder info = new StringBuilder();
        
        info.append("**Available Frameworks**: ").append(getFrameworksSummary(project)).append("\n");
        info.append("**Recommended Assertions**: ").append(getRecommendedAssertionStyle(project)).append("\n");
        info.append("**Advanced Mocking**: ").append(hasAdvancedMockingCapabilities(project) ? "Available" : "Basic").append("\n");
        info.append("**Build Tool**: ").append(detectBuildTool(project)).append("\n");
        info.append("**Environment**: ").append(getEnvironmentInfo()).append("\n");
        
        return info.toString();
    }

    /**
     * Gets essential framework info in a compact format.
     * 
     * @param project The project to check
     * @return Compact framework information
     */
    public static String getEssentialFrameworkInfo(@NotNull Project project) {
        return String.format("%s | %s | %s", 
            getFrameworksSummary(project),
            getRecommendedAssertionStyle(project),
            detectBuildTool(project)
        );
    }

    /**
     * Checks if a class is available in the project's classpath.
     * 
     * @param project The project to check
     * @param className The fully qualified class name
     * @return true if the class is available
     */
    private static boolean isClassAvailable(@NotNull Project project, @NotNull String className) {
        try {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));
            return psiClass != null;
        } catch (Exception e) {
            return false;
        }
    }
}