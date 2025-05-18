# Zest Browser Integration Changelog

## Version 1.1.0 - API Response Parsing

### Added
- **API Response Parsing**: Added direct extraction of code from API responses
  - Created new `responseParser.js` to handle JSON response parsing
  - Added regex-based code block extraction from message content
  - Implemented language detection and prioritization
  - Added tracking of processed response IDs to avoid duplicates

- **Extended JavaScript Bridge**:
  - Added `extractCodeFromResponse` method to the bridge
  - Added Java-side handling for extracted code with `handleExtractedCode`
  - Maintained backward compatibility with existing code extraction

- **Enhanced Code Extraction**:
  - Added support for collapsed code blocks with automatic expansion
  - Added text-based button finding to handle various UI frameworks
  - Improved extraction from both CodeMirror editors and standard code blocks

- **Fallback Mechanisms**:
  - Implemented tiered approach with multiple fallback options
  - Added graceful degradation from API parsing to DOM extraction
  - Added extensive error handling throughout the process

- **Documentation**:
  - Added comprehensive documentation in `/docs` folder
  - Created overview document explaining the architecture
  - Added implementation guide for developers

### Changed
- **Refactored JavaScript Code**:
  - Moved JavaScript from inline Java strings to dedicated JS files
  - Improved organization with separate files for different concerns
  - Enhanced readability with better function and variable naming

- **Improved Request Interception**:
  - Enhanced request and response handling logic
  - Added better detection of API endpoints and response types
  - Improved error handling and logging

- **Updated Bridge Initialization**:
  - Improved the mechanism for creating the JavaScript bridge
  - Added placeholder-based injection of JBCefJSQuery
  - Enhanced script loading with proper resource handling

### Fixed
- **Compatibility Issues**:
  - Fixed compatibility with different browser environments
  - Addressed issues with older JavaScript engines
  - Fixed CSS selector compatibility problems

- **Error Handling**:
  - Improved error handling throughout the codebase
  - Added better logging of errors for debugging
  - Enhanced recovery from various failure scenarios

## Version 1.0.0 - Initial Release

### Added
- Initial implementation of browser integration
- Basic code extraction from HTML
- JavaScript bridge for communication with IntelliJ
- Request interception for adding project context
- DOM-based code extraction using CodeMirror selectors