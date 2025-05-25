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

// Function to inject "To IDE" buttons into code blocks
window.injectToIDEButtons = function() {
    console.log('Injecting To IDE buttons...');

    try {
        // Find all button containers (the ones with Copy, Save, Expand buttons)
        const buttonContainers = document.querySelectorAll('.flex.items-center.gap-0\\.5');

        buttonContainers.forEach((container, index) => {
            // Check if we already added a To IDE button to this container
            if (container.querySelector('.to-ide-button')) {
                return; // Skip if button already exists
            }

            // Check if this container has code-related buttons (Copy, Save, etc.)
            const hasCodeButtons = container.querySelector('.copy-code-button') ||
                                 container.querySelector('.save-code-button') ||
                                 container.textContent.toLowerCase().includes('copy') ||
                                 container.textContent.toLowerCase().includes('save');

            if (hasCodeButtons) {
                console.log(`Adding To IDE button to container ${index}`);

                // Create the To IDE button
                const toIdeButton = document.createElement('button');
                toIdeButton.className = 'to-ide-button bg-none border-none bg-gray-50 hover:bg-gray-100 dark:bg-gray-850 dark:hover:bg-gray-800 transition rounded-md px-1.5 py-0.5';
                toIdeButton.textContent = 'To IDE';
                toIdeButton.title = 'Send code to IDE with diff preview';

                // Add click handler
                toIdeButton.addEventListener('click', function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    handleToIDEButtonClick(this);
                });

                // Append to the container
                container.appendChild(toIdeButton);
            }
        });

        console.log('To IDE button injection completed');
    } catch (e) {
        console.error('Error injecting To IDE buttons:', e);
    }
};

// Function to handle To IDE button clicks
function handleToIDEButtonClick(button) {
    console.log('To IDE button clicked');

    try {
        // Find the closest code block container
        let codeBlock = button.closest('.relative');
        if (!codeBlock) {
            // Try other common code block containers
            codeBlock = button.closest('pre') ||
                       button.closest('[class*="code"]') ||
                       button.closest('.cm-editor')?.closest('.relative');
        }

        if (!codeBlock) {
            console.error('Could not find associated code block');
            return;
        }

        // First, click expand buttons to ensure all code is visible
        findAndClickExpandButtons();

        // Add delay to allow HTML to render after expanding
        setTimeout(() => {
            try {
                let code = '';
                let language = '';

                // Try to extract from CodeMirror first
                const cmEditor = codeBlock.querySelector('.cm-editor');
                if (cmEditor) {
                    const codeLines = cmEditor.querySelectorAll('.cm-line');
                    code = Array.from(codeLines)
                        .map(line => line.textContent)
                        .join('\n');

                    // Get language if available
                    const langElement = codeBlock.querySelector('.text-text-300');
                    language = langElement ? langElement.textContent.trim() : '';
                } else {
                    // Fallback to regular code block extraction
                    const codeElement = codeBlock.querySelector('code') ||
                                      codeBlock.querySelector('pre') ||
                                      codeBlock;
                    code = codeElement.textContent;

                    // Try to extract language from class names or other indicators
                    const langElement = codeBlock.querySelector('[class*="language-"]') ||
                                      codeBlock.querySelector('.text-text-300');
                    if (langElement) {
                        const classList = Array.from(langElement.classList || []);
                        const langClass = classList.find(cls => cls.startsWith('language-'));
                        language = langClass ? langClass.replace('language-', '') : langElement.textContent.trim();
                    }
                }

                if (!code.trim()) {
                    console.error('No code content found after expansion');
                    return;
                }

                // Call the showCodeDiffAndReplace function
                if (window.intellijBridge && window.intellijBridge.callIDE) {
                    console.log('Sending code to IDE for diff and replace:', { code: code.substring(0, 100) + '...', language });

                    window.intellijBridge.callIDE('showCodeDiffAndReplace', {
                        code: code,
                        language: language,
                        textToReplace: window.__text_to_replace_ide___ || '__##use_selected_text##__'
                    }).then(function() {
                        console.log('Code sent to IDE for diff and replace successfully');
                        window.__text_to_replace_ide___ = null;
                    }).catch(function(error) {
                        console.error('Failed to send code to IDE:', error);
                    });
                } else {
                    console.error('IntelliJ Bridge not found');
                }
            } catch (e) {
                console.error('Error extracting code after expansion:', e);
            }
        }, 300); // 300ms delay to allow DOM expansion to complete

    } catch (e) {
        console.error('Error handling To IDE button click:', e);
    }
}

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

