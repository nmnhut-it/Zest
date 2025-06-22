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
        promptBuilder.append("# TEST GENERATION REQUEST - ").append(language.toUpperCase()).append(" FILE\n\n");
        promptBuilder.append("Please generate comprehensive unit tests for the following ").append(language).append(" file:\n\n");

        // Include file name and content
        promptBuilder.append("## File: ").append(targetName).append(".").append(language.toLowerCase().substring(0, 2)).append("\n\n");

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

        // Add code analysis context (since we have full file, this provides structure overview)
        String classContext = context.getClassContext();
        if (classContext != null && !classContext.trim().isEmpty()) {
            promptBuilder.append("## File Structure Analysis\n\n");
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
            } else if (frameworkContext.toLowerCase().contains("cocos")) {
                promptBuilder.append("   - Test game logic and scene management\n");
                promptBuilder.append("   - Mock Cocos2d-x framework dependencies\n");
                promptBuilder.append("   - Test lifecycle methods (ctor, onEnter, onExit)\n");
                promptBuilder.append("   - Verify node hierarchy and child management\n");
                promptBuilder.append("   - Test action sequences and animations\n");
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
        String baseName = context.getClassName(); // This is now the filename without extension
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
        
        // Import from the file being tested
        promptBuilder.append("import * as ").append(toCamelCase(targetName)).append(" from './").append(targetName).append("';\n");
        promptBuilder.append("// Import specific functions/classes as needed:\n");
        promptBuilder.append("// import { functionName, ClassName } from './").append(targetName).append("';\n\n");

        // Test structure for the entire file
        promptBuilder.append("describe('").append(targetName).append(" module', () => {\n");
        promptBuilder.append("  // Setup and teardown for the entire file\n");
        promptBuilder.append("  beforeEach(() => {\n");
        promptBuilder.append("    // Initialize test data\n");
        promptBuilder.append("  });\n\n");

        promptBuilder.append("  describe('exported functions', () => {\n");
        promptBuilder.append("    it('should test each exported function', () => {\n");
        promptBuilder.append("      // Test exported functions\n");
        promptBuilder.append("    });\n");
        promptBuilder.append("  });\n\n");

        promptBuilder.append("  describe('exported classes', () => {\n");
        promptBuilder.append("    it('should test class constructors and methods', () => {\n");
        promptBuilder.append("      // Test class functionality\n");
        promptBuilder.append("    });\n");
        promptBuilder.append("  });\n\n");

        promptBuilder.append("  describe('module behavior', () => {\n");
        promptBuilder.append("    it('should test overall module integration', () => {\n");
        promptBuilder.append("      // Test how different parts work together\n");
        promptBuilder.append("    });\n");
        promptBuilder.append("  });\n");
        promptBuilder.append("});");
    }

    private String toCamelCase(String input) {
        return input.substring(0, 1).toLowerCase() + input.substring(1);
    }
}
