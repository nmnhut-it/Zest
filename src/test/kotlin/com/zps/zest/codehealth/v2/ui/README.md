# Code Health V2 UI Tests

UI tests for the Code Health feature using JetBrains RemoteRobot.

## Prerequisites

1. **Java 17+** - Required for running the IDE
2. **Gradle** - For building and running tests

## Running UI Tests

### Step 1: Start the IDE with RemoteRobot

```bash
# Start IntelliJ with RemoteRobot server enabled
./gradlew runIdeForUiTests
```

Wait for the IDE to fully load (may take 1-2 minutes).

### Step 2: Open a Project

In the test IDE:
1. Open a project with Java/Kotlin files
2. Wait for indexing to complete

### Step 3: Run the UI Tests

In a **separate terminal**:

```bash
# Run all UI tests
./gradlew testUI

# Run specific test class
./gradlew testUI --tests "com.zps.zest.codehealth.v2.ui.CodeHealthNotificationUITest"

# Run specific test method
./gradlew testUI --tests "*.testOpenCodeHealthOverviewViaStatusBar"
```

## Test Structure

```
src/test/kotlin/com/zps/zest/codehealth/v2/ui/
├── BaseUITest.kt                    # Base class with common utilities
├── CodeHealthNotificationUITest.kt  # Main UI tests
├── pages/
│   └── CodeHealthPages.kt           # Page objects for UI elements
└── README.md                        # This file
```

## Page Objects

### NotificationPage
Interactions with Code Health notification balloons:
- `isCodeHealthNotificationVisible()`
- `clickFixNow()`
- `clickViewDetails()`

### CodeHealthOverviewPage
Interactions with the Code Health Overview editor:
- `isOpen()`
- `getOverallHealthScore()`
- `clickCriticalIssuesTab()`

### StatusBarWidgetPage
Interactions with the Guardian status bar widget:
- `isVisible()`
- `click()`

## Test Categories

### Always Runnable
These tests don't require specific IDE state:
- `testOpenCodeHealthOverviewViaStatusBar`
- `testOverviewRefreshShowsData`
- `testOverviewTabNavigation`
- `testBugFix_FreshDataDisplayedAfterNotificationClick`

### Requires Notification (@Ignore by default)
These tests need a notification to be present:
- `testClickViewDetailsOpensOverview`
- `testClickFixNowOpensOverview`
- `testNotificationStructure`

To run these:
1. Trigger a Code Health check manually
2. Wait for notification to appear
3. Remove `@Ignore` annotation or run specifically

## Debugging

### View Test Reports
```bash
# After running tests, reports are at:
build/reports/tests/testUI/index.html
```

### Enable Video Recording
Tests include video recording support (optional):
```kotlin
@Video
@Test
fun testSomething() {
    // Video saved to build/videos/
}
```

### Connect to IDE Manually
You can explore the UI hierarchy:
```bash
# Open in browser while IDE is running
http://localhost:8082
```

## Troubleshooting

### "Connection refused" Error
- Make sure IDE is running with `./gradlew runIdeForUiTests`
- Check port 8082 is not blocked

### "Element not found" Error
- IDE may still be loading - wait longer
- Element XPath may have changed - inspect UI hierarchy
- Run tests with `showStandardStreams = true` for debugging

### Tests Hang
- IDE may have modal dialog open - close it
- Indexing may be in progress - wait for completion

## Writing New Tests

```kotlin
class MyNewUITest : BaseUITest() {

    @Test
    fun testSomething() = step("Test description") {
        // Use page objects
        val notificationPage = NotificationPage(robot)

        // Or find elements directly
        val button = robot.find<JButtonFixture>(byXpath("//div[@text='Click Me']"))
        button.click()

        // Assert
        assertTrue(somethingIsTrue)
    }
}
```

## CI/CD Integration

For headless CI environments:
```yaml
# GitHub Actions example
- name: Run UI Tests
  run: |
    Xvfb :99 &
    export DISPLAY=:99
    ./gradlew runIdeForUiTests &
    sleep 120  # Wait for IDE
    ./gradlew testUI
```
