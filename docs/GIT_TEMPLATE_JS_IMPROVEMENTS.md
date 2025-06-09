# JavaScript Improvements for Git Template Handling

## Add to git.js in the GitModal object:

```javascript
// Add validation function
validateTemplate: function(template) {
    if (!template || typeof template !== 'string') {
        return { isValid: false, error: 'Template must be a non-empty string' };
    }
    
    if (!template.includes('{FILES_LIST}')) {
        return { isValid: false, error: 'Template must contain {FILES_LIST} placeholder' };
    }
    
    if (!template.includes('{DIFFS}')) {
        return { isValid: false, error: 'Template must contain {DIFFS} placeholder' };
    }
    
    return { isValid: true };
},

// Update getCommitPromptTemplate with better error handling
getCommitPromptTemplate: async function() {
    try {
        const response = await window.intellijBridge.callIDE('getCommitPromptTemplate');
        if (response && response.success && response.template) {
            // Validate on client side too
            const validation = this.validateTemplate(response.template);
            if (!validation.isValid) {
                console.warn('Invalid template from server:', validation.error);
                return this.getDefaultTemplate();
            }
            return response.template;
        }
    } catch (error) {
        console.error('Error getting commit prompt template:', error);
    }
    
    // Return default template if fetch fails
    return this.getDefaultTemplate();
},

// Default template fallback
getDefaultTemplate: function() {
    return `Generate a well-structured git commit message based on the changes below.

## Changed files:
{FILES_LIST}

## File changes:
{DIFFS}

## Instructions:
Please follow this structure for the commit message:

1. First line: Short summary (50-72 chars) following conventional commit format
   - format: <type>(<scope>): <subject>
   - example: feat(auth): implement OAuth2 login

2. Body: Detailed explanation of what changed and why
   - Separated from summary by a blank line
   - Explain what and why, not how
   - Wrap at 72 characters

3. Footer (optional):
   - Breaking changes (BREAKING CHANGE: description)

Please provide ONLY the commit message, no additional explanation, no markdown formatting, no code blocks.`;
},

// Add template caching to reduce server calls
_templateCache: null,
_templateCacheTime: 0,
TEMPLATE_CACHE_DURATION: 5 * 60 * 1000, // 5 minutes

getCachedTemplate: async function() {
    const now = Date.now();
    if (this._templateCache && (now - this._templateCacheTime) < this.TEMPLATE_CACHE_DURATION) {
        return this._templateCache;
    }
    
    const template = await this.getCommitPromptTemplate();
    this._templateCache = template;
    this._templateCacheTime = now;
    return template;
},

// Update buildCommitPromptWithTemplate to use cached version
buildCommitPromptWithTemplate: async function(selectedFiles, diffs) {
    // Get the template (uses cache)
    const template = await this.getCachedTemplate();
    
    // ... rest of the existing implementation
}
```

## Add error recovery to QuickCommitPipeline:

```javascript
// In QuickCommitPipeline.generateMessage()
async generateMessage() {
    this.updateStatus('✨', 'Generating commit message...');
    
    try {
        // Get diffs for all files
        const diffs = await this.getFileDiffs(this.context.files);
        
        // Build prompt using the shared template
        let prompt;
        try {
            prompt = await GitModal.buildCommitPromptWithTemplate(this.context.files, diffs);
        } catch (templateError) {
            console.error('Template error, using fallback:', templateError);
            // Use a simple fallback prompt
            prompt = `Files changed:\n${this.context.files.map(f => f.filePath).join('\n')}\n\nGenerate a commit message.`;
        }
        
        // ... rest of the implementation
    } catch (error) {
        console.error('Error generating message:', error);
        this.updateStatus('❌', 'Failed to generate message');
        // ... error handling
    }
}
```

## Add template preview functionality:

```javascript
// Add to GitModal
previewTemplate: async function() {
    const template = await this.getCachedTemplate();
    
    // Sample data
    const sampleFiles = [
        { status: 'M', filePath: 'src/main/java/Example.java' },
        { status: 'A', filePath: 'src/test/java/ExampleTest.java' }
    ];
    
    const sampleDiffs = {
        'src/main/java/Example.java': 'diff --git a/Example.java b/Example.java\n@@ -1,5 +1,7 @@\n-old code\n+new code'
    };
    
    const preview = await this.buildCommitPromptWithTemplate(sampleFiles, sampleDiffs);
    
    // Show in a modal or console
    console.log('Template Preview:', preview);
    return preview;
}
```

## Add user feedback for template issues:

```javascript
// Add notification helper
showTemplateNotification: function(message, type = 'info') {
    // Create a notification element
    const notification = document.createElement('div');
    notification.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        padding: 12px 20px;
        background: ${type === 'error' ? '#ef4444' : '#3b82f6'};
        color: white;
        border-radius: 6px;
        box-shadow: 0 4px 12px rgba(0,0,0,0.15);
        z-index: 10000;
        font-size: 14px;
        max-width: 300px;
        animation: slideIn 0.3s ease-out;
    `;
    notification.textContent = message;
    
    document.body.appendChild(notification);
    
    // Auto-remove after 5 seconds
    setTimeout(() => {
        notification.style.animation = 'slideOut 0.3s ease-in';
        setTimeout(() => notification.remove(), 300);
    }, 5000);
}
```
