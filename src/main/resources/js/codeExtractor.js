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
// Function to show file selection modal with changed files
window.showFileSelectionModal = function(changedFiles) {
    console.log('Showing file selection modal with files:', changedFiles);

    try {
        // Detect current theme from document
        const isDark = document.documentElement.classList.contains('dark') ||
                      document.body.classList.contains('dark') ||
                      window.matchMedia('(prefers-color-scheme: dark)').matches;

        console.log('Detected theme:', isDark ? 'dark' : 'light');

        // Create beautiful modal with pure CSS
        const modalHtml = `
            <div id="git-file-selection-modal" style="
                position: fixed;
                top: 0;
                left: 0;
                right: 0;
                bottom: 0;
                background: rgba(0, 0, 0, 0.7);
                backdrop-filter: blur(8px);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 9999;
                animation: fadeIn 0.3s ease-out;
            ">
                <div style="
                    background: ${isDark ? 'linear-gradient(135deg, #1f2937 0%, #111827 100%)' : 'linear-gradient(135deg, #ffffff 0%, #f8fafc 100%)'};
                    border-radius: 20px;
                    box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5), 0 0 0 1px ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.05)'};
                    max-width: 600px;
                    width: 90%;
                    max-height: 85vh;
                    overflow: hidden;
                    color: ${isDark ? '#f3f4f6' : '#1f2937'};
                    animation: slideUp 0.3s ease-out;
                    position: relative;
                ">
                    <!-- Decorative Header Background -->
                    <div style="
                        position: absolute;
                        top: 0;
                        left: 0;
                        right: 0;
                        height: 120px;
                        background: linear-gradient(135deg, ${isDark ? '#3b82f6' : '#6366f1'} 0%, ${isDark ? '#1d4ed8' : '#8b5cf6'} 100%);
                        opacity: 0.1;
                        border-radius: 20px 20px 0 0;
                    "></div>

                    <!-- Modal Header -->
                    <div style="
                        padding: 32px 32px 24px 32px;
                        border-bottom: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                        position: relative;
                    ">
                        <div style="
                            display: flex;
                            align-items: center;
                            margin-bottom: 8px;
                        ">
                            <div style="
                                width: 48px;
                                height: 48px;
                                background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%);
                                border-radius: 12px;
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                margin-right: 16px;
                                box-shadow: 0 8px 16px rgba(59, 130, 246, 0.3);
                            ">
                                <svg width="24" height="24" fill="white" viewBox="0 0 24 24">
                                    <path d="M12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2zm-1 15l-5-5 1.41-1.41L11 14.17l7.59-7.59L20 8l-9 9z"/>
                                </svg>
                            </div>
                            <div>
                                <h3 style="
                                    font-size: 24px;
                                    font-weight: 700;
                                    margin: 0;
                                    background: linear-gradient(135deg, ${isDark ? '#f3f4f6' : '#1f2937'} 0%, ${isDark ? '#9ca3af' : '#6b7280'} 100%);
                                    -webkit-background-clip: text;
                                    -webkit-text-fill-color: transparent;
                                    background-clip: text;
                                ">Select Files to Commit</h3>
                                <p style="
                                    margin: 4px 0 0 0;
                                    font-size: 14px;
                                    color: ${isDark ? '#9ca3af' : '#6b7280'};
                                    font-weight: 500;
                                ">Choose which files to include in your commit</p>
                            </div>
                        </div>
                    </div>

                    <!-- File List -->
                    <div style="
                        padding: 24px 32px;
                        max-height: 400px;
                        overflow-y: auto;
                        scrollbar-width: thin;
                        scrollbar-color: ${isDark ? '#4b5563 transparent' : '#d1d5db transparent'};
                    ">
                        <div style="
                            margin-bottom: 20px;
                            padding: 16px;
                            background: ${isDark ? 'rgba(59, 130, 246, 0.1)' : 'rgba(99, 102, 241, 0.05)'};
                            border: 1px solid ${isDark ? 'rgba(59, 130, 246, 0.2)' : 'rgba(99, 102, 241, 0.1)'};
                            border-radius: 12px;
                            backdrop-filter: blur(10px);
                        ">
                            <label style="
                                display: flex;
                                align-items: center;
                                font-size: 14px;
                                font-weight: 600;
                                cursor: pointer;
                                color: ${isDark ? '#e5e7eb' : '#374151'};
                            ">
                                <input type="checkbox" id="select-all-files" style="
                                    margin-right: 12px;
                                    width: 18px;
                                    height: 18px;
                                    accent-color: #3b82f6;
                                    cursor: pointer;
                                ">
                                <span style="
                                    background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%);
                                    -webkit-background-clip: text;
                                    -webkit-text-fill-color: transparent;
                                    background-clip: text;
                                ">Select All Files</span>
                            </label>
                        </div>
                        <div id="file-list-container" style="
                            border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                            border-radius: 16px;
                            padding: 8px;
                            background: ${isDark ? 'rgba(0, 0, 0, 0.2)' : 'rgba(255, 255, 255, 0.5)'};
                            backdrop-filter: blur(10px);
                        ">
                            <!-- Files will be populated here -->
                        </div>
                    </div>

                    <!-- Modal Footer -->
                    <div style="
                        padding: 24px 32px 32px 32px;
                        border-top: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                        display: flex;
                        justify-content: flex-end;
                        gap: 12px;
                        background: ${isDark ? 'rgba(0, 0, 0, 0.1)' : 'rgba(255, 255, 255, 0.3)'};
                        backdrop-filter: blur(10px);
                    ">
                        <button id="cancel-file-selection" style="
                            background: ${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(243, 244, 246, 0.8)'};
                            color: ${isDark ? '#e5e7eb' : '#374151'};
                            border: 1px solid ${isDark ? 'rgba(156, 163, 175, 0.3)' : 'rgba(209, 213, 219, 0.5)'};
                            padding: 12px 24px;
                            border-radius: 12px;
                            font-size: 14px;
                            font-weight: 600;
                            cursor: pointer;
                            transition: all 0.2s ease;
                            backdrop-filter: blur(10px);
                        " onmouseover="
                            this.style.background = '${isDark ? 'rgba(107, 114, 128, 0.9)' : 'rgba(229, 231, 235, 0.9)'}';
                            this.style.transform = 'translateY(-1px)';
                        " onmouseout="
                            this.style.background = '${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(243, 244, 246, 0.8)'}';
                            this.style.transform = 'translateY(0)';
                        ">
                            Cancel
                        </button>
                        <button id="commit-only-btn" disabled style="
                            background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                            color: white;
                            border: none;
                            padding: 12px 24px;
                            border-radius: 12px;
                            font-size: 14px;
                            font-weight: 600;
                            cursor: pointer;
                            transition: all 0.2s ease;
                            box-shadow: 0 4px 12px rgba(16, 185, 129, 0.3);
                            opacity: 0.5;
                        " onmouseover="
                            if (!this.disabled) {
                                this.style.transform = 'translateY(-2px)';
                                this.style.boxShadow = '0 8px 20px rgba(16, 185, 129, 0.4)';
                            }
                        " onmouseout="
                            if (!this.disabled) {
                                this.style.transform = 'translateY(0)';
                                this.style.boxShadow = '0 4px 12px rgba(16, 185, 129, 0.3)';
                            }
                        ">
                            📝 Write Msg & Commit
                        </button>
                        <button id="commit-and-push-btn" disabled style="
                            background: linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%);
                            color: white;
                            border: none;
                            padding: 12px 24px;
                            border-radius: 12px;
                            font-size: 14px;
                            font-weight: 600;
                            cursor: pointer;
                            transition: all 0.2s ease;
                            box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
                            opacity: 0.5;
                        " onmouseover="
                            if (!this.disabled) {
                                this.style.transform = 'translateY(-2px)';
                                this.style.boxShadow = '0 8px 20px rgba(59, 130, 246, 0.4)';
                            }
                        " onmouseout="
                            if (!this.disabled) {
                                this.style.transform = 'translateY(0)';
                                this.style.boxShadow = '0 4px 12px rgba(59, 130, 246, 0.3)';
                            }
                        ">
                            🚀 Write Msg, Commit & Push
                        </button>
                    </div>
                </div>
            </div>

            <style>
                @keyframes fadeIn {
                    from { opacity: 0; }
                    to { opacity: 1; }
                }

                @keyframes slideUp {
                    from {
                        opacity: 0;
                        transform: translateY(20px) scale(0.95);
                    }
                    to {
                        opacity: 1;
                        transform: translateY(0) scale(1);
                    }
                }

                #file-list-container::-webkit-scrollbar {
                    width: 6px;
                }

                #file-list-container::-webkit-scrollbar-track {
                    background: transparent;
                }

                #file-list-container::-webkit-scrollbar-thumb {
                    background: ${isDark ? '#4b5563' : '#d1d5db'};
                    border-radius: 3px;
                }

                #file-list-container::-webkit-scrollbar-thumb:hover {
                    background: ${isDark ? '#6b7280' : '#9ca3af'};
                }
            </style>
        `;

        // Inject modal HTML into the page
        const modalContainer = document.createElement('div');
        modalContainer.innerHTML = modalHtml;
        document.body.appendChild(modalContainer.firstElementChild);

        // Populate the file list
        populateFileList(changedFiles, isDark);

        // Show the modal
        const modal = document.getElementById('git-file-selection-modal');
        if (modal) {
            modal.style.display = 'flex';

            // Set up event listeners
            setupModalEventListeners();
        }

        console.log('File selection modal displayed successfully');

    } catch (e) {
        console.error('Error showing file selection modal:', e);
    }
};

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
        container.innerHTML = `<p style="
            text-align: center;
            color: ${isDark ? '#9ca3af' : '#6b7280'};
            padding: 32px;
            font-style: italic;
        ">No changed files found</p>`;
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

        // Create file item with beautiful styling
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';

        const statusLabel = getStatusLabel(status);
        const statusColor = getStatusColor(status);

        fileItem.innerHTML = `
            <div style="
                display: flex;
                align-items: center;
                padding: 16px;
                margin: 8px;
                background: ${isDark ? 'rgba(55, 65, 81, 0.6)' : 'rgba(255, 255, 255, 0.8)'};
                border: 1px solid ${isDark ? 'rgba(75, 85, 99, 0.4)' : 'rgba(229, 231, 235, 0.6)'};
                border-radius: 12px;
                transition: all 0.2s ease;
                backdrop-filter: blur(10px);
                cursor: pointer;
            " onmouseover="
                this.style.background = '${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(255, 255, 255, 0.95)'}';
                this.style.transform = 'translateX(4px)';
                this.style.borderColor = '${isDark ? 'rgba(59, 130, 246, 0.4)' : 'rgba(99, 102, 241, 0.3)'}';
            " onmouseout="
                this.style.background = '${isDark ? 'rgba(55, 65, 81, 0.6)' : 'rgba(255, 255, 255, 0.8)'}';
                this.style.transform = 'translateX(0)';
                this.style.borderColor = '${isDark ? 'rgba(75, 85, 99, 0.4)' : 'rgba(229, 231, 235, 0.6)'}';
            ">
                <input type="checkbox" class="file-checkbox" data-file-path="${filePath}" data-status="${status}" id="file-${index}" style="
                    margin-right: 16px;
                    width: 18px;
                    height: 18px;
                    accent-color: #3b82f6;
                    cursor: pointer;
                ">
                <span style="
                    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
                    font-weight: 700;
                    margin-right: 16px;
                    padding: 6px 12px;
                    border-radius: 8px;
                    font-size: 12px;
                    background: ${statusColor.bg};
                    color: ${statusColor.text};
                    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
                    min-width: 24px;
                    text-align: center;
                ">${statusLabel}</span>
                <label for="file-${index}" style="
                    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
                    font-size: 13px;
                    cursor: pointer;
                    flex: 1;
                    color: ${isDark ? '#e5e7eb' : '#374151'};
                    font-weight: 500;
                " title="${filePath}">${filePath}</label>
            </div>
        `;

        container.appendChild(fileItem);
    });

    // Update proceed button state
    updateProceedButtonState();
}

