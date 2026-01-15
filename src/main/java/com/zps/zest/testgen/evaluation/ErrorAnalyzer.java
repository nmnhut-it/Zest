package com.zps.zest.testgen.evaluation;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes compilation errors and provides concrete fix suggestions.
 * Uses IntelliJ PSI to look up actual class/method information.
 */
public class ErrorAnalyzer {

    private final Project project;
    private final JavaPsiFacade psiFacade;
    private final GlobalSearchScope scope;

    public ErrorAnalyzer(@NotNull Project project) {
        this.project = project;
        this.psiFacade = JavaPsiFacade.getInstance(project);
        this.scope = GlobalSearchScope.allScope(project);
    }

    /**
     * Analyze an error and return a concrete fix suggestion.
     */
    public FixSuggestion analyze(String errorMessage, int line) {
        for (ErrorPattern pattern : ErrorPattern.values()) {
            Matcher matcher = pattern.pattern.matcher(errorMessage);
            if (matcher.find()) {
                return pattern.analyzer.analyze(this, matcher, errorMessage, line);
            }
        }
        return new FixSuggestion(ErrorCategory.OTHER, errorMessage, line, null);
    }

    /**
     * Analyze multiple errors and group suggestions.
     */
    public AnalysisResult analyzeAll(List<TestCodeValidator.CompilationError> errors) {
        Map<ErrorCategory, List<FixSuggestion>> grouped = new EnumMap<>(ErrorCategory.class);

        for (TestCodeValidator.CompilationError error : errors) {
            FixSuggestion suggestion = analyze(error.getMessage(), error.getStartLine());
            grouped.computeIfAbsent(suggestion.category, k -> new ArrayList<>()).add(suggestion);
        }

        return new AnalysisResult(errors.size(), grouped);
    }

    // === PSI Lookup Methods ===

    @Nullable
    String findImportForClass(String className) {
        PsiClass psiClass = psiFacade.findClass(className, scope);
        if (psiClass != null) {
            return psiClass.getQualifiedName();
        }

        // Try short name search
        PsiClass[] classes = PsiShortNamesCache.getInstance(project)
                .getClassesByName(className, scope);
        if (classes.length > 0) {
            return classes[0].getQualifiedName();
        }
        return null;
    }

    @Nullable
    String findMethodSignature(String className, String methodName) {
        PsiClass psiClass = psiFacade.findClass(className, scope);
        if (psiClass == null) {
            PsiClass[] classes = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(className, scope);
            if (classes.length > 0) {
                psiClass = classes[0];
            }
        }

        if (psiClass != null) {
            for (PsiMethod method : psiClass.getMethods()) {
                if (method.getName().equals(methodName)) {
                    return formatMethodSignature(method);
                }
            }
        }
        return null;
    }