// Git File Selection Modal Functions
// Function to show file selection modal with changed files
window.showFileSelectionModal = function(changedFiles) {
    console.log('Showing modern file selection modal with files:', changedFiles);
    
    try {
        // Remove any existing modal
        const existingModal = document.getElementById('git-file-selection-modal');
        if (existingModal) {
            existingModal.remove();
        }
        
        // Inject the beautiful modal HTML (replaced by Java during setup)
        const modalHtmlContent = '[[MODAL_HTML_CONTENT]]';
        
        // Debug logging
        console.log('Modal HTML content length:', modalHtmlContent.length);
        console.log('Modal HTML preview:', modalHtmlContent.substring(0, 200) + '...');
        
        if (!modalHtmlContent || modalHtmlContent === '[[MODAL_HTML_CONTENT]]') {
            console.error('Modal HTML content not properly injected by Java');
            showFallbackFileSelectionModal(changedFiles);
            return;
        }
        
        // Create a temporary container to hold the HTML
        const tempContainer = document.createElement('div');
        tempContainer.innerHTML = modalHtmlContent;
        
        // Debug: Check what was parsed
        console.log('Temp container children count:', tempContainer.children.length);
        console.log('Temp container first child:', tempContainer.firstChild);
        
        // Extract all children from the temp container and append them directly to body
        // This ensures the modal element is directly in the body, not nested in a wrapper div
        const childrenToMove = Array.from(tempContainer.children);
        childrenToMove.forEach(child => {
            console.log('Moving child to body:', child.tagName, child.id);
            document.body.appendChild(child);
        });
        
        // If no children were found, try alternative approach
        if (childrenToMove.length === 0) {
            console.log('No children found, trying innerHTML approach');
            // Try direct innerHTML injection as fallback
            const modalElement = document.createElement('div');
            modalElement.innerHTML = modalHtmlContent;
            // Look for the modal element in the parsed content
            const modal = modalElement.querySelector('#git-file-selection-modal');
            if (modal) {
                document.body.appendChild(modal);
                console.log('Modal found and appended via alternative method');
            } else {
                console.error('No modal element found even with alternative parsing');
                showFallbackFileSelectionModal(changedFiles);
                return;
            }
        }
        
        // Parse and populate the file list
        parseAndPopulateFiles(changedFiles);
        
        // Show the modal
        const modal = document.getElementById('git-file-selection-modal');
        if (modal) {
            modal.style.display = 'flex';
            console.log('Modern file selection modal displayed successfully');
        } else {
            console.error('Modal element not found after injection');
            // Debug: Log what elements were actually added
            console.log('Available elements with git-file-selection-modal:', 
                document.querySelectorAll('#git-file-selection-modal'));
            console.log('All elements in body:', document.body.children);
            // Fallback to old modal
            showFallbackFileSelectionModal(changedFiles);
        }
        
    } catch (e) {
        console.error('Error showing file selection modal:', e);
        console.error('Stack trace:', e.stack);
        // Fallback to old modal if injection fails
        showFallbackFileSelectionModal(changedFiles);
    }
};

