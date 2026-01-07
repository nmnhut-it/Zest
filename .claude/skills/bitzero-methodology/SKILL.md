---
name: bitzero-methodology
description: Strategies for exploring and testing tightly-coupled BitZero code. Use when code seems untestable, has singleton dependencies, or you need to understand complex game logic before writing tests.
---

# BitZero Testing Methodology

Practical strategies for testing real BitZero game servers like escoba-server.

## The Reality

BitZero code has heavy coupling:
- 200+ `getInstance()` calls (singletons everywhere)
- `UserDAOImpl.getInstance()` in every handler
- `MsgService.sendByUID()` static calls
- `BitZeroServer.getInstance()` for user management

**Don't fight it. Use integration tests.**

## Testing Strategy

### Level 1: Unit Test (Pure Methods)

For code with NO singleton calls in constructor:

```java
// BasePlayer constructor is clean - unit testable
@Test
void basePlayer_ScoreCalculation() {
    BasePlayer player = new BasePlayer(1, false, "Test", 1, "", 1, 0, false, false, 1000L);

    player.getScore().put(SCORE_ENUM.ESCOBA, 2);
    player.getScore().put(SCORE_ENUM.MOST_CARD, 1);

    assertEquals(3, player.getTotalScore());
}
```

### Level 2: Integration Test (Everything Else)

For code that uses singletons - just start the server:

```java
class TableIntegrationTest extends IntegrationTestBase {

    @Test
    void tableService_IsInitialized() {
        // Server started by @BeforeAll - singletons ready
        TableServiceImpl service = TableServiceImpl.getInstance();
        assertNotNull(service);
    }

    @Test
    void tableConfig_IsLoaded() {
        assertNotNull(TableConst.TABLE_CONFIG);
        assertEquals(4, TableConst.TABLE_CONFIG.maxPlayerInTable);
    }
}
```

## Integration Test Infrastructure

### TestServer

```java
public class TestServer {
    public void start() throws Exception {
        BitZeroServer server = BitZeroServer.getInstance();
        server.setClustered(false);
        server.start();
        waitForServerReady();
    }
}
```

### IntegrationTestBase

```java
public abstract class IntegrationTestBase {
    protected static TestServer testServer;

    @BeforeAll
    static void startTestServer() throws Exception {
        testServer = new TestServer();
        testServer.start();  // All singletons initialized!
    }

    protected TableServiceImpl getTableService() {
        return TableServiceImpl.getInstance();
    }
}
```

## Codebase Exploration Strategy

Use MCP tools to understand dependencies before testing:

```
1. lookupClass("MyHandler")
   → See what it extends, what methods it has

2. getCallHierarchy("MyHandler", "handleClientRequest", callers=false)
   → See what singletons it calls

3. getTypeHierarchy("BaseClientRequestHandler")
   → Find the protected send() method (seam!)

4. lookupClass("BaseGame")
   → See constructor params (injection points)
```

## What's Testable in BitZero

### Pure Methods (Unit Test)

Look for methods with no external dependencies:

```java
// In BasePlayer - pure calculation, easy to test
public int getTotalScore() {
    return this.score.entrySet().stream()
        .filter(e -> e.getKey() != SCORE_ENUM.TOTAL_SCORE)
        .map(Map.Entry::getValue)
        .reduce(0, Integer::sum);
}

public int countNumber7Cards() {
    int count = 0;
    for (int card7Id : GameConst.CARD_7_IDS) {
        if (this.listCardResult.contains(card7Id)) count++;
    }
    return count;
}
```

### Singleton-Heavy Code (Integration Test)

When code uses `UserDAOImpl.getInstance()` or similar:

```java
public class AccountIntegrationTest extends IntegrationTestBase {

    @Test
    void getUserData_ValidUser_ReturnsProfile() {
        // Server running, singletons initialized
        var service = TableServiceImpl.getInstance();
        var tables = service.getAvailableTables(1, 10);
        assertNotNull(tables);
    }
}
```

## Finding What's Testable: Checklist

| Look For | How to Find | Test Type |
|----------|-------------|-----------|
| Clean constructor | `lookupClass` → no `getInstance()` in constructor | Unit test |
| Pure methods | No `getInstance()`, no field access to singletons | Unit test |
| Singleton usage | `getInstance()` anywhere | Integration test |
| Static calls | `MsgService.sendByUID()` | Integration test |

## Escoba-Server Specific Patterns

### What CAN Be Unit Tested

```
BasePlayer
├── Constructor: No singletons - CLEAN
├── Pure methods:
│   ├── getTotalScore() → unit test
│   ├── countNumber7Cards() → unit test
│   ├── check7Vang() → unit test
│   └── isStillOnline() → unit test
└── Singleton methods:
    ├── pack() → uses UserDAOImpl - integration test
    └── addGold() → uses ItemHandler - integration test
```

### What NEEDS Integration Test

```
BaseTable
├── Constructor: Calls BitZeroServer.getInstance() - BLOCKED
└── All methods need integration test

TableServiceImpl
├── Singleton itself
└── All methods need integration test

Handlers
├── Use TableServiceImpl.getInstance()
├── Use UserDAOImpl.getInstance()
└── All need integration test
```

## Test Double Patterns

### StubPlayer (for unit tests)

```java
// BasePlayer is already testable - just create normally
BasePlayer player = new BasePlayer(
    1, false, "TestPlayer", 1, "",
    1, 0, false, false, 1000L
);
player.setPlayerStatus(PLAYER_STATUS.PLAYING);
player.setListCardResult(List.of(24, 25, 26)); // three 7s
```

## Practical Workflow

```
1. READ the code
   lookupClass, getMethodBody

2. CHECK the constructor
   - Has getInstance()? → Integration test
   - Clean? → Unit test possible

3. IDENTIFY pure methods
   - No getInstance() in method body
   - No static calls to singletons
   - Just works on instance fields

4. CHOOSE test type
   - Pure methods on clean class → Unit test
   - Everything else → Integration test

5. WRITE tests
   - Unit: Create object, call method, assert
   - Integration: Extend IntegrationTestBase, use real services
```

## Key Principles

1. **Don't fight singletons** - Use integration tests
2. **Unit test what's pure** - Clean constructors, pure methods
3. **Start server once** - Reuse across test class
4. **No mocking frameworks** - Real server, real behavior
5. **Test what matters** - Game logic, scoring, state transitions
