package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Tools for fixing Java compilation errors.
 * Focuses on granular, unambiguous changes only.
 */
public class TestFixingTools {
    private static final Logger LOG = Logger.getInstance(TestFixingTools.class);
    private final Project project;
    
    public TestFixingTools(@NotNull Project project) {
        this.project = project;
    }
    
    @Tool("Add import statement at the top of Java file after package declaration")
    public String addImport(@NotNull String filePath, @NotNull String importStatement) {
        try {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (file == null) {
                return "ERROR: File not found: " + filePath;
            }
            
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            String cleanImport = cleanImportStatement(importStatement);
            
            // Check if import already exists
            if (content.contains("import " + cleanImport + ";")) {
                return "Import already exists: " + cleanImport;
            }
            
            // Find insertion point after package declaration
            String newContent;
            if (content.contains("package ")) {
                // Insert after package line
                newContent = content.replaceFirst(
                    "(package\\s+[^;]+;\\s*\\n)",
                    "$1import " + cleanImport + ";\n"
                );
            } else {
                // Insert at beginning of file
                newContent = "import " + cleanImport + ";\n\n" + content;
            }
            
            // Write the changes
            WriteAction.runAndWait(() -> {
                try {
                    file.setBinaryContent(newContent.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOG.error("Failed to write import to file", e);
                }
            });
            
            return "Added import: " + cleanImport;
            
        } catch (Exception e) {
            LOG.error("Error adding import", e);
            return "ERROR: Failed to add import - " + e.getMessage();
        }
    }
    
    @Tool("Replace specific code string with new code (oldCode must be unique in file)")
    public String replaceCode(@NotNull String filePath, @NotNull String oldCode, @NotNull String newCode) {
        try {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (file == null) {
                return "ERROR: File not found: " + filePath;
            }
            
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            
            // Check if oldCode exists
            if (!content.contains(oldCode)) {
                return "ERROR: Code not found in file: '" + oldCode + "'";
            }
            
            // Check for uniqueness - critical for safety
            int firstIndex = content.indexOf(oldCode);
            int lastIndex = content.lastIndexOf(oldCode);
            if (firstIndex != lastIndex) {
                return "ERROR: Multiple occurrences of '" + oldCode + "' found. Be more specific to ensure unique replacement.";
            }
            
            // Perform replacement
            String newContent = content.replace(oldCode, newCode);
            
            // Write the changes
            WriteAction.runAndWait(() -> {
                try {
                    file.setBinaryContent(newContent.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOG.error("Failed to write replacement to file", e);
                }
            });
            
            return "Replaced: '" + oldCode + "' with '" + newCode + "'";
            
        } catch (Exception e) {
            LOG.error("Error replacing code", e);
            return "ERROR: Failed to replace code - " + e.getMessage();
        }
    }
    
    /**
     * Clean import statement by removing 'import' keyword and semicolon.
     */
    private String cleanImportStatement(@NotNull String importStatement) {
        String clean = importStatement.trim();
        if (clean.startsWith("import ")) {
            clean = clean.substring(7);
        }
        if (clean.endsWith(";")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.trim();
    }
}