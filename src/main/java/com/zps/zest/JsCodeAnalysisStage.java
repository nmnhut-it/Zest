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

            // For JS/TS files, include the whole file content as additional context
            context.setTargetContent(content); // Set full file as target content

            // Collect imports/requires
            String imports = collectImports(content, language);
            context.setImports(imports);

            // Analyze the entire file structure instead of just target code
            String structureAnalysis = analyzeEntireFileStructure(content, language);
            context.setClassContext(structureAnalysis);

            // Detect framework dependencies from entire file
            detectFrameworks(content, context);

            // Set file-level information
            context.setClassName(virtualFile.getNameWithoutExtension());
            context.setStructureType("file");

            LOG.info("Completed JS/TS full file analysis for: " + context.getClassName());
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

    private String analyzeEntireFileStructure(String content, String language) {
        StringBuilder analysis = new StringBuilder();

        analysis.append("=== FULL FILE ANALYSIS ===\n");
        analysis.append("Language: ").append(language).append("\n");
        analysis.append("File Size: ").append(content.length()).append(" characters\n");
        analysis.append("Lines: ").append(content.split("\n").length).append("\n\n");

        // Analyze all functions in the file
        String functions = extractAllFunctions(content);
        if (!functions.isEmpty()) {
            analysis.append("=== ALL FUNCTIONS ===\n").append(functions).append("\n");
        }

        // Analyze all classes/objects in the file
        String classes = extractAllClasses(content);
        if (!classes.isEmpty()) {
            analysis.append("=== CLASSES/OBJECTS ===\n").append(classes).append("\n");
        }

        // Analyze all variables/constants
        String variables = extractAllVariables(content);
        if (!variables.isEmpty()) {
            analysis.append("=== VARIABLES/CONSTANTS ===\n").append(variables).append("\n");
        }

        // Analyze exports
        String exports = extractExports(content);
        if (!exports.isEmpty()) {
            analysis.append("=== EXPORTS ===\n").append(exports).append("\n");
        }

        // Analyze dependencies used within the code
        String localDependencies = extractAllDependencies(content);
        if (!localDependencies.isEmpty()) {
            analysis.append("=== DEPENDENCIES ===\n").append(localDependencies).append("\n");
        }

        // Analyze patterns (async/await, promises, etc.)
        String patterns = analyzePatterns(content);
        if (!patterns.isEmpty()) {
            analysis.append("=== PATTERNS DETECTED ===\n").append(patterns).append("\n");
        }

        // File structure overview
        String structure = analyzeFileStructure(content);
        if (!structure.isEmpty()) {
            analysis.append("=== FILE STRUCTURE ===\n").append(structure).append("\n");
        }

        return analysis.toString();
    }

    private String extractAllFunctions(String content) {
        StringBuilder functions = new StringBuilder();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isFunctionLine(line)) {
                String functionName = extractFunctionNameFromLine(line);
                functions.append("- Line ").append(i + 1).append(": ").append(functionName).append("\n");
                functions.append("  ").append(line.length() > 80 ? line.substring(0, 77) + "..." : line).append("\n");
            }
        }

        return functions.toString();
    }

    private String extractAllClasses(String content) {
        StringBuilder classes = new StringBuilder();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isClassLine(line)) {
                String className = extractClassNameFromLine(line);
                classes.append("- Line ").append(i + 1).append(": ").append(className).append("\n");
                classes.append("  ").append(line.length() > 80 ? line.substring(0, 77) + "..." : line).append("\n");
            }
        }

        return classes.toString();
    }

    private String extractAllVariables(String content) {
        StringBuilder variables = new StringBuilder();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isVariableLine(line)) {
                String varName = extractVariableNameFromLine(line);
                variables.append("- Line ").append(i + 1).append(": ").append(varName).append("\n");
            }
        }

        return variables.toString();
    }

    private String extractExports(String content) {
        StringBuilder exports = new StringBuilder();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("export ") || line.startsWith("module.exports") || line.startsWith("exports.")) {
                exports.append("- Line ").append(i + 1).append(": ").append(line).append("\n");
            }
        }

        return exports.toString();
    }

    private String extractAllDependencies(String content) {
        StringBuilder deps = new StringBuilder();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("import ") || line.contains("require(") || line.contains("from ")) {
                deps.append("- Line ").append(i + 1).append(": ").append(line).append("\n");
            }
        }

        return deps.toString();
    }

    private String analyzeFileStructure(String content) {
        StringBuilder structure = new StringBuilder();
        String[] lines = content.split("\n");

        int functionCount = 0;
        int classCount = 0;
        int variableCount = 0;
        int commentLines = 0;
        int emptyLines = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                emptyLines++;
            } else if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                commentLines++;
            } else if (isFunctionLine(trimmed)) {
                functionCount++;
            } else if (isClassLine(trimmed)) {
                classCount++;
            } else if (isVariableLine(trimmed)) {
                variableCount++;
            }
        }

        structure.append("Functions: ").append(functionCount).append("\n");
        structure.append("Classes/Objects: ").append(classCount).append("\n");
        structure.append("Variables/Constants: ").append(variableCount).append("\n");
        structure.append("Comment Lines: ").append(commentLines).append("\n");
        structure.append("Empty Lines: ").append(emptyLines).append("\n");
        structure.append("Code Lines: ").append(lines.length - commentLines - emptyLines).append("\n");

        return structure.toString();
    }

    private boolean isFunctionLine(String line) {
        return line.matches("^\\s*(export\\s+)?(async\\s+)?function\\s+\\w+.*") ||
                line.matches("^\\s*(const|let|var)\\s+\\w+\\s*=\\s*(async\\s+)?(function|\\(.*\\)\\s*=>).*") ||
                line.matches("^\\s*\\w+\\s*:\\s*(async\\s+)?function.*") ||
                line.matches("^\\s*\\w+\\s*\\(.*\\)\\s*\\{.*") ||
                line.matches("^\\s*(ctor|onEnter|onExit|onEnterTransitionDidFinish|init|update)\\s*:.*");
    }

    private boolean isClassLine(String line) {
        return line.matches("^\\s*(export\\s+)?class\\s+\\w+.*") ||
                line.matches("^\\s*var\\s+\\w+\\s*=\\s*cc\\.\\w+\\.extend.*") ||
                line.matches("^\\s*cc\\.Class\\s*\\(.*");
    }

    private boolean isVariableLine(String line) {
        return line.matches("^\\s*(const|let|var)\\s+\\w+.*") &&
                !line.contains("function") && !line.contains("=>");
    }

    private String extractFunctionNameFromLine(String line) {
        if (line.contains("function ")) {
            String[] parts = line.split("function\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\(")[0].trim();
            }
        }
        if (line.matches(".*(const|let|var)\\s+\\w+\\s*=.*")) {
            String[] parts = line.split("(const|let|var)\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\s*=")[0].trim();
            }
        }
        if (line.matches(".*\\w+\\s*:.*")) {
            return line.split("\\s*:")[0].trim();
        }
        return "anonymous";
    }

    private String extractClassNameFromLine(String line) {
        if (line.contains("class ")) {
            String[] parts = line.split("class\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\s+")[0].trim();
            }
        }
        if (line.matches(".*var\\s+\\w+\\s*=\\s*cc\\.\\w+\\.extend.*")) {
            String[] parts = line.split("var\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\s*=")[0].trim();
            }
        }
        return "UnknownClass";
    }

    private String extractVariableNameFromLine(String line) {
        String[] parts = line.split("(const|let|var)\\s+");
        if (parts.length > 1) {
            return parts[1].split("[\\s=:]")[0].trim();
        }
        return "UnknownVariable";
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

        // Standard JavaScript patterns
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

        // Cocos2d-x specific patterns
        if (content.contains("cc.")) {
            patterns.append("- Cocos2d-x framework patterns detected\n");

            if (content.contains("cc.Node") || content.contains("cc.Sprite") || content.contains("cc.Layer")) {
                patterns.append("- Cocos2d-x node hierarchy patterns\n");
            }
            if (content.contains("addChild") || content.contains("removeChild")) {
                patterns.append("- Cocos2d-x child management patterns\n");
            }
            if (content.contains("cc.MoveTo") || content.contains("cc.ScaleTo") || content.contains("runAction")) {
                patterns.append("- Cocos2d-x action/animation patterns\n");
            }
            if (content.contains("ctor:") || content.contains("onEnter:") || content.contains("onExit:")) {
                patterns.append("- Cocos2d-x lifecycle methods detected\n");
            }
            if (content.contains("cc.eventManager") || content.contains("cc.EventListener")) {
                patterns.append("- Cocos2d-x event system patterns\n");
            }
            if (content.contains(".extend(") || content.contains("cc.Class")) {
                patterns.append("- Cocos2d-x class definition patterns\n");
            }
            if (content.contains("cc.director") || content.contains("cc.game")) {
                patterns.append("- Cocos2d-x director/game management patterns\n");
            }
        }

        return patterns.toString();
    }

    private void detectFrameworks(String content, CodeContext context) {
        StringBuilder frameworks = new StringBuilder();

        // Detect Cocos2d-x first (priority since you specifically mentioned it)
        if (content.contains("cc.") || content.contains("cocos2d")) {
            frameworks.append("Cocos2d-x ");
            context.setTestFramework("Jest"); // Common with game development

            // Detect Cocos2d-x version
            if (content.contains("cc.Class")) {
                frameworks.append("3.x ");
            } else if (content.contains("cc.Node.extend")) {
                frameworks.append("2.x ");
            }
        }

        // Detect other common frameworks
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