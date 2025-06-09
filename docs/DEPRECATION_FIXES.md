# Fixing Deprecation Warnings in Zest Plugin

## Summary of Issues Fixed

### 1. **Compilation Errors (Fixed)**
- `Messages.showMessageDialog` doesn't accept `JScrollPane` - replaced with custom `DialogWrapper`

### 2. **Deprecation Warnings (To Address)**

#### StringEscapeUtils Deprecation
The `org.apache.commons.lang.StringEscapeUtils` is deprecated. Replace with:
- `org.apache.commons.text.StringEscapeUtils` (Apache Commons Text)
- Or use custom escaping methods

**Files affected:**
- `GenerateCodeCommentsAction.java`
- `WebBrowserPanel.java`

**Fix:**
```java
// Replace
import org.apache.commons.lang.StringEscapeUtils;

// With
import org.apache.commons.text.StringEscapeUtils;

// Or use IntelliJ's built-in
import com.intellij.openapi.util.text.StringUtil;
// StringUtil.escapeStringCharacters() for Java escaping
```

#### UIUtil.isUnderDarcula() Deprecation
Replace with new theme detection:

**Files affected:**
- `SimpleGitDiffViewer.java`

**Fix:**
```java
// Replace
boolean isDarkTheme = UIUtil.isUnderDarcula();

// With
import com.intellij.util.ui.StartupUiUtil;
boolean isDarkTheme = StartupUiUtil.isUnderDarcula();

// Or better yet
import com.intellij.ide.ui.UITheme;
boolean isDarkTheme = UITheme.isDark();

// Or use JBColor which automatically handles theme switching
import com.intellij.ui.JBColor;
Color bgColor = JBColor.namedColor("Panel.background", 
    new JBColor(Color.WHITE, new Color(0x3c3f41)));
```

#### AnActionEvent.createFromDataContext Deprecation
**File affected:**
- `WebBrowserPanel.java`

**Fix:**
```java
// Replace
AnActionEvent event = AnActionEvent.createFromDataContext(
    ActionPlaces.UNKNOWN,
    new Presentation(),
    dataContext
);

// With
AnActionEvent event = AnActionEvent.createFromAnAction(
    action,
    null,
    ActionPlaces.UNKNOWN,
    dataContext
);

// Or use the constructor directly
AnActionEvent event = new AnActionEvent(
    null,
    dataContext,
    ActionPlaces.UNKNOWN,
    new Presentation(),
    ActionManager.getInstance(),
    0
);
```

## Gradle Dependencies Update

If using Apache Commons Text instead of Lang:
```gradle
dependencies {
    // Remove or comment out
    // implementation 'commons-lang:commons-lang:2.6'
    
    // Add
    implementation 'org.apache.commons:commons-text:1.10.0'
}
```

## JavaScript Escaping Alternative

Since you already created `JavaScriptUtils`, use that instead:
```java
// Use your custom utility
import com.zps.zest.utils.JavaScriptUtils;
String escaped = JavaScriptUtils.escapeForJavaScript(text);
```

## Theme-Aware Colors

Create a utility for theme-aware colors:
```java
public class ThemeUtils {
    public static boolean isDarkTheme() {
        return UIManager.getLookAndFeel().getName().contains("Darcula") ||
               EditorColorsManager.getInstance().isDarkEditor();
    }
    
    public static Color getBackgroundColor() {
        return JBColor.namedColor("Panel.background", 
            new JBColor(Color.WHITE, new Color(0x3c3f41)));
    }
    
    public static Color getTextColor() {
        return JBColor.namedColor("Label.foreground",
            new JBColor(Color.BLACK, Color.WHITE));
    }
}
```

## Action Items

1. **High Priority**: Fix compilation errors ✅ (Done)
2. **Medium Priority**: Replace deprecated StringEscapeUtils
3. **Low Priority**: Update theme detection and AnActionEvent creation
4. **Optional**: Create utility classes for common operations

The compilation errors have been fixed. The deprecation warnings won't prevent the plugin from working but should be addressed in future updates for compatibility with newer IntelliJ versions.
