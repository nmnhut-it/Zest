package com.zps.zest;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage for analyzing JavaScript/TypeScript code structure.
 * Provides textual analysis for JS/TS files.
 */
public class JsCodeAnalysisStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(JsCodeAnalysisStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        ReadAction.run(() -> {
            PsiFile psiFile = context.getPsiFile();
            VirtualFile virtualFile = psiFile.getVirtualFile();
            String content = context.getEditor().getDocument().getText();
            String language = context.getLanguage();

            // Collect imports/requires
            String imports = collectImports(content, language);
            context.setImports(imports);

            // Analyze the target code structure
            String structureAnalysis = analyzeCodeStructure(context.getTargetContent(), language);
            context.setClassContext(structureAnalysis);

            // Detect framework dependencies
            detectFrameworks(content, context);

            LOG.info("Completed JS/TS code analysis for " + context.getStructureType() + ": " + context.getClassName());
        });
    }

    private String collectImports(String content, String language) {
        StringBuilder imports = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (isImportStatement(trimmed)) {
                imports.append(line).append("\n");
            }
        }

        return imports.toString();
    }

    private boolean isImportStatement(String line) {
        return line.startsWith("import ") ||
               line.startsWith("const ") && line.contains("require(") ||
               line.startsWith("let ") && line.contains("require(") ||
               line.startsWith("var ") && line.contains("require(") ||
               line.matches("^\\s*from\\s+['\"].*['\"]\\s+import.*");
    }

    private String analyzeCodeStructure(String targetContent, String language) {
        StringBuilder analysis = new StringBuilder();
        
        analysis.append("=== CODE STRUCTURE ANALYSIS ===\n");
        analysis.append("Language: ").append(language).append("\n\n");

        // Analyze functions
        String functions = extractFunctions(targetContent);
        if (!functions.isEmpty()) {
            analysis.append("Functions found:\n").append(functions).append("\n");
        }

        // Analyze variables/constants
        String variables = extractVariables(targetContent);
        if (!variables.isEmpty()) {
            analysis.append("Variables/Constants:\n").append(variables).append("\n");
        }

        // Analyze dependencies used within the code
        String localDependencies = extractLocalDependencies(targetContent);
        if (!localDependencies.isEmpty()) {
            analysis.append("Local Dependencies:\n").append(localDependencies).append("\n");
        }

        // Analyze patterns (async/await, promises, etc.)
        String patterns = analyzePatterns(targetContent);
        if (!patterns.isEmpty()) {
            analysis.append("Patterns Detected:\n").append(patterns).append("\n");
        }

        return analysis.toString();
    }

    private String extractFunctions(String content) {
        StringBuilder functions = new StringBuilder();
        Pattern functionPattern = Pattern.compile(
            "(function\\s+\\w+\\s*\\([^)]*\\)|\\w+\\s*:\\s*function\\s*\\([^)]*\\)|\\w+\\s*=\\s*\\([^)]*\\)\\s*=>|async\\s+function\\s+\\w+|\\w+\\s*\\([^)]*\\)\\s*\\{)",
            Pattern.MULTILINE
        );

        Matcher matcher = functionPattern.matcher(content);
        while (matcher.find()) {
            String match = matcher.group().trim();
            functions.append("- ").append(match.split("\\{")[0].trim()).append("\n");
        }

        return functions.toString();
    }

    private String extractVariables(String content) {
        StringBuilder variables = new StringBuilder();
        Pattern varPattern = Pattern.compile(
            "(const\\s+\\w+|let\\s+\\w+|var\\s+\\w+)\\s*[=:]",
            Pattern.MULTILINE
        );

        Matcher matcher = varPattern.matcher(content);
        while (matcher.find()) {
            String match = matcher.group().trim();
            variables.append("- ").append(match.split("[=:]")[0].trim()).append("\n");
        }

        return variables.toString();
    }

    private String extractLocalDependencies(String content) {
        StringBuilder deps = new StringBuilder();
        
        // Look for common patterns like require(), import from local files
        Pattern localDepPattern = Pattern.compile(
            "(require\\s*\\(\\s*['\"]\\./|import.*from\\s*['\"]\\./|import\\s*['\"]\\./)",
            Pattern.MULTILINE
        );

        Matcher matcher = localDepPattern.matcher(content);
        while (matcher.find()) {
            String match = matcher.group().trim();
            deps.append("- ").append(match).append("\n");
        }

        return deps.toString();
    }

    private String analyzePatterns(String content) {
        StringBuilder patterns = new StringBuilder();

        if (content.contains("async") || content.contains("await")) {
            patterns.append("- Async/Await pattern detected\n");
        }
        if (content.contains(".then(") || content.contains(".catch(")) {
            patterns.append("- Promise chains detected\n");
        }
        if (content.contains("setTimeout") || content.contains("setInterval")) {
            patterns.append("- Timer functions detected\n");
        }
        if (content.contains("addEventListener") || content.contains("on(")) {
            patterns.append("- Event handling patterns detected\n");
        }
        if (content.contains("class ") && content.contains("extends")) {
            patterns.append("- Class inheritance detected\n");
        }
        if (content.contains("=> {") || content.contains("=> ")) {
            patterns.append("- Arrow function patterns detected\n");
        }

        return patterns.toString();
    }

    private void detectFrameworks(String content, CodeContext context) {
        StringBuilder frameworks = new StringBuilder();

        // Detect common frameworks
        if (content.contains("react") || content.contains("React") || content.contains("jsx")) {
            frameworks.append("React ");
            context.setTestFramework("Jest"); // Common with React
        }
        if (content.contains("vue") || content.contains("Vue")) {
            frameworks.append("Vue.js ");
        }
        if (content.contains("angular") || content.contains("Angular") || content.contains("@angular")) {
            frameworks.append("Angular ");
            context.setTestFramework("Jasmine"); // Common with Angular
        }
        if (content.contains("express") || content.contains("app.get") || content.contains("app.post")) {
            frameworks.append("Express.js ");
        }
        if (content.contains("cc.") || content.contains("cocos2d")) {
            frameworks.append("Cocos2d-x ");
        }

        // Detect testing frameworks
        if (content.contains("describe(") || content.contains("it(") || content.contains("test(")) {
            if (content.contains("jest")) {
                context.setTestFramework("Jest");
            } else if (content.contains("mocha")) {
                context.setTestFramework("Mocha");
            } else {
                context.setTestFramework("Jest"); // Default assumption
            }
        }

        // Set framework context
        if (frameworks.length() > 0) {
            context.setFrameworkContext(frameworks.toString().trim());
        }

        // Default test framework if none detected
        if (context.getTestFramework() == null) {
            context.setTestFramework("Jest"); // Most common default
        }
    }
}
