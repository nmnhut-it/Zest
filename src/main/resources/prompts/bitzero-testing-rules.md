# BitZero Testing Rules

## MANDATORY: Extend IntegrationTestBase
```java
public class MyHandlerTest extends IntegrationTestBase {
}
```

## MANDATORY: NO MOCKITO
Use test doubles from `bitzero/test/` package:
- `TestSession` instead of `mock(ISession.class)`
- `TestUser` instead of `mock(User.class)`
- Override `send()` method to capture messages

## Test Double Pattern with CONFIGURABLE Results
```java
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
```

## Singleton Override Pattern (with cleanup!)
```java
TableServiceImpl original = TableServiceImpl.getInstance();
try {
    TableServiceImpl.setInstance(testService);
    // Test code here
} finally {
    TableServiceImpl.setInstance(original);  // ALWAYS cleanup!
}
```

## Test Naming Convention
```
methodName_HappyPath
methodName_ErrorPath
methodName_EdgeCase_Description
```

## Required Coverage for Handlers
For EACH command in switch statement:
- Happy path (success)
- Error path (failure return)
- Edge cases (null game, user not at table, etc.)

## Verify Behavior, Not Stub Config
```java
// BAD - tests nothing
assertTrue(stub.configuredValue);

// GOOD - tests behavior
assertTrue(table.tryStartGameCalled, "method should be called");
assertEquals(user, table.tryStartGameCalledWith, "should pass correct user");
```
