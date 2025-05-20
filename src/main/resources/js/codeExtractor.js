/**
 * Code Extractor for IDE Integration
 *
 * This script provides functionality to extract code from the browser
 * and send it back to the IDE. It works with both CodeMirror editors
 * and standard code blocks.
 */

// Define the CodeMirror extractor function
window.extractCodeToIntelliJ = function(textToReplace) {
    // First, check if there are any collapsed code blocks that need to be expanded
    findAndClickExpandButtons();
    
    // Wait a short time to allow expansion to complete
    setTimeout(() => {
        // Try to extract from CodeMirror editors first
        if (!extractFromCodeMirror(textToReplace)) {
            // If that fails, fall back to standard code blocks
            extractFromRegularBlocks(textToReplace);
        }
    }, 200); // Slightly longer timeout to ensure expansion completes
    
    return true;
};

// Function to find and click expand buttons by their text content
function findAndClickExpandButtons() {
    console.log('Searching for expand buttons...');
    
    try {
        // Get all elements that might contain text
        const allElements = document.querySelectorAll('*');
        
        // Filter to find elements with "Expand" text
        const expandElements = Array.from(allElements).filter(element => {
            const text = element.textContent.trim().toLowerCase();
            return text === 'expand';
        });
        
        console.log(`Found ${expandElements.length} elements with 'expand' text`);
        
        // For each element with "Expand" text, find the closest clickable parent
        expandElements.forEach((element, index) => {
            console.log(`Examining expand element ${index}:`, element);
            
            // First check if the element itself is clickable
            if (element.tagName === 'BUTTON' || element.tagName === 'A' || element.onclick) {
                console.log('Element is directly clickable, clicking...');
                element.click();
                return;
            }
            
            // Find the closest parent that's a button, link, or has onClick
            let parent = element.parentElement;
            let depth = 0;
            const maxDepth = 3; // Don't go too far up the tree
            
            while (parent && depth < maxDepth) {
                console.log(`Checking parent at depth ${depth}:`, parent);
                
                if (parent.tagName === 'BUTTON' || 
                    parent.tagName === 'A' || 
                    parent.onclick || 
                    parent.classList.contains('flex') && parent.querySelector('svg')) {
                    console.log('Found clickable parent, clicking...');
                    parent.click();
                    return;
                }
                
                parent = parent.parentElement;
                depth++;
            }
            
            console.log('No clickable parent found for this element');
        });
        
        // Look specifically for flex containers with "Expand" text and SVG icons
        const flexContainers = document.querySelectorAll('.flex');
        const expandFlexContainers = Array.from(flexContainers).filter(container => {
            const text = container.textContent.trim().toLowerCase();
            const hasSvg = container.querySelector('svg') !== null;
            return text.includes('expand') && hasSvg;
        });
        
        console.log(`Found ${expandFlexContainers.length} flex containers with 'expand' text and SVG icons`);
        expandFlexContainers.forEach(container => {
            console.log('Clicking flex container with expand text:', container);
            container.click();
        });
    } catch (e) {
        console.error('Error expanding code blocks:', e);
    }
}

// Extract from CodeMirror editors
function extractFromCodeMirror(textToReplace) {
    // Find all CodeMirror editors
    const cmEditors = document.querySelectorAll('.cm-editor');
    
    if (cmEditors.length === 0) {
        console.log('No CodeMirror editors found');
        return false;
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
}

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
//    console.log('code', code);
    if (window.intellijBridge && window.intellijBridge.callIDE) {
        window.intellijBridge.callIDE('codeCompleted', { 
            text: code, 
            textToReplace: textToReplace || (window.__text_to_replace_ide___ ? window.__text_to_replace_ide___ : '__##use_selected_text##__') 
        })
        .then(function() {
            console.log('Code sent to IntelliJ successfully');
            // Reset the text to replace after successful extraction
            window.__text_to_replace_ide___ = null;
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
                text: codeBlocks[codeBlocks.length - 1].textContent // Use the last code block
            }).then(function(result) {
                console.log('Code sent to IntelliJ successfully');
                // Reset the text to replace after successful extraction
                window.__text_to_replace_ide___ = null;
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
                            text: codeBlocks[codeBlocks.length - 1].textContent // Use the last code block
                        });
                    } else {
                        // Otherwise, just insert at caret position
                        window.intellijBridge.callIDE('insertText', {
                            text: codeBlocks[codeBlocks.length - 1].textContent // Use the last code block
                        });
                    }
                    // Reset the text to replace after successful extraction
                    window.__text_to_replace_ide___ = null;
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

// Ensure automatic copy flag is set
window.shouldAutomaticallyCopy = true;

console.log('Compatible code extractor function initialized with collapsed code block support');