# Configuration Migration Guide

## For Users Upgrading from Older Versions

### What's Changed

1. **Multi-line Text Support**: System prompts and commit templates now properly support multi-line text
2. **Validation**: Commit templates are now validated for required placeholders
3. **New Settings UI**: Comprehensive tabbed interface for all settings
4. **Template System**: Configurable commit message generation templates

### Automatic Migration

When you open a project with an older configuration:

1. **Missing Properties**: Any missing properties will be added with default values
2. **Invalid Templates**: Templates without required placeholders will be replaced with defaults
3. **Escaping**: Multi-line text will be automatically escaped properly
4. **Backward Compatibility**: Your existing settings will be preserved

### Manual Steps (If Needed)

1. **Check Your Settings**:
   ```
   File → Settings → Tools → Zest Plugin
   ```
   Review all tabs to ensure settings are correct.

2. **Auth Token**: If you had an auth token saved, verify it's still present in the API & Models tab.

3. **Custom Templates**: If you had customized the commit template via code, you'll need to update it through the UI.

### Configuration File Location

Your configuration is stored in:
```
<project-root>/zest-plugin.properties
```

### Backing Up Configuration

Before upgrading, you may want to backup your configuration:

```bash
# In your project directory
cp zest-plugin.properties zest-plugin.properties.backup
```

### Troubleshooting

#### Issue: Settings Not Loading
**Solution**: 
1. Check if `zest-plugin.properties` exists in project root
2. Ensure file permissions allow reading
3. Try resetting to defaults via Settings UI

#### Issue: Commit Template Invalid
**Solution**:
1. Open Settings → Commit Template tab
2. Click "Validate" to see what's wrong
3. Ensure template contains `{FILES_LIST}` and `{DIFFS}`
4. Click "Reset to Default" if needed

#### Issue: Multi-line Text Appears as Single Line
**Solution**:
1. This is normal in the properties file (escaped as `\n`)
2. The UI will display it properly with line breaks
3. No action needed

#### Issue: Auth Token Lost
**Solution**:
1. Re-enter in Settings → API & Models tab
2. Click Apply to save
3. Token will be stored in properties file

### New Features to Explore

1. **Template Examples**: Try different commit message styles from the dropdown
2. **Template Preview**: See how your template works with sample data
3. **Context Settings**: Choose between Context Injection and Project Index
4. **System Prompts**: Customize AI behavior for different contexts

### Rolling Back

If you need to rollback to an older version:

1. Close IntelliJ IDEA
2. Restore your backup: `cp zest-plugin.properties.backup zest-plugin.properties`
3. Downgrade the plugin
4. Restart IntelliJ IDEA

### Getting Help

- Check the template help button in Settings
- Review example templates for inspiration  
- Report issues through the plugin's issue tracker

## For Developers

### API Changes

1. **ConfigurationManager Constants Now Public**:
   ```java
   ConfigurationManager.DEFAULT_SYSTEM_PROMPT
   ConfigurationManager.DEFAULT_CODE_SYSTEM_PROMPT  
   ConfigurationManager.DEFAULT_COMMIT_PROMPT_TEMPLATE
   ```

2. **New Validation**:
   ```java
   // This now throws IllegalArgumentException if invalid
   config.setCommitPromptTemplate(template);
   ```

3. **Property Escaping**:
   - Multi-line strings are automatically escaped/unescaped
   - No need to handle this manually

### Testing Your Integration

```java
// Test configuration programmatically
ConfigurationManager config = ConfigurationManager.getInstance(project);

// Verify settings loaded correctly
assert config.getApiUrl() != null;
assert config.getCommitPromptTemplate().contains("{FILES_LIST}");
assert config.getCommitPromptTemplate().contains("{DIFFS}");

// Test setting updates
try {
    config.setCommitPromptTemplate("Invalid template");
    // Should throw exception
} catch (IllegalArgumentException e) {
    // Expected
}
```

### Extending Configuration

To add new configuration options:

1. Add field to ConfigurationManager
2. Add getter/setter with validation
3. Update load/save methods with escaping if needed
4. Add UI component to settings
5. Document in this guide

Remember to maintain backward compatibility!
