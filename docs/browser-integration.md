# Zest Browser Integration Documentation

## Overview

Zest provides seamless integration between IntelliJ IDEA and web-based AI assistants. This document explains the core components of the browser integration, how code extraction works, and how the API-based response parsing has been implemented.

## Core Components

### 1. JavaScript Bridge

The JavaScript Bridge provides bidirectional communication between the web browser and IntelliJ IDEA.

#### Key Files:
- `intellijBridge.js`: Sets up the bridge interface for JavaScript to call Java methods
- `JavaScriptBridge.java`: Handles requests from JavaScript and performs actions in the IDE

#### Core Functionality:
- Code extraction and insertion
- Project information retrieval
- Editor interaction (getting selected text, inserting code)
- UI interactions (showing dialogs, diffs)

### 2. Code Extractor

The Code Extractor identifies and extracts code from the browser UI.

#### Key Files:
- `codeExtractor.js`: Identifies and extracts code from CodeMirror editors and standard code blocks
- `AutoCodeExtractorWithBridge.java`: Injects the code extractor JavaScript

#### Core Functionality:
- DOM-based code extraction from CodeMirror editors
- Fallback to standard code blocks
- Support for collapsed code blocks
- Sending extracted code to IntelliJ

### 3. Request/Response Interceptor

The Interceptor modifies API requests and responses to enhance functionality.

#### Key Files:
- `interceptor.js`: Intercepts fetch requests and responses
- `responseParser.js`: Parses API responses to extract code

#### Core Functionality:
- Adding project context to requests
- Parsing responses for code blocks
- Handling system prompts
- Supporting different AI platforms and chat interfaces

## Code Extraction Methods

Zest provides multiple methods for extracting code from AI assistants, with automatic fallbacks for maximum reliability:

### 1. API Response Parsing (Primary Method)

This method extracts code directly from the JSON API responses, avoiding the need for DOM manipulation.

#### Benefits:
- More reliable than DOM-based extraction
- Works regardless of UI state (collapsed/expanded code blocks)
- Access to additional metadata (language, timestamps)
- Less sensitive to UI changes

#### Implementation:
1. The interceptor catches API responses with completed messages
2. The response parser extracts code blocks using regex
3. Code is sent to IntelliJ via the `extractCodeFromResponse` bridge function

### 2. DOM-based Extraction (Fallback Method)

This method extracts code by manipulating the DOM to find code blocks.

#### Benefits:
- Works across different UI structures
- Can handle complex rendered output
- Supports legacy chat interfaces

#### Implementation:
1. Looks for collapsed code blocks and expands them
2. Tries to extract from CodeMirror editors first
3. Falls back to standard code blocks if necessary
4. Sends code to IntelliJ via the bridge

## Integration Flow

1. **Bridge Initialization**:
   - JCEFBrowserManager loads and injects all required JavaScript files
   - Bridge is established between browser and IDE

2. **Request Enhancement**:
   - API requests are intercepted
   - Project context is added to messages
   - System prompts are managed

3. **Response Processing**:
   - API responses are intercepted
   - Code blocks are extracted from response JSON
   - If extraction succeeds, code is sent to IDE
   - If extraction fails, falls back to DOM-based extraction

4. **Code Handling in IDE**:
   - Extracted code is presented in a diff view
   - User can review and apply changes
   - Code is inserted or replaces selection in the editor

## Enhancements and Features

### 1. Collapsed Code Block Support

The implementation can find and click "Expand" buttons before extracting code, ensuring collapsed code blocks are properly handled.

### 2. Priority Language Selection

When multiple code blocks are present in a response, the implementation prioritizes commonly used languages like Java, JavaScript, TypeScript, etc.

### 3. Robust Fallback Mechanism

The tiered approach ensures code extraction works across different chat interfaces and response formats:
- First tier: API response parsing
- Second tier: DOM-based extraction
- Final tier: Manual extraction

## Extension Points

The architecture allows for easy extension to support:

1. **Additional Chat Interfaces**:
   - Add URL patterns to the interceptor
   - Update response parsing for different JSON structures

2. **New Code Block Formats**:
   - Extend the regex patterns in the response parser
   - Add new DOM selectors to the code extractor

3. **Enhanced Interactions**:
   - Add new bridge functions for additional IDE features
   - Extend the request/response handling for more context

## Technical Notes

### JavaScript File Organization

- **intellijBridge.js**: Core bridge functionality
- **codeExtractor.js**: DOM-based code extraction
- **responseParser.js**: API response parsing
- **interceptor.js**: Request/response interception

### Bridge Communication Pattern

1. JavaScript calls Java via JBCefJSQuery
2. Java performs the requested action
3. Java returns a response to JavaScript
4. JavaScript processes the response

### API Response Parsing

The response parser uses regex to find code blocks in the format:
```
```language
code
```
```

It extracts both the language and the code content for proper handling in the IDE.

### Exception Handling

All operations include robust error handling with fallbacks:
- API parsing errors fall back to DOM extraction
- DOM extraction errors fall back to manual extraction
- Bridge communication errors are logged and reported