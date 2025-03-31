package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage for handling imports in the generated test code before file creation.
 * This stage processes the test code to ensure all necessary imports are included
 * before the file is written to disk.
 */
class ImportHandlingStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(ImportHandlingStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        if (context.getTestCode() == null || context.getTestCode().isEmpty()) {
            throw new PipelineExecutionException("No test code available to process imports");
        }

        try {
            // Copy imports from source file to test code
            String codeWithImports = addImportsToTestCode(context);
            context.setTestCode(codeWithImports);
        } catch (Exception e) {
            LOG.error("Error in ImportHandlingStage: " + e.getMessage(), e);
            throw new PipelineExecutionException("Failed to process imports: " + e.getMessage(), e);
        }
    }

    /**
     * Adds necessary imports from the source file to the test code.
     *
     * @param context The test generation context
     * @return The updated test code with additional imports
     */
    private String addImportsToTestCode(CodeContext context) {
        String testCode = context.getTestCode();

        // Extract existing imports from the test code
        Set<String> existingImports = extractImports(testCode);
        LOG.info("Found " + existingImports.size() + " existing imports in generated test code");

        // Get all source file imports
        String sourceImports = context.getImports();
        if (sourceImports == null || sourceImports.isEmpty()) {
            LOG.info("No imports found in source file");
            return testCode;
        }

        // Minimal fix:
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
        String importSection = testCode.substring(packageEndIndex, classStartIndex).trim();
        String classBody = testCode.substring(classStartIndex);

        // Add source imports that don't already exist
        StringBuilder finalImportSection = new StringBuilder();

        finalImportSection.append("\n\n// Imports from source class\n");
        // Then, when adding the imports later:
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
     *
     * @param code The code to extract imports from
     * @return A set of import statements
     */
    private Set<String> extractImports(String code) {
        Set<String> imports = new HashSet<>();
        Pattern importPattern = Pattern.compile("import\\s+([^;]+);");
        Matcher matcher = importPattern.matcher(code);
        while (matcher.find()) {
            imports.add(matcher.group(0).trim());
        }
        return imports;
    }
}