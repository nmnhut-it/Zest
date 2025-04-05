package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool for fixing imports in generated test files.
 * This is based on the ImportHandlingStage logic but made available as a tool.
 * Updated to use JSON-RPC style parameters.
 */
public class FixTestImportsTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(FixTestImportsTool.class);
    private final Project project;

    public FixTestImportsTool(@NotNull Project project) {
        super("fix_test_imports", "Adds necessary imports from source files to test files");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String testCode = getStringParam(params, "testCode", "");
        String sourceImports = getStringParam(params, "sourceImports", "");

        if (testCode.isEmpty()) {
            return "Error: Test code is required. Please provide the test code to process.";
        }

        try {
            return addImportsToTestCode(testCode, sourceImports);
        } catch (Exception e) {
            LOG.error("Error fixing test imports: " + e.getMessage(), e);
            return "Error fixing test imports: " + e.getMessage();
        }
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("testCode", "package com.example;\n\npublic class MyTest { ... }");
        params.addProperty("sourceImports", "import java.util.*;\nimport java.io.File;");
        return params;
    }

    /**
     * Adds necessary imports from the source file to the test code.
     */
    private String addImportsToTestCode(String testCode, String sourceImports) {
        // Extract existing imports from the test code
        Set<String> existingImports = extractImports(testCode);
        LOG.info("Found " + existingImports.size() + " existing imports in test code");

        if (sourceImports == null || sourceImports.isEmpty()) {
            LOG.info("No imports provided from source file");
            return testCode;
        }

        // Extract imports from source file
        Set<String> sourceImportSet = new HashSet<>();
        Pattern importPattern = Pattern.compile("import\\s+([^;]+);");
        Matcher matcher = importPattern.matcher(sourceImports);
        while (matcher.find()) {
            // Only add the actual import part (not the "import" keyword)
            // and validate it contains a package specification (has a dot)
            String importClass = matcher.group(1).trim();
            if (importClass.contains(".")) {
                sourceImportSet.add(importClass);
            }
        }
        LOG.info("Found " + sourceImportSet.size() + " imports in source file");

        // Split the test code into package declaration, imports, and class body
        int packageEndIndex = testCode.indexOf(";") + 1;
        String packageDeclaration = testCode.substring(0, packageEndIndex);

        int classStartIndex = testCode.indexOf("public class");
        if (classStartIndex == -1) {
            LOG.warn("Could not find 'public class' in test code");
            return testCode;
        }

        String importSection = testCode.substring(packageEndIndex, classStartIndex).trim();
        String classBody = testCode.substring(classStartIndex);

        // Add source imports that don't already exist
        StringBuilder finalImportSection = new StringBuilder();

        finalImportSection.append("\n\n// Imports from source class\n");
        for (String importStatement : sourceImportSet) {
            if (!existingImports.contains(importStatement)) {
                finalImportSection.append("import ").append(importStatement).append(";\n");
            }
        }
        finalImportSection.append("\n\n// ------------\n");

        finalImportSection.append(importSection);

        // Reconstruct the test code with the updated imports
        return packageDeclaration + "\n\n" + finalImportSection.toString() + "\n" + classBody;
    }

    /**
     * Extracts import statements from the given code.
     */
    private Set<String> extractImports(String code) {
        Set<String> imports = new HashSet<>();
        Pattern importPattern = Pattern.compile("import\\s+([^;]+);");
        Matcher matcher = importPattern.matcher(code);
        while (matcher.find()) {
            imports.add(matcher.group(1).trim());
        }
        return imports;
    }
}