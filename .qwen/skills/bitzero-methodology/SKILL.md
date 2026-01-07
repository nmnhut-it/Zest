---
name: bitzero-methodology
description: Strategies for exploring and testing tightly-coupled BitZero code. Use when code seems untestable, has singleton dependencies, or you need to understand complex game logic before writing tests.
---

# BitZero Framework Guide

Comprehensive guide for understanding and testing BitZero game servers.

## Framework Architecture

### Package Structure

```
bitzero/
├── engine/                    # Low-level networking & threading
│   ├── core/                  # BitZeroEngine, readers, writers
│   ├── sessions/              # ISession, ISessionManager
│   ├── controllers/           # Request processing queues
│   ├── io/                    # IRequest, IResponse, protocols
│   └── events/                # Event system
│
├── server/                    # Game server layer
│   ├── BitZeroServer          # Main server singleton
│   ├── entities/              # User, Room, Zone
│   ├── extensions/            # BaseBZExtension, handlers
│   ├── core/                  # Events, managers
│   └── api/                   # Server APIs
│
└── framework/                 # Business utilities
    ├── dao/                   # Data access (UserDAOImpl)
    ├── common/                # Shared utilities
    └── authen/                # Authentication
```

### Key Classes

| Class | Purpose | Singleton? |
|-------|---------|------------|
| `BitZeroServer` | Main server, manages zones/users | Yes |
| `BitZeroEngine` | Low-level I/O | Yes |
| `ISession` | Client connection | No |
| `User` | Logged-in player | No |
| `BaseBZExtension` | Game logic container | Per-zone |
| `BaseClientRequestHandler` | Handles client commands | Per-extension |
| `UserDAOImpl` | Data access | Yes |

### Request Flow

```
Client Request
    ↓
BitZeroEngine (reads bytes)
    ↓
ISession (identifies client)
    ↓
Controller (queues request)
    ↓
Extension.handleClientRequest()
    ↓
Handler.handleClientRequest(User, DataCmd)
    ↓
Business Logic
    ↓
send(BaseMsg, User) → Response to client
```

### Extension & Handler Pattern

```java
// Extension: registers handlers for command IDs
public class GameExtension extends BZExtension {
    @Override
    public void init() {
        addRequestHandler(CmdDefine.LOGIN, LoginHandler.class);
        addRequestHandler(CmdDefine.GET_USER_DATA, AccountRequestHandler.class);
    }
}

// Handler: processes specific commands
public class AccountRequestHandler extends BaseClientRequestHandler {
    @Override
    public void handleClientRequest(User user, DataCmd cmd) {
        switch (cmd.getId()) {
            case CmdDefine.GET_USER_DATA:
                processGetUserData(user, cmd);
                break;
        }
    }

    private void processGetUserData(User user, DataCmd cmd) {
        UProfileModel profile = UserDAOImpl.getInstance().getUProfile(user.getId());
        ResponseUserData res = new ResponseUserData();
        res.coin = profile.getCoin();
        send(res, user);  // Uses parent extension's send()
    }
}
```

### Common Singletons

```java
// Server management
BitZeroServer.getInstance()
BitZeroServer.getInstance().getUserManager()
BitZeroServer.getInstance().getZoneManager()

// Data access
UserDAOImpl.getInstance()
UserDAOImpl.getInstance().getUProfile(userId)
UserDAOImpl.getInstance().getUserModel(userId, ModelClass.class)

// Messaging
MsgService.sendByUID(userId, msg)
MsgService.sendBySession(session, msg)

// Table/Game services
TableServiceImpl.getInstance()
```

---

## Testing Strategy

### The Reality

BitZero code has heavy coupling:
- 200+ `getInstance()` calls (singletons everywhere)
- `UserDAOImpl.getInstance()` in every handler
- `MsgService.sendByUID()` static calls
- `BitZeroServer.getInstance()` for user management

**Don't fight it. Use integration tests for singleton-heavy code.**

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

### Level 2: Handler Test (Override send())

For handlers, override `send()` to capture responses:

