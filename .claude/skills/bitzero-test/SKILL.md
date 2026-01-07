---
name: bitzero-test
description: Generate tests for BitZero server handlers, extensions, and controllers. Use when testing doLogin, handleClientRequest, ISession, or any BitZero game server code.
---

# BitZero Test Generation

Generate tests for BitZero game server code using built-in test doubles.

## MCP Tools (zest-intellij server)

Use these MCP tools - they work via IntelliJ's indexing and don't require JDK in terminal.

### Code Validation

| Tool | Purpose |
|------|---------|
| `validateCode(projectPath, code, className)` | Check if code compiles using IntelliJ |

**Always validate before saving:**
```
1. Generate test code
2. validateCode(projectPath, code, "MyClassTest")
3. Fix errors if any, re-validate
4. Save file
```

### Code Exploration (works with JARs)

| Tool | Purpose |
|------|---------|
| `lookupClass(className)` | Get class/method signatures |
| `getTypeHierarchy(className)` | See superclasses and interfaces |
| `findImplementations(className, methodName)` | Find interface implementations |
| `findUsages(className, memberName)` | See how methods are called |
| `getCallHierarchy(className, methodName)` | Trace callers/callees |
| `getMethodBody(className, methodName)` | Get method implementation |
| `getProjectDependencies(projectPath)` | See available test libraries |
| `getProjectJdk(projectPath)` | Get JAVA_HOME for shell commands |

### Example Workflow

```
1. getProjectDependencies(projectPath)   → Check JUnit, available libs
2. lookupClass("ISession")               → See interface methods
3. getTypeHierarchy("ISession")          → Find TestSession
4. [Write test code]
5. validateCode(projectPath, code, "MyTest")  → Verify it compiles
6. [Save file]
```

## BitZero is in a JAR

BitZero framework classes are in JARs. Standard grep/ripgrep won't find them.
Use the MCP tools above - they read JARs through IntelliJ's indexing.

## Test Type Selection

Choose the right test type based on code coupling:

| Coupling Level | Test Type | When to Use |
|---------------|-----------|-------------|
| Low | Unit test | Pure logic, injectable dependencies |
| Medium | Integration test | Uses `TestServer`, embedded server |
| High | E2E test | Real server, full flow |

**Don't force unit tests.** If code uses `BitZeroServer.getInstance()` or other singletons, use integration/e2e tests instead.

## Test Doubles (No Mockito)

| Instead of | Use |
|------------|-----|
| `mock(ISession.class)` | `new TestSession()` |
| `mock(User.class)` | `new TestUser("name")` |
| `ArgumentCaptor` | Override `send()` method |

Test doubles location: `bitzero/test/`

## Test Patterns by Code Type

### Handler with Injectable Dependencies → Unit Test

```java
public class MyHandlerTest {
    static class TestableHandler extends MyHandler {
        final List<BaseMsg> sent = new ArrayList<>();

        @Override
        protected void send(BaseMsg msg, User recipient) {
            sent.add(msg);
        }
    }

    @Test
    void handleRequest_ValidInput_SendsResponse() {
        TestableHandler handler = new TestableHandler();
        TestUser user = TestUser.createLoggedIn("player1");
        DataCmd cmd = createTestCmd();

        handler.handleClientRequest(user, cmd);

        assertEquals(1, handler.sent.size());
    }
}
```

### doLogin or Singleton Usage → Integration Test

```java
public class LoginExtensionTest extends IntegrationTestBase {

    @BeforeClass
    public static void setup() throws Exception {
        startServer();  // Embedded BitZeroServer
    }

    @Test
    void doLogin_ValidCredentials_CreatesUser() {
        TestLoginResult result = doLogin((short) 1, "user1", "pass123");

        result.assertSuccess()
              .assertSessionLoggedIn()
              .assertUserCreated();
    }
}
```

### Full Game Flow → E2E Test

```java
public class GameFlowE2ETest extends IntegrationTestBase {

    @Test
    void playerCanLoginAndJoinRoom() {
        // Login
        TestLoginResult login = doLogin((short) 1, "player1", "pass");
        login.assertSuccess();

        // Join room
        DataCmd joinCmd = createJoinRoomCmd("room1");
        TestSession session = login.getSession();

        // ... full flow testing
    }
}
```

## Workflow

### Step 1: Understand the Target

```
lookupClass(className)           → Get method signatures
getTypeHierarchy(className)      → See inheritance chain
findImplementations(interfaceName) → See concrete implementations
```

### Step 2: Analyze Dependencies

Ask: Does this code...
- Use `BitZeroServer.getInstance()`? → Integration test
- Have constructor injection? → Unit test possible
- Need real session manager? → Integration test
- Just transform data? → Unit test

### Step 3: Choose Test Approach

