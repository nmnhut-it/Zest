/**
 * Demo JavaScript file for Zest browser integration.
 * This file demonstrates the JavaScript bridge functionality.
 */

// Global object to keep track of IntelliJ communication
const intellijBridgeDemo = {
    // Function to get selected text from editor
    getSelectedText: async function() {
        console.log("Getting selected text from IntelliJ editor...");
        
        try {
            if (!window.intellijBridge || !window.intellijBridge.callIDE) {
                throw new Error("IntelliJ bridge not available");
            }
            
            const response = await window.intellijBridge.callIDE('getSelectedText');
            
            if (response.success) {
                console.log("Received text from IntelliJ:", response.result);
                return response.result;
            } else {
                throw new Error(response.error || "Unknown error");
            }
        } catch (error) {
            console.error("Error getting selected text:", error);
            throw error;
        }
    },
    
    // Function to insert text into editor
    insertTextToEditor: async function(text) {
        console.log("Inserting text into IntelliJ editor:", text);
        
        try {
            if (!window.intellijBridge || !window.intellijBridge.callIDE) {
                throw new Error("IntelliJ bridge not available");
            }
            
            const response = await window.intellijBridge.callIDE('insertText', { text });
            
            if (response.success) {
                console.log("Text inserted successfully");
                return true;
            } else {
                throw new Error(response.error || "Unknown error");
            }
        } catch (error) {
            console.error("Error inserting text:", error);
            throw error;
        }
    },
    
    // Function to get current file name
    getCurrentFileName: async function() {
        console.log("Getting current file name from IntelliJ...");
        
        try {
            if (!window.intellijBridge || !window.intellijBridge.callIDE) {
                throw new Error("IntelliJ bridge not available");
            }
            
            const response = await window.intellijBridge.callIDE('getCurrentFileName');
            
            if (response.success) {
                console.log("Current file name:", response.result);
                return response.result;
            } else {
                throw new Error(response.error || "Unknown error");
            }
        } catch (error) {
            console.error("Error getting file name:", error);
            throw error;
        }
    },
    
    // Initialize event listeners for IDE communication
    initialize: function() {
        console.log("Initializing IntelliJ bridge demo...");
        
        // Listen for text sent from IntelliJ
        document.addEventListener('ideTextReceived', (event) => {
            console.log("Received text from IDE:", event.detail.text);
            
            // Dispatch custom event for other handlers
            const customEvent = new CustomEvent('intellijTextReceived', {
                detail: { text: event.detail.text }
            });
            document.dispatchEvent(customEvent);
        });
        
        console.log("IntelliJ bridge demo initialized");
    }
};

// Initialize the demo when the page loads
document.addEventListener('DOMContentLoaded', () => {
    intellijBridgeDemo.initialize();
});

// Export the demo object for use in other scripts
window.intellijBridgeDemo = intellijBridgeDemo;
