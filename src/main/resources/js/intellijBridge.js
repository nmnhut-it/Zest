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
  }
};

window.shouldAutomaticallyCopy = true;
console.log('IntelliJ Bridge initialized');