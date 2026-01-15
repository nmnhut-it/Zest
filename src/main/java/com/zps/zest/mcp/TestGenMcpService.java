package com.zps.zest.mcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * MCP service for test generation utilities.
 * Provides validation and PSI lookup capabilities for MCP tools.
 */
public class TestGenMcpService {
    private static final Logger LOG = Logger.getInstance(TestGenMcpService.class);

    private final Project project;
    private TestGenRules rules;

    public TestGenMcpService(@NotNull Project project) {
        this.project = project;
        this.rules = TestGenRules.defaults();
    }

    /**
     * Set custom rules for test generation.
     */
    public void setRules(@NotNull TestGenRules rules) {
        this.rules = rules;
    }

    /**
     * Get current rules.
     */
    @NotNull
    public TestGenRules getRules() {
        return rules;
    }

    /**
     * Basic validation of test code structure.
     */
    @NotNull
    public ValidationResult validateTestCode(@NotNull String code, @NotNull String className) {
        List<String> errors = new ArrayList<>();

        if (!code.contains("class " + className)) {
            errors.add("Generated code does not contain expected class: " + className);
        }
        if (!code.contains("@Test")) {
            errors.add("No @Test annotations found");
        }
        if (!code.contains("import org.junit")) {
            errors.add("Missing JUnit imports");
        }
        if (code.contains("// TODO") || code.contains("/* TODO")) {
            errors.add("Code contains TODO markers");
        }
        if (code.contains("throw new UnsupportedOperationException")) {
            errors.add("Code contains unimplemented methods");
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Find a class by fully qualified name.
     */
    @Nullable
    public PsiClass findClass(String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<PsiClass>) () ->
                JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project)));
    }

    /**
     * Get class source code.
     */
    @Nullable
    public String getClassSource(String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            PsiClass psiClass = findClass(className);
            if (psiClass != null) {
                PsiFile file = psiClass.getContainingFile();
                if (file != null) {
                    return file.getText();
                }
            }
            return null;
        });
    }

    /**
     * Validation result for test code.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : Collections.emptyList();
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
