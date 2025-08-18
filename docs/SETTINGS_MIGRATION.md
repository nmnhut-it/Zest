# Zest Plugin Settings Migration Guide

## Overview

The Zest Plugin has been updated to use IntelliJ IDEA's built-in settings storage instead of project-specific properties files. This provides a better user experience and integrates seamlessly with the IDE's settings system.

## What Changed

### Before (Legacy)
- Settings stored in `zest-plugin.properties` or `ollama-plugin.properties` files in project root
- Manual file editing required for configuration changes
- Settings not integrated with IDE settings UI

### After (Current)
- Settings stored in IDE's built-in PropertiesComponent
- Settings accessible via File → Settings → Zest Plugin
- Automatic migration of existing settings
- No more properties files in project root

## Key Improvements

1. **Better UI**: Organized tabbed interface with proper form controls
2. **Validation**: Real-time validation of settings (e.g., commit templates)
3. **Password Field**: Auth token now uses a secure password field with show/hide toggle
4. **Compact Layout**: FormBuilder used for cleaner, more compact layouts
5. **Contextual Help**: Inline descriptions and help text for settings

## Migration Process

When you open a project with legacy properties files:

1. Settings are automatically migrated to the IDE storage
2. A notification appears offering to delete the old properties files
3. Access settings via File → Settings → Zest Plugin

## Settings Organization

The settings are now organized into tabs:

- **General**: API configuration, context mode, documentation search
- **Models**: Model names and iteration settings
- **Features**: Inline completion, RAG, MCP toggles
- **Prompts**: System prompts with separate tabs for each type

## Technical Details

### Storage Location
Settings are stored using `PropertiesComponent` with the prefix `zest.plugin.`

### Code Changes
- `ConfigurationManager` now reads/writes from PropertiesComponent
- `ZestSettingsConfigurable` provides the UI
- Legacy file migration handled automatically

### API Compatibility
All existing APIs in `ConfigurationManager` remain unchanged, ensuring backward compatibility.

## For Developers

### Accessing Settings
```java
ConfigurationManager config = ConfigurationManager.getInstance(project);
String apiUrl = config.getApiUrl(); // Works as before
```

### Adding New Settings
1. Add field to `ConfigurationManager`
2. Add UI component to `ZestSettingsConfigurable`
3. Update `isModified()`, `apply()`, and `reset()` methods
4. Settings automatically persist via PropertiesComponent

### Testing
The migration is automatic and transparent. Test by:
1. Creating a project with old properties files
2. Opening it with the updated plugin
3. Verifying settings are migrated correctly
4. Checking that settings UI works properly
