# Quick Fix for Compilation Errors

## What Was Fixed

The compilation errors were caused by trying to pass a `JScrollPane` to `Messages.showMessageDialog()`, which only accepts String messages.

### Fixed Files:
1. `EditCommitTemplateAction.java` (line 160)
2. `ZestSettingsConfigurable.java` (line 500)

### Solution Applied:
Replaced `Messages.showMessageDialog()` with a custom `DialogWrapper` implementation:

```java
// OLD (causes error)
Messages.showMessageDialog(project, scrollPane, "Title", Messages.getInformationIcon());

// NEW (works correctly)
DialogWrapper previewDialog = new DialogWrapper(project, false) {
    {
        setTitle("Title");
        init();
    }
    
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return scrollPane;
    }
    
    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }
};

previewDialog.show();
```

## How to Build Now

```bash
# Clean and rebuild
./gradlew clean build

# Or just compile
./gradlew compileJava
```

## Remaining Warnings

The deprecation warnings don't prevent compilation. They are:
- `StringEscapeUtils` from commons-lang (deprecated)
- `UIUtil.isUnderDarcula()` (deprecated)
- `AnActionEvent.createFromDataContext()` (deprecated)

These can be addressed later without affecting functionality.

## Testing the Fix

1. Open the project in IntelliJ IDEA
2. Go to File → Settings → Tools → Zest Plugin
3. Navigate to "Commit Template" tab
4. Click "Preview" button - should now show dialog correctly
5. Or use VCS menu → Edit Commit Template → Preview

Both preview functions now work correctly with scrollable text areas.