// Function to parse files and populate the beautiful modal
function parseAndPopulateFiles(changedFiles) {
    console.log('Parsing files for modern modal:', changedFiles);
    // The modal's JavaScript (from the injected HTML) will handle this
    // We just trigger the existing function if it exists
    if (typeof window.gitModalInstance !== 'undefined') {
        window.gitModalInstance.showFileSelectionModal(changedFiles);
    }
}

// Fallback function using the old modal (in case injection fails)
function showFallbackFileSelectionModal(changedFiles) {
    console.log('Using fallback modal for files:', changedFiles);
    
    try {
        // Keep the old modal implementation as fallback
        const isDark = document.documentElement.classList.contains('dark') || 
                      document.body.classList.contains('dark') ||
                      window.matchMedia('(prefers-color-scheme: dark)').matches;
        
        const themeClasses = {
            modal: isDark ? 'bg-gray-900 text-white' : 'bg-white text-gray-900',
            header: isDark ? 'border-gray-700' : 'border-gray-200',
            subtitle: isDark ? 'text-gray-300' : 'text-gray-600',
            container: isDark ? 'border-gray-700' : 'border-gray-200',
            fileItem: isDark ? 'border-gray-600 bg-gray-800' : 'border-gray-300 bg-gray-50',
            label: isDark ? 'text-gray-200' : 'text-gray-700',
            cancelBtn: isDark ? 'bg-gray-700 hover:bg-gray-600 text-white border-gray-600' : 'bg-gray-100 hover:bg-gray-200 text-gray-700 border-gray-300'
        };

        const modalHtml = `
            <div id="git-file-selection-modal" class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
                <div class="${themeClasses.modal} rounded-lg shadow-xl max-w-2xl w-full mx-4 max-h-[80vh] overflow-hidden">
                    <div class="px-6 py-4 border-b ${themeClasses.header}">
                        <h3 class="text-lg font-semibold">Select Files to Commit</h3>
                        <p class="text-sm ${themeClasses.subtitle} mt-1">Choose which files to include in your commit</p>
                    </div>
                    <div class="px-6 py-4 max-h-96 overflow-y-auto">
                        <div class="mb-4 p-3 border ${themeClasses.container} rounded">
                            <label class="flex items-center text-sm font-medium cursor-pointer ${themeClasses.label}">
                                <input type="checkbox" id="select-all-files" class="mr-2">
                                Select All Files
                            </label>
                        </div>
                        <div id="file-list-container" class="border ${themeClasses.container} rounded p-2">
                        </div>
                    </div>
                    <div class="px-6 py-4 border-t ${themeClasses.header} flex justify-end gap-3">
                        <button id="cancel-file-selection" class="${themeClasses.cancelBtn} transition rounded-md px-4 py-2 text-sm border">
                            Cancel
                        </button>
                        <button id="proceed-with-commit" class="bg-blue-600 hover:bg-blue-700 text-white transition rounded-md px-4 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed" disabled>
                            🚀 Generate & Send to Chat
                        </button>
                    </div>
                </div>
            </div>
        `;

        const modalContainer = document.createElement('div');
        modalContainer.innerHTML = modalHtml;
        document.body.appendChild(modalContainer.firstElementChild);
        
        populateFileList(changedFiles, isDark);
        
        const modal = document.getElementById('git-file-selection-modal');
        if (modal) {
            modal.style.display = 'flex';
            setupModalEventListeners();
        }
        
    } catch (e) {
        console.error('Error showing fallback modal:', e);
    }
}

