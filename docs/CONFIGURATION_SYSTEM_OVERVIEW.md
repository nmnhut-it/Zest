# Zest Plugin Configuration System Overview

## Architecture

The configuration system consists of several components working together:

```
┌─────────────────────────────────────────────────────────────┐
│                     User Interface Layer                      │
├─────────────────────────────────────────────────────────────┤
│  ZestSettingsConfigurable  │  EditCommitTemplateAction       │
│  (Main Settings UI)        │  (Quick Template Editor)        │
└────────────────┬───────────┴─────────────┬──────────────────┘
                 │                         │
                 ▼                         ▼
┌─────────────────────────────────────────────────────────────┐
│                  ConfigurationManager                         │
│  • Singleton per project                                      │
│  • Handles all settings load/save                            │
│  • Property file escaping/unescaping                         │
│  • Validation and defaults                                   │
└─────────────────────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│               zest-plugin.properties                          │
│  (Project-specific configuration file)                        │
└─────────────────────────────────────────────────────────────┘
```

## Components

### 1. **ConfigurationManager** (`com.zps.zest.ConfigurationManager`)
- Central configuration handler
- Singleton pattern (one instance per project)
- Handles property file I/O with proper escaping
- Provides getters/setters for all settings
- Validates settings on set
- Auto-migrates old configurations

### 2. **ZestSettingsConfigurable** (`com.zps.zest.settings.ZestSettingsConfigurable`)
- Main settings UI with 4 tabs
- Implements IntelliJ's `Configurable` interface
- Provides comprehensive settings management
- Includes validation, preview, and help features

### 3. **Property Storage** (`zest-plugin.properties`)
- Stored in project root directory
- Plain text properties format
- Multi-line values properly escaped
- Backward compatible

## Configuration Categories

### 1. **API & Models**
```properties
apiUrl=https://chat.zingplay.com/api/chat/completions
authToken=your-token-here
testModel=unit_test_generator
codeModel=code-expert
maxIterations=3
```

### 2. **Features**
```properties
ragEnabled=false
mcpEnabled=false
mcpServerUri=http://localhost:8080/mcp
```

### 3. **Context Settings**
```properties
contextInjectionEnabled=true
projectIndexEnabled=false
knowledgeId=
```

### 4. **System Prompts**
```properties
systemPrompt=You are an assistant...
codeSystemPrompt=You are an expert programming assistant...
commitPromptTemplate=Generate a well-structured git commit message...
```

## Key Features

### 1. **Multi-line Text Handling**
- Properties file escaping: `\n` → `\\n`, `\r` → `\\r`, etc.
- Automatic escaping/unescaping in ConfigurationManager
- Preserves formatting in prompts and templates

### 2. **Validation**
- Commit templates validated for required placeholders
- Settings validated before saving
- Invalid values fall back to defaults

### 3. **Migration Support**
- Old config files automatically upgraded
- Missing properties get default values
- Defaults saved back to file for consistency

### 4. **Mutual Exclusion**
- Context Injection and Project Index are mutually exclusive
- UI enforces this with radio buttons
- ConfigurationManager maintains consistency

### 5. **Template System**
- Configurable commit message templates
- Required placeholders: `{FILES_LIST}`, `{DIFFS}`
- Template examples and preview functionality
- Validation before use

## Usage Examples

### From Code
```java
// Get configuration
ConfigurationManager config = ConfigurationManager.getInstance(project);

// Read settings
String apiUrl = config.getApiUrl();
boolean ragEnabled = config.isRagEnabled();

// Update settings
config.setApiUrl("https://new-api.example.com");
config.setRagEnabled(true);

// Custom commit template
config.setCommitPromptTemplate("My custom template with {FILES_LIST} and {DIFFS}");

// Save changes
config.saveConfig();
```

### From UI
1. **Via IDE Settings**: File → Settings → Tools → Zest Plugin
2. **Via Actions**: 
   - VCS menu → Edit Commit Template
   - Keyboard: Ctrl+Shift+Z, S (settings) or T (template)
3. **Via Toolbar**: If configured in plugin.xml

## Security Considerations

1. **Auth Token**: Stored in plain text (consider encryption in future)
2. **File Permissions**: Properties file has same permissions as project
3. **No Sensitive Data**: Don't store passwords or secrets in templates

## Extension Points

### Adding New Settings
1. Add field to ConfigurationManager
2. Add getter/setter with validation
3. Update loadConfig() and saveConfig()
4. Add UI component to ZestSettingsConfigurable
5. Update isModified(), apply(), and reset()

### Adding New Placeholders
1. Define placeholder in template documentation
2. Implement replacement in commit generation code
3. Update help text and examples
4. Consider backward compatibility

## Best Practices

1. **Always validate** before setting configuration values
2. **Use defaults** for missing or invalid values
3. **Escape multi-line** text properly
4. **Save atomically** to prevent corruption
5. **Handle errors gracefully** with fallbacks
6. **Document changes** in templates and prompts

## Testing

1. **Unit Tests**: Test escaping/unescaping, validation
2. **Integration Tests**: Test file I/O, migration
3. **UI Tests**: Test settings dialog interaction
4. **Edge Cases**: Empty values, special characters, large texts

## Future Enhancements

1. **Encryption**: Encrypt sensitive values like auth tokens
2. **Import/Export**: Share configurations between projects
3. **Profiles**: Multiple configuration profiles
4. **Cloud Sync**: Sync settings across machines
5. **Team Settings**: Shared team configurations
6. **Version Control**: Track configuration changes
