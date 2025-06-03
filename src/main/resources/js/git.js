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

        // Update commit button
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

        // Write message button - now generates commit message
        const writeMsgBtn = document.getElementById('write-msg-btn');
        if (writeMsgBtn) {
            writeMsgBtn.addEventListener('click', function(e) {
                e.preventDefault();
                console.log('Write message button clicked');
                GitModal.generateCommitMessage();
            });
        }

        // Commit only button
        const commitOnlyBtn = document.getElementById('commit-only-btn');
        if (commitOnlyBtn) {
            commitOnlyBtn.addEventListener('click', function(e) {
                e.preventDefault();
                console.log('Commit button clicked');
                GitModal.handleCommit();
            });
        }

        // Push button
        const pushBtn = document.getElementById('push-btn');
        if (pushBtn) {
            pushBtn.addEventListener('click', function(e) {
                e.preventDefault();
                console.log('Push button clicked');
                GitModal.handlePush();
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

        // Operation status close button
        const statusCloseBtn = document.getElementById('operation-status-close');
        if (statusCloseBtn) {
            statusCloseBtn.addEventListener('click', function(e) {
                e.preventDefault();
                console.log('Status close button clicked');
                GitUI.hideOperationStatus();
                // Hide the entire modal if operation was successful
                const statusTitle = document.getElementById('operation-status-title');
                if (statusTitle && (statusTitle.textContent.includes('Success') || statusTitle.textContent.includes('Successful'))) {
//                    GitModal.hideModal();
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
     * Generate commit message using OpenWebUI API
     */
   generateCommitMessage: async function() {
       console.log('Generating commit message...');

       // Get selected files
       const selectedCheckboxes = document.querySelectorAll('.file-checkbox:checked');
       const selectedFiles = Array.from(selectedCheckboxes).map(cb => ({
           path: cb.dataset.filePath,
           status: cb.dataset.status
       }));

       if (selectedFiles.length === 0) {
           alert('Please select at least one file to generate a commit message.');
           return;
       }

       // Show loading state
       const messageInput = document.getElementById('commit-message-input');
       const writeMsgBtn = document.getElementById('write-msg-btn');
       const originalBtnText = writeMsgBtn.textContent;

       messageInput.disabled = true;
       messageInput.placeholder = 'Generating commit message...';
       writeMsgBtn.disabled = true;
       writeMsgBtn.textContent = '✨';
       writeMsgBtn.style.minWidth = '';  // No minimum width needed
       writeMsgBtn.style.display = 'flex';
       writeMsgBtn.style.alignItems = 'center';
       writeMsgBtn.style.justifyContent = 'center';
       writeMsgBtn.style.fontSize = '20px';  // Make emoji bigger during generation
       writeMsgBtn.classList.add('sparkle-animation');  // Add animation

       try {
           // Get diffs for selected files
           const diffs = await this.getFileDiffs(selectedFiles);

           // Build prompt
           const prompt = this.buildCommitPrompt(selectedFiles, diffs);

           // Call OpenWebUI API
           let commitMessage = await this.callOpenWebUIAPI(prompt);
           console.log("raw resp from commit msg", commitMessage);

           // Handle code blocks properly
           // Check if the entire message is wrapped in code blocks
           const isEntireMessageCodeBlock = /^\s*```[\s\S]*```\s*$/.test(commitMessage);

           if (isEntireMessageCodeBlock) {
               // If the entire message is a code block, just remove the outer triple backticks
               // and any language identifier that might be after the opening backticks
               commitMessage = commitMessage.replace(/^\s*```(?:\w*\n?)/, '').replace(/```\s*$/, '');
           } else {
               // Only remove embedded code blocks if they're not the entire message
               commitMessage = commitMessage.replace(/(```[\s\S]*?\r?\n?```)/g, '');
           }

           // Clean up the message while preserving intentional line breaks
           commitMessage = commitMessage.split('\n')
               .map(line => line.trim()) // Trim each line
               .filter(line => line) // Remove empty lines
               .join('\n'); // Join with newlines

           // Trim extra whitespace at beginning and end
           commitMessage = commitMessage.trim();
           console.log("Processed commit message:", commitMessage);

           // Update UI with generated message
           messageInput.value = commitMessage;
           messageInput.disabled = false;
           messageInput.placeholder = 'Enter commit message...';

           // Auto-resize textarea
           messageInput.style.height = 'auto';
           messageInput.style.height = Math.min(messageInput.scrollHeight, 120) + 'px';

       } catch (error) {
           console.error('Error generating commit message:', error);
           alert('Failed to generate commit message: ' + error.message);
           messageInput.disabled = false;
           messageInput.placeholder = 'Enter commit message...';
       } finally {
           writeMsgBtn.disabled = false;
           writeMsgBtn.textContent = originalBtnText || '✨';  // Fallback to just the sparkle if original text is empty
           writeMsgBtn.style.minWidth = '';  // Reset min-width
           writeMsgBtn.style.display = '';   // Reset display
           writeMsgBtn.style.alignItems = ''; // Reset alignItems
           writeMsgBtn.style.justifyContent = ''; // Reset justifyContent
           writeMsgBtn.style.fontSize = '';  // Reset font size
           writeMsgBtn.classList.remove('sparkle-animation');  // Remove animation
       }
   },

    /**
     * Get diffs for selected files
     */
    getFileDiffs: async function(selectedFiles) {
        const diffs = {};

        for (const file of selectedFiles) {
            try {
                const response = await window.intellijBridge.callIDE('getFileDiff', {
                    filePath: file.path,
                    status: file.status
                });

                if (response && response.diff) {
                    diffs[file.path] = response.diff;
                }
            } catch (error) {
                console.error('Error getting diff for', file.path, error);
                diffs[file.path] = 'Error getting diff';
            }
        }

        return diffs;
    },

 /**
  * Build a comprehensive commit prompt that follows a structured long-format style
  * @param {Array} selectedFiles - Array of file objects with path and status
  * @param {Object} diffs - Object with file paths as keys and diff content as values
  * @return {String} - The formatted commit prompt
  */
 buildCommitPrompt: function(selectedFiles, diffs) {
     let prompt = "Generate a detailed git commit message following a structured long-format style:\n\n";

     // Add file list with better organization
     prompt += "## Changed files:\n";

     // Group files by status for better organization
     const filesByStatus = {
         'M': [], 'A': [], 'D': [], 'R': [], 'C': [], 'U': []
     };

     selectedFiles.forEach(file => {
         const status = file.status || 'U'; // Default to 'U' for unknown
         if (filesByStatus[status]) {
             filesByStatus[status].push(file.path);
         } else {
             filesByStatus['U'].push(file.path);
         }
     });

     const statusMap = {
         'M': 'Modified',
         'A': 'Added',
         'D': 'Deleted',
         'R': 'Renamed',
         'C': 'Copied',
         'U': 'Other'
     };

     // Output files grouped by status
     Object.entries(filesByStatus).forEach(([status, files]) => {
         if (files.length > 0) {
             prompt += `\n### ${statusMap[status]} files:\n`;
             files.forEach(path => prompt += `- ${path}\n`);
         }
     });

     // Add diffs with better formatting
     prompt += "\n## File changes:\n";
     Object.entries(diffs).forEach(([path, diff]) => {
         prompt += `\n### ${path}\n\`\`\`diff\n`;
         // Limit diff size but keep enough context
         const lines = diff.split('\n').slice(0, 75);
         prompt += lines.join('\n');
         if (diff.split('\n').length > 75) {
             prompt += '\n... (diff truncated for brevity)';
         }
         prompt += '\n```\n';
     });

     // Instructions for structured long commit format
     prompt += `
 ## Commit Message Format Instructions:
 Please follow this structure for the long-format commit message:

 1. First line: Short summary (50-72 chars) following conventional commit format
    - format: <type>(<scope>): <subject>
    - example: feat(auth): implement OAuth2 login

 2. Body: Detailed explanation of what changed and why
    - Separated from summary by a blank line
    - Explain what and why, not how
    - Wrap at 72 characters

 3. Footer (optional):
    - Breaking changes (BREAKING CHANGE: description)

 Example output format:
 \`\`\`
 feat(user-profile): implement password reset functionality

 Add secure password reset flow with email verification and rate limiting.
 This change improves security by requiring email confirmation before
 allowing password changes.

 - Added PasswordResetController with email verification
 - Implemented rate limiting to prevent brute force attacks
 - Added unit and integration tests

 BREAKING CHANGE: Password reset API endpoint changed from /reset to /users/reset
 \`\`\`

 Please provide ONLY the commit message, no additional explanation.`;

     return prompt;
 },

    /**
     * Call OpenWebUI API
     */
    callOpenWebUIAPI: async function(prompt) {
        // Get current page URL to determine API endpoint
        const currentUrl = window.location.origin;
        const apiUrl = `${currentUrl}/api/chat/completions`;

        console.log('Calling OpenWebUI API at:', apiUrl);
        window.__zest_usage__  = "CHAT_GIT_COMMIT_MESSAGE"
        // Get auth token from cookie
        const authToken = this.getAuthTokenFromCookie();
        if (!authToken) {
            throw new Error('No authentication token found. Please ensure you are logged in.');
        }

        const payload = {
            model: "Qwen2.5-Coder-7B", // Default model, could be made configurable
            messages: [
                {
                    role: "user",
                    content: prompt
                }
            ],
            stream: false,
            temperature: 0.7,
            max_tokens: 200
        };

        const response = await fetch(apiUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${authToken}`
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error(`API request failed: ${response.status} ${response.statusText}`);
        }

        const data = await response.json();

        if (data.choices && data.choices.length > 0 && data.choices[0].message) {
            return data.choices[0].message.content.trim();
        } else {
            throw new Error('Invalid API response format');
        }
    },

    /**
     * Get auth token from cookie
     */
    getAuthTokenFromCookie: function() {
        // OpenWebUI typically stores the token in a cookie named 'token' or similar
        if (document.cookie){
        const cookies = document.cookie.split(';');
        for (const cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === 'token' || name === 'auth-token' || name === 'authorization') {
                return decodeURIComponent(value);
            }
        }
        }

        // Also check localStorage as some implementations use it
        const localToken = localStorage.getItem('token') || localStorage.getItem('auth-token');
        if (localToken) {
            return localToken;
        }

        return null;
    },

    /**
     * Handle commit operation
     */
    handleCommit: function() {
        console.log('Handling commit...');

        const messageInput = document.getElementById('commit-message-input');
        const message = messageInput ? messageInput.value.trim() : '';

        if (!message) {
            alert('Please enter a commit message first.');
            if (messageInput) messageInput.focus();
            return;
        }

        // Get selected files
        const selectedCheckboxes = document.querySelectorAll('.file-checkbox:checked');
        const selectedFiles = Array.from(selectedCheckboxes).map(cb => ({
            path: cb.dataset.filePath,
            status: cb.dataset.status
        }));

        if (selectedFiles.length === 0) {
            alert('Please select at least one file to commit.');
            return;
        }

        console.log('Committing with message:', { message, files: selectedFiles });

        // Disable commit button to prevent multiple submissions
        const commitBtn = document.getElementById('commit-only-btn');
        if (commitBtn) commitBtn.disabled = true;

        // Show initial status message and modal overlay
        GitStatus.showMessage('Starting commit operation...');
        GitUI.showCommitInProgress();

        // Send commit message and selected files to IDE
        if (window.intellijBridge && window.intellijBridge.callIDE) {
            window.intellijBridge.callIDE('commitWithMessage', {
                message: message,
                selectedFiles: selectedFiles,
                shouldPush: false // Always false - push is separate
            }).then(function(response) {
                console.log('Commit request sent to IDE successfully:', response);
                
                // Re-enable commit button after response
                if (commitBtn) commitBtn.disabled = false;
                
                // Update the status display
                if (response && response.success) {
                    GitUI.showCommitSuccess();
                    // Status overlay now has a close button instead of auto-closing the modal
                } else {
                    const errorMsg = response && response.error ? response.error : 'Unknown error occurred during commit';
                    GitUI.showCommitError(errorMsg);
                }
            }).catch(function(error) {
                console.error('Failed to send commit request to IDE:', error);
                
                // Re-enable commit button after error
                if (commitBtn) commitBtn.disabled = false;
                
                // Show error in modal and as a status message
                GitUI.showCommitError(error.message || 'Failed to commit');
                GitStatus.showMessage('Error: ' + (error.message || 'Failed to commit'));
            });
        } else {
            console.error('IntelliJ Bridge not found');
            GitStatus.showMessage('Error: IntelliJ Bridge not available');
            GitUI.showCommitError('IntelliJ Bridge not available. Please check the connection.');
            
            // Re-enable commit button
            if (commitBtn) commitBtn.disabled = false;
        }
    },

    /**
     * Handle push operation
     */
    handlePush: function() {
        console.log('Handling push...');
        
        // Disable push button to prevent multiple submissions
        const pushBtn = document.getElementById('push-btn');
        if (pushBtn) pushBtn.disabled = true;
        
        // Show initial status message and modal overlay
        GitStatus.showMessage('Starting push operation...');
        GitUI.showPushInProgress();
        
        // Call the git push operation in IDE
        if (window.intellijBridge && window.intellijBridge.callIDE) {
            window.intellijBridge.callIDE('gitPush')
                .then(function(response) {
                    console.log('Push request sent to IDE successfully:', response);
                    
                    // Re-enable push button after response
                    if (pushBtn) pushBtn.disabled = false;
                    
                    // Update the status display
                    if (response && response.success) {
//                        GitUI.showPushSuccess();
                        // Status overlay now has a close button instead of auto-closing the modal
                    } else {
                        const errorMsg = response && response.error ? response.error : 'Unknown error occurred during push';
                        GitUI.showPushError(errorMsg);
                    }
                })
                .catch(function(error) {
                    console.error('Failed to send push request to IDE:', error);
                    
                    // Re-enable push button after error
                    if (pushBtn) pushBtn.disabled = false;
                    
                    // Show error in modal and as a status message
                    GitUI.showPushError(error.message || 'Failed to push');
                    GitStatus.showMessage('Error: ' + (error.message || 'Failed to push'));
                });
        } else {
            console.error('IntelliJ Bridge not found');
            GitStatus.showMessage('Error: IntelliJ Bridge not available');
            GitUI.showPushError('IntelliJ Bridge not available. Please check the connection.');
            
            // Re-enable push button
            if (pushBtn) pushBtn.disabled = false;
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
// 2. At the very end of your file, ADD these lines to ensure global accessibility:
// Make sure GitModal methods are accessible globally
window.GitModal = GitModal;

// Explicitly expose methods for the Java bridge
window.GitModal.populateFileList = GitModal.populateFileList.bind(GitModal);
window.GitModal.hideModal = GitModal.hideModal.bind(GitModal);

// 3. Optional: Add a method to check if the modal is currently open
GitModal.isOpen = function() {
    const modal = document.getElementById('git-file-selection-modal');
    return modal && modal.style.display !== 'none';
};

window.GitModal.isOpen = GitModal.isOpen.bind(GitModal);
console.log('Git integration module loaded successfully');