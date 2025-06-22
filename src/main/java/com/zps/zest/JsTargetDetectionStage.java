package com.zps.zest;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

/**
 * Stage for detecting target code structure in JavaScript/TypeScript files.
 * Uses textual analysis since PSI support for JS/TS may be limited.
 */
public class JsTargetDetectionStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(JsTargetDetectionStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        if (context.getEditor() == null || context.getPsiFile() == null) {
            throw new PipelineExecutionException("No editor or file found");
        }

        ReadAction.run(() -> {
            PsiFile psiFile = context.getPsiFile();
            VirtualFile virtualFile = psiFile.getVirtualFile();
            Document document = context.getEditor().getDocument();
            
            if (virtualFile == null) {
                try {
                    throw new PipelineExecutionException("No virtual file found");
                } catch (PipelineExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            String fileName = virtualFile.getName();
            String fileType = virtualFile.getFileType().getName();
            String content = document.getText();
            int cursorOffset = context.getEditor().getCaretModel().getOffset();

            // Detect file language
            String language = detectLanguage(fileName, fileType);
            context.setLanguage(language);

            // Find the target structure (function, class, or module) at cursor
            JsCodeStructure structure = findCodeStructureAtCursor(content, cursorOffset, language);
            
            if (structure == null) {
                // Fallback: use a reasonable portion of the file around the cursor
                structure = createFallbackStructure(content, cursorOffset, fileName);
                LOG.info("Using fallback structure: " + structure.name);
            }

            // Set context information
            context.setClassName(structure.name);
            context.setTargetContent(structure.content);
            context.setStructureType(structure.type);
            context.setStartOffset(structure.startOffset);
            context.setEndOffset(structure.endOffset);

            LOG.info("Detected " + structure.type + ": " + structure.name + " in " + language + " file");
        });
    }

    private String detectLanguage(String fileName, String fileType) {
        if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) {
            return "TypeScript";
        } else if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) {
            return "JavaScript";
        } else if (fileType.toLowerCase().contains("typescript")) {
            return "TypeScript";
        } else if (fileType.toLowerCase().contains("javascript")) {
            return "JavaScript";
        }
        return "JavaScript"; // Default fallback
    }

    private JsCodeStructure findCodeStructureAtCursor(String content, int cursorOffset, String language) {
        String[] lines = content.split("\n");
        int cursorLine = content.substring(0, cursorOffset).split("\n").length - 1;

        // Try to find function, class, or other meaningful structure
        JsCodeStructure structure = findFunction(lines, cursorLine, content);
        if (structure != null) return structure;

        structure = findClass(lines, cursorLine, content);
        if (structure != null) return structure;

        structure = findModule(lines, cursorLine, content);
        if (structure != null) return structure;

        return null;
    }

    private JsCodeStructure findFunction(String[] lines, int cursorLine, String fullContent) {
        // Look backwards for function declaration
        for (int i = cursorLine; i >= 0; i--) {
            String line = lines[i].trim();
            if (isFunctionDeclaration(line)) {
                String functionName = extractFunctionName(line);
                JsCodeBounds bounds = findCodeBounds(lines, i);
                if (bounds != null && cursorLine >= i && cursorLine <= bounds.endLine) {
                    String content = extractContentFromBounds(lines, bounds);
                    return new JsCodeStructure(
                        functionName, 
                        content, 
                        "function", 
                        calculateOffset(lines, bounds.startLine),
                        calculateOffset(lines, bounds.endLine + 1)
                    );
                }
            }
        }
        return null;
    }

    private JsCodeStructure findClass(String[] lines, int cursorLine, String fullContent) {
        // Look backwards for class declaration
        for (int i = cursorLine; i >= 0; i--) {
            String line = lines[i].trim();
            if (isClassDeclaration(line)) {
                String className = extractClassName(line);
                JsCodeBounds bounds = findCodeBounds(lines, i);
                if (bounds != null && cursorLine >= i && cursorLine <= bounds.endLine) {
                    String content = extractContentFromBounds(lines, bounds);
                    return new JsCodeStructure(
                        className, 
                        content, 
                        "class", 
                        calculateOffset(lines, bounds.startLine),
                        calculateOffset(lines, bounds.endLine + 1)
                    );
                }
            }
        }
        return null;
    }

    private JsCodeStructure findModule(String[] lines, int cursorLine, String fullContent) {
        // For cases where we're not in a specific function/class, 
        // try to find a meaningful module or export block
        for (int i = cursorLine; i >= 0; i--) {
            String line = lines[i].trim();
            if (isModuleExport(line)) {
                String moduleName = extractModuleName(line);
                // Find the bounds of this export block
                JsCodeBounds bounds = findExportBounds(lines, i);
                if (bounds != null && cursorLine >= i && cursorLine <= bounds.endLine) {
                    String content = extractContentFromBounds(lines, bounds);
                    return new JsCodeStructure(
                        moduleName, 
                        content, 
                        "module", 
                        calculateOffset(lines, bounds.startLine),
                        calculateOffset(lines, bounds.endLine + 1)
                    );
                }
            }
        }
        return null;
    }

    private JsCodeStructure createFallbackStructure(String content, int cursorOffset, String fileName) {
        String[] lines = content.split("\n");
        int cursorLine = content.substring(0, cursorOffset).split("\n").length - 1;
        
        // Try to find any meaningful code around the cursor
        int startLine = Math.max(0, cursorLine - 15);
        int endLine = Math.min(lines.length - 1, cursorLine + 15);
        
        // Look for natural boundaries (empty lines, comments, etc.)
        for (int i = cursorLine; i >= startLine; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || isCodeBoundary(line)) {
                startLine = Math.max(0, i + 1);
                break;
            }
        }
        
        for (int i = cursorLine; i <= endLine; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || isCodeBoundary(line)) {
                endLine = Math.max(cursorLine, i - 1);
                break;
            }
        }
        
        // Ensure we have at least some meaningful content
        if (endLine - startLine < 3) {
            startLine = Math.max(0, cursorLine - 5);
            endLine = Math.min(lines.length - 1, cursorLine + 5);
        }
        
        // Extract the content
        StringBuilder fallbackContent = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            fallbackContent.append(lines[i]);
            if (i < endLine) fallbackContent.append("\n");
        }
        
        // Generate a meaningful name based on content
        String structureName = generateFallbackName(lines, cursorLine, fileName);
        
        return new JsCodeStructure(
            structureName,
            fallbackContent.toString(),
            "code-block",
            calculateOffset(lines, startLine),
            calculateOffset(lines, endLine + 1)
        );
    }

    private String generateFallbackName(String[] lines, int cursorLine, String fileName) {
        // Try to find meaningful identifiers near the cursor
        String currentLine = lines[cursorLine].trim();
        
        // Look for variable assignments, object properties, etc.
        if (currentLine.matches(".*\\b\\w+\\s*[=:].*")) {
            String[] parts = currentLine.split("[=:]");
            if (parts.length > 0) {
                String name = parts[0].replaceAll("(const|let|var|function|class)\\s*", "").trim();
                if (!name.isEmpty() && name.matches("\\w+")) {
                    return name;
                }
            }
        }
        
        // Look for function calls or property access
        if (currentLine.matches(".*\\w+\\s*\\(.*\\).*") || currentLine.matches(".*\\w+\\.\\w+.*")) {
            String[] words = currentLine.split("\\W+");
            for (String word : words) {
                if (word.matches("\\w{3,}") && !isCommonKeyword(word)) {
                    return word + "_Context";
                }
            }
        }
        
        // Default fallback
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        return "CodeBlock_" + baseName + "_Line" + (cursorLine + 1);
    }

    private boolean isCommonKeyword(String word) {
        String[] keywords = {"const", "let", "var", "function", "class", "if", "else", "for", "while", 
                           "return", "this", "true", "false", "null", "undefined", "console", "log"};
        for (String keyword : keywords) {
            if (keyword.equals(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isCodeBoundary(String line) {
        // Look for patterns that indicate natural code boundaries
        return line.startsWith("//") && (line.contains("===") || line.contains("---") || line.contains("***")) ||
               line.startsWith("/*") ||
               line.startsWith("/**") ||
               line.matches("^\\s*//\\s*[A-Z][a-zA-Z\\s]+$") || // Comment headers like "// Main function"
               line.matches("^\\s*//\\s*-{3,}.*") || // Comment separators
               line.matches("^\\s*//\\s*={3,}.*") || // Comment separators
               line.startsWith("import ") ||
               line.startsWith("export ") ||
               line.matches("^\\s*(module\\.exports|exports\\.).*"); // Module boundaries
    }
    private boolean isFunctionDeclaration(String line) {
        // Improved pattern matching for various JS/TS function patterns
        return line.matches("^\\s*(export\\s+)?(async\\s+)?function\\s+\\w+.*\\(.*\\).*") ||
               line.matches("^\\s*(export\\s+)?(const|let|var)\\s+\\w+\\s*=\\s*(async\\s+)?function.*\\(.*\\).*") ||
               line.matches("^\\s*(export\\s+)?(const|let|var)\\s+\\w+\\s*=\\s*(async\\s+)?\\(.*\\)\\s*=>.*") ||
               line.matches("^\\s*\\w+\\s*:\\s*(async\\s+)?function\\s*\\(.*\\).*") ||
               line.matches("^\\s*(async\\s+)?\\w+\\s*\\(.*\\)\\s*\\{.*") ||
               line.matches("^\\s*\\w+\\s*:\\s*(async\\s+)?\\(.*\\)\\s*=>.*") ||
               // Method definitions in objects/classes
               line.matches("^\\s*\\w+\\s*\\(.*\\)\\s*\\{.*") ||
               // Arrow functions assigned to variables
               line.matches("^\\s*(const|let|var)\\s+\\w+\\s*=\\s*\\(.*\\)\\s*=>.*");
    }

    private boolean isClassDeclaration(String line) {
        return line.matches("^\\s*class\\s+\\w+.*") || 
               line.matches("^\\s*export\\s+class\\s+\\w+.*");
    }

    private boolean isModuleExport(String line) {
        return line.matches("^\\s*export\\s+(default\\s+)?\\{.*") ||
               line.matches("^\\s*module\\.exports\\s*=.*") ||
               line.matches("^\\s*exports\\.\\w+.*");
    }

    private String extractFunctionName(String line) {
        // Handle various function declaration patterns
        
        // Standard function declaration: function name() {}
        if (line.matches(".*function\\s+\\w+.*")) {
            String[] parts = line.split("function\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\(")[0].trim();
            }
        }
        
        // Variable assignment with function: const name = function() {}
        if (line.matches(".*(const|let|var)\\s+\\w+\\s*=.*function.*")) {
            String[] parts = line.split("(const|let|var)\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\s*=")[0].trim();
            }
        }
        
        // Arrow function: const name = () => {}
        if (line.matches(".*(const|let|var)\\s+\\w+\\s*=.*=>.*")) {
            String[] parts = line.split("(const|let|var)\\s+");
            if (parts.length > 1) {
                return parts[1].split("\\s*=")[0].trim();
            }
        }
        
        // Method definition in object: name: function() {} or name() {}
        if (line.matches(".*\\w+\\s*[:()].*")) {
            String trimmed = line.trim();
            int colonIndex = trimmed.indexOf(':');
            int parenIndex = trimmed.indexOf('(');
            int splitIndex = -1;
            
            if (colonIndex > 0 && (parenIndex < 0 || colonIndex < parenIndex)) {
                splitIndex = colonIndex;
            } else if (parenIndex > 0) {
                splitIndex = parenIndex;
            }
            
            if (splitIndex > 0) {
                return trimmed.substring(0, splitIndex).trim();
            }
        }
        
        return "anonymous";
    }

    private String extractClassName(String line) {
        // Handle class declarations with potential export keywords
        if (line.contains("class ")) {
            String[] parts = line.split("class\\s+");
            if (parts.length > 1) {
                String namepart = parts[1].split("\\s+")[0].split("\\{")[0].split("\\(")[0].trim();
                return namepart.isEmpty() ? "UnknownClass" : namepart;
            }
        }
        return "UnknownClass";
    }

    private String extractModuleName(String line) {
        if (line.contains("module.exports")) {
            return "ModuleExports";
        }
        if (line.contains("export")) {
            return "ExportBlock";
        }
        return "Module";
    }

    private JsCodeBounds findCodeBounds(String[] lines, int startLine) {
        int braceCount = 0;
        boolean foundOpenBrace = false;
        
        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i];
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpenBrace = true;
                } else if (c == '}') {
                    braceCount--;
                    if (foundOpenBrace && braceCount == 0) {
                        return new JsCodeBounds(startLine, i);
                    }
                }
            }
        }
        
        return foundOpenBrace ? new JsCodeBounds(startLine, lines.length - 1) : null;
    }

    private JsCodeBounds findExportBounds(String[] lines, int startLine) {
        // For export blocks, find the end of the statement or block
        if (lines[startLine].contains("{")) {
            return findCodeBounds(lines, startLine);
        } else {
            // Single line export
            return new JsCodeBounds(startLine, startLine);
        }
    }

    private String extractContentFromBounds(String[] lines, JsCodeBounds bounds) {
        StringBuilder content = new StringBuilder();
        for (int i = bounds.startLine; i <= bounds.endLine; i++) {
            content.append(lines[i]);
            if (i < bounds.endLine) content.append("\n");
        }
        return content.toString();
    }

    private int calculateOffset(String[] lines, int lineNumber) {
        int offset = 0;
        for (int i = 0; i < lineNumber && i < lines.length; i++) {
            offset += lines[i].length() + 1; // +1 for newline
        }
        return offset;
    }

    static class JsCodeStructure {
        final String name;
        final String content;
        final String type;
        final int startOffset;
        final int endOffset;

        JsCodeStructure(String name, String content, String type, int startOffset, int endOffset) {
            this.name = name;
            this.content = content;
            this.type = type;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    static class JsCodeBounds {
        final int startLine;
        final int endLine;

        JsCodeBounds(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}
