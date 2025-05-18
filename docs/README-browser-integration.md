# Zest Browser Integration

## Overview

The Zest Browser Integration component provides seamless communication between IntelliJ IDEA and web-based AI assistants. It enables code extraction, project context injection, and bidirectional communication between the browser and the IDE.

## Features

- **Code Extraction**: Extract code from AI assistant responses with high reliability
- **Project Context**: Inject real-time project context into AI assistant prompts
- **Bridge Communication**: Bidirectional communication between browser and IDE
- **Multiple Extraction Methods**: API-based extraction with DOM-based fallbacks
- **Automatic Expansion**: Handling of collapsed code blocks

## Components

### JavaScript Files

| File | Description |
|------|-------------|
| **intellijBridge.js** | Core bridge functionality for browser-to-IDE communication |
| **responseParser.js** | Parses API responses to extract code blocks |
| **codeExtractor.js** | DOM-based code extraction for various UI structures |
| **interceptor.js** | Request/response interception for project context and code extraction |

### Java Files

| File | Description |
|------|-------------|
| **JavaScriptBridge.java** | Handles requests from JavaScript and performs IDE actions |
| **JCEFBrowserManager.java** | Manages the browser instance and injects JavaScript files |
| **AutoCodeExtractorWithBridge.java** | Handles code extraction events and processing |

## Usage

The browser integration is automatically initialized when a Zest browser window is opened in the IDE. It provides several ways to extract code:

1. **API Response Parsing**: Automatically extracts code from API responses
2. **DOM-based Extraction**: Automatically finds and extracts code from the rendered HTML
3. **Manual Extraction**: User can manually select code to extract

## Architecture

```
┌─────────────────┐          ┌─────────────────┐         ┌─────────────────┐
│     Browser     │          │      Bridge     │         │     IntelliJ    │
│                 │          │                 │         │                 │
│  - Web UI       │◄────────►│  - JS Bridge    │◄───────►│  - Editor       │
│  - JavaScript   │          │  - Java Bridge  │         │  - Project      │
│  - DOM          │          │  - Callbacks    │         │  - Files        │
└─────────────────┘          └─────────────────┘         └─────────────────┘
```

## Configuration

The browser integration is pre-configured and requires no manual setup. All JavaScript files are automatically injected into the browser when it loads.

## Development

To modify the browser integration:

1. Edit JavaScript files in `src/main/resources/js`
2. Update Java bridge handlers in `JavaScriptBridge.java`
3. Add new JavaScript files to the initialization in `JCEFBrowserManager.java`

## Documentation

Detailed documentation is available in the `/docs` folder:

- [Browser Integration Overview](./browser-integration.md)
- [Implementation Guide](./implementation-guide.md)
- [Changelog](./changelog.md)

## Troubleshooting

Common issues and solutions:

- **Code not extracting**: Check browser console for errors
- **Bridge not connecting**: Verify JCEF initialization in logs
- **API parsing failing**: Check response format in browser network tab
- **DOM extraction failing**: Verify selectors match the current UI

## Future Enhancements

Planned enhancements include:

- Support for more AI assistant platforms
- Enhanced code block language detection
- Improved handling of multi-file code examples
- Integration with IDE code generation features