```java
class TestAccountHandler extends AccountRequestHandler {
    List<BaseMsg> captured = new ArrayList<>();

    @Override
    protected void send(BaseMsg msg, User user) {
        captured.add(msg);
    }
}

@Test
void testGetUserData() {
    TestAccountHandler handler = new TestAccountHandler();
    TestUser user = new TestUser("test");
    DataCmd cmd = new DataCmd(CmdDefine.GET_USER_DATA);

    handler.handleClientRequest(user, cmd);

    assertEquals(1, handler.captured.size());
    assertTrue(handler.captured.get(0) instanceof ResponseUserData);
}
```

### Level 3: Integration Test (Full Server)

For code that uses singletons - start the server:

```java
class TableIntegrationTest extends IntegrationTestBase {

    @Test
    void tableService_IsInitialized() {
        // Server started by @BeforeAll - singletons ready
        TableServiceImpl service = TableServiceImpl.getInstance();
        assertNotNull(service);
    }
}
```

---

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

---

## What's Testable Checklist

| Look For | How to Find | Test Type |
|----------|-------------|-----------|
| Clean constructor | `lookupClass` → no `getInstance()` in constructor | Unit test |
| Pure methods | No `getInstance()`, no field access to singletons | Unit test |
| Handler with send() | Extends `BaseClientRequestHandler` | Override send() |
| Singleton usage | `getInstance()` anywhere | Integration test |
| Static calls | `MsgService.sendByUID()` | Integration test |

---

## Escoba-Server Patterns

### Module Structure

```
modules/
├── account/
│   ├── setting/
│   │   ├── AccountRequestHandler.java  # Handles client requests
│   │   └── AccountEventHandler.java    # Handles server events
│   ├── model/
│   │   └── UProfileModel.java          # User profile data
│   └── payload/
│       ├── request/                    # Request DTOs
│       └── response/                   # Response DTOs
├── battle/
│   ├── game/                           # Game logic
│   └── table/                          # Table management
└── payment/
    └── setting/
        └── PaymentRequestHandler.java
```

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
Handlers (use UserDAOImpl.getInstance())
Services (use BitZeroServer.getInstance())
DAO operations (need database)
Table operations (need TableServiceImpl)
```

---

## Test Double Patterns

### TestSession (ISession stub)

```java
public class TestSession implements ISession {
    private int id;
    private boolean connected = true;
    private boolean loggedIn = false;
    private final List<String> calls = new ArrayList<>();

    public TestSession(int id) { this.id = id; }

    public TestSession loggedIn() {
        this.loggedIn = true;
        return this;
    }

    // Spy tracking
    @Override
    public void setLoggedIn(boolean f) {
        calls.add("setLoggedIn:" + f);
        loggedIn = f;
    }

    public boolean wasMethodCalled(String method) {
        return calls.stream().anyMatch(c -> c.startsWith(method));
    }

    // ... implement remaining ISession methods
}
```

### TestUser (User stub)

```java
public class TestUser extends User {
    public TestUser(String name) {
        super(name, new TestSession());
    }

    public static TestUser createLoggedIn(String name) {
        TestUser user = new TestUser(name);
        user.setConnected(true);
        user.getSession().setLoggedIn(true);
        return user;
    }
}
```

### SpyExtension (captures send calls)

```java
public class SpyExtension extends BaseBZExtension {
    private final List<BaseMsg> sentMessages = new ArrayList<>();

    @Override
    public void send(BaseMsg msg, User recipient) {
        sentMessages.add(msg);
        // Don't call super - no real sending
    }

    public List<BaseMsg> getSentMessages() {
        return new ArrayList<>(sentMessages);
    }
}
```

---

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

4. IDENTIFY seams
   - protected send() in handlers → override
   - Constructor parameters → inject test doubles
   - Data provider methods → stub

5. CHOOSE test type
   - Pure methods on clean class → Unit test
   - Handlers → Override send() test
   - Everything else → Integration test

6. WRITE tests
   - Unit: Create object, call method, assert
   - Handler: Subclass, override send(), verify captured
   - Integration: Extend IntegrationTestBase, use real services
```

---

## Key Principles

1. **Don't fight singletons** - Use integration tests
2. **Override send()** - The primary seam in handlers
3. **Test what's pure** - Clean constructors, pure methods
4. **Start server once** - Reuse across test class
5. **No mocking frameworks** - Real server, real behavior
6. **Test what matters** - Game logic, scoring, state transitions
