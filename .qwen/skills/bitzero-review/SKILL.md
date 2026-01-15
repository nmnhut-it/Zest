---
name: bitzero-review
description: Review BitZero game server code for bugs, security issues, and testability. Use when reviewing handlers, extensions, sessions, or game logic.
---

# BitZero Code Review

Review BitZero game server code with focus on multiplayer game patterns.

## Important: BitZero is in a JAR

BitZero framework classes are in JAR files. Standard grep/ripgrep won't work.
Use **PSI-based MCP tools** which read JARs through IntelliJ's indexing.

## MCP Tools for JAR Access

```
mcp_zest_lookupClass        → Get class/method signatures from JARs
mcp_zest_getTypeHierarchy   → See parent classes, interfaces
mcp_zest_findImplementations → Find interface implementations
mcp_zest_findUsages         → See how methods are called
mcp_zest_getCallHierarchy   → Trace request flow (callers/callees)
mcp_zest_getMethodBody      → Get method implementation
mcp_zest_findDeadCode       → Find unused code (project only)
```

Example: Reviewing a handler
```
1. lookupClass("MyHandler")           → See method signatures
2. getTypeHierarchy("MyHandler")      → Check parent (BaseClientRequestHandler?)
3. lookupClass("BaseClientRequestHandler") → Understand contract from JAR
4. findUsages("MyHandler", "handleClientRequest") → See call patterns
```

## Review Checklist

### 1. Session & User Safety

| Issue | What to Check |
|-------|---------------|
| **Null session** | Is `request.getSender()` checked? |
| **Not logged in** | Is `session.isLoggedIn()` verified? |
| **User mismatch** | User from session same as expected? |

```java
// Bad - no null check
User user = (User) request.getSender().getProperty("user");

// Good - defensive
ISession session = request.getSender();
if (session == null || !session.isLoggedIn()) {
    return;
}
User user = (User) session.getProperty("user");
if (user == null) {
    return;
}
```

### 2. Input Validation (DataCmd)

| Issue | What to Check |
|-------|---------------|
| **Missing validation** | Is DataCmd content validated? |
| **Buffer overread** | Does readString/readInt have bounds? |
| **Type confusion** | Is expected data type verified? |

```java
// Bad
String itemId = cmd.readString();
int quantity = cmd.readInt();
buyItem(user, itemId, quantity);

// Good
String itemId = cmd.readString();
if (itemId == null || itemId.length() > 50) {
    sendError(user, "Invalid item");
    return;
}
int quantity = cmd.readInt();
if (quantity <= 0 || quantity > 999) {
    sendError(user, "Invalid quantity");
    return;
}
```

### 3. Game Exploit Prevention

| Exploit Type | What to Check |
|--------------|---------------|
| **Speed hack** | Action rates limited? |
| **Item dupe** | Transaction atomic? |
| **Resource exploit** | Bounds checked server-side? |
| **Race condition** | State properly locked? |

```java
// Bad - client-trusted
int damage = cmd.readInt();
enemy.takeDamage(damage);

// Good - server-calculated
int damage = calculateDamage(user.getWeapon(), enemy.getDefense());
enemy.takeDamage(damage);
```

### 4. Response Handling

| Issue | What to Check |
|-------|---------------|
| **Info leak** | Error exposes internals? |
| **Missing response** | Client always notified? |
| **Wrong recipient** | Response to right user? |

```java
// Bad - leaks exception
catch (Exception e) {
    send(new ErrorMsg(e.toString()), user);
}

// Good
catch (Exception e) {
    LOG.error("Error in handler", e);
    send(new ErrorMsg("Operation failed"), user);
}
```

### 5. Testability Assessment

| Coupling | Testability | Approach |
|----------|-------------|----------|
| Constructor injection | Good | Unit test |
| Uses singletons | Fair | Integration test |
| Static method calls | Poor | E2E test or refactor |

```java
// Hard to test - direct singleton
public void handleClientRequest(User user, DataCmd cmd) {
    DatabaseService db = BitZeroServer.getInstance().getDatabase();
    db.save(user);
}

// Easier - but still needs integration test if singleton is deep
private final DatabaseService db;
public MyHandler(DatabaseService db) {
    this.db = db;
}
```

