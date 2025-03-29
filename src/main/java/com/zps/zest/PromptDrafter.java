package com.zps.zest;

public class PromptDrafter {


    public static String createPrompt(String packageName, String className,
                                      String imports, String junitVersion, String classContext, boolean hasMockito) {
        // Validate and sanitize package name
        packageName = validatePackageName(packageName);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Generate a comprehensive ").append(junitVersion).append(" test suite for the following Java class:\n\n");
        promptBuilder.append("IMPORTANT - Package naming requirements:\n");
        promptBuilder.append("- Use exactly this package name: ").append(packageName).append("\n");
        promptBuilder.append("- DO NOT use generic package names like com.example or org.test\n");
        promptBuilder.append("- Follow Java package naming conventions (all lowercase, dot-separated)\n\n");
        promptBuilder.append("Package: ").append(packageName).append("\n");
        promptBuilder.append("Class: ").append(className).append("\n\n");
        promptBuilder.append("Imports:\n```java\n").append(imports).append("\n```\n\n");
        promptBuilder.append("\n\n Those imports MUST BE INCLUDED in the result\n");
        promptBuilder.append("Class Information:\n").append(classContext).append("\n\n");

        promptBuilder.append("Test Requirements:\n");
        if (junitVersion.equals("JUnit 5")) {
            appendJUnit5Requirements(promptBuilder);
        } else {
            appendJUnit4Requirements(promptBuilder);
        }

        promptBuilder.append("2. Name the test class \"").append(className).append("Test\"\n");
        promptBuilder.append("3. Follow these test structure best practices:\n");
        promptBuilder.append("   - Each test method should verify a specific behavior of the class\n");
        promptBuilder.append("   - Use descriptive test method names that explain what is being tested\n");
        promptBuilder.append("   - Follow the AAA pattern (Arrange, Act, Assert) with clear separation\n");
        promptBuilder.append("   - Initialize test data in setup methods when appropriate\n");

        promptBuilder.append("4. Test all public methods with these scenarios:\n");
        promptBuilder.append("   - Happy path tests with normal, expected inputs\n");
        promptBuilder.append("   - Edge cases (empty collections, null inputs, boundary values)\n");
        promptBuilder.append("   - Error cases (if methods throw exceptions)\n");
        promptBuilder.append("   - Any specific business logic edge cases relevant to this class\n");
        promptBuilder.append("   - Integration tests for how methods interact with each other\n");
        promptBuilder.append("   - MAKE SURE ONLY MEANINGFUL TESTS ARE INCLUDED\n");

        // Use version-specific assertion methods
        appendAssertionRequirements(promptBuilder, junitVersion);
        appendMockingRequirements(promptBuilder, hasMockito);

        promptBuilder.append("7. Include comprehensive JavaDoc for the test class and detailed comments for tests explaining:\n");
        promptBuilder.append("   - Overall testing strategy for the class\n");
        promptBuilder.append("   - Explanation of test data choices\n");
        promptBuilder.append("   - Explanation of assertions and their business meaning\n");

        // Include version-specific imports
        appendImportRequirements(promptBuilder, junitVersion, packageName);

        promptBuilder.append("9. For code coverage:\n");
        promptBuilder.append("   - Ensure all public methods of the class are tested\n");
        promptBuilder.append("   - Ensure all branches of conditional logic are tested\n");
        promptBuilder.append("   - Cover null handling and exception paths\n");
        promptBuilder.append("   - MAKE SURE ONLY MEANINGFUL TESTS ARE INCLUDED\n");
        promptBuilder.append("   - Test class state and interactions between methods\n");

        promptBuilder.append("10. IMPORTANT: Generate complete, compilable code\n");
        promptBuilder.append("    - Do not use placeholders or TODOs\n");
        promptBuilder.append("    - Include complete method implementations\n");
        promptBuilder.append("    - Create realistic test data that matches class requirements\n");
        promptBuilder.append("    - Use the exact package name: ").append(packageName).append("\n");
        promptBuilder.append("    - Ensure all imports are explicit (no wildcards like org.junit.*)\n");
        promptBuilder.append("    - MAKE SURE ALL NECESSARY IMPORTS ARE INCLUDED.\n");
        promptBuilder.append("    - Use full method signatures for assertions as specified in section 5\n\n");

        promptBuilder.append("Long code responses are accepted. Return ONLY the complete test class code without any explanations, additional comments, or markdown formatting.");

        return promptBuilder.toString();
    }
    private static void appendMockingRequirements(StringBuilder promptBuilder, boolean hasMockito) {
        promptBuilder.append("6. For mocking:\n");

        if (hasMockito) {
            promptBuilder.append("   - Use only standard Mockito functions that exist in the library. \n");
            promptBuilder.append("   - Stick to these core Mockito functions: mock(), when(), verify(), any(), eq(), times(), never(), doReturn(), doThrow(), doAnswer(), doNothing(), and reset(). \n");
            promptBuilder.append("   - Do not invent or hallucinate non-existing Mockito functions. \n");
            promptBuilder.append("   - Example: \n");
            promptBuilder.append("     ```java\n");
            promptBuilder.append("     // Creating mocks\n");
            promptBuilder.append("     UserRepository mockRepository = mock(UserRepository.class);\n");
            promptBuilder.append("     \n");
            promptBuilder.append("     // Stubbing\n");
            promptBuilder.append("     when(mockRepository.findById(eq(1L))).thenReturn(new User(1L, \"Test User 1\"));\n");
            promptBuilder.append("     \n");
            promptBuilder.append("     // Verification\n");
            promptBuilder.append("     verify(mockRepository, times(1)).save(any(User.class));\n");
            promptBuilder.append("     ```\n");
        } else {
            promptBuilder.append("   - Avoid using mocking frameworks. \n");
            promptBuilder.append("   - Instead, always use helper methods to create objects. \n");
            promptBuilder.append("   - Always use existing constructors when creating objects. Don't invent setters or methods that don't exist. \n");
            promptBuilder.append("   - Example: \n");
            promptBuilder.append("     ```java\n");
            promptBuilder.append("     private User createTestUser(long userId) {\n");
            promptBuilder.append("         // Use existing constructor - don't invent one\n");
            promptBuilder.append("         return new User(userId, \"Test User \" + userId);\n");
            promptBuilder.append("     }\n");
            promptBuilder.append("     ```\n");
            promptBuilder.append("   - For complex dependencies, consider implementing test-specific implementations:\n");
            promptBuilder.append("     ```java\n");
            promptBuilder.append("     class TestUserRepository implements UserRepository {\n");
            promptBuilder.append("         private Map<Long, User> users = new HashMap<>();\n");
            promptBuilder.append("         private List<User> savedUsers = new ArrayList<>();\n");
            promptBuilder.append("         \n");
            promptBuilder.append("         @Override\n");
            promptBuilder.append("         public User findById(Long id) {\n");
            promptBuilder.append("             return users.get(id);\n");
            promptBuilder.append("         }\n");
            promptBuilder.append("         \n");
            promptBuilder.append("         @Override\n");
            promptBuilder.append("         public User save(User user) {\n");
            promptBuilder.append("             savedUsers.add(user);\n");
            promptBuilder.append("             users.put(user.getId(), user);\n");
            promptBuilder.append("             return user;\n");
            promptBuilder.append("         }\n");
            promptBuilder.append("         \n");
            promptBuilder.append("         // Helper methods for verification\n");
            promptBuilder.append("         public List<User> getSavedUsers() {\n");
            promptBuilder.append("             return savedUsers;\n");
            promptBuilder.append("         }\n");
            promptBuilder.append("     }\n");
            promptBuilder.append("     ```\n");
            promptBuilder.append("   - For objects that cannot be easily created, add a TODO comment explaining what needs to be implemented. DO NOT EXTENDS THE WHOLE CLASS\n");
        }
    }
    private static String validatePackageName(String packageName) {
        // Prevent generic package names
        if (packageName == null || packageName.isEmpty() ||
                packageName.startsWith("com.example") ||
                packageName.equals("example") ||
                packageName.equals("test")) {
            return "org.acme.project"; // Default to a non-generic package
        }

        // Ensure package follows Java conventions
        if (!packageName.matches("^[a-z]+(\\.[a-z][a-z0-9_]*)*$")) {
            // Replace invalid characters and format properly
            packageName = packageName.toLowerCase()
                    .replaceAll("[^a-z0-9_.]", "")
                    .replaceAll("\\.+", ".");

            // Ensure it starts with a letter
            if (!packageName.matches("^[a-z].*")) {
                packageName = "org." + packageName;
            }
        }

        return packageName;
    }

