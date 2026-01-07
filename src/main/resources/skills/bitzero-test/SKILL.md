---
name: bitzero-test
description: Generate tests for BitZero server handlers, extensions, and controllers. Use when testing doLogin, handleClientRequest, ISession, or any BitZero game server code.
---

# BitZero Test Generation (V6)

Generate tests for BitZero game server code. V6 combines QC Mindset + Exploration Constraints for best results (46/50 in A/B testing).

## V6 Methodology: QC Mindset

You are a **Quality Control Engineer**. Your job is to **FIND BUGS**, not just make code compile.

### Phase Structure

| Phase | Tool Calls | Purpose |
|-------|------------|---------|
| 1 | 1x `getJavaCodeUnderTest` | Get comprehensive context |
| 2 | 0 | Build coverage matrix |
| 3 | 0 | Write test code |
| 4 | Max 3x `lookupClass` | Fix compilation errors |
| 5 | 1x `validateCode` | Verify before saving |

### Coverage Matrix (Build for EACH method)

| Method | Happy Path | Error Path | Edge Cases |
|--------|------------|------------|------------|
| methodName | Normal success | Failure condition | Boundary values |

## MCP Tools (zest-intellij server)

Use MCP tools - they work via IntelliJ's indexing and read JARs.

### Essential Tools

| Tool | Purpose |
|------|---------|
| `getJavaCodeUnderTest(className)` | Get class + context + examples |
| `validateCode(projectPath, code, className)` | Check if code compiles |
| `lookupClass(className)` | Get class/method signatures |
| `getTypeHierarchy(className)` | See superclasses and interfaces |

### V6 Workflow

1. getJavaCodeUnderTest(className) -> Get everything needed
2. Build coverage matrix -> Plan tests (NO tool calls)
3. Write test code -> NO Mockito! (NO tool calls)
4. validateCode() -> Check compilation
5. Fix errors with lookupClass() -> Max 3 calls
6. Save file

## MANDATORY Rules

### 1. Extend IntegrationTestBase

    public class MyHandlerTest extends IntegrationTestBase {
    }

### 2. NO MOCKITO - Use Test Doubles with Configurable Results

FORBIDDEN: @Mock, mock(), mockStatic(), when().thenReturn()

REQUIRED - Test doubles with configurable results:

    static class TestTable extends BaseTable {
        boolean tryStartGameResult = true;  // Configurable!
        boolean tryStartGameCalled = false;
        User tryStartGameCalledWith = null;

        @Override
        public boolean tryStartGame(User user) {
            tryStartGameCalled = true;
            tryStartGameCalledWith = user;
            return tryStartGameResult;
        }
    }

### 3. Singleton Override Pattern (with cleanup!)

    TableServiceImpl original = TableServiceImpl.getInstance();
    try {
        TableServiceImpl.setInstance(testService);
        // Test code here
    } finally {
        TableServiceImpl.setInstance(original);  // ALWAYS cleanup!
    }

### 4. Test BOTH Success AND Failure Paths

    @Test
    void tryStartGame_HappyPath() {
        testTable.tryStartGameResult = true;
        handler.handleClientRequest(user, cmd);
        assertTrue(testTable.tryStartGameCalled);
    }

    @Test
    void tryStartGame_ErrorPath() {
        testTable.tryStartGameResult = false;  // Configure failure!
        handler.handleClientRequest(user, cmd);
        assertTrue(testTable.tryStartGameCalled);
    }

### 5. Verify Behavior, Not Stub Config

BAD - tests nothing:
    assertTrue(stub.configuredValue);

GOOD - tests behavior:
    assertTrue(table.tryStartGameCalled, "method should be called");
    assertEquals(user, table.tryStartGameCalledWith, "should pass correct user");

## Test Patterns by Code Type

| Code Type | Pattern |
|-----------|---------|
| Handler | Test EACH switch case, happy + error + edge |
| Game Logic | Direct unit test, boundary conditions |
| Domain Entity | State verification, edge cases |
| Complex Model | Singleton override, configurable doubles |

## Test Scenarios to Cover

1. **Happy Path** - Valid input, expected response
2. **Error Path** - Failure conditions, error returns
3. **Edge Cases** - Null, empty, boundary values
4. **State Changes** - Session properties, user state

## Output Requirements

1. Complete JUnit 5 test class
2. Extends IntegrationTestBase
3. NO Mockito
4. Test doubles with CONFIGURABLE return values
5. All stubs track calls for verification
6. Minimum 8-10 tests for comprehensive coverage
7. Test naming: methodName_HappyPath, methodName_ErrorPath
