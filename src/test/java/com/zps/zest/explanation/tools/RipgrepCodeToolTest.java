package com.zps.zest.explanation.tools;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Comprehensive unit tests for RipgrepCodeTool to verify native ripgrep integration
 */
public class RipgrepCodeToolTest extends LightJavaCodeInsightFixtureTestCase {

    private RipgrepCodeTool ripgrepTool;
    private Set<String> relatedFiles;
    private List<String> usagePatterns;

    @Override
    protected String getTestDataPath() {
        // Return a dummy path or actual test data path if you have one
        return "src/test/testData";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        relatedFiles = new HashSet<>();
        usagePatterns = new ArrayList<>();
        ripgrepTool = new RipgrepCodeTool(myFixture.getProject(), relatedFiles, usagePatterns);
    }

    @Override
    protected void tearDown() throws Exception {
        relatedFiles.clear();
        usagePatterns.clear();
        super.tearDown();
    }

    // Basic Search Tests

    public void testBasicSearch() {
        // Create test file with Java code
        myFixture.addFileToProject("TestClass.java",
            "public class TestClass {\n" +
            "    @Test\n" +
            "    public void testMethod() {\n" +
            "        UserService service = new UserService();\n" +
            "        service.getUserById(123);\n" +
            "    }\n" +
            "}");

        // Test basic annotation search
        String result = ripgrepTool.searchCode("@Test", null, null);

        // Should either find results with ripgrep or show installation message
        assertTrue("Should contain @Test results or installation message",
                   result.contains("@Test") || result.contains("Ripgrep not available"));
    }

    public void testRegexSearch() {
        // Create test files with various patterns
        myFixture.addFileToProject("RegexTest.java",
            "public class RegexTest {\n" +
            "    private String userEmail = \"test@example.com\";\n" +
            "    private String adminEmail = \"admin@site.org\";\n" +
            "    public void sendEmail() {}\n" +
            "    public void receiveEmail() {}\n" +
            "}");

        // Test regex pattern for email-like strings
        String result = ripgrepTool.searchCode("[a-zA-Z]+@[a-zA-Z]+\\.[a-zA-Z]+", null, null);

        assertTrue("Should find email patterns or show availability message",
                   (result.contains("@example.com") || result.contains("@site.org"))
                   || result.contains("Ripgrep not available"));
    }

