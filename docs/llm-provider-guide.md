# LLM Provider Integration Guide

## Overview

The LLM Provider is a JavaScript module that enables seamless integration with OpenWebUI's language model API within the IntelliJ JCEF browser environment. It provides a clean, type-safe interface for AI-powered features including code generation, review, documentation, and debugging assistance.

## Features

- 🤖 **Multiple LLM Operations**: Code generation, review, explanation, documentation, and debugging
- 🔐 **Automatic Authentication**: Handles token retrieval from cookies and localStorage
- 📊 **Usage Tracking**: Built-in usage type categorization for analytics
- 🌊 **Streaming Support**: Real-time response streaming for better UX
- 🎯 **Specialized Methods**: Purpose-built functions for common development tasks
- 🔧 **Configurable**: Easy to customize model, temperature, and token limits

## Installation

The LLM Provider is automatically loaded when the JCEF browser initializes. It's injected into the page through the `JCEFBrowserManager`:

```java
// In JCEFBrowserManager.java
String llmProviderScript = loadResourceAsString("/js/LLMProvider.js");
cefBrowser.executeJavaScript(llmProviderScript, frame.getURL(), 0);
```

## Basic Usage

### Initialization

The provider auto-initializes with default settings:

```javascript
// Default configuration
{
    defaultModel: 'Qwen2.5-Coder-7B',
    temperature: 0.7,
    maxTokens: 200,
    stream: false
}

// Custom initialization
window.LLMProvider.initialize({
    defaultModel: 'gpt-4',
    temperature: 0.5,
    maxTokens: 500
});
```

### Simple API Call

```javascript
try {
    const response = await window.LLMProvider.callAPI('Explain closures in JavaScript');
    console.log(response);
} catch (error) {
    console.error('LLM call failed:', error);
}
```

## API Reference

### Core Methods

#### `callAPI(prompt, options)`

The main method for making LLM API calls.

```javascript
const response = await window.LLMProvider.callAPI(prompt, {
    model: 'Qwen2.5-Coder-7B',        // Model to use
    temperature: 0.7,                  // Creativity level (0-1)
    maxTokens: 200,                    // Maximum response length
    stream: false,                     // Enable streaming
    usageType: 'GENERAL_CHAT',        // Usage tracking
    systemPrompt: null,               // System message
    messages: null                    // Full message array
});
```

### Specialized Methods

#### `generateCommitMessage(diff, options)`

Generate git commit messages from diffs:

```javascript
const diff = "diff --git a/file.js b/file.js\n...";
const commitMessage = await window.LLMProvider.generateCommitMessage(diff);
// Returns: "feat(auth): implement OAuth2 login flow"
```

#### `generateCode(description, language, options)`

Generate code from natural language:

```javascript
const code = await window.LLMProvider.generateCode(
    'Create a debounce function',
    'javascript'
);
```

#### `reviewCode(code, language, options)`

Get AI-powered code review:

```javascript
const review = await window.LLMProvider.reviewCode(
    'function add(a, b) { return a + b }',
    'javascript'
);
```

#### `explainCode(code, language, options)`

Get beginner-friendly code explanations:

```javascript
const explanation = await window.LLMProvider.explainCode(
    'const [state, setState] = useState(0);',
    'javascript'
);
```

#### `generateDocumentation(code, language, options)`

Generate comprehensive documentation:

```javascript
const docs = await window.LLMProvider.generateDocumentation(
    'class UserService { ... }',
    'javascript'
);
```

#### `debugCode(code, error, language, options)`

Get debugging help:

```javascript
const solution = await window.LLMProvider.debugCode(
    'const result = array.map(x => x.value);',
    'TypeError: Cannot read property "value" of undefined',
    'javascript'
);
```

### Streaming API

For real-time responses:

```javascript
await window.LLMProvider.streamAPI(
    'Write a sorting algorithm',
    (chunk) => {
        // Handle each chunk as it arrives
        console.log(chunk);
    },
    { maxTokens: 1000 }
);
```

### Model Management

```javascript
// List available models
const models = await window.LLMProvider.getAvailableModels();

// Update configuration
window.LLMProvider.updateConfig({
    defaultModel: 'gpt-4',
    temperature: 0.3
});
```

## Usage Types

The provider tracks different usage types for analytics:

```javascript
window.LLMProvider.UsageTypes = {
    CHAT_GIT_COMMIT_MESSAGE: 'CHAT_GIT_COMMIT_MESSAGE',
    CODE_GENERATION: 'CODE_GENERATION',
    CODE_REVIEW: 'CODE_REVIEW',
    CODE_EXPLANATION: 'CODE_EXPLANATION',
    DOCUMENTATION: 'DOCUMENTATION',
    DEBUGGING: 'DEBUGGING',
    GENERAL_CHAT: 'GENERAL_CHAT'
}
```

