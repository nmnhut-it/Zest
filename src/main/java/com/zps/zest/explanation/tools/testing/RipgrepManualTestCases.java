package com.zps.zest.explanation.tools.testing;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Predefined test cases for manual regression testing of RipgrepCodeTool.
 * Each test case includes input parameters and expected behavior descriptions.
 */
public class RipgrepManualTestCases {

    public enum TestMode {
        BASIC_SEARCH,
        FIND_FILES,
        SEARCH_WITH_CONTEXT,
        BEFORE_CONTEXT,
        AFTER_CONTEXT
    }

    public static class TestCase {
        public final String name;
        public final String description;
        public final String query;
        @Nullable
        public final String filePattern;
        @Nullable
        public final String excludePattern;
        public final TestMode mode;
        public final int contextLines;
        public final int beforeLines;
        public final int afterLines;
        public final String expectedBehavior;
        public final String successCriteria;

        public TestCase(String name, String description, String query,
                       @Nullable String filePattern, @Nullable String excludePattern,
                       TestMode mode, int contextLines, int beforeLines, int afterLines,
                       String expectedBehavior, String successCriteria) {
            this.name = name;
            this.description = description;
            this.query = query;
            this.filePattern = filePattern;
            this.excludePattern = excludePattern;
            this.mode = mode;
            this.contextLines = contextLines;
            this.beforeLines = beforeLines;
            this.afterLines = afterLines;
            this.expectedBehavior = expectedBehavior;
            this.successCriteria = successCriteria;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Empty test case for manual input
    public static final TestCase EMPTY_TEST_CASE = new TestCase(
        "-- Select Test Case --",
        "Manual test input",
        "",
        null,
        null,
        TestMode.BASIC_SEARCH,
        0, 0, 0,
        "User-defined test",
        "User-defined criteria"
    );

    private static final List<TestCase> TEST_CASES = new ArrayList<>();

    static {
        // Basic Search Tests
        TEST_CASES.add(new TestCase(
            "Basic: Find TODO Comments",
            "Search for TODO and FIXME comments in the codebase",
            "TODO|FIXME",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find all TODO and FIXME comments in the project",
            "Returns list of files with TODO/FIXME comments with line numbers"
        ));

        TEST_CASES.add(new TestCase(
            "Basic: Find Annotations",
            "Search for Java annotations like @Override, @Test",
            "@(Override|Test|Autowired|Component)",
            "*.java",
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find all specified annotations in Java files",
            "Returns list of Java files containing annotations with line numbers"
        ));

        TEST_CASES.add(new TestCase(
            "Basic: Email Pattern",
            "Search for email addresses using regex",
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find email addresses in any file",
            "Returns files containing email-like patterns"
        ));

        // File Finding Tests
        TEST_CASES.add(new TestCase(
            "Files: Find Java Files",
            "List all Java files in the project",
            "",
            "**/*.java",
            null,
            TestMode.FIND_FILES,
            0, 0, 0,
            "Should list all Java files recursively",
            "Returns list of all .java files with full paths"
        ));

        TEST_CASES.add(new TestCase(
            "Files: Find Test Files",
            "Find all test files (Java and Kotlin)",
            "",
            "*Test.java,*Tests.java,*Test.kt,*Tests.kt",
            null,
            TestMode.FIND_FILES,
            0, 0, 0,
            "Should find all files with 'Test' or 'Tests' suffix",
            "Returns list of test files in Java and Kotlin"
        ));

        TEST_CASES.add(new TestCase(
            "Files: Find Config Files",
            "Find configuration files (properties, yaml, xml)",
            "",
            "*.properties,*.yml,*.yaml,*.xml",
            null,
            TestMode.FIND_FILES,
            0, 0, 0,
            "Should find all configuration files",
            "Returns list of config files with various extensions"
        ));

        TEST_CASES.add(new TestCase(
            "Files: Multiple Patterns with Comma",
            "Find build files using comma separator (pom.xml, build.gradle, package.json)",
            "",
            "pom.xml,build.gradle,package.json",
            null,
            TestMode.FIND_FILES,
            0, 0, 0,
            "Should find all build configuration files using comma-separated patterns",
            "Returns pom.xml, build.gradle, and package.json files if they exist"
        ));

        // Search with Context Tests
        TEST_CASES.add(new TestCase(
            "Context: Method Definitions",
            "Find method definitions with 2 lines of context",
            "public\\s+(static\\s+)?\\w+\\s+\\w+\\s*\\(",
            "*.java",
            null,
            TestMode.SEARCH_WITH_CONTEXT,
            2, 0, 0,
            "Should find public method definitions with surrounding code",
            "Returns methods with 2 lines before and after for context"
        ));

        TEST_CASES.add(new TestCase(
            "Context: Import Statements",
            "Find import statements with context",
            "^import\\s+",
            "*.{java,kt}",
            null,
            TestMode.SEARCH_WITH_CONTEXT,
            1, 0, 0,
            "Should find import statements with 1 line of context",
            "Returns import statements showing package declarations nearby"
        ));

        // Before Context Tests
        TEST_CASES.add(new TestCase(
            "Before: Return Statements",
            "Find return statements with 3 lines before",
            "return\\s+",
            "*.java",
            null,
            TestMode.BEFORE_CONTEXT,
            0, 3, 0,
            "Should show what code leads to return statements",
            "Returns return statements with 3 preceding lines of logic"
        ));

        TEST_CASES.add(new TestCase(
            "Before: Exception Throws",
            "Find exception throws with preceding code",
            "throw\\s+new\\s+",
            "*.java",
            null,
            TestMode.BEFORE_CONTEXT,
            0, 5, 0,
            "Should show code leading to exception throws",
            "Returns throw statements with 5 lines of preceding context"
        ));

        // After Context Tests
        TEST_CASES.add(new TestCase(
            "After: If Conditions",
            "Find if statements with following code",
            "if\\s*\\(",
            "*.java",
            null,
            TestMode.AFTER_CONTEXT,
            0, 0, 3,
            "Should show what happens after if conditions",
            "Returns if statements with 3 lines of following code"
        ));

        TEST_CASES.add(new TestCase(
            "After: Method Calls",
            "Find specific method calls with following lines",
            "\\.getUserById\\(",
            "*.java",
            null,
            TestMode.AFTER_CONTEXT,
            0, 0, 2,
            "Should show what happens after getUserById calls",
            "Returns method calls with 2 lines of following code"
        ));

        // Exclusion Pattern Tests
        TEST_CASES.add(new TestCase(
            "Exclude: Non-Test Classes",
            "Find classes excluding test files (comma-separated excludes)",
            "public\\s+class\\s+",
            "*.java",
            "*Test*,**/test/**",
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find class definitions excluding test files using comma-separated exclude patterns",
            "Returns only production code classes, no test classes"
        ));

        TEST_CASES.add(new TestCase(
            "Exclude: Production Imports",
            "Find imports excluding generated and build files",
            "^import\\s+",
            "*.java",
            "**/build/**,**/generated/**",
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find imports only in source files",
            "Returns imports excluding build and generated directories"
        ));

        // Complex Regex Tests
        TEST_CASES.add(new TestCase(
            "Regex: Logger Declarations",
            "Find Logger declarations using complex regex",
            "private\\s+(static\\s+)?final\\s+Logger\\s+\\w+\\s*=",
            "*.java",
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find Logger field declarations",
            "Returns Logger declarations with various modifiers"
        ));

        TEST_CASES.add(new TestCase(
            "Regex: SQL Queries",
            "Find potential SQL query strings",
            "(SELECT|INSERT|UPDATE|DELETE)\\s+.*(FROM|INTO|SET)\\s+",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should find SQL-like query patterns in code",
            "Returns potential SQL query strings"
        ));

        // Performance Tests
        TEST_CASES.add(new TestCase(
            "Performance: Large Search",
            "Search common word across all files",
            "the",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should handle large result sets efficiently",
            "Returns results within reasonable time (<5 seconds)"
        ));

        TEST_CASES.add(new TestCase(
            "Performance: Complex Regex",
            "Search with complex nested regex pattern",
            "\\b(\\w+)\\s*\\(([^)]*)\\)\\s*\\{[^}]*\\}",
            "*.java",
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should handle complex regex patterns",
            "Returns matches for function-like patterns"
        ));

        // Edge Cases
        TEST_CASES.add(new TestCase(
            "Edge: Empty Query",
            "Test with empty search query",
            "",
            "*.java",
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should handle empty query gracefully",
            "Returns appropriate error message or empty result"
        ));

        TEST_CASES.add(new TestCase(
            "Edge: Invalid Regex",
            "Test with invalid regex pattern",
            "[[[",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should handle invalid regex gracefully",
            "Returns error message about invalid pattern"
        ));

        TEST_CASES.add(new TestCase(
            "Edge: Non-existent Pattern",
            "Search for pattern that doesn't exist",
            "XYZABC123DOESNOTEXIST",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should handle no results gracefully",
            "Returns 'No results found' message"
        ));

        TEST_CASES.add(new TestCase(
            "Edge: Special Characters",
            "Search for special regex characters",
            "\\$\\{.*\\}",
            null,
            null,
            TestMode.BASIC_SEARCH,
            0, 0, 0,
            "Should handle special characters in patterns",
            "Returns matches for template variable patterns"
        ));
    }

    /**
     * Get all predefined test cases
     */
    public static List<TestCase> getAllTestCases() {
        return new ArrayList<>(TEST_CASES);
    }

    /**
     * Get test cases by mode
     */
    public static List<TestCase> getTestCasesByMode(TestMode mode) {
        List<TestCase> filtered = new ArrayList<>();
        for (TestCase testCase : TEST_CASES) {
            if (testCase.mode == mode) {
                filtered.add(testCase);
            }
        }
        return filtered;
    }

    /**
     * Get test case by name
     */
    @Nullable
    public static TestCase getTestCaseByName(String name) {
        for (TestCase testCase : TEST_CASES) {
            if (testCase.name.equals(name)) {
                return testCase;
            }
        }
        return null;
    }
}