// Function to update proceed button state
function updateProceedButtonState() {
    const commitOnlyBtn = document.getElementById('commit-only-btn');
    const commitAndPushBtn = document.getElementById('commit-and-push-btn');
    const fileCheckboxes = document.querySelectorAll('.file-checkbox');
    const selectedFiles = Array.from(fileCheckboxes).filter(cb => cb.checked);

    const hasSelection = selectedFiles.length > 0;

    if (commitOnlyBtn) {
        commitOnlyBtn.disabled = !hasSelection;
        commitOnlyBtn.style.opacity = hasSelection ? '1' : '0.5';
        commitOnlyBtn.style.cursor = hasSelection ? 'pointer' : 'not-allowed';
    }

    if (commitAndPushBtn) {
        commitAndPushBtn.disabled = !hasSelection;
        commitAndPushBtn.style.opacity = hasSelection ? '1' : '0.5';
        commitAndPushBtn.style.cursor = hasSelection ? 'pointer' : 'not-allowed';
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

    // Commit only button
    const commitOnlyBtn = document.getElementById('commit-only-btn');
    if (commitOnlyBtn) {
        commitOnlyBtn.addEventListener('click', function(e) {
            e.preventDefault();
            console.log('Commit only button clicked');
            handleCommitAction(false); // false = don't push
        });
        console.log('Commit only button listener added');
    } else {
        console.error('Commit only button not found!');
    }

    // Commit and push button
    const commitAndPushBtn = document.getElementById('commit-and-push-btn');
    if (commitAndPushBtn) {
        commitAndPushBtn.addEventListener('click', function(e) {
            e.preventDefault();
            console.log('Commit and push button clicked');
            handleCommitAction(true); // true = also push
        });
        console.log('Commit and push button listener added');
    } else {
        console.error('Commit and push button not found!');
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


// Function to handle commit actions (with or without push)
function handleCommitAction(shouldPush) {
    console.log(`Proceeding with commit${shouldPush ? ' and push' : ''}...`);

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
            console.log('Calling IDE bridge with selected files and push flag...');

            // Use different action names based on whether we should push
            const actionName = shouldPush ? 'filesSelectedForCommitAndPush' : 'filesSelectedForCommit';

            window.intellijBridge.callIDE(actionName, {
                selectedFiles: selectedFiles,
                shouldPush: shouldPush
            }).then(function(response) {
                console.log(`Selected files sent to IDE successfully for ${shouldPush ? 'commit and push' : 'commit only'}:`, response);
            }).catch(function(error) {
                console.error('Failed to send selected files to IDE:', error);
                alert('Failed to send files to IDE: ' + error.message);
            });
        } else {
            console.error('IntelliJ Bridge not found');
            alert('IntelliJ Bridge not available. Please check the connection.');
        }

    } catch (e) {
        console.error('Error handling commit action:', e);
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

// Backward compatibility function (kept for existing code that might call it)
function handleProceedWithCommit() {
    console.log('Legacy handleProceedWithCommit called, defaulting to commit only');
    handleCommitAction(false);
}

// Ensure automatic copy flag is set
window.shouldAutomaticallyCopy = true;

console.log('Compatible code extractor function initialized with collapsed code block support, To IDE button injection, and Git file selection modal with dual commit options');