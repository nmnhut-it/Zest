# Complete Plugin.xml Configuration for Zest Settings

Add these entries to your `plugin.xml` file to enable the comprehensive settings UI:

```xml
<idea-plugin>
    <!-- ... existing configuration ... -->
    
    <extensions defaultExtensionNs="com.intellij">
        <!-- ... existing extensions ... -->
        
        <!-- Project Settings Configurable -->
        <projectConfigurable 
            instance="com.zps.zest.settings.ZestSettingsConfigurableProvider"
            displayName="Zest Plugin"
            id="zest.settings"
            parentId="tools"
            nonDefaultProject="true"/>
    </extensions>
    
    <actions>
        <!-- ... existing actions ... -->
        
        <!-- Settings Actions -->
        <group id="Zest.SettingsGroup" text="Settings" popup="true">
            <add-to-group group-id="Zest.Menu" anchor="last"/>
            
            <action id="Zest.OpenSettings"
                    class="com.zps.zest.actions.OpenZestSettingsAction"
                    text="Open Zest Settings..."
                    description="Open Zest Plugin configuration settings">
                <keyboard-shortcut first-keystroke="ctrl shift Z" second-keystroke="S" keymap="$default"/>
            </action>
            
            <action id="Zest.EditCommitTemplate"
                    class="com.zps.zest.actions.EditCommitTemplateAction"
                    text="Edit Commit Template..."
                    description="Edit the template used for generating commit messages">
                <keyboard-shortcut first-keystroke="ctrl shift Z" second-keystroke="T" keymap="$default"/>
            </action>
        </group>
        
        <!-- Add to VCS menu -->
        <group id="Zest.VcsGroup">
            <separator/>
            <reference ref="Zest.EditCommitTemplate"/>
            <add-to-group group-id="VcsGroups" anchor="last"/>
        </group>
        
        <!-- Add to main menu if you have a Zest menu -->
        <group id="Zest.Menu" text="Zest" description="Zest plugin actions">
            <add-to-group group-id="MainMenu" anchor="before" relative-to-action="HelpMenu"/>
            
            <!-- Quick Commit is probably already here -->
            <reference ref="Zest.QuickCommit"/>
            <separator/>
            
            <!-- Settings group will be added here by the group definition above -->
        </group>
    </actions>
</idea-plugin>
```

## Settings Structure

The settings will appear in the IDE settings dialog under:
```
File → Settings → Tools → Zest Plugin
```

The settings are organized into 4 tabs:

### 1. **API & Models Tab**
- API URL
- Auth Token
- Test Model
- Code Model  
- Max Iterations

### 2. **Features Tab**
- RAG Enable/Disable
- MCP Enable/Disable with Server URI
- Context Settings (Context Injection vs Project Index)
- Knowledge ID
- Index Project button

### 3. **System Prompts Tab**
- System Prompt (general assistant)
- Code System Prompt (programming assistant)
- Reset buttons for each

### 4. **Commit Template Tab**
- Template editor with syntax highlighting
- Template examples dropdown
- Validate button
- Reset to default button
- Preview button
- Help button

## Keyboard Shortcuts

- **Ctrl+Shift+Z, S** - Open Zest Settings
- **Ctrl+Shift+Z, T** - Edit Commit Template
- **Ctrl+Shift+Z, C** - Quick Commit (existing)

## Features

1. **Comprehensive Configuration**: All settings from ConfigurationManager in one place
2. **Tabbed Interface**: Organized settings by category
3. **Validation**: Real-time validation for templates and settings
4. **Template Examples**: Pre-built templates for different commit styles
5. **Preview**: See how templates work with sample data
6. **Context-Aware**: Enables/disables fields based on feature selections
7. **Mutual Exclusion**: Handles Context Injection vs Project Index radio buttons
8. **Property Escaping**: Properly handles multi-line text in properties file

## Usage

1. Users can access settings via:
   - File → Settings → Tools → Zest Plugin
   - VCS menu → Edit Commit Template
   - Zest menu → Settings → Open Zest Settings
   - Keyboard shortcuts

2. All changes are saved to `zest-plugin.properties` in the project root

3. Invalid settings show error messages and prevent saving

4. Templates are validated for required placeholders

## Implementation Notes

- The `ZestSettingsConfigurableProvider` creates the configurable instance
- Settings are project-specific (stored per project)
- Multi-line text fields (prompts, templates) are properly escaped/unescaped
- The UI follows IntelliJ's design guidelines with proper spacing and layout