    @Nullable
    List<String> findAbstractMethods(String className) {
        PsiClass psiClass = psiFacade.findClass(className, scope);
        if (psiClass == null) {
            PsiClass[] classes = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(className, scope);
            if (classes.length > 0) {
                psiClass = classes[0];
            }
        }

        if (psiClass == null) return null;

        List<String> abstractMethods = new ArrayList<>();
        for (PsiMethod method : psiClass.getAllMethods()) {
            if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
                abstractMethods.add(formatMethodStub(method));
            }
        }
        return abstractMethods.isEmpty() ? null : abstractMethods;
    }

    private String formatMethodSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getPresentableText());
        }
        sb.append(")");
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(" → ").append(returnType.getPresentableText());
        }
        return sb.toString();
    }

    private String formatMethodStub(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append("@Override\n");
        sb.append("public ");
        PsiType returnType = method.getReturnType();
        sb.append(returnType != null ? returnType.getPresentableText() : "void");
        sb.append(" ").append(method.getName()).append("(");

        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getPresentableText())
              .append(" ").append(params[i].getName());
        }
        sb.append(") {\n");
        sb.append("    ").append(getDefaultReturn(returnType)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String getDefaultReturn(PsiType type) {
        if (type == null || PsiType.VOID.equals(type)) return "// TODO";
        if (PsiType.BOOLEAN.equals(type)) return "return false;";
        if (PsiType.INT.equals(type) || PsiType.LONG.equals(type)
                || PsiType.SHORT.equals(type) || PsiType.BYTE.equals(type)) return "return 0;";
        if (PsiType.DOUBLE.equals(type) || PsiType.FLOAT.equals(type)) return "return 0.0;";
        if (PsiType.CHAR.equals(type)) return "return '\\0';";
        return "return null;";
    }

    // === Error Patterns ===

    enum ErrorCategory {
        IMPORT("Import Error"),
        ABSTRACT_METHOD("Missing Method Implementation"),
        SIGNATURE("Method Signature Error"),
        CONSTRUCTOR("Constructor Error"),
        OTHER("Other Error");

        final String displayName;
        ErrorCategory(String displayName) { this.displayName = displayName; }
    }

    enum ErrorPattern {
        CANNOT_RESOLVE_SYMBOL(
            Pattern.compile("Cannot resolve symbol '([^']+)'"),
            (analyzer, matcher, msg, line) -> {
                String symbol = matcher.group(1);
                String qualifiedName = analyzer.findImportForClass(symbol);
                if (qualifiedName != null) {
                    return new FixSuggestion(
                        ErrorCategory.IMPORT, msg, line,
                        "Add import: " + qualifiedName
                    );
                }
                return new FixSuggestion(
                    ErrorCategory.IMPORT, msg, line,
                    "Class '" + symbol + "' not found in project. Check dependencies."
                );
            }
        ),

        CANNOT_FIND_SYMBOL(
            Pattern.compile("cannot find symbol.*symbol:\\s*(?:class|variable)\\s+(\\w+)"),
            (analyzer, matcher, msg, line) -> {
                String symbol = matcher.group(1);
                String qualifiedName = analyzer.findImportForClass(symbol);
                if (qualifiedName != null) {
                    return new FixSuggestion(
                        ErrorCategory.IMPORT, msg, line,
                        "Add import: " + qualifiedName
                    );
                }
                return new FixSuggestion(
                    ErrorCategory.IMPORT, msg, line,
                    "Symbol '" + symbol + "' not found. Check spelling or dependencies."
                );
            }
        ),

        PACKAGE_NOT_EXIST(
            Pattern.compile("package (\\S+) does not exist"),
            (analyzer, matcher, msg, line) -> new FixSuggestion(
                ErrorCategory.IMPORT, msg, line,
                "Package '" + matcher.group(1) + "' not found. Check dependencies or remove import."
            )
        ),

        MUST_IMPLEMENT(
            Pattern.compile("must (?:either be declared abstract or )?implement.*'(\\w+)\\(\\)'.*'(\\w+)'"),
            (analyzer, matcher, msg, line) -> {
                String methodName = matcher.group(1);
                String className = matcher.group(2);
                List<String> methods = analyzer.findAbstractMethods(className);
                if (methods != null && !methods.isEmpty()) {
                    String stub = methods.stream()
                        .filter(m -> m.contains(methodName))
                        .findFirst()
                        .orElse(methods.get(0));
                    return new FixSuggestion(
                        ErrorCategory.ABSTRACT_METHOD, msg, line,
                        "Add method:\n" + stub
                    );
                }
                return new FixSuggestion(
                    ErrorCategory.ABSTRACT_METHOD, msg, line,
                    "Implement missing method: " + methodName + "()"
                );
            }
        ),

        CANNOT_BE_APPLIED(
            Pattern.compile("'(\\w+)\\([^)]*\\)'.*cannot be applied to '\\(([^)]+)\\)'"),
            (analyzer, matcher, msg, line) -> {
                String methodName = matcher.group(1);
                return new FixSuggestion(
                    ErrorCategory.SIGNATURE, msg, line,
                    "Wrong argument types for " + methodName + "(). Check method signature."
                );
            }
        ),

        NO_SUITABLE_CONSTRUCTOR(
            Pattern.compile("no suitable constructor found|There is no (?:default|parameterless) constructor"),
            (analyzer, matcher, msg, line) -> new FixSuggestion(
                ErrorCategory.CONSTRUCTOR, msg, line,
                "No matching constructor. Use lookupClass to check available constructors."
            )
        ),

        INCOMPATIBLE_TYPES(
            Pattern.compile("incompatible types|Incompatible types"),
            (analyzer, matcher, msg, line) -> new FixSuggestion(
                ErrorCategory.SIGNATURE, msg, line,
                "Type mismatch. Check expected vs actual types."
            )
        );

        final Pattern pattern;
        final ErrorPatternAnalyzer analyzer;

        ErrorPattern(Pattern pattern, ErrorPatternAnalyzer analyzer) {
            this.pattern = pattern;
            this.analyzer = analyzer;
        }
    }

    @FunctionalInterface
    interface ErrorPatternAnalyzer {
        FixSuggestion analyze(ErrorAnalyzer ctx, Matcher matcher, String msg, int line);
    }

    // === Result Classes ===

    public static class FixSuggestion {
        public final ErrorCategory category;
        public final String originalError;
        public final int line;
        public final String suggestion;

        FixSuggestion(ErrorCategory category, String originalError, int line, String suggestion) {
            this.category = category;
            this.originalError = originalError;
            this.line = line;
            this.suggestion = suggestion;
        }
    }

    public static class AnalysisResult {
        public final int totalErrors;
        public final Map<ErrorCategory, List<FixSuggestion>> byCategory;

        AnalysisResult(int totalErrors, Map<ErrorCategory, List<FixSuggestion>> byCategory) {
            this.totalErrors = totalErrors;
            this.byCategory = byCategory;
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Compilation Errors\n\n");
            sb.append("Found **").append(totalErrors).append("** error(s)\n\n");

            for (ErrorCategory category : ErrorCategory.values()) {
                List<FixSuggestion> suggestions = byCategory.get(category);
                if (suggestions == null || suggestions.isEmpty()) continue;

                sb.append("## ").append(category.displayName)
                  .append(" (").append(suggestions.size()).append(")\n\n");

                for (FixSuggestion s : suggestions) {
                    sb.append("- **Line ").append(s.line).append("**: ")
                      .append(s.originalError).append("\n");
                    if (s.suggestion != null) {
                        sb.append("  - Fix: ").append(s.suggestion.replace("\n", "\n    ")).append("\n");
                    }
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }
}