## Integration Examples

### With Git Integration

```javascript
// In git.js
const diffSummary = this.buildDiffSummary(selectedFiles, diffs);
const commitMessage = await window.LLMProvider.generateCommitMessage(diffSummary);
```

### With Agent Framework

```javascript
// In agentFramework.js
class CodeGeneratorAgent extends Agent {
    async generateWithAI(prompt) {
        if (window.LLMProvider) {
            return await window.LLMProvider.generateCode(prompt);
        }
        // Fallback logic
    }
}
```

### With IntelliJ Bridge

```javascript
// Generate and insert code
const code = await window.LLMProvider.generateCode('REST API endpoint');
await window.intellijBridge.callIDE('insertCode', {
    code: code,
    language: 'javascript'
});
```

## Error Handling

The provider includes comprehensive error handling:

```javascript
try {
    const response = await window.LLMProvider.callAPI(prompt);
} catch (error) {
    if (error.message.includes('No authentication token')) {
        // User needs to log in
    } else if (error.message.includes('API request failed')) {
        // API error - check status code
    } else {
        // Other errors
    }
}
```

## Authentication

The provider automatically retrieves authentication tokens from:

1. Cookies (`auth_token`, `token`)
2. localStorage (`auth_token`, `token`)

If no token is found, methods will throw an error asking the user to log in.

## Configuration

### Default Settings

```javascript
{
    defaultModel: 'Qwen2.5-Coder-7B',
    temperature: 0.7,    // Creativity (0 = deterministic, 1 = creative)
    maxTokens: 200,      // Response length limit
    stream: false        // Streaming mode
}
```

### Per-Method Defaults

Different methods have optimized defaults:

- **Commit Messages**: `temperature: 0.5, maxTokens: 100`
- **Code Generation**: `temperature: 0.7, maxTokens: 1000`
- **Code Review**: `temperature: 0.3, maxTokens: 800`
- **Documentation**: `temperature: 0.3, maxTokens: 800`

## Best Practices

1. **Always handle errors**: LLM calls can fail due to network, auth, or API issues

2. **Use specialized methods**: They have optimized prompts and settings

3. **Respect token limits**: Large prompts/responses may hit limits

4. **Cache responses**: For expensive operations, consider caching

5. **Provide context**: Better prompts yield better results

```javascript
// Good
const code = await LLMProvider.generateCode(
    'Create a React hook for debouncing that handles cleanup'
);

// Better
const code = await LLMProvider.generateCode(
    'Create a React hook for debouncing with these requirements:\n' +
    '- Accept a callback and delay\n' +
    '- Handle component unmounting\n' +
    '- Return a memoized debounced function\n' +
    '- Include TypeScript types'
);
```

## Troubleshooting

### Common Issues

1. **"No authentication token found"**
   - Ensure user is logged into OpenWebUI
   - Check if cookies are enabled
   - Try refreshing the page

2. **"API request failed: 429"**
   - Rate limit hit - add delays between requests
   - Consider implementing request queuing

3. **"Invalid API response format"**
   - Check if the API endpoint is correct
   - Verify the model is available

### Debug Mode

Enable debug logging:

```javascript
// In console
window.LLMProvider.debug = true;
```

## Future Enhancements

- [ ] Request queuing and rate limiting
- [ ] Response caching with TTL
- [ ] Retry logic with exponential backoff
- [ ] Model-specific prompt optimization
- [ ] Fine-tuning support
- [ ] Local model integration
- [ ] Multi-provider support (OpenAI, Anthropic, etc.)

## Security Considerations

1. **Token Storage**: Tokens are retrieved from browser storage only
2. **HTTPS Only**: API calls require secure connections
3. **Input Sanitization**: User inputs are sanitized before sending
4. **No Token Persistence**: The provider never stores tokens

## Contributing

To extend the LLM Provider:

1. Add new methods following the existing pattern
2. Include proper error handling
3. Add usage type if needed
4. Update documentation
5. Test with different models

Example:

```javascript
/**
 * Generate unit tests for code
 */
generateTests: async function(code, language = 'javascript', framework = 'jest', options = {}) {
    const systemPrompt = `You are a test-driven development expert...`;
    const prompt = `Generate ${framework} tests for:\n\`\`\`${language}\n${code}\n\`\`\``;
    
    return this.callAPI(prompt, {
        ...options,
        systemPrompt: systemPrompt,
        usageType: this.UsageTypes.CODE_GENERATION,
        temperature: 0.3,
        maxTokens: 1000
    });
}
```

## License

This module is part of the Zest IntelliJ plugin and follows the same license terms.