    private static void appendJUnit5Requirements(StringBuilder promptBuilder) {
        promptBuilder.append("1. Use ONLY these JUnit 5 annotations:\n");
        promptBuilder.append("   - @Test - for basic test methods\n");
        promptBuilder.append("   - @DisplayName - for descriptive test names\n");
        promptBuilder.append("   - @BeforeEach, @AfterEach - for setup/teardown before/after each test\n");
        promptBuilder.append("   - @BeforeAll, @AfterAll - for setup/teardown before/after all tests\n");
        promptBuilder.append("   - @ParameterizedTest - for tests with multiple sets of arguments\n");
        promptBuilder.append("   - @ValueSource, @CsvSource, @MethodSource - for parameterized test sources\n");
        promptBuilder.append("   - @Nested - for grouping related tests\n");
        promptBuilder.append("   - @Disabled - for temporarily disabling tests\n");
        promptBuilder.append("   - @Timeout - for specifying time limits\n");
     }

    private static void appendJUnit4Requirements(StringBuilder promptBuilder) {
        promptBuilder.append("1. Use ONLY these JUnit 4 annotations:\n");
        promptBuilder.append("   - @Test - for basic test methods\n");
        promptBuilder.append("   - @Before, @After - for setup/teardown before/after each test\n");
        promptBuilder.append("   - @BeforeClass, @AfterClass - for setup/teardown before/after all tests\n");
        promptBuilder.append("   - @Ignore - for temporarily disabling tests\n");
        promptBuilder.append("   - @Rule - for TestName, ExpectedException, TemporaryFolder, etc.\n");
         promptBuilder.append("   - @Category - for categorizing tests\n");
    }

