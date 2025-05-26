/**
 * Git Integration Module
 * 
 * Handles all git-related operations including file parsing, 
 * status detection, and communication with the IDE bridge.
 */

// Git file status parsing and utilities
const GitUtils = {
    /**
     * Enhanced robust file parsing
     * Handles multiple git output formats including porcelain, name-status, and short formats
     */
    parseChangedFiles: function(changedFiles) {
        console.log("=== GIT FILE PARSING ===");
        console.log("Raw input:", JSON.stringify(changedFiles));
        
        if (!changedFiles || typeof changedFiles !== 'string') {
            console.error("Invalid input for parsing");
            return [];
        }
        
        // Normalize line endings
        const normalized = changedFiles.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
        console.log("Normalized:", JSON.stringify(normalized));
        
        // Split into lines and filter empty ones
        const lines = normalized.split('\n').filter(line => line.trim().length > 0);
        console.log("Non-empty lines:", lines.length);
        
        const parsedFiles = [];
        
        lines.forEach((line, index) => {
            console.log(`Processing line ${index}: "${line}"`);
            
            const trimmedLine = line.trim();
            if (!trimmedLine) {
                console.log(`  Skipping empty line`);
                return;
            }
            
            let status = '';
            let filePath = '';
            
            // Strategy 1: Tab-separated (git diff --name-status format)
            if (trimmedLine.includes('\t')) {
                const tabParts = trimmedLine.split('\t');
                if (tabParts.length >= 2) {
                    status = tabParts[0].trim();
                    filePath = tabParts.slice(1).join('\t').trim(); // Handle filenames with tabs
                    console.log(`  Tab-parsed: "${status}" | "${filePath}"`);
                }
            }
            
            // Strategy 2: Git status porcelain format (XY filename)
            if (!status && !filePath && trimmedLine.length >= 3) {
                const statusChars = trimmedLine.substring(0, 2);
                const filenamePart = trimmedLine.substring(3);
                
                // Handle untracked files (?? filename)
                if (statusChars === '??') {
                    status = 'A'; // Treat untracked as "Added"
                    filePath = filenamePart.trim();
                    console.log(`  Untracked-parsed: "${status}" | "${filePath}"`);
                } else {
                    // Convert XY format to single status
                    let primaryStatus = '';
                    if (statusChars[1] !== ' ' && /[MADRC]/.test(statusChars[1])) {
                        primaryStatus = statusChars[1]; // Unstaged status
                    } else if (statusChars[0] !== ' ' && /[MADRC]/.test(statusChars[0])) {
                        primaryStatus = statusChars[0]; // Staged status
                    }
                    
                    if (primaryStatus && filenamePart.trim()) {
                        status = primaryStatus;
                        
                        // Special handling for renamed files (R old_name -> new_name)
                        if (primaryStatus === 'R' || primaryStatus === 'C') {
                            if (filenamePart.includes(' -> ')) {
                                const renameParts = filenamePart.split(' -> ');
                                if (renameParts.length === 2) {
                                    filePath = renameParts[1].trim(); // Use new name
                                    console.log(`  Renamed-parsed: "${status}" | "${renameParts[0].trim()}" -> "${filePath}"`);
                                } else {
                                    filePath = filenamePart.trim();
                                    console.log(`  Rename-fallback-parsed: "${status}" | "${filePath}"`);
                                }
                            } else {
                                filePath = filenamePart.trim();
                                console.log(`  Rename-simple-parsed: "${status}" | "${filePath}"`);
                            }
                        } else {
                            filePath = filenamePart.trim();
                            console.log(`  Porcelain-parsed: "${status}" | "${filePath}"`);
                        }
                    }
                }
            }
            
            // Strategy 3: Space-separated (git status --short format)
            if (!status && !filePath) {
                // Look for pattern: STATUS FILENAME or STATUS<space>FILENAME
                const match = trimmedLine.match(/^([MADRC?])\s+(.+)$/);
                if (match) {
                    status = match[1] === '?' ? 'A' : match[1]; // Convert ? to A for untracked
                    filePath = match[2];
                    console.log(`  Regex-parsed: "${status}" | "${filePath}"`);
                } else {
                    // Try simple space split
                    const spaceParts = trimmedLine.split(/\s+/);
                    if (spaceParts.length >= 2 && /^[MADRC?]$/.test(spaceParts[0])) {
                        status = spaceParts[0] === '?' ? 'A' : spaceParts[0]; // Convert ? to A
                        filePath = spaceParts.slice(1).join(' ');
                        console.log(`  Space-parsed: "${status}" | "${filePath}"`);
                    }
                }
            }
            
            // Validate and add
            if (status && filePath && /^[MADRC]$/.test(status)) {
                parsedFiles.push({ status, filePath, originalLine: line });
                console.log(`  ✓ Successfully parsed: ${status} ${filePath}`);
            } else {
                console.warn(`  ✗ Failed to parse line: "${line}"`);
                console.warn(`    Extracted: status="${status}", filePath="${filePath}"`);
            }
        });
        
        console.log(`Successfully parsed ${parsedFiles.length} files from ${lines.length} lines`);
        console.log("=========================");
        
        return parsedFiles;
    },

    /**
     * Get status color for UI display
     */
    getStatusColor: function(status) {
        switch (status) {
            case 'M': return { bg: '#fef3c7', text: '#92400e' }; // Yellow - Modified
            case 'A': return { bg: '#dcfce7', text: '#166534' }; // Green - Added/New
            case 'D': return { bg: '#fee2e2', text: '#991b1b' }; // Red - Deleted
            case 'R': return { bg: '#e0e7ff', text: '#3730a3' }; // Blue - Renamed
            case 'C': return { bg: '#f3e8ff', text: '#7c3aed' }; // Purple - Copied
            default: return { bg: '#f3f4f6', text: '#374151' }; // Gray - Other
        }
    },

    /**
     * Get status label for UI display
     */
    getStatusLabel: function(status) {
        switch (status) {
            case 'M': return 'M'; // Modified
            case 'A': return 'A'; // Added
            case 'D': return 'D'; // Deleted
            case 'R': return 'R'; // Renamed
            case 'C': return 'C'; // Copied
            default: return status;
        }
    },

    /**
     * Get status description for tooltips
     */
    getStatusDescription: function(status) {
        switch (status) {
            case 'M': return 'Modified';
            case 'A': return 'Added/New';
            case 'D': return 'Deleted';
            case 'R': return 'Renamed';
            case 'C': return 'Copied';
            default: return 'Unknown';
        }
    }
};

