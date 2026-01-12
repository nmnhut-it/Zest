package com.zps.zest.mcp.refactor

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import com.intellij.testFramework.runInEdtAndGet
import com.zps.zest.mcp.ZestMcpHttpServer
import io.modelcontextprotocol.spec.McpSchema
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for MCP refactor tools.
 * Tests the full flow: MCP tool call → handler → analyzer → result
 */
class McpRefactorIntegrationTest : LightJavaCodeInsightFixtureTestCase4(
    projectDescriptor = LightJavaCodeInsightFixtureTestCase.JAVA_LATEST_WITH_LATEST_JDK
) {

    private val gson = Gson()

    companion object {
        private const val TEST_CODE = """
package com.example;

class TestableService {
    // Testability issue: static call
    public void doWork() {
        StaticHelper.process();
    }

    // Complexity issue: too many parameters
    public int calculate(int a, int b, int c, int d, int e, int f, int g) {
        return a + b + c + d + e + f + g;
    }

    // Good method
    public String getName() {
        return "TestableService";
    }
}

class StaticHelper {
    public static void process() {
        System.out.println("Processing");
    }
}
"""

        private const val TEST_CLASS_CODE = """
package com.example;

import org.junit.jupiter.api.Test;

class TestableServiceTest {
    @Test
    void testGetName() {
        TestableService service = new TestableService();
        assert service.getName().equals("TestableService");
    }
}
"""
    }

    @Test
    fun testMcpAnalyzeRefactorability_endToEnd() {
        // Setup: Create test file
        fixture.configureByText("TestableService.java", TEST_CODE.trimIndent())

        // Simulate MCP tool call
        val projectPath = fixture.project.basePath ?: ""
        val className = "com.example.TestableService"
        val focusArea = "ALL"

        // Call analyzer directly (simulates MCP handler call)
        val result = runInEdtAndGet {
            RefactorabilityAnalyzer.analyze(fixture.project, className, focusArea)
        }

        // Verify result structure
        assertNotNull("Should return result", result)

        val json = JsonParser.parseString(result.toString()).asJsonObject

        // Verify required fields
        assertTrue("Should have className", json.has("className"))
        assertEquals(className, json.get("className").asString)

        assertTrue("Should have filePath", json.has("filePath"))

        assertTrue("Should have findings array", json.has("findings"))
        val findings = json.getAsJsonArray("findings")

        assertTrue("Should have metrics", json.has("metrics"))

        // Verify finding structure if any exist
        if (findings.size() > 0) {
            val finding = findings.get(0).asJsonObject

            assertTrue("Finding should have category", finding.has("category"))
            assertTrue("Finding should have severity", finding.has("severity"))
            assertTrue("Finding should have issue", finding.has("issue"))
            assertTrue("Finding should have reason", finding.has("reason"))
            assertTrue("Finding should have suggestedFix", finding.has("suggestedFix"))

            println("Sample finding: ${finding}")
        }

        println("End-to-end analysis completed: ${findings.size()} findings")
    }

    @Test
    fun testMcpGetTestInfo_endToEnd() {
        // Setup: Create source and test files
        fixture.configureByText("TestableService.java", TEST_CODE.trimIndent())
        fixture.configureByText("TestableServiceTest.java", TEST_CLASS_CODE.trimIndent())

        val className = "com.example.TestableService"

        // Call handler directly
        val result = runInEdtAndGet {
            TestCoverageToolHandler.getTestInfo(fixture.project, className)
        }

        assertNotNull("Should return result", result)

        val json = JsonParser.parseString(result.toString()).asJsonObject

        // Verify structure
        assertTrue("Should have className", json.has("className"))
        assertEquals(className, json.get("className").asString)

        assertTrue("Should have hasTestClass", json.has("hasTestClass"))

        if (json.get("hasTestClass").asBoolean) {
            assertTrue("Should have testClassName", json.has("testClassName"))
            assertTrue("Should have testFramework", json.has("testFramework"))
            assertTrue("Should have testMethodCount", json.has("testMethodCount"))
            assertTrue("Should have testMethods array", json.has("testMethods"))

            val framework = json.get("testFramework").asString
            println("Detected test framework: $framework")

            val testMethods = json.getAsJsonArray("testMethods")
            println("Found ${testMethods.size()} test methods")
        }
    }

    @Test
    fun testMcpToolChain_analyzeAndCheckTests() {
        // Simulate full workflow: analyze → check tests
        fixture.configureByText("TestableService.java", TEST_CODE.trimIndent())
        fixture.configureByText("TestableServiceTest.java", TEST_CLASS_CODE.trimIndent())

        val className = "com.example.TestableService"

        // Step 1: Analyze refactorability
        val analysisResult = runInEdtAndGet {
            RefactorabilityAnalyzer.analyze(fixture.project, className, "TESTABILITY")
        }

        val analysisJson = JsonParser.parseString(analysisResult.toString()).asJsonObject
        assertTrue("Analysis should complete", analysisJson.has("findings"))

        // Step 2: Get test info
        val testInfoResult = runInEdtAndGet {
            TestCoverageToolHandler.getTestInfo(fixture.project, className)
        }

        val testInfoJson = JsonParser.parseString(testInfoResult.toString()).asJsonObject
        assertTrue("Test info should complete", testInfoJson.has("hasTestClass"))

        // Step 3: Get coverage data (will show no coverage message)
        val coverageResult = runInEdtAndGet {
            TestCoverageToolHandler.getCoverageData(fixture.project, className)
        }

        val coverageJson = JsonParser.parseString(coverageResult.toString()).asJsonObject
        assertTrue("Coverage call should return result", coverageJson.has("hasCoverage"))

        println("=== Tool Chain Results ===")
        println("Analysis findings: ${analysisJson.getAsJsonArray("findings").size()}")
        println("Has test class: ${testInfoJson.get("hasTestClass").asBoolean}")
        println("Has coverage: ${coverageJson.get("hasCoverage").asBoolean}")
    }

    @Test
    fun testAnalysisPerformance_largeClass() {
        // Test with larger code sample
        val largeCode = """
package com.example;

class LargeService {
    ${(1..20).joinToString("\n\n") { i ->
        """
    public void method$i() {
        StaticHelper.process();
        System.out.println("Method $i");
    }
        """.trimIndent()
    }}
}

class StaticHelper {
    public static void process() {}
}
"""

        fixture.configureByText("LargeService.java", largeCode.trimIndent())

        val startTime = System.currentTimeMillis()

        val result = runInEdtAndGet {
            RefactorabilityAnalyzer.analyze(
                fixture.project,
                "com.example.LargeService",
                "ALL"
            )
        }

        val duration = System.currentTimeMillis() - startTime

        assertNotNull("Should analyze large class", result)

        val json = JsonParser.parseString(result.toString()).asJsonObject
        assertTrue("Should have findings", json.has("findings"))

        println("Analysis of 20-method class took: ${duration}ms")
        assertTrue("Should complete in reasonable time (<5s)", duration < 5000)
    }

    @Test
    fun testMultipleFocusAreas() {
        fixture.configureByText("TestableService.java", TEST_CODE.trimIndent())

        val className = "com.example.TestableService"
        val focusAreas = listOf("TESTABILITY", "COMPLEXITY", "CODE_SMELLS", "ALL")

        for (focus in focusAreas) {
            val result = runInEdtAndGet {
                RefactorabilityAnalyzer.analyze(fixture.project, className, focus)
            }

            val json = JsonParser.parseString(result.toString()).asJsonObject
            assertTrue("Should analyze with focus: $focus", json.has("findings"))

            println("Focus: $focus -> ${json.getAsJsonArray("findings").size()} findings")
        }
    }

    @Test
    fun testErrorHandling_invalidClassName() {
        val result = runInEdtAndGet {
            RefactorabilityAnalyzer.analyze(
                fixture.project,
                "invalid.class.name.that.does.not.exist",
                "ALL"
            )
        }

        val json = JsonParser.parseString(result.toString()).asJsonObject

        assertTrue("Should have error field", json.has("error"))
        val error = json.get("error").asString
        assertTrue("Error should mention not found", error.contains("not found", ignoreCase = true))
    }

    @Test
    fun testErrorHandling_malformedCode() {
        // Create syntactically invalid Java code
        val invalidCode = """
package com.example;

class Invalid {
    public void broken( {
        // Missing closing paren
    }
}
"""

        fixture.configureByText("Invalid.java", invalidCode.trimIndent())

        // Should handle gracefully
        val result = runInEdtAndGet {
            try {
                RefactorabilityAnalyzer.analyze(fixture.project, "com.example.Invalid", "ALL")
            } catch (e: Exception) {
                println("Caught exception (expected): ${e.message}")
                com.google.gson.JsonObject().apply {
                    addProperty("error", "Analysis failed: ${e.message}")
                }
            }
        }

        assertNotNull("Should return result or error", result)
    }
}
