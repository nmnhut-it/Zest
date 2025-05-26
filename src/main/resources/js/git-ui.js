/**
 * Git UI Module
 * 
 * Handles all UI-related functionality for git operations including
 * modal templates, file item creation, and visual components.
 */

const GitUI = {
    /**
     * Create the main modal HTML template with horizontal split layout
     */
    createModal: function(isDark = false) {
        return `
            <div id="git-file-selection-modal" style="
                position: fixed;
                top: 0;
                left: 0;
                right: 0;
                bottom: 0;
                background: rgba(0, 0, 0, 0.8);
                backdrop-filter: blur(8px);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 9999;
                animation: fadeIn 0.3s ease-out;
            ">
                <div style="
                    background: ${isDark ? 'linear-gradient(135deg, #1f2937 0%, #111827 100%)' : 'linear-gradient(135deg, #ffffff 0%, #f8fafc 100%)'};
                    border-radius: 12px;
                    box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
                    width: 90vw;
                    max-width: 1000px;
                    height: 80vh;
                    max-height: 700px;
                    overflow: hidden;
                    color: ${isDark ? '#f3f4f6' : '#1f2937'};
                    animation: slideUp 0.3s ease-out;
                    position: relative;
                    display: flex;
                    flex-direction: column;
                    border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.05)'};
                ">
                    ${this.createModalHeader(isDark)}
                    ${this.createModalBody(isDark)}
                    ${this.createModalFooter(isDark)}
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

                #file-list-container::-webkit-scrollbar,
                #diff-viewer::-webkit-scrollbar {
                    width: 6px;
                }

                #file-list-container::-webkit-scrollbar-track,
                #diff-viewer::-webkit-scrollbar-track {
                    background: transparent;
                }

                #file-list-container::-webkit-scrollbar-thumb,
                #diff-viewer::-webkit-scrollbar-thumb {
                    background: ${isDark ? '#4b5563' : '#d1d5db'};
                    border-radius: 3px;
                }

                #file-list-container::-webkit-scrollbar-thumb:hover,
                #diff-viewer::-webkit-scrollbar-thumb:hover {
                    background: ${isDark ? '#6b7280' : '#9ca3af'};
                }
            </style>
        `;
    },

    /**
     * Create modal header
     */
    createModalHeader: function(isDark) {
        return `
            <!-- Modal Header -->
            <div style="
                padding: 16px 20px;
                border-bottom: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                background: ${isDark ? 'rgba(59, 130, 246, 0.08)' : 'rgba(99, 102, 241, 0.05)'};
                display: flex;
                align-items: center;
                flex-shrink: 0;
            ">
                <div style="
                    width: 32px;
                    height: 32px;
                    background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%);
                    border-radius: 8px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin-right: 12px;
                    box-shadow: 0 4px 10px rgba(59, 130, 246, 0.3);
                ">
                    <svg width="16" height="16" fill="white" viewBox="0 0 24 24">
                        <path d="M12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2zm-1 15l-5-5 1.41-1.41L11 14.17l7.59-7.59L20 8l-9 9z"/>
                    </svg>
                </div>
                <div>
                    <h3 style="
                        font-size: 16px;
                        font-weight: 700;
                        margin: 0;
                        color: ${isDark ? '#f3f4f6' : '#1f2937'};
                    ">Select Files to Commit</h3>
                    <p style="
                        margin: 2px 0 0 0;
                        font-size: 12px;
                        color: ${isDark ? '#9ca3af' : '#6b7280'};
                        font-weight: 500;
                    ">Choose files and preview changes before committing</p>
                </div>
            </div>
        `;
    },

    /**
     * Create modal body with horizontal split: files top, diff bottom
     */
    createModalBody: function(isDark) {
        return `
            <!-- Modal Body -->
            <div style="
                display: flex;
                flex-direction: column;
                flex: 1;
                min-height: 0;
                overflow: hidden;
            ">
                <!-- Top Panel - File List -->
                <div style="
                    height: 40%;
                    padding: 16px 20px 8px 20px;
                    display: flex;
                    flex-direction: column;
                    min-height: 0;
                    border-bottom: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                ">
                    <!-- Select All Section -->
                    <div style="
                        margin-bottom: 12px;
                        padding: 8px 12px;
                        background: ${isDark ? 'rgba(59, 130, 246, 0.1)' : 'rgba(99, 102, 241, 0.05)'};
                        border: 1px solid ${isDark ? 'rgba(59, 130, 246, 0.2)' : 'rgba(99, 102, 241, 0.1)'};
                        border-radius: 6px;
                        flex-shrink: 0;
                    ">
                        <label style="
                            display: flex;
                            align-items: center;
                            font-size: 12px;
                            font-weight: 600;
                            cursor: pointer;
                            color: ${isDark ? '#e5e7eb' : '#374151'};
                        ">
                            <input type="checkbox" id="select-all-files" style="
                                margin-right: 6px;
                                width: 14px;
                                height: 14px;
                                accent-color: #3b82f6;
                                cursor: pointer;
                            ">
                            <span>Select All Files</span>
                        </label>
                    </div>

                    <!-- File List Container -->
                    <div id="file-list-container" style="
                        border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                        border-radius: 6px;
                        padding: 6px;
                        background: ${isDark ? 'rgba(0, 0, 0, 0.2)' : 'rgba(255, 255, 255, 0.5)'};
                        backdrop-filter: blur(10px);
                        flex: 1;
                        overflow-y: auto;
                        min-height: 120px;
                    ">
                        <!-- Files will be populated here -->
                    </div>
                </div>

                <!-- Bottom Panel - Diff Viewer -->
                <div style="
                    height: 60%;
                    padding: 8px 20px 16px 20px;
                    display: flex;
                    flex-direction: column;
                    min-height: 0;
                ">
                    <!-- Diff Header -->
                    <div style="
                        margin-bottom: 12px;
                        flex-shrink: 0;
                    ">
                        <h4 style="
                            font-size: 13px;
                            font-weight: 600;
                            margin: 0 0 4px 0;
                            color: ${isDark ? '#e5e7eb' : '#374151'};
                        ">File Changes Preview</h4>
                        <p style="
                            font-size: 11px;
                            color: ${isDark ? '#9ca3af' : '#6b7280'};
                            margin: 0;
                        ">Click on a file to preview its changes</p>
                    </div>

                    <!-- Diff Viewer -->
                    <div id="diff-viewer" style="
                        border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                        border-radius: 6px;
                        background: ${isDark ? '#0d1117' : '#f8fafc'};
                        flex: 1;
                        overflow-y: auto;
                        font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
                        font-size: 11px;
                        line-height: 1.4;
                        padding: 12px;
                        color: ${isDark ? '#e6edf3' : '#24292f'};
                        min-height: 150px;
                    ">
                        <div style="
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            height: 100%;
                            flex-direction: column;
                            color: ${isDark ? '#6b7280' : '#9ca3af'};
                            text-align: center;
                        ">
                            <div style="font-size: 24px; margin-bottom: 8px;">📄</div>
                            <div style="font-size: 12px; font-weight: 500;">Select a file to preview changes</div>
                            <div style="font-size: 10px; margin-top: 4px;">File diffs will appear here</div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    },

    /**
     * Create modal footer (removed commit & push button)
     */
    createModalFooter: function(isDark) {
        return `
            <!-- Modal Footer -->
            <div style="
                padding: 12px 20px 16px 20px;
                border-top: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                display: flex;
                justify-content: space-between;
                align-items: center;
                background: ${isDark ? 'rgba(0, 0, 0, 0.1)' : 'rgba(255, 255, 255, 0.3)'};
                backdrop-filter: blur(10px);
                flex-shrink: 0;
            ">
                <div style="
                    font-size: 11px;
                    color: ${isDark ? '#9ca3af' : '#6b7280'};
                " id="file-count-display">
                    0 files selected
                </div>
                
                <div style="display: flex; gap: 8px;">
                    <button id="cancel-file-selection" style="
                        background: ${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(243, 244, 246, 0.8)'};
                        color: ${isDark ? '#e5e7eb' : '#374151'};
                        border: 1px solid ${isDark ? 'rgba(156, 163, 175, 0.3)' : 'rgba(209, 213, 219, 0.5)'};
                        padding: 6px 12px;
                        border-radius: 6px;
                        font-size: 11px;
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
                        padding: 6px 12px;
                        border-radius: 6px;
                        font-size: 11px;
                        font-weight: 600;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        box-shadow: 0 3px 8px rgba(16, 185, 129, 0.3);
                        opacity: 0.5;
                    " onmouseover="
                        if (!this.disabled) {
                            this.style.transform = 'translateY(-1px)';
                            this.style.boxShadow = '0 4px 12px rgba(16, 185, 129, 0.4)';
                        }
                    " onmouseout="
                        if (!this.disabled) {
                            this.style.transform = 'translateY(0)';
                            this.style.boxShadow = '0 3px 8px rgba(16, 185, 129, 0.3)';
                        }
                    ">
                        📝 Write Message & Commit
                    </button>
                </div>
            </div>
        `;
    },

    /**
     * Create a file item element with shorter paths
     */
    createFileItem: function(status, filePath, index, isDark) {
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';

        const statusLabel = GitUtils.getStatusLabel(status);
        const statusColor = GitUtils.getStatusColor(status);
        const statusDescription = GitUtils.getStatusDescription(status);
        
        // Show only filename, not full path
        const fileName = filePath.split('/').pop() || filePath;

        fileItem.innerHTML = `
            <div class="file-item-content" data-file-path="${filePath}" data-status="${status}" style="
                display: flex;
                align-items: center;
                padding: 8px 12px;
                margin: 3px 0;
                background: ${isDark ? 'rgba(55, 65, 81, 0.6)' : 'rgba(255, 255, 255, 0.8)'};
                border: 1px solid ${isDark ? 'rgba(75, 85, 99, 0.4)' : 'rgba(229, 231, 235, 0.6)'};
                border-radius: 6px;
                transition: all 0.2s ease;
                cursor: pointer;
            " onmouseover="
                this.style.background = '${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(255, 255, 255, 0.95)'}';
                this.style.borderColor = '${isDark ? 'rgba(59, 130, 246, 0.4)' : 'rgba(99, 102, 241, 0.3)'}';
                this.style.transform = 'translateX(2px)';
            " onmouseout="
                this.style.background = '${isDark ? 'rgba(55, 65, 81, 0.6)' : 'rgba(255, 255, 255, 0.8)'}';
                this.style.borderColor = '${isDark ? 'rgba(75, 85, 99, 0.4)' : 'rgba(229, 231, 235, 0.6)'}';
                this.style.transform = 'translateX(0)';
            " onclick="GitUI.selectFileForDiff('${filePath}', '${status}')">
                <input type="checkbox" class="file-checkbox" data-file-path="${filePath}" data-status="${status}" id="file-${index}" style="
                    margin-right: 10px;
                    width: 14px;
                    height: 14px;
                    accent-color: #3b82f6;
                    cursor: pointer;
                " onclick="event.stopPropagation();">
                <span style="
                    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
                    font-weight: 600;
                    margin-right: 10px;
                    padding: 3px 6px;
                    border-radius: 3px;
                    font-size: 10px;
                    background: ${statusColor.bg};
                    color: ${statusColor.text};
                    min-width: 16px;
                    text-align: center;
                " title="${statusDescription}">${statusLabel}</span>
                <label for="file-${index}" style="
                    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
                    font-size: 11px;
                    cursor: pointer;
                    flex: 1;
                    color: ${isDark ? '#e5e7eb' : '#374151'};
                    font-weight: 400;
                " title="${filePath}">${fileName}</label>
            </div>
        `;

        return fileItem;
    },

    /**
     * Show "no files" message
     */
    showNoFilesMessage: function(container, isDark) {
        container.innerHTML = `
            <div style="
                text-align: center;
                color: ${isDark ? '#9ca3af' : '#6b7280'};
                padding: 32px 16px;
                font-style: italic;
                background: ${isDark ? 'rgba(55, 65, 81, 0.3)' : 'rgba(255, 255, 255, 0.5)'};
                border-radius: 6px;
                border: 2px dashed ${isDark ? 'rgba(156, 163, 175, 0.3)' : 'rgba(209, 213, 219, 0.5)'};
            ">
                <div style="font-size: 24px; margin-bottom: 8px;">📁</div>
                <div style="font-size: 12px; font-weight: 500; margin-bottom: 4px;">No changed files found</div>
                <div style="font-size: 10px; opacity: 0.7;">
                    Make sure you have uncommitted changes in your repository
                </div>
            </div>
        `;
    },

    /**
     * Select a file for diff viewing
     */
    selectFileForDiff: function(filePath, status) {
        console.log(`Selecting file for diff: ${filePath} (${status})`);
        
        const diffViewer = document.getElementById('diff-viewer');
        if (!diffViewer) return;

        // Show loading state
        diffViewer.innerHTML = `
            <div style="
                display: flex;
                align-items: center;
                justify-content: center;
                height: 100%;
                flex-direction: column;
                color: #6b7280;
            ">
                <div style="font-size: 20px; margin-bottom: 8px;">⏳</div>
                <div style="font-size: 11px;">Loading diff for ${filePath.split('/').pop()}...</div>
            </div>
        `;

        // Request diff from IDE
        if (window.intellijBridge && window.intellijBridge.callIDE) {
            window.intellijBridge.callIDE('getFileDiff', {
                filePath: filePath,
                status: status
            }).then(function(response) {
                if (response && response.diff) {
                    GitUI.displayDiff(filePath, status, response.diff);
                } else {
                    GitUI.showDiffError(filePath, 'No diff available');
                }
            }).catch(function(error) {
                console.error('Failed to get diff:', error);
                GitUI.showDiffError(filePath, error.message);
            });
        } else {
            GitUI.showDiffError(filePath, 'IDE bridge not available');
        }
    },

    /**
     * Display diff in the viewer
     */
    displayDiff: function(filePath, status, diffContent) {
        const diffViewer = document.getElementById('diff-viewer');
        if (!diffViewer) return;

        const statusDescription = GitUtils.getStatusDescription(status);
        const statusColor = GitUtils.getStatusColor(status);
        const fileName = filePath.split('/').pop() || filePath;

        // Format diff content
        const formattedDiff = this.formatDiffContent(diffContent);

        diffViewer.innerHTML = `
            <div style="margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid rgba(107, 114, 128, 0.2);">
                <div style="display: flex; align-items: center; margin-bottom: 4px;">
                    <span style="
                        font-weight: 600;
                        margin-right: 8px;
                        padding: 2px 6px;
                        border-radius: 3px;
                        font-size: 9px;
                        background: ${statusColor.bg};
                        color: ${statusColor.text};
                        min-width: 16px;
                        text-align: center;
                    ">${status}</span>
                    <span style="font-weight: 600; font-size: 12px;">${fileName}</span>
                </div>
                <div style="font-size: 10px; color: #6b7280;">${statusDescription} • ${filePath}</div>
            </div>
            <div style="
                font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
                font-size: 10px;
                line-height: 1.3;
                white-space: pre-wrap;
                word-break: break-all;
            ">${formattedDiff}</div>
        `;
    },

    /**
     * Show diff error
     */
    showDiffError: function(filePath, errorMessage) {
        const diffViewer = document.getElementById('diff-viewer');
        if (!diffViewer) return;

        const fileName = filePath.split('/').pop() || filePath;

        diffViewer.innerHTML = `
            <div style="
                display: flex;
                align-items: center;
                justify-content: center;
                height: 100%;
                flex-direction: column;
                color: #ef4444;
                text-align: center;
            ">
                <div style="font-size: 20px; margin-bottom: 8px;">⚠️</div>
                <div style="font-weight: 600; margin-bottom: 4px; font-size: 11px;">Cannot load diff</div>
                <div style="font-size: 10px; margin-bottom: 8px;">${fileName}</div>
                <div style="font-size: 9px; opacity: 0.8;">${errorMessage}</div>
            </div>
        `;
    },

    /**
     * Format diff content for display
     */
    formatDiffContent: function(diffContent) {
        if (!diffContent || typeof diffContent !== 'string') {
            return 'No diff content available';
        }

        // Basic diff formatting with color coding
        return diffContent
            .split('\n')
            .map(line => {
                if (line.startsWith('+') && !line.startsWith('+++')) {
                    return `<span style="color: #22c55e; background: rgba(34, 197, 94, 0.1); display: block; padding: 1px 4px;">${this.escapeHtml(line)}</span>`;
                } else if (line.startsWith('-') && !line.startsWith('---')) {
                    return `<span style="color: #ef4444; background: rgba(239, 68, 68, 0.1); display: block; padding: 1px 4px;">${this.escapeHtml(line)}</span>`;
                } else if (line.startsWith('@@')) {
                    return `<span style="color: #3b82f6; font-weight: 600; display: block; padding: 2px 4px; background: rgba(59, 130, 246, 0.1);">${this.escapeHtml(line)}</span>`;
                } else if (line.startsWith('diff --git') || line.startsWith('index ') || line.startsWith('+++') || line.startsWith('---')) {
                    return `<span style="color: #6b7280; font-weight: 500; display: block; padding: 1px 4px;">${this.escapeHtml(line)}</span>`;
                } else {
                    return `<span style="display: block; padding: 1px 4px;">${this.escapeHtml(line)}</span>`;
                }
            })
            .join('');
    },

    /**
     * Escape HTML entities
     */
    escapeHtml: function(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
};

console.log('Git UI module loaded successfully');