// Git modal operations
const GitModal = {
    /**
     * Show file selection modal with changed files
     */
    showFileSelectionModal: function(changedFiles) {
        console.log('Showing git file selection modal with files:', changedFiles);

        try {
            // Detect current theme from document
            const isDark = document.documentElement.classList.contains('dark') ||
                          document.body.classList.contains('dark') ||
                          window.matchMedia('(prefers-color-scheme: dark)').matches;

            console.log('Detected theme:', isDark ? 'dark' : 'light');

            // Create modal using GitUI
            const modalHtml = GitUI.createModal(isDark);
            
            // Inject modal HTML into the page
            const modalContainer = document.createElement('div');
            modalContainer.innerHTML = modalHtml;
            document.body.appendChild(modalContainer.firstElementChild);

            // Populate the file list
            this.populateFileList(changedFiles, isDark);

            // Show the modal
            const modal = document.getElementById('git-file-selection-modal');
            if (modal) {
                modal.style.display = 'flex';
                // Set up event listeners
                this.setupModalEventListeners();
            }

            console.log('Git file selection modal displayed successfully');

        } catch (e) {
            console.error('Error showing git file selection modal:', e);
        }
    },

    /**
     * Populate the file list in the modal
     */
    populateFileList: function(changedFiles, isDark = false) {
        console.log("=== POPULATE FILE LIST DEBUG ===");
        console.log("Input changedFiles:", JSON.stringify(changedFiles));
        console.log("Input type:", typeof changedFiles);
        console.log("Input length:", changedFiles ? changedFiles.length : "null");
        
        const container = document.getElementById('file-list-container');
        if (!container) {
            console.error("Container not found!");
            return;
        }

        // Clear existing content
        container.innerHTML = '';

        if (!changedFiles || typeof changedFiles !== 'string') {
            console.error("Invalid changedFiles input:", changedFiles);
            GitUI.showNoFilesMessage(container, isDark);
            return;
        }

        // Robust parsing of changed files
        const parsedFiles = GitUtils.parseChangedFiles(changedFiles);
        
        console.log("Parsed files:", parsedFiles);

        if (parsedFiles.length === 0) {
            console.warn("No files parsed successfully");
            GitUI.showNoFilesMessage(container, isDark);
            return;
        }

        // Create file items
        parsedFiles.forEach((fileInfo, index) => {
            const { status, filePath } = fileInfo;
            console.log(`Creating UI for file ${index}: ${status} ${filePath}`);
            
            const fileItem = GitUI.createFileItem(status, filePath, index, isDark);
            container.appendChild(fileItem);
        });

        // Update proceed button state
        this.updateProceedButtonState();
        console.log("=================================");
    },

    /**
     * Update proceed button state based on selection
     */
    updateProceedButtonState: function() {
        const commitOnlyBtn = document.getElementById('commit-only-btn');
        const fileCheckboxes = document.querySelectorAll('.file-checkbox');
        const selectedFiles = Array.from(fileCheckboxes).filter(cb => cb.checked);

        const hasSelection = selectedFiles.length > 0;

        // Update commit button (removed commit & push button)
        if (commitOnlyBtn) {
            commitOnlyBtn.disabled = !hasSelection;
            commitOnlyBtn.style.opacity = hasSelection ? '1' : '0.5';
            commitOnlyBtn.style.cursor = hasSelection ? 'pointer' : 'not-allowed';
        }

        // Update file count display
        const fileCountDisplay = document.getElementById('file-count-display');
        if (fileCountDisplay) {
            fileCountDisplay.textContent = `${selectedFiles.length} file${selectedFiles.length !== 1 ? 's' : ''} selected`;
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
    },

    /**
     * Set up modal event listeners
     */
    setupModalEventListeners: function() {
        console.log('Setting up git modal event listeners');

        // Cancel button
        const cancelBtn = document.getElementById('cancel-file-selection');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', function(e) {
                e.preventDefault();
                console.log('Cancel button clicked');
                GitModal.hideModal();
            });
        }

        // Commit only button (removed commit & push button)
        const commitOnlyBtn = document.getElementById('commit-only-btn');
        if (commitOnlyBtn) {
            commitOnlyBtn.addEventListener('click', function(e) {
                e.preventDefault();
                console.log('Commit only button clicked');
                GitModal.handleCommitAction(false); // false = don't push
            });
        }

        // Select all checkbox
        const selectAllBtn = document.getElementById('select-all-files');
        if (selectAllBtn) {
            selectAllBtn.addEventListener('change', function(e) {
                console.log('Select all changed:', e.target.checked);
                GitModal.handleSelectAllChange(e);
            });
        }

        // Individual file checkboxes - use event delegation
        const container = document.getElementById('file-list-container');
        if (container) {
            container.addEventListener('change', function(e) {
                if (e.target.classList.contains('file-checkbox')) {
                    console.log('File checkbox changed:', e.target.dataset.filePath, e.target.checked);
                    GitModal.updateProceedButtonState();
                }
            });
        }

        // Modal backdrop click to close
        const modal = document.getElementById('git-file-selection-modal');
        if (modal) {
            modal.addEventListener('click', function(event) {
                if (event.target === modal) {
                    console.log('Modal backdrop clicked');
                    GitModal.hideModal();
                }
            });
        }

        // ESC key to close modal
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && document.getElementById('git-file-selection-modal')) {
                console.log('ESC key pressed, closing modal');
                GitModal.hideModal();
            }
        });
    },

    /**
     * Handle select all checkbox change
     */
    handleSelectAllChange: function(event) {
        const isChecked = event.target.checked;
        const fileCheckboxes = document.querySelectorAll('.file-checkbox');

        fileCheckboxes.forEach(checkbox => {
            checkbox.checked = isChecked;
        });

        this.updateProceedButtonState();
    },

    /**
     * Handle commit actions (only commit, removed push option)
     */
    handleCommitAction: function(shouldPush = false) {
        console.log(`Proceeding with commit...`);

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
            this.hideModal();

            // Send selected files to Java via bridge (always commit only)
            if (window.intellijBridge && window.intellijBridge.callIDE) {
                console.log('Calling IDE bridge with selected files for commit only...');

                window.intellijBridge.callIDE('filesSelectedForCommit', {
                    selectedFiles: selectedFiles,
                    shouldPush: false // Always false since we removed push option
                }).then(function(response) {
                    console.log('Selected files sent to IDE successfully for commit:', response);
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
    },

    /**
     * Hide the modal
     */
    hideModal: function() {
        console.log('Hiding git file selection modal');

        const modal = document.getElementById('git-file-selection-modal');
        if (modal) {
            modal.remove();
        }
    }
};

// Status message functionality
const GitStatus = {
    /**
     * Show status message
     */
    showMessage: function(message) {
        // Remove any existing status message
        const existingStatus = document.getElementById('git-status-message');
        if (existingStatus) {
            existingStatus.remove();
        }

        // Create status message element
        const statusElement = document.createElement('div');
        statusElement.id = 'git-status-message';
        statusElement.textContent = message;

        // Style the status message
        statusElement.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            background: #4CAF50;
            color: white;
            padding: 12px 20px;
            border-radius: 6px;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 14px;
            font-weight: 500;
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
            z-index: 10000;
            opacity: 0;
            transform: translateX(100%);
            transition: all 0.3s ease;
            max-width: 300px;
            word-wrap: break-word;
        `;

        // Add to DOM
        document.body.appendChild(statusElement);

        // Animate in
        setTimeout(() => {
            statusElement.style.opacity = '1';
            statusElement.style.transform = 'translateX(0)';
        }, 10);

        // Auto-remove after 3 seconds with fade out
        setTimeout(() => {
            statusElement.style.opacity = '0';
            statusElement.style.transform = 'translateX(100%)';

            // Remove from DOM after animation
            setTimeout(() => {
                if (statusElement.parentNode) {
                    statusElement.parentNode.removeChild(statusElement);
                }
            }, 300);
        }, 3000);

        console.log('Git Status:', message);
    }
};

// Global API exposure
window.showFileSelectionModal = GitModal.showFileSelectionModal.bind(GitModal);
window.showStatusMessage = GitStatus.showMessage.bind(GitStatus);

console.log('Git integration module loaded successfully');
