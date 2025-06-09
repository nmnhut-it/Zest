# Plugin.xml Additions for Commit Template Feature

Add these entries to your `plugin.xml` file:

## 1. Settings Configurable
```xml
<!-- Project-level settings for Zest plugin -->
<projectConfigurable 
    instance="com.zps.zest.settings.ZestSettingsConfigurableProvider"
    displayName="Zest Plugin"
    id="zest.settings"
    parentId="tools"
    nonDefaultProject="true"/>
```

## 2. Actions
```xml
<!-- Edit Commit Template Action -->
<action id="Zest.EditCommitTemplate"
        class="com.zps.zest.actions.EditCommitTemplateAction"
        text="Edit Commit Message Template..."
        description="Edit the template used for generating commit messages">
    <add-to-group group-id="VcsGroups" anchor="last"/>
    <add-to-group group-id="Zest.Menu" anchor="after" relative-to-action="Zest.QuickCommit"/>
</action>
```

## 3. If you want to add it to the toolbar
```xml
<group id="Zest.GitToolbar">
    <action id="Zest.EditCommitTemplateToolbar"
            class="com.zps.zest.actions.EditCommitTemplateAction"
            text="Edit Commit Template"
            description="Edit the template used for generating commit messages"
            icon="AllIcons.Actions.Edit"/>
</group>

<!-- Add to main toolbar -->
<add-to-group group-id="MainToolBar" anchor="last">
    <reference ref="Zest.GitToolbar"/>
</add-to-group>
```

## 4. Keyboard Shortcut (Optional)
```xml
<action id="Zest.EditCommitTemplateShortcut"
        class="com.zps.zest.actions.EditCommitTemplateAction"
        text="Edit Commit Template">
    <keyboard-shortcut first-keystroke="ctrl shift Z" second-keystroke="T" keymap="$default"/>
</action>
```

## Complete Example Section:
```xml
<idea-plugin>
    <!-- ... other configurations ... -->
    
    <extensions defaultExtensionNs="com.intellij">
        <!-- Settings -->
        <projectConfigurable 
            instance="com.zps.zest.settings.ZestSettingsConfigurableProvider"
            displayName="Zest Plugin"
            id="zest.settings"
            parentId="tools"
            nonDefaultProject="true"/>
    </extensions>
    
    <actions>
        <!-- Existing actions ... -->
        
        <!-- Commit Template Actions -->
        <action id="Zest.EditCommitTemplate"
                class="com.zps.zest.actions.EditCommitTemplateAction"
                text="Edit Commit Message Template..."
                description="Edit the template used for generating commit messages">
            <add-to-group group-id="VcsGroups" anchor="last"/>
            <keyboard-shortcut first-keystroke="ctrl shift Z" second-keystroke="T" keymap="$default"/>
        </action>
    </actions>
</idea-plugin>
```

## Notes:
1. The settings will appear under File → Settings → Tools → Zest Plugin
2. The action will appear in VCS menus
3. Users can access it via Ctrl+Shift+Z, T shortcut
4. Make sure all referenced classes exist before adding to plugin.xml