// Function to populate the file list in the modal
function populateFileList(changedFiles, isDark = false) {
    const container = document.getElementById('file-list-container');
    if (!container) return;
    
    // Clear existing content
    container.innerHTML = '';
    
    console.log('Raw changed files received:', changedFiles);
    
    // Parse changed files (format: "M\tfile1.txt\nA\tfile2.txt")
    const fileLines = changedFiles.trim().split('\n').filter(line => line.trim());
    
    console.log('Parsed file lines:', fileLines);
    
    if (fileLines.length === 0) {
        container.innerHTML = '<p class="text-center text-gray-500 py-4">No changed files found</p>';
        return;
    }
    
    fileLines.forEach((line, index) => {
        const parts = line.split('\t');
        console.log('Processing line:', line, 'Parts:', parts);
        
        if (parts.length < 2) {
            console.warn('Skipping invalid line:', line);
            return;
        }
        
        const status = parts[0].trim();
        const filePath = parts[1].trim();
        
        console.log('File:', {status, filePath});
        
        // Create file item with proper theming
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';
        
        const statusLabel = getStatusLabel(status);
        const statusColor = getStatusColor(status);
        const itemBg = isDark ? 'bg-gray-700 border-gray-600' : 'bg-gray-50 border-gray-300';
        const textColor = isDark ? 'text-gray-200' : 'text-gray-800';
        
        fileItem.innerHTML = `
            <div class="flex items-center p-3 m-1 border rounded ${itemBg}">
                <input type="checkbox" class="file-checkbox mr-3" data-file-path="${filePath}" data-status="${status}" id="file-${index}">
                <span class="file-status font-mono font-bold mr-3 px-2 py-1 rounded text-xs" style="background-color: ${statusColor.bg}; color: ${statusColor.text};">${statusLabel}</span>
                <label for="file-${index}" class="file-path font-mono text-sm cursor-pointer flex-1 ${textColor}" title="${filePath}">${filePath}</label>
            </div>
        `;
        
        container.appendChild(fileItem);
    });
    
    // Update proceed button state
    updateProceedButtonState();
}

// Helper function to get status color
function getStatusColor(status) {
    switch (status) {
        case 'M': return { bg: '#fef3c7', text: '#92400e' }; // Yellow
        case 'A': return { bg: '#dcfce7', text: '#166534' }; // Green
        case 'D': return { bg: '#fee2e2', text: '#991b1b' }; // Red
        case 'R': return { bg: '#e0e7ff', text: '#3730a3' }; // Blue
        default: return { bg: '#f3f4f6', text: '#374151' }; // Gray
    }
}

// Helper function to get status label
function getStatusLabel(status) {
    switch (status) {
        case 'M': return 'M';
        case 'A': return 'A';
        case 'D': return 'D';
        case 'R': return 'R';
        default: return status;
    }
}

// Helper function to get status CSS class
function getStatusClass(status) {
    // This function is now replaced by getStatusColor
    return '';
}

// Function to set up modal event listeners
function setupModalEventListeners() {
    console.log('Setting up modal event listeners');
    
    // Cancel button
    const cancelBtn = document.getElementById('cancel-file-selection');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function(e) {
            e.preventDefault();
            console.log('Cancel button clicked');
            hideFileSelectionModal();
        });
        console.log('Cancel button listener added');
    } else {
        console.error('Cancel button not found!');
    }
    
    // Proceed button
    const proceedBtn = document.getElementById('proceed-with-commit');
    if (proceedBtn) {
        proceedBtn.addEventListener('click', function(e) {
            e.preventDefault();
            console.log('Proceed button clicked');
            handleProceedWithCommit();
        });
        console.log('Proceed button listener added');
    } else {
        console.error('Proceed button not found!');
    }
    
    // Select all checkbox
    const selectAllBtn = document.getElementById('select-all-files');
    if (selectAllBtn) {
        selectAllBtn.addEventListener('change', function(e) {
            console.log('Select all changed:', e.target.checked);
            handleSelectAllChange(e);
        });
        console.log('Select all listener added');
    } else {
        console.error('Select all checkbox not found!');
    }
    
    // Individual file checkboxes - use event delegation since they're added dynamically
    const container = document.getElementById('file-list-container');
    if (container) {
        container.addEventListener('change', function(e) {
            if (e.target.classList.contains('file-checkbox')) {
                console.log('File checkbox changed:', e.target.dataset.filePath, e.target.checked);
                updateProceedButtonState();
            }
        });
        console.log('File checkbox listeners added via delegation');
    } else {
        console.error('File list container not found!');
    }
    
    // Modal backdrop click to close
    const modal = document.getElementById('git-file-selection-modal');
    if (modal) {
        modal.addEventListener('click', function(event) {
            if (event.target === modal) {
                console.log('Modal backdrop clicked');
                hideFileSelectionModal();
            }
        });
        console.log('Modal backdrop listener added');
    } else {
        console.error('Modal not found!');
    }
    
    // ESC key to close modal
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && document.getElementById('git-file-selection-modal')) {
            console.log('ESC key pressed, closing modal');
            hideFileSelectionModal();
        }
    });
    
    console.log('All modal event listeners set up successfully');
}