| Code Pattern | Best Approach |
|--------------|---------------|
| Pure business logic | Unit test |
| `extends BaseClientRequestHandler` | Unit test with overridden `send()` |
| `extends BZExtension` with `doLogin` | Integration test with `TestServer` |
| Uses singletons directly | Integration test |
| Complex game flows | E2E test |

### Step 4: Generate and Validate

```
validateCode(projectPath, code, className) → Check test compiles
```

## Test Scenarios to Cover

1. **Happy Path** - Valid input, expected response
2. **Auth State** - Logged in vs not logged in
3. **Invalid Input** - Null, empty, malformed DataCmd
4. **Error Paths** - BZException scenarios
5. **State Changes** - Session properties, user state

## Escoba-Server Examples

### Testing Pure Game Logic (BaseGame)

```java
public class CardValueTest {
    // Pure calculation - no dependencies
    @Test
    void getCardValue_CardId8_Returns3() {
        // Cards: 0-3=1, 4-7=2, 8-11=3...
        assertEquals(3, getCardValue(8));
    }

    @Test
    void checkBeginingEscoba_Sum15_Returns1() {
        // Test escoba detection logic
        List<Integer> cards = Arrays.asList(0, 4, 8, 12); // 1+2+3+4=10
        assertEquals(0, checkBeginingEscoba(cards));

        List<Integer> escoba = Arrays.asList(8, 12, 24); // 3+4+7=14... adjust
        // Test actual sum=15 case
    }

    private int getCardValue(int cardId) {
        return cardId / 4 + 1;
    }
}
```

### Testing with StubTable (Constructor Injection)

```java
public class BaseGameTest {
    // Stub provides controlled environment
    static class StubTable extends BaseTable {
        private List<BasePlayer> players = new ArrayList<>();

        public StubTable() {
            super(1, 1, TABLE_MODE.NORMAL, false, BetLevel.LEVEL_1, "test");
        }

        public void addPlayer(BasePlayer p) { players.add(p); }

        @Override
        public List<BasePlayer> getListPlayerPlaying() { return players; }

        @Override
        public void broadcast(TableMsgWrapper msg) { /* no-op */ }
    }

    static class StubPlayer extends BasePlayer {
        private List<Integer> cards = new ArrayList<>();

        public StubPlayer(int id, String name) {
            super(id, name, 0);
        }

        @Override
        public List<Integer> getListCardOnHand() { return cards; }

        public void setCards(List<Integer> c) { this.cards = c; }
    }

    @Test
    void game_DealCards_EachPlayerGetsThree() {
        StubTable table = new StubTable();
        table.addPlayer(new StubPlayer(1, "p1"));
        table.addPlayer(new StubPlayer(2, "p2"));

        BaseGame game = new BaseGame(1, table);
        game.start();

        // Verify card distribution
    }
}
```

### Testing Handlers (Override send() Seam)

```java
public class AccountRequestHandlerTest {
    // Override protected send() to capture messages
    static class TestableHandler extends AccountRequestHandler {
        public final List<BaseMsg> sent = new ArrayList<>();
        public final List<User> recipients = new ArrayList<>();

        @Override
        protected void send(BaseMsg msg, User recipient) {
            sent.add(msg);
            recipients.add(recipient);
        }
    }

    @Test
    void handleClientRequest_UnknownCmd_NoResponse() {
        TestableHandler handler = new TestableHandler();
        User user = createTestUser();
        DataCmd cmd = createCmd((short) 9999); // Unknown

        handler.handleClientRequest(user, cmd);

        assertTrue(handler.sent.isEmpty());
    }
}
```

### Integration Test (Singleton-Heavy Code)

When code uses `UserDAOImpl.getInstance()` directly:

```java
public class AccountIntegrationTest extends IntegrationTestBase {
    @Test
    void getUserData_ValidUser_ReturnsProfile() {
        // Start embedded server first
        TestLoginResult login = doLogin("user1", "pass123");
        login.assertSuccess();

        // Now UserDAOImpl singleton is initialized
        DataCmd getProfileCmd = createCmd(CMD.GET_USER_DATA);
        TestResponse response = sendRequest(login.getSession(), getProfileCmd);

        response.assertSuccess();
        // verify profile data
    }
}
```

## Seam Reference (Escoba-Server)

| Class | Seam | Test Strategy |
|-------|------|---------------|
| `BaseGame` | Constructor takes `BaseTable` | Inject StubTable |
| `BaseGame` | `getCardValue()` pure method | Direct unit test |
| `BaseClientRequestHandler` | `protected send()` | Override and capture |
| `GameRequestHandler` | `protected send()` | Override and capture |
| Handlers using `UserDAOImpl` | None (singleton) | Integration test |

## Output

Generate complete test class with:
- Package matching test source root
- All required imports (NO Mockito!)
- Test doubles as inner classes if needed
- Given-When-Then structure
- Descriptive method names: `methodName_scenario_expectedResult`