    private static void appendAssertionRequirements(StringBuilder promptBuilder, String junitVersion) {
        promptBuilder.append("5. For assertions, use ONLY these methods with class prefixes (NO static imports):\n");

        if (junitVersion.equals("JUnit 5")) {
            promptBuilder.append("   Import: import org.junit.jupiter.api.Assertions; // IMPORTANT: NOT static import\n\n");
            promptBuilder.append("   Method signatures (ALWAYS use with Assertions prefix):\n");
            promptBuilder.append("   - Assertions.assertEquals(expected, actual, String message)\n");
            promptBuilder.append("   - Assertions.assertTrue(boolean condition, String message)\n");
            promptBuilder.append("   - Assertions.assertFalse(boolean condition, String message)\n");
            promptBuilder.append("   - Assertions.assertNull(Object object, String message)\n");
            promptBuilder.append("   - Assertions.assertNotNull(Object object, String message)\n");
            promptBuilder.append("   - Exception ex = Assertions.assertThrows(Class<T> expectedType, Executable executable)\n");

            // Add more examples with explicit class prefix
        } else {
            promptBuilder.append("   Import: import org.junit.Assert; // IMPORTANT: NOT static import\n\n");
            promptBuilder.append("   Method signatures (ALWAYS use with Assert prefix):\n");
            promptBuilder.append("   - Assert.assertEquals(String message, Object expected, Object actual)\n");
            promptBuilder.append("   - Assert.assertTrue(String message, boolean condition)\n");
            promptBuilder.append("   - Assert.assertFalse(String message, boolean condition)\n");
            promptBuilder.append("   - Assert.assertNull(String message, Object object)\n");
            promptBuilder.append("   - Assert.assertNotNull(String message, Object object)\n");

            // Add more examples with explicit class prefix
        }

        promptBuilder.append("\n   CRITICAL:\n");
        promptBuilder.append("   - NEVER use static imports for assertions\n");
        promptBuilder.append("   - ALWAYS prefix assertion methods with 'Assertions.' or 'Assert.'\n");
        promptBuilder.append("   - Example: Assertions.assertNotNull(result, \"Result should not be null\");\n");
        promptBuilder.append("   - NEVER do: assertNotNull(result, \"Result should not be null\");\n");
    }
    private static void appendImportRequirements(StringBuilder promptBuilder, String junitVersion, String packageName) {
        promptBuilder.append("8. Include ALL these necessary imports (DO NOT use wildcard imports like .* except where specified):\n");
        promptBuilder.append(" - CRITICAL: INCLUDE ALL IMPORTS FROM THE SOURCE CLASS, INCLUDING WILDCARD IMPORTS.   \n");
        promptBuilder.append(" - Redundant imports are accepted\n");

        if (junitVersion.equals("JUnit 5")) {
            promptBuilder.append("   - JUnit 5 core imports:\n");
            promptBuilder.append("     import org.junit.jupiter.api.Assertions; // DO NOT use static imports here\n");
            promptBuilder.append("     import org.junit.jupiter.api.BeforeEach;\n");
            promptBuilder.append("     import org.junit.jupiter.api.AfterEach;\n");
            promptBuilder.append("     import org.junit.jupiter.api.BeforeAll;\n");
            promptBuilder.append("     import org.junit.jupiter.api.AfterAll;\n");
            promptBuilder.append("     import org.junit.jupiter.api.Test;\n");
            promptBuilder.append("     import org.junit.jupiter.api.DisplayName;\n");
            promptBuilder.append("     import org.junit.jupiter.api.Nested;\n");
            promptBuilder.append("     import org.junit.jupiter.api.Disabled;\n");
            promptBuilder.append("     import org.junit.jupiter.api.Timeout;\n");
            promptBuilder.append("     import java.time.Duration; // For timeout assertions\n\n");

            promptBuilder.append("   - JUnit 5 parameterized test imports (only if needed):\n");
            promptBuilder.append("     import org.junit.jupiter.params.ParameterizedTest;\n");
            promptBuilder.append("     import org.junit.jupiter.params.provider.ValueSource;\n");
            promptBuilder.append("     import org.junit.jupiter.params.provider.CsvSource;\n");
            promptBuilder.append("     import org.junit.jupiter.params.provider.MethodSource;\n");
            promptBuilder.append("     import org.junit.jupiter.params.provider.Arguments;\n");
            promptBuilder.append("     import java.util.stream.Stream; // For MethodSource\n\n");
        } else {
            promptBuilder.append("   - JUnit 4 core imports:\n");
            promptBuilder.append("     import org.junit.Assert; // DO NOT use static imports here\n");
            promptBuilder.append("     import org.junit.Before;\n");
            promptBuilder.append("     import org.junit.After;\n");
            promptBuilder.append("     import org.junit.BeforeClass;\n");
            promptBuilder.append("     import org.junit.AfterClass;\n");
            promptBuilder.append("     import org.junit.Test;\n");
            promptBuilder.append("     import org.junit.Ignore;\n");
            promptBuilder.append("     import org.junit.Rule;\n");
            promptBuilder.append("     import org.junit.rules.ExpectedException;\n");
            promptBuilder.append("     import org.junit.rules.Timeout;\n\n");

            promptBuilder.append("   - JUnit 4 parameterized test imports (only if needed):\n");
            promptBuilder.append("     import org.junit.runner.RunWith;\n");
            promptBuilder.append("     import org.junit.runners.Parameterized;\n");
            promptBuilder.append("     import org.junit.runners.Parameterized.Parameters;\n");
            promptBuilder.append("     import java.util.Arrays;\n");
            promptBuilder.append("     import java.util.Collection;\n\n");
        }


        promptBuilder.append("\n   - IMPORTANT: Import the class being tested and its dependencies:\n");
        promptBuilder.append("     import ").append(packageName).append(".").append(packageName.substring(packageName.lastIndexOf(".") + 1)).append("; // Import the specific class, not a wildcard\n");
        promptBuilder.append("     // Also add specific imports for any dependencies used by the class\n\n");

        promptBuilder.append("   - Common Java utility imports (include only what you actually use):\n");
        promptBuilder.append("     import java.util.List;\n");
        promptBuilder.append("     import java.util.ArrayList;\n");
        promptBuilder.append("     import java.util.Map;\n");
        promptBuilder.append("     import java.util.HashMap;\n");
        promptBuilder.append("     import java.util.Set;\n");
        promptBuilder.append("     import java.util.HashSet;\n");
        promptBuilder.append("     import java.util.Optional;\n");
        promptBuilder.append("     import java.io.IOException; // For exception handling\n");
        promptBuilder.append("     import java.time.LocalDate; // For date handling\n");
        promptBuilder.append("     import java.time.LocalDateTime; // For datetime handling\n\n");

        promptBuilder.append("   CRITICAL IMPORT REQUIREMENTS:\n");
        promptBuilder.append("   - NEVER use wildcard imports like 'org.junit.*' or 'java.util.*' - specify each import exactly\n");
        promptBuilder.append("   - ALWAYS follow the exact import structure above when using assertion methods\n");
        promptBuilder.append("   - When you need to use assertions, import the class and call methods with class prefix\n");
        promptBuilder.append("     (e.g., 'Assertions.assertEquals()' for JUnit 5 or 'Assert.assertEquals()' for JUnit 4)\n");
        promptBuilder.append("   - Do not forget to import exception classes like IOException, IllegalArgumentException when used\n");
        promptBuilder.append("   - MAKE SURE ALL IMPORTS FROM THE SOURCE CLASS ARE INCLUDED\n");
    }
}