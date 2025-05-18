/**
 * Code Extractor for IDE Integration
 *
 * This script provides functionality to extract code from the browser
 * and send it back to the IDE. It works with both CodeMirror editors
 * and standard code blocks.
 */

// Define the CodeMirror extractor function
window.extractCodeToIntelliJ = function(textToReplace) {
    // Find all CodeMirror editors first
    const cmEditors = document.querySelectorAll('.cm-editor');
    
    if (cmEditors.length === 0) {
        console.log('No CodeMirror editors found');
        // Try regular code blocks if no CodeMirror editors found
        return extractFromRegularBlocks(textToReplace);
    }
    
    // Get the most recent editor (last one)
    const cmEditor = cmEditors[cmEditors.length - 1];
    
    // Find the containing code block (parent .relative)
    let codeBlock = cmEditor;
    while (codeBlock && (!codeBlock.classList || !codeBlock.classList.contains('relative'))) {
        codeBlock = codeBlock.parentElement;
        if (!codeBlock) break;
    }
    
    if (!codeBlock) {
        console.log('Could not find parent code block');
        // Try to extract code directly from editor if parent block not found
        return extractFromEditor(cmEditor, textToReplace);
    }
    
    // Extract language
    const langElement = codeBlock.querySelector('.text-text-300');
    const language = langElement ? langElement.textContent.trim() : '';
    
    // Extract code from CodeMirror lines
    return extractFromEditor(cmEditor, textToReplace);
};

// Helper function to extract code from a CodeMirror editor
function extractFromEditor(cmEditor, textToReplace) {
    if (!cmEditor) return false;
    
    // Extract code from CodeMirror lines
    const codeLines = cmEditor.querySelectorAll('.cm-line');
    const code = Array.from(codeLines)
        .map(line => line.textContent)
        .join('\n');
    
    // Skip if no code
    if (!code) {
        console.log('No code content found');
        return false;
    }
    
    // Send to IntelliJ using the bridge
    console.log('code', code);
    if (window.intellijBridge && window.intellijBridge.callIDE && window.__text_to_replace_ide___) {
        window.intellijBridge.callIDE('codeCompleted', { text: code, textToReplace: textToReplace })
            .then(function() {
                console.log('Code sent to IntelliJ successfully');
            })
            .catch(function(error) {
                console.error('Failed to send code to IntelliJ:', error);
            });
        return true;
    } else {
        console.error('IntelliJ Bridge not found');
        return false;
    }
}

// Helper function to extract code from standard code blocks
function extractFromRegularBlocks(textToReplace) {
    try {
        // Find all code blocks
        const codeBlocks = document.querySelectorAll('pre code, pre, code');
        if (codeBlocks.length === 0) {
            console.log('No code blocks found on page');
            return false;
        }
        
        // If we have a specific text to replace, use that
        if (textToReplace && textToReplace !== '__##use_selected_text##__') {
            window.intellijBridge.callIDE('codeCompleted', {
                textToReplace: textToReplace,
                text: codeBlocks[0].textContent
            }).then(function(result) {
                console.log('Code sent to IntelliJ successfully');
            }).catch(function(error) {
                console.error('Error sending code to IntelliJ:', error);
            });
        } else {
            // Otherwise, get selected text and insert the code
            window.intellijBridge.callIDE('getSelectedText', {})
                .then(function(response) {
                    if (response.result) {
                        // If we have selected text in the editor, replace it
                        window.intellijBridge.callIDE('codeCompleted', {
                            textToReplace: response.result,
                            text: codeBlocks[0].textContent
                        });
                    } else {
                        // Otherwise, just insert at caret position
                        window.intellijBridge.callIDE('insertText', {
                            text: codeBlocks[0].textContent
                        });
                    }
                })
                .catch(function(error) {
                    console.error('Error getting selected text:', error);
                });
        }
        return true;
    } catch (e) {
        console.error('Error extracting code:', e);
        return false;
    }
}

console.log('Compatible code extractor function initialized');