    public void testCaseSensitiveSearch() {
        myFixture.addFileToProject("CaseTest.java",
            "public class CaseTest {\n" +
            "    private String TEST = \"uppercase\";\n" +
            "    private String test = \"lowercase\";\n" +
            "    private String Test = \"mixedcase\";\n" +
            "}");

        // Case-insensitive search should find all variations
        String result = ripgrepTool.searchCode("test", null, null);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Case-insensitive search should find multiple matches",
                       result.contains("TEST") || result.contains("test") || result.contains("Test"));
        }
    }

    // File Pattern Tests

    public void testFilePatternSearch() {
        // Create files with different extensions
        myFixture.addFileToProject("JavaFile.java", "public class JavaFile { @Entity String data; }");
        myFixture.addFileToProject("KotlinFile.kt", "class KotlinFile { @Entity val data: String }");
        myFixture.addFileToProject("config.properties", "database.url=localhost");

        // Test Java files only
        String result = ripgrepTool.searchCode("@Entity", "*.java", null);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find pattern in Java files only",
                       result.contains("JavaFile.java"));
            assertFalse("Should not find pattern in Kotlin files",
                        result.contains("KotlinFile.kt"));
        }
    }

    public void testMultipleFilePatterns() {
        myFixture.addFileToProject("Main.java", "public class Main { void process() {} }");
        myFixture.addFileToProject("Helper.kt", "class Helper { fun process() {} }");
        myFixture.addFileToProject("script.py", "def process(): pass");
        myFixture.addFileToProject("style.css", ".process { color: red; }");

        // Test multiple extension patterns
        String result = ripgrepTool.searchCode("process", "*.{java,kt}", null);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find in Java files", result.contains("Main.java"));
            assertTrue("Should find in Kotlin files", result.contains("Helper.kt"));
            assertFalse("Should not find in Python files", result.contains("script.py"));
            assertFalse("Should not find in CSS files", result.contains("style.css"));
        }
    }

    public void testRecursiveGlobPattern() {
        myFixture.addFileToProject("src/main/java/App.java", "public class App { void start() {} }");
        myFixture.addFileToProject("src/test/java/AppTest.java", "public class AppTest { void testStart() {} }");
        myFixture.addFileToProject("docs/App.java", "public class App { void document() {} }");

        // Test recursive glob pattern
        String result = ripgrepTool.searchCode("start", "src/**/*.java", null);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find in src directories",
                       result.contains("src/main/java/App.java") || result.contains("src/test/java/AppTest.java"));
            assertFalse("Should not find outside src", result.contains("docs/App.java"));
        }
    }

    // Exclusion Pattern Tests

    public void testExcludePattern() {
        myFixture.addFileToProject("Main.java", "public class Main { @Test void test() {} }");
        myFixture.addFileToProject("MainTest.java", "public class MainTest { @Test void test() {} }");
        myFixture.addFileToProject("TestHelper.java", "public class TestHelper { @Test void help() {} }");

        // Exclude test files
        String result = ripgrepTool.searchCode("@Test", null, "*Test*");

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find in non-test files", result.contains("Main.java"));
            assertFalse("Should exclude test files", result.contains("MainTest.java"));
            assertFalse("Should exclude helper test files", result.contains("TestHelper.java"));
        }
    }

    public void testMultipleExcludePatterns() {
        myFixture.addFileToProject("src/Main.java", "public class Main { void run() {} }");
        myFixture.addFileToProject("test/Test.java", "public class Test { void run() {} }");
        myFixture.addFileToProject("build/Generated.java", "public class Generated { void run() {} }");
        myFixture.addFileToProject("docs/Doc.java", "public class Doc { void run() {} }");

        // Exclude multiple patterns
        String result = ripgrepTool.searchCode("run", null, "test,build");

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find in src files", result.contains("src/Main.java"));
            assertTrue("Should find in docs files", result.contains("docs/Doc.java"));
            assertFalse("Should exclude test files", result.contains("test/Test.java"));
            assertFalse("Should exclude build files", result.contains("build/Generated.java"));
        }
    }

    // Context Line Tests

    public void testContextSearch() {
        myFixture.addFileToProject("ContextTest.java",
            "public class ContextTest {\n" +
            "    // Before line\n" +
            "    public void testMethod() {\n" +
            "        // After line\n" +
            "    }\n" +
            "}");

        // Test with context lines
        String result = ripgrepTool.searchCodeWithContext("testMethod", null, null, 1);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should contain the match", result.contains("testMethod"));
        }
    }

    public void testBeforeContextSearch() {
        myFixture.addFileToProject("BeforeTest.java",
            "public class BeforeTest {\n" +
            "    private String setup = \"initialized\";\n" +
            "    // Comment before method\n" +
            "    public void targetMethod() {\n" +
            "        System.out.println(\"Target\");\n" +
            "    }\n" +
            "}");

        String result = ripgrepTool.searchWithBeforeContext("targetMethod", null, null, 2);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find target method", result.contains("targetMethod"));
        }
    }

    public void testAfterContextSearch() {
        myFixture.addFileToProject("AfterTest.java",
            "public class AfterTest {\n" +
            "    public void setupMethod() {\n" +
            "        String config = \"value\";\n" +
            "        processConfig(config);\n" +
            "        finalizeSetup();\n" +
            "    }\n" +
            "}");

        String result = ripgrepTool.searchWithAfterContext("setupMethod", null, null, 3);

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find setup method", result.contains("setupMethod"));
        }
    }

    // File Finding Tests

    public void testFindFiles() {
        myFixture.addFileToProject("Main.java", "public class Main {}");
        myFixture.addFileToProject("Helper.java", "public class Helper {}");
        myFixture.addFileToProject("Test.kt", "class Test");
        myFixture.addFileToProject("config.xml", "<config/>");

        String result = ripgrepTool.findFiles("*.java");

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find Java files", result.contains("Main.java"));
            assertTrue("Should find Helper.java", result.contains("Helper.java"));
            assertFalse("Should not find Kotlin files", result.contains("Test.kt"));
            assertFalse("Should not find XML files", result.contains("config.xml"));
        }
    }

    public void testFindFilesRecursive() {
        myFixture.addFileToProject("src/main/java/App.java", "public class App {}");
        myFixture.addFileToProject("src/test/java/AppTest.java", "public class AppTest {}");
        myFixture.addFileToProject("lib/External.java", "public class External {}");

        String result = ripgrepTool.findFiles("src/**/*.java");

        if (!result.contains("Ripgrep not available")) {
            assertTrue("Should find files in src", result.contains("src/main/java/App.java"));
            assertTrue("Should find test files in src", result.contains("src/test/java/AppTest.java"));
            assertFalse("Should not find files outside src", result.contains("lib/External.java"));
        }
    }

    // Edge Cases and Error Handling

    public void testEmptyQuery() {
        myFixture.addFileToProject("Test.java", "public class Test { }");

        String result = ripgrepTool.searchCode("", null, null);
        assertNotNull("Should handle empty query", result);
    }

    public void testNullQuery() {
        myFixture.addFileToProject("Test.java", "public class Test { }");

        try {
            String result = ripgrepTool.searchCode(null, null, null);
            assertNotNull("Should handle null query", result);
        } catch (NullPointerException e) {
            fail("Should not throw NPE for null query");
        }
    }

    public void testInvalidRegexPattern() {
        myFixture.addFileToProject("Test.java", "public class Test { }");

        // Invalid regex pattern
        String result = ripgrepTool.searchCode("[[[", null, null);
        assertNotNull("Should handle invalid regex", result);
        assertTrue("Should return error or no results",
                   result.contains("Error") || result.contains("No results") || result.contains("Ripgrep not available"));
    }

    public void testLargeResultSet() {
        // Create many files that would match
        for (int i = 0; i < 30; i++) {
            myFixture.addFileToProject("Test" + i + ".java",
                "public class Test" + i + " { void commonMethod() {} }");
        }

        String result = ripgrepTool.searchCode("commonMethod", null, null);

        if (!result.contains("Ripgrep not available")) {
            // Should limit results (MAX_RESULTS is 20)
            int count = result.split("Test\\d+\\.java").length - 1;
            assertTrue("Should limit results to MAX_RESULTS", count <= 20);
        }
    }

    // Usage Pattern Recording Tests

    public void testUsagePatternRecording() {
        myFixture.addFileToProject("ImportTest.java",
            "import java.util.List;\n" +
            "import com.example.Service;\n" +
            "public class ImportTest {\n" +
            "    @Autowired\n" +
            "    private Service service;\n" +
            "    \n" +
            "    public void test() {\n" +
            "        service.process();\n" +
            "    }\n" +
            "}");

        // Clear patterns first
        usagePatterns.clear();

        // Search for imports
        ripgrepTool.searchCode("Service", null, null);

        if (!ripgrepTool.searchCode("Service", null, null).contains("Ripgrep not available")) {
            // Check if usage patterns were recorded
            boolean hasImportPattern = usagePatterns.stream()
                .anyMatch(p -> p.contains("Import") && p.contains("Service"));
            boolean hasMethodPattern = usagePatterns.stream()
                .anyMatch(p -> p.contains("Method call") || p.contains("service"));

            assertTrue("Should record some usage patterns",
                       hasImportPattern || hasMethodPattern || !usagePatterns.isEmpty());
        }
    }

    public void testRelatedFilesTracking() {
        myFixture.addFileToProject("FileA.java", "public class FileA { void test() {} }");
        myFixture.addFileToProject("FileB.java", "public class FileB { void test() {} }");

        // Clear related files first
        relatedFiles.clear();

        // Search that should find both files
        ripgrepTool.searchCode("test", null, null);

        if (!ripgrepTool.searchCode("test", null, null).contains("Ripgrep not available")) {
            // Check if files were added to relatedFiles
            assertFalse("Should track related files", relatedFiles.isEmpty());
        }
    }

    // Platform-specific Tests

    public void testPlatformDetection() {
        // This test verifies the tool works on the current platform
        String result = ripgrepTool.searchCode("test", null, null);

        assertNotNull("Should work on current platform", result);
        assertFalse("Should not return null or empty", result.trim().isEmpty());
    }

    public void testRipgrepAvailability() {
        // Test that the tool handles ripgrep availability gracefully
        String result = ripgrepTool.searchCode("test", null, null);

        assertNotNull("Result should not be null", result);
        assertFalse("Result should not be empty", result.trim().isEmpty());

        // Should either contain results or helpful error message
        boolean hasResults = result.contains("Found") || result.contains("result(s)");
        boolean hasGuidance = result.contains("Ripgrep not available") || result.contains("install");

        assertTrue("Should either have results or guidance", hasResults || hasGuidance);
    }

    // Performance Tests

    public void testSearchTimeout() {
        // Create a complex search that might take time
        myFixture.addFileToProject("Complex.java",
            String.join("\n", Collections.nCopies(1000, "public void method() { /* complex */ }")));

        long startTime = System.currentTimeMillis();
        String result = ripgrepTool.searchCode("complex", null, null);
        long endTime = System.currentTimeMillis();

        assertNotNull("Should complete within timeout", result);

        // Verify it doesn't hang (30 second timeout in implementation)
        assertTrue("Should complete within 35 seconds", (endTime - startTime) < 35000);
    }
}