# Testing Refactor Tools

## Overview

Automated tests for MCP refactor tools using IntelliJ's test framework.

## Test Files

### Unit Tests: `RefactorToolsTest.kt`

Tests individual components:
- **RefactorabilityAnalyzer**
  - Static call detection
  - Complexity analysis
  - Team rules loading
  - Focus area filtering
  - Error handling

- **TestCoverageToolHandler**
  - Test class detection
  - Test framework detection (JUnit 4/5, TestNG)
  - Coverage data retrieval
  - Missing test suggestions

- **AskUserToolHandler**
  - Question type parsing
  - Data structure validation

### Integration Tests: `McpRefactorIntegrationTest.kt`

Tests full MCP workflow:
- End-to-end analysis flow
- Tool chaining (analyze → check tests → get coverage)
- Performance benchmarks
- Error handling
- Edge cases

## Running Tests

### From IntelliJ IDEA

1. **Run single test:**
   - Right-click on test method
   - Select "Run 'testMethodName()'"

2. **Run entire test class:**
   - Right-click on test class file
   - Select "Run 'RefactorToolsTest'"

3. **Run all refactor tests:**
   - Right-click on `/test/kotlin/com/zps/zest/mcp/refactor/` folder
   - Select "Run Tests in 'refactor'"

### From Command Line

Using Gradle:

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.zps.zest.mcp.refactor.RefactorToolsTest"

# Run specific test method
./gradlew test --tests "com.zps.zest.mcp.refactor.RefactorToolsTest.testAnalyzeRefactorability_detectsStaticCalls"

# Run all refactor tests
./gradlew test --tests "com.zps.zest.mcp.refactor.*"

# Run with output
./gradlew test --info
```

## Test Coverage

### What's Tested

✅ **RefactorabilityAnalyzer:**
- Static method call detection
- Complexity metrics calculation
- IntelliJ inspection integration
- Team rules loading from `.zest/rules.md`
- Focus area filtering (TESTABILITY, COMPLEXITY, CODE_SMELLS, ALL)
- Error handling for missing classes
- Performance with large classes

✅ **TestCoverageToolHandler:**
- Test class discovery (multiple naming conventions)
- Test framework detection
- Test method counting
- Coverage data retrieval (when available)
- Missing test suggestions
- Graceful handling of no coverage data

✅ **Integration:**
- Full MCP tool chain
- Multi-step workflows
- Error propagation
- Result JSON structure validation

### What's NOT Tested (Requires Manual Testing)

❌ **UI Interaction:**
- `AskUserToolHandler` dialog interaction
  - Requires user clicking buttons
  - Test manually with Claude Code `/refactor` command

❌ **Live Coverage Data:**
- Actual coverage from IntelliJ runner
  - Requires running tests with coverage first
  - Test manually: Run → Run with Coverage

❌ **MCP Server HTTP:**
- Full HTTP/SSE transport
  - Tested via external MCP clients (Claude Code, Continue.dev)

## Example Test Output

```
RefactorToolsTest > testAnalyzeRefactorability_detectsStaticCalls() PASSED
  Refactorability analysis result: {
    "className": "com.example.OrderService",
    "filePath": "/.../OrderService.java",
    "findings": [
      {
        "category": "TESTABILITY",
        "severity": "HIGH",
        "issue": "Static method call: PaymentGateway.charge()",
        "reason": "Cannot mock static methods with standard Mockito"
      }
    ],
    "metrics": {
      "cyclomaticComplexity": {"avg": 5, "max": 8},
      "staticCalls": 2
    }
  }

McpRefactorIntegrationTest > testMcpToolChain_analyzeAndCheckTests() PASSED
  === Tool Chain Results ===
  Analysis findings: 3
  Has test class: true
  Has coverage: false

McpRefactorIntegrationTest > testAnalysisPerformance_largeClass() PASSED
  Analysis of 20-method class took: 147ms
```

## Test Patterns

### Using IntelliJ Test Framework

```kotlin
class MyTest : LightJavaCodeInsightFixtureTestCase4() {
    @Test
    fun testSomething() {
        // Create test file
        fixture.configureByText("MyClass.java", """
            package com.example;
            class MyClass {}
        """.trimIndent())

        // Run on EDT
        val result = runInEdtAndGet {
            RefactorabilityAnalyzer.analyze(
                fixture.project,
                "com.example.MyClass",
                "ALL"
            )
        }

        // Assertions
        assertNotNull(result)
        assertTrue(result.has("findings"))
    }
}
```

### Testing JSON Responses

```kotlin
val json = JsonParser.parseString(result.toString()).asJsonObject

assertTrue("Should have field", json.has("fieldName"))
assertEquals("value", json.get("fieldName").asString)

val array = json.getAsJsonArray("items")
assertTrue(array.size() > 0)
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Test Refactor Tools

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run refactor tests
        run: ./gradlew test --tests "com.zps.zest.mcp.refactor.*"
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v2
        with:
          name: test-results
          path: build/test-results/
```

## Debugging Tests

### Enable Verbose Logging

```kotlin
@Test
fun testWithLogging() {
    val result = runInEdtAndGet {
        RefactorabilityAnalyzer.analyze(project, className, "ALL")
    }

    println("Result: ${result}") // Prints to test output
}
```

### Run Single Test in Debug Mode

1. Set breakpoint in test method
2. Right-click → "Debug 'testMethodName()'"
3. Step through code

### Check Test Logs

After running tests:
- IntelliJ: View → Tool Windows → Run
- Command line: `build/test-results/test/`

## Common Test Failures

### "Cannot find symbol" in test code
- **Cause**: Missing imports or wrong class name
- **Fix**: Check test code syntax, add imports

### "Project not found"
- **Cause**: Test fixture not initialized
- **Fix**: Ensure using `LightJavaCodeInsightFixtureTestCase4`

### "Must be called on EDT"
- **Cause**: PSI operations not on Event Dispatch Thread
- **Fix**: Wrap in `runInEdtAndGet { }`

### Tests pass locally but fail in CI
- **Cause**: Different IntelliJ version or JDK
- **Fix**: Match JDK versions, check CI logs

## Next Steps

After tests pass:

1. **Run full test suite**: `./gradlew test`
2. **Check coverage**: `./gradlew jacocoTestReport`
3. **Manual testing**: Test with Claude Code `/refactor`
4. **Integration testing**: Test with Continue.dev

## Metrics

Current test coverage:
- **RefactorabilityAnalyzer**: ~80% (core logic)
- **TestCoverageToolHandler**: ~70% (non-UI parts)
- **Integration flows**: ~90%

Areas needing more tests:
- Edge cases with malformed code
- IntelliJ inspection variations
- Different test frameworks (TestNG)