// Function to handle select all checkbox
function handleSelectAllChange(event) {
    const isChecked = event.target.checked;
    const fileCheckboxes = document.querySelectorAll('.file-checkbox');
    
    fileCheckboxes.forEach(checkbox => {
        checkbox.checked = isChecked;
    });
    
    updateProceedButtonState();
}

// Function to update proceed button state
function updateProceedButtonState() {
    const proceedBtn = document.getElementById('proceed-with-commit');
    const fileCheckboxes = document.querySelectorAll('.file-checkbox');
    const selectedFiles = Array.from(fileCheckboxes).filter(cb => cb.checked);
    
    if (proceedBtn) {
        proceedBtn.disabled = selectedFiles.length === 0;
    }
    
    // Update select all checkbox state
    const selectAllBtn = document.getElementById('select-all-files');
    if (selectAllBtn) {
        if (selectedFiles.length === 0) {
            selectAllBtn.indeterminate = false;
            selectAllBtn.checked = false;
        } else if (selectedFiles.length === fileCheckboxes.length) {
            selectAllBtn.indeterminate = false;
            selectAllBtn.checked = true;
        } else {
            selectAllBtn.indeterminate = true;
        }
    }
}

// Function to handle proceed with commit
function handleProceedWithCommit() {
    console.log('Proceeding with commit...');
    
    try {
        // Get selected files
        const selectedCheckboxes = document.querySelectorAll('.file-checkbox:checked');
        const selectedFiles = Array.from(selectedCheckboxes).map(cb => ({
            path: cb.dataset.filePath,
            status: cb.dataset.status
        }));
        
        console.log('Found', selectedCheckboxes.length, 'selected checkboxes');
        console.log('Selected files for commit:', selectedFiles);
        
        if (selectedFiles.length === 0) {
            console.error('No files selected');
            alert('Please select at least one file to commit.');
            return;
        }
        
        // Hide modal first
        hideFileSelectionModal();
        
        // Send selected files to Java via bridge
        if (window.intellijBridge && window.intellijBridge.callIDE) {
            console.log('Calling IDE bridge with selected files...');
            window.intellijBridge.callIDE('filesSelectedForCommit', {
                selectedFiles: selectedFiles
            }).then(function(response) {
                console.log('Selected files sent to IDE successfully:', response);
            }).catch(function(error) {
                console.error('Failed to send selected files to IDE:', error);
                alert('Failed to send files to IDE: ' + error.message);
            });
        } else {
            console.error('IntelliJ Bridge not found');
            alert('IntelliJ Bridge not available. Please check the connection.');
        }
        
    } catch (e) {
        console.error('Error handling proceed with commit:', e);
        alert('Error processing commit: ' + e.message);
    }
}

// Function to hide file selection modal
function hideFileSelectionModal() {
    console.log('Hiding file selection modal');
    
    const modal = document.getElementById('git-file-selection-modal');
    if (modal) {
        modal.remove();
    }
}

// Ensure automatic copy flag is set
window.shouldAutomaticallyCopy = true;

console.log('Compatible code extractor function initialized with collapsed code block support, To IDE button injection, and Git file selection modal');