**Don't force unit tests.** Recommend integration/e2e tests when code is tightly coupled.

### 6. Thread Safety

| Issue | What to Check |
|-------|---------------|
| **Shared mutable state** | Is it synchronized? |
| **User state changes** | Updates atomic? |
| **Collection modification** | ConcurrentModificationException? |

```java
// Bad - TOCTOU race
if (user.getGold() >= price) {
    user.setGold(user.getGold() - price);
}

// Good
synchronized (user) {
    if (user.getGold() >= price) {
        user.setGold(user.getGold() - price);
    }
}
```

## Output Format

```markdown
# Review: ClassName

## Summary
- **Risk Level**: High/Medium/Low
- **Testability**: Good/Fair/Poor (with recommended test type)
- **Issues Found**: N

## Critical Issues

### 1. [Issue Title]
**Location**: `method()` line N
**Problem**: Description
**Fix**:
```java
// suggested code
```

## Medium Issues
...

## Testability Assessment

| Aspect | Status |
|--------|--------|
| Dependencies | Injectable / Singleton / Static |
| Recommended Test | Unit / Integration / E2E |
| Can use TestSession | Yes / No |
| Can use TestServer | Yes / No |

**Recommendation**: [Specific test approach]
```

## Patterns to Flag

1. **Missing `@Override`** on handler methods
2. **Casting without instanceof** check
3. **Not closing resources** in finally/try-with-resources
4. **Hardcoded command IDs** instead of constants
5. **Direct `BitZeroServer.getInstance()`** in business logic
6. **Mutable static fields** in handlers
7. **Client-trusted values** for damage, gold, items

## Escoba-Server Specific Patterns

### Handler Switch Pattern

Escoba uses switch-case in handlers. Watch for:

```java
// Escoba pattern - AccountRequestHandler
@Override
public void handleClientRequest(User user, DataCmd dataCmd) {
    switch (dataCmd.getId()) {
        case CMD.USER_INFO:
            processUserInfo(user, dataCmd);
            break;
        case CMD.MONEY_IN_GAME:
            processMoneyInGame(user, dataCmd);
            break;
        // Missing default case?
    }
}
```

**Review points:**
- Does it have a `default` case?
- Are all CMD constants handled?
- Is `user` null-checked before use?

### Singleton Usage Analysis

Escoba has heavy singleton usage (200+ `getInstance()` calls):

```
UserDAOImpl.getInstance()      → Database access
BitZeroServer.getInstance()    → Server services
MsgService.sendByUID()         → Static messaging
```

**Testability impact:**
- Code with `UserDAOImpl.getInstance()` → Integration test
- Code with `MsgService.sendByUID()` → Integration test
- Pure calculation methods → Unit test

### Game Logic Patterns (BaseGame)

```java
// Pure methods - testable
private int getCardValue(int cardId) { return cardId/4+1; }
private int checkBeginingEscoba() { /* sum check */ }
private int isBetterThan(List<Integer> l1, List<Integer> l2) { }

// Coupled methods - need integration test
private void notifyPlayCard() { /* uses MsgService */ }
private void processResult() { /* uses table.broadcast() */ }
```

### Testability Quick Reference

| Escoba Class | Seams Found | Recommended Test |
|--------------|-------------|------------------|
| `BaseGame` | Constructor (BaseTable), pure methods | Unit + Integration |
| `AccountRequestHandler` | `protected send()` | Unit with override |
| `GameRequestHandler` | `protected send()` | Unit with override |
| `Extension` (doLogin) | None - singleton heavy | Integration |
| `UserDAOImpl` | Singleton | Mock DB or real |

### Common Escoba Issues

1. **Unchecked card operations**
   ```java
   // Bad - no bounds check
   int card = listCardOpen.remove(index);

   // Good
   if (index >= 0 && index < listCardOpen.size()) {
       int card = listCardOpen.remove(index);
   }
   ```

2. **Gold manipulation without locks**
   ```java
   // Potential race in concurrent play
   player.setGold(player.getGold() + winnings);
   ```

3. **Missing player validation**
   ```java
   // Bad - trusts client
   int playerId = cmd.readInt();
   BasePlayer player = table.getPlayer(playerId);

   // Good - verify sender is the player
   BasePlayer player = table.getPlayerByUserId(user.getId());
   ```
