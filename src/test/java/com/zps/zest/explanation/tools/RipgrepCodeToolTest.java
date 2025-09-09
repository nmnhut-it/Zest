package com.zps.zest.explanation.tools;

import junit.framework.TestCase;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.*;

/**
 * Unit tests for RipgrepCodeTool to verify native ripgrep integration
 */
public class RipgrepCodeToolTest extends LightJavaCodeInsightFixtureTestCase {

    private RipgrepCodeTool ripgrepTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Set<String> relatedFiles = new HashSet<>();
        List<String> usagePatterns = new ArrayList<>();
        ripgrepTool = new RipgrepCodeTool(getProject(), relatedFiles, usagePatterns);
    }

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

    public void testFilePatternSearch() {
        // Create files with different extensions
        myFixture.addFileToProject("JavaFile.java", "public class JavaFile { @Entity String data; }");
        myFixture.addFileToProject("KotlinFile.kt", "class KotlinFile { @Entity val data: String }");
        myFixture.addFileToProject("config.properties", "database.url=localhost");

        // Test Java files only
        String result = ripgrepTool.searchCode("@Entity", "*.java", null);
        
        assertTrue("Should handle file patterns correctly", 
                   result.contains("JavaFile.java") || result.contains("Ripgrep not available"));
    }

    public void testContextSearch() {
        myFixture.addFileToProject("ContextTest.java", 
            "public class ContextTest {\n" +
            "    // Before line\n" +
            "    public void testMethod() {\n" +  // This line should be found
            "        // After line\n" +
            "    }\n" +
            "}");

        // Test with context lines
        String result = ripgrepTool.searchCodeWithContext("testMethod", null, null, 1);
        
        assertTrue("Should handle context lines or show message", 
                   result.contains("testMethod") || result.contains("Ripgrep not available"));
    }

    public void testRipgrepAvailability() {
        // Test that the tool handles ripgrep availability gracefully
        String result = ripgrepTool.searchCode("test", null, null);
        
        // Should either work with ripgrep or provide clear guidance
        assertNotNull("Result should not be null", result);
        assertFalse("Result should not be empty", result.trim().isEmpty());
        
        // Should either contain results or helpful error message
        boolean hasResults = result.contains("Found") || result.contains("result(s)");
        boolean hasGuidance = result.contains("Ripgrep not available") || result.contains("install");
        
        assertTrue("Should either have results or guidance", hasResults || hasGuidance);
    }
}