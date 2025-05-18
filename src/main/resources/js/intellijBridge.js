/**
 * IntelliJ Bridge Initialization
 *
 * This script initializes the JavaScript bridge between the browser and IntelliJ.
 * IMPORTANT: This file contains placeholders for the JBCefJSQuery.inject method,
 * which must be processed by the Java code before injection.
 */

window.intellijBridge = {
  callIDE: function(action, data) {
    return new Promise(function(resolve, reject) {
      try {
        // Create the request
        var request = JSON.stringify({
          action: action,
          data: data || {}
        });
        
        console.log('Sending request to IDE:', action);
        
        // This is a placeholder that will be replaced by Java code
        // with the actual JBCefJSQuery.inject call
        [[JBCEF_QUERY_INJECT]]
      } catch(e) {
        console.error('Error calling IDE:', e);
        reject(e);
      }
    });
  },
  
  /**
   * Extracts code from an API response and sends it to the IDE
   * @param {Object} data - Object containing the code, language, and text to replace
   * @returns {Promise} Promise that resolves when the code is sent to the IDE
   */
  extractCodeFromResponse: function(data) {
    return this.callIDE('extractCodeFromResponse', {
      code: data.code,
      language: data.language,
      textToReplace: data.textToReplace || '__##use_selected_text##__'
    });
  },
  
  /**
   * Performs a file replacement using the ReplaceInFileTool
   * @param {Object} data - Object containing the file path, search text, replacement text, and options
   * @returns {Promise} Promise that resolves when the replacement is complete
   */
  replaceInFile: function(data) {
    return this.callIDE('replaceInFile', {
      filePath: data.filePath,
      search: data.search,
      replace: data.replace,
      regex: data.regex || false,
      caseSensitive: data.caseSensitive || true
    });
  },
  
  /**
   * Performs multiple replacements in a file in a single operation
   * @param {Object} data - Object containing the file path and an array of replacements
   * @returns {Promise} Promise that resolves when all replacements are complete
   */
  batchReplaceInFile: function(data) {
    return this.callIDE('batchReplaceInFile', {
      filePath: data.filePath,
      replacements: data.replacements
    });
  }
};

window.shouldAutomaticallyCopy = true;
console.log('IntelliJ Bridge initialized with response parsing support');