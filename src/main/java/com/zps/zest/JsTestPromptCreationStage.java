package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Stage for creating test generation prompts specifically for JavaScript/TypeScript code.
 * Tailored to JS/TS testing frameworks and patterns.
 */
public class JsTestPromptCreationStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(JsTestPromptCreationStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Creating JS/TS test generation prompt");

        String targetName = context.getClassName();
        String targetContent = context.getTargetContent();
        String structureType = context.getStructureType();
        String language = context.getLanguage();
        String testFramework = context.getTestFramework();
        String frameworkContext = context.getFrameworkContext();

        if (targetName == null || targetContent == null || targetContent.trim().isEmpty()) {
            throw new PipelineExecutionException("Missing code structure information required for test generation");
        }

        // Build the prompt for JS/TS test generation
        StringBuilder promptBuilder = new StringBuilder();

        // Add header with clear instructions
        promptBuilder.append("# TEST GENERATION REQUEST - ").append(language.toUpperCase()).append("\n\n");
        promptBuilder.append("Please generate comprehensive unit tests for the following ").append(structureType).append(":\n\n");

        // Include structure name and content
        promptBuilder.append("## ").append(structureType.substring(0, 1).toUpperCase()).append(structureType.substring(1))
                .append(": ").append(targetName).append("\n\n");

        // Add framework context
        if (frameworkContext != null && !frameworkContext.isEmpty()) {
            promptBuilder.append("**Framework Context**: ").append(frameworkContext).append("\n");
        }
        if (testFramework != null && !testFramework.isEmpty()) {
            promptBuilder.append("**Test Framework**: ").append(testFramework).append("\n\n");
        }

        promptBuilder.append("```").append(language.toLowerCase()).append("\n");
        promptBuilder.append(targetContent);
        promptBuilder.append("\n```\n\n");

        // Add imports context if available
        String imports = context.getImports();
        if (imports != null && !imports.trim().isEmpty()) {
            promptBuilder.append("## Imports/Dependencies\n\n");
            promptBuilder.append("```").append(language.toLowerCase()).append("\n");
            promptBuilder.append(imports);
            promptBuilder.append("\n```\n\n");
        }

        // Add code analysis context
        String classContext = context.getClassContext();
        if (classContext != null && !classContext.trim().isEmpty()) {
            promptBuilder.append("## Code Analysis Context\n\n");
            promptBuilder.append("```\n");
            promptBuilder.append(classContext);
            promptBuilder.append("\n```\n\n");
        }

        // Test requirements specific to JS/TS
        promptBuilder.append("## Test Requirements\n\n");
        promptBuilder.append("Please generate tests that cover:\n");
        promptBuilder.append("1. **Happy Path Testing**\n");
        promptBuilder.append("   - Normal operation scenarios\n");
        promptBuilder.append("   - Expected return values and behaviors\n\n");

        promptBuilder.append("2. **Edge Cases**\n");
        promptBuilder.append("   - Null/undefined inputs\n");
        promptBuilder.append("   - Empty arrays/objects\n");
        promptBuilder.append("   - Boundary value testing\n\n");

        promptBuilder.append("3. **Error Handling**\n");
        promptBuilder.append("   - Invalid parameters\n");
        promptBuilder.append("   - Exception throwing scenarios\n");
        promptBuilder.append("   - Promise rejections (if applicable)\n\n");

        promptBuilder.append("4. **Async Testing** (if applicable)\n");
        promptBuilder.append("   - Proper async/await testing\n");
        promptBuilder.append("   - Promise resolution/rejection\n");
        promptBuilder.append("   - Timer/callback testing\n\n");

        // Framework-specific requirements
        if (testFramework != null) {
            promptBuilder.append("5. **").append(testFramework).append(" Specific Requirements**\n");
            switch (testFramework.toLowerCase()) {
                case "jest":
                    promptBuilder.append("   - Use Jest matchers (expect, toBe, toEqual, etc.)\n");
                    promptBuilder.append("   - Mock functions with jest.fn() when needed\n");
                    promptBuilder.append("   - Use beforeEach/afterEach for setup/teardown\n");
                    promptBuilder.append("   - Mock modules with jest.mock() if needed\n");
                    break;
                case "mocha":
                    promptBuilder.append("   - Use describe() and it() blocks\n");
                    promptBuilder.append("   - Use Chai assertions (expect, should, assert)\n");
                    promptBuilder.append("   - Use Sinon for mocking if needed\n");
                    break;
                case "jasmine":
                    promptBuilder.append("   - Use Jasmine matchers\n");
                    promptBuilder.append("   - Use spyOn for function mocking\n");
                    promptBuilder.append("   - Use beforeEach/afterEach appropriately\n");
                    break;
                default:
                    promptBuilder.append("   - Follow ").append(testFramework).append(" best practices\n");
                    promptBuilder.append("   - Use appropriate assertion methods\n");
            }
            promptBuilder.append("\n");
        }

        // Framework-specific testing patterns
        if (frameworkContext != null && !frameworkContext.isEmpty()) {
            promptBuilder.append("6. **").append(frameworkContext).append(" Testing Patterns**\n");
            if (frameworkContext.toLowerCase().contains("react")) {
                promptBuilder.append("   - Use React Testing Library patterns\n");
                promptBuilder.append("   - Test component rendering and interactions\n");
                promptBuilder.append("   - Mock props and state changes\n");
            } else if (frameworkContext.toLowerCase().contains("vue")) {
                promptBuilder.append("   - Use Vue Test Utils patterns\n");
                promptBuilder.append("   - Test component mounting and data\n");
            } else if (frameworkContext.toLowerCase().contains("angular")) {
                promptBuilder.append("   - Use Angular testing utilities\n");
                promptBuilder.append("   - TestBed configuration if needed\n");
            }
            promptBuilder.append("\n");
        }

        // Output format requirements
        promptBuilder.append("## Output Format Requirements\n\n");
        promptBuilder.append("Please provide:\n");
        promptBuilder.append("1. **Complete test file** with all necessary imports\n");
        promptBuilder.append("2. **Well-organized test structure** with describe/it blocks\n");
        promptBuilder.append("3. **Meaningful test descriptions** that explain what is being tested\n");
        promptBuilder.append("4. **Setup and teardown** code where appropriate\n");
        promptBuilder.append("5. **Comments** explaining complex test scenarios\n");
        promptBuilder.append("6. **Mocking strategies** for external dependencies\n\n");

        // Test file naming convention
        String testFileName = generateTestFileName(context, language);
        promptBuilder.append("## Test File Name\n");
        promptBuilder.append("Suggested test file name: `").append(testFileName).append("`\n\n");

        // Example test structure
        promptBuilder.append("## Expected Test Structure\n\n");
        promptBuilder.append("```").append(language.toLowerCase()).append("\n");
        generateTestTemplate(promptBuilder, targetName, structureType, testFramework);
        promptBuilder.append("\n```\n\n");

        // Store the generated prompt in the context
        context.setPrompt(promptBuilder.toString());
        LOG.info("JS/TS test generation prompt creation completed");
    }

    private String generateTestFileName(CodeContext context, String language) {
        String baseName = context.getClassName();
        if (baseName.equals("anonymous")) {
            String fileName = context.getPsiFile().getVirtualFile().getName();
            baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        }
        
        String extension = language.toLowerCase().contains("typescript") ? ".test.ts" : ".test.js";
        return baseName + extension;
    }

    private void generateTestTemplate(StringBuilder promptBuilder, String targetName, String structureType, String testFramework) {
        if (testFramework == null) testFramework = "jest";

        // Import statements
        if (testFramework.toLowerCase().equals("jest")) {
            promptBuilder.append("// Jest will auto-import describe, it, expect\n");
        } else if (testFramework.toLowerCase().equals("mocha")) {
            promptBuilder.append("const { expect } = require('chai');\n");
        }
        
        promptBuilder.append("import { ").append(targetName).append(" } from './").append(targetName.toLowerCase()).append("';\n\n");

        // Test structure
        promptBuilder.append("describe('").append(targetName).append("', () => {\n");
        promptBuilder.append("  // Setup and teardown\n");
        promptBuilder.append("  beforeEach(() => {\n");
        promptBuilder.append("    // Initialize test data\n");
        promptBuilder.append("  });\n\n");

        promptBuilder.append("  describe('happy path', () => {\n");
        promptBuilder.append("    it('should handle normal operation', () => {\n");
        promptBuilder.append("      // Test normal behavior\n");
        promptBuilder.append("    });\n");
        promptBuilder.append("  });\n\n");

        promptBuilder.append("  describe('edge cases', () => {\n");
        promptBuilder.append("    it('should handle null/undefined inputs', () => {\n");
        promptBuilder.append("      // Test edge cases\n");
        promptBuilder.append("    });\n");
        promptBuilder.append("  });\n\n");

        promptBuilder.append("  describe('error handling', () => {\n");
        promptBuilder.append("    it('should throw appropriate errors', () => {\n");
        promptBuilder.append("      // Test error scenarios\n");
        promptBuilder.append("    });\n");
        promptBuilder.append("  });\n");
        promptBuilder.append("});");
    }
}
