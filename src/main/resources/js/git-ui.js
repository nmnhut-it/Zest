/**
 * Git UI Module
 * 
 * Handles all UI-related functionality for git operations including
 * modal templates, file item creation, and visual components.
 */

const GitUI = {
    /**
     * Create quick commit overlay - streamlined for fast commits
     */
    createQuickCommitOverlay: function(isDark = false) {
        return `
            <div id="quick-commit-overlay" style="
                position: fixed;
                top: 0;
                left: 0;
                right: 0;
                bottom: 0;
                background: rgba(0, 0, 0, 0.85);
                backdrop-filter: blur(10px);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 10000;
                animation: fadeIn 0.2s ease-out;
            ">
                <div id="quick-commit-container" style="
                    background: ${isDark ? 'linear-gradient(135deg, #1a1a1a 0%, #0d0d0d 100%)' : 'linear-gradient(135deg, #ffffff 0%, #f5f5f5 100%)'};
                    border-radius: 16px;
                    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
                    width: 600px;
                    max-width: 90vw;
                    overflow: hidden;
                    color: ${isDark ? '#e0e0e0' : '#333333'};
                    border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)'};
                ">
                    <!-- Header -->
                    <div style="
                        padding: 20px 24px 16px;
                        border-bottom: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    ">
                        <div style="display: flex; align-items: center; gap: 12px;">
                            <h2 style="margin: 0; font-size: 20px; font-weight: 600;">
                                ⚡ Quick Commit & Push
                            </h2>
                            <span style="
                                font-size: 11px;
                                color: ${isDark ? '#888' : '#666'};
                                background: ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.05)'};
                                padding: 3px 8px;
                                border-radius: 4px;
                            ">Ctrl+Shift+Z, C</span>
                        </div>
                        <button id="close-quick-commit" style="
                            background: transparent;
                            border: none;
                            font-size: 24px;
                            cursor: pointer;
                            color: ${isDark ? '#666' : '#999'};
                            padding: 0;
                            width: 32px;
                            height: 32px;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            border-radius: 4px;
                            transition: all 0.2s;
                        " onmouseover="this.style.background='${isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)'}'" 
                          onmouseout="this.style.background='transparent'">×</button>
                    </div>
                    
                    <!-- Message Section -->
                    <div style="padding: 20px 24px;">
                        <div id="commit-status" style="
                            display: flex;
                            align-items: center;
                            gap: 10px;
                            margin-bottom: 16px;
                            color: ${isDark ? '#888' : '#666'};
                            font-size: 14px;
                        ">
                            <span id="status-icon" style="font-size: 20px; animation: pulse 1.5s infinite;">✨</span>
                            <span id="status-text">Analyzing changes...</span>
                        </div>
                        
                        <textarea id="quick-commit-message" style="
                            width: 100%;
                            min-height: 120px;
                            max-height: 300px;
                            padding: 12px 16px;
                            border: 2px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)'};
                            border-radius: 8px;
                            background: ${isDark ? 'rgba(0, 0, 0, 0.3)' : 'rgba(255, 255, 255, 0.8)'};
                            color: ${isDark ? '#e0e0e0' : '#333'};
                            font-family: 'SF Mono', Monaco, 'Cascadia Code', 'Consolas', monospace;
                            font-size: 14px;
                            line-height: 1.5;
                            resize: vertical;
                            outline: none;
                            transition: all 0.2s;
                        " placeholder="Generating commit message..." disabled
                          onfocus="this.style.borderColor='#3b82f6'"
                          onblur="this.style.borderColor='${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)'}'"
                          oninput="GitUI.styleCommitMessage(this)"></textarea>
                        
                        <!-- Files Summary -->
                        <div id="files-summary" style="
                            margin-top: 16px;
                            padding: 10px 14px;
                            background: ${isDark ? 'rgba(59, 130, 246, 0.1)' : 'rgba(59, 130, 246, 0.05)'};
                            border: 1px solid ${isDark ? 'rgba(59, 130, 246, 0.2)' : 'rgba(59, 130, 246, 0.1)'};
                            border-radius: 6px;
                            font-size: 13px;
                            color: ${isDark ? '#93bbfc' : '#2563eb'};
                            display: flex;
                            align-items: center;
                            gap: 8px;
                        ">
                            <span style="font-size: 16px;">📊</span>
                            <span id="files-count-text">Calculating changes...</span>
                        </div>
                    </div>
                    
                    <!-- Actions -->
                    <div style="
                        padding: 16px 24px 20px;
                        background: ${isDark ? 'rgba(0, 0, 0, 0.2)' : 'rgba(0, 0, 0, 0.02)'};
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        gap: 12px;
                    ">
                        <button id="regenerate-message" style="
                            background: ${isDark ? 'rgba(139, 92, 246, 0.2)' : 'rgba(139, 92, 246, 0.1)'};
                            color: ${isDark ? '#a78bfa' : '#7c3aed'};
                            border: 1px solid ${isDark ? 'rgba(139, 92, 246, 0.3)' : 'rgba(139, 92, 246, 0.2)'};
                            padding: 8px 16px;
                            border-radius: 6px;
                            font-size: 14px;
                            font-weight: 500;
                            cursor: pointer;
                            transition: all 0.2s;
                            display: flex;
                            align-items: center;
                            gap: 6px;
                            opacity: 0.5;
                        " disabled>
                            <span style="font-size: 16px;">🔄</span> Regenerate
                        </button>
                        
                        <div style="display: flex; gap: 8px;">
                            <button id="commit-only" style="
                                background: ${isDark ? 'rgba(16, 185, 129, 0.2)' : 'rgba(16, 185, 129, 0.1)'};
                                color: ${isDark ? '#6ee7b7' : '#059669'};
                                border: 1px solid ${isDark ? 'rgba(16, 185, 129, 0.3)' : 'rgba(16, 185, 129, 0.2)'};
                                padding: 8px 16px;
                                border-radius: 6px;
                                font-size: 14px;
                                font-weight: 500;
                                cursor: pointer;
                                transition: all 0.2s;
                                opacity: 0.5;
                            " disabled>
                                Commit
                            </button>
                            
                            <button id="commit-and-push" style="
                                background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
                                color: white;
                                border: none;
                                padding: 8px 20px;
                                border-radius: 6px;
                                font-size: 14px;
                                font-weight: 600;
                                cursor: pointer;
                                transition: all 0.2s;
                                display: flex;
                                align-items: center;
                                gap: 6px;
                                opacity: 0.5;
                                box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
                            " disabled>
                                <span style="font-size: 16px;">🚀</span> Commit & Push
                            </button>
                        </div>
                    </div>
                    
                    <!-- Progress Bar -->
                    <div id="progress-section" style="
                        display: none;
                        padding: 0 24px 20px;
                    ">
                        <div style="
                            background: ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)'};
                            height: 4px;
                            border-radius: 2px;
                            overflow: hidden;
                        ">
                            <div id="progress-bar" style="
                                height: 100%;
                                background: linear-gradient(90deg, #3b82f6, #6366f1);
                                width: 0%;
                                transition: width 0.3s ease;
                            "></div>
                        </div>
                        <div id="progress-text" style="
                            margin-top: 8px;
                            font-size: 12px;
                            color: ${isDark ? '#888' : '#666'};
                            text-align: center;
                        "></div>
                    </div>
                </div>
            </div>
            
            <style>
                @keyframes fadeIn {
                    from { opacity: 0; }
                    to { opacity: 1; }
                }
                @keyframes pulse {
                    0%, 100% { opacity: 1; transform: scale(1); }
                    50% { opacity: 0.6; transform: scale(1.1); }
                }
                
                #quick-commit-overlay * {
                    box-sizing: border-box;
                }
                
                #quick-commit-message::-webkit-scrollbar {
                    width: 8px;
                }
                
                #quick-commit-message::-webkit-scrollbar-track {
                    background: transparent;
                }
                
                #quick-commit-message::-webkit-scrollbar-thumb {
                    background: ${isDark ? 'rgba(255, 255, 255, 0.2)' : 'rgba(0, 0, 0, 0.2)'};
                    border-radius: 4px;
                }
                
                #quick-commit-overlay button:not(:disabled):hover {
                    transform: translateY(-1px);
                    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
                }
                
                #quick-commit-overlay button:disabled {
                    cursor: not-allowed;
                }
            </style>
        `;
    },
    
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
                    height: 85vh;
                    max-height: 800px;
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

                    <!-- Operation Status Display (initially hidden) -->
                    <div id="operation-status" style="
                        position: absolute;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        background: ${isDark ? 'rgba(0, 0, 0, 0.9)' : 'rgba(255, 255, 255, 0.9)'};
                        backdrop-filter: blur(5px);
                        display: none;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        z-index: 10;
                        animation: fadeIn 0.3s ease-out;
                        color: ${isDark ? '#f3f4f6' : '#1f2937'};
                        text-align: center;
                        padding: 0 20px;
                    ">
                        <div id="operation-status-icon" style="font-size: 42px; margin-bottom: 12px;">⏳</div>
                        <div id="operation-status-title" style="font-size: 16px; font-weight: 600; margin-bottom: 6px;">Operation in Progress</div>
                        <div id="operation-status-message" style="font-size: 13px; max-width: 400px; opacity: 0.8; margin-bottom: 20px;">Please wait while we process your request...</div>
                        <button id="operation-status-close" style="
                            background: ${isDark ? '#3b82f6' : '#3b82f6'};
                            color: white;
                            border: none;
                            padding: 6px 14px;
                            border-radius: 5px;
                            font-size: 13px;
                            font-weight: 500;
                            cursor: pointer;
                            transition: all 0.2s ease;
                            display: none;
                        " onmouseover="
                            this.style.transform = 'translateY(-2px)';
                            this.style.boxShadow = '0 4px 12px rgba(59, 130, 246, 0.4)';
                        " onmouseout="
                            this.style.transform = 'translateY(0)';
                            this.style.boxShadow = 'none';
                        ">
                            Done
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
                
                @keyframes sparkle {
                    0% { transform: scale(1); opacity: 1; }
                    50% { transform: scale(1.2); opacity: 0.8; }
                    100% { transform: scale(1); opacity: 1; }
                }
                
                .sparkle-animation {
                    animation: sparkle 1s infinite ease-in-out;
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
     * Create modal header with message textarea and compact Generate button
     */
    createModalHeader: function(isDark) {
        return `
            <!-- Modal Header -->
            <div style="
                padding: 12px 16px;
                border-bottom: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                background: ${isDark ? 'rgba(59, 130, 246, 0.05)' : 'rgba(99, 102, 241, 0.03)'};
                display: flex;
                align-items: center;
                gap: 12px;
                flex-shrink: 0;
            ">
                <!-- Commit Message Input -->
                <div style="flex: 1; display: flex; gap: 8px; align-items: flex-start;">
                        <textarea 
                        id="commit-message-input"
                        placeholder="Enter commit message..."
                        style="
                            flex: 1;
                            min-height: 48px;
                            max-height: 100px;
                            padding: 8px 12px;
                            border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.2)' : 'rgba(0, 0, 0, 0.15)'};
                            border-radius: 6px;
                            background: ${isDark ? 'rgba(0, 0, 0, 0.3)' : 'rgba(255, 255, 255, 0.8)'};
                            color: ${isDark ? '#e5e7eb' : '#374151'};
                            font-size: 14px;
                            font-family: 'SF Mono', Monaco, 'Cascadia Code', 'Consolas', monospace;
                            resize: vertical;
                            outline: none;
                            transition: all 0.2s ease;
                            line-height: 1.5;
                        "
                        onfocus="
                            this.style.borderColor = '#3b82f6';
                            this.style.boxShadow = '0 0 0 3px rgba(59, 130, 246, 0.1)';
                        "
                        onblur="
                            this.style.borderColor = '${isDark ? 'rgba(255, 255, 255, 0.2)' : 'rgba(0, 0, 0, 0.15)'}';
                            this.style.boxShadow = 'none';
                        "
                        oninput="GitUI.styleCommitMessage(this)"
                    ></textarea>
                    
                    <button id="write-msg-btn" title="Generate commit message" style="
                        background: linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%);
                        color: white;
                        border: none;
                        width: 36px;
                        height: 36px;
                        border-radius: 6px;
                        font-size: 16px;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        box-shadow: 0 2px 6px rgba(139, 92, 246, 0.3);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        flex-shrink: 0;
                    " onmouseover="
                        this.style.transform = 'translateY(-1px)';
                        this.style.boxShadow = '0 3px 10px rgba(139, 92, 246, 0.4)';
                    " onmouseout="
                        this.style.transform = 'translateY(0)';
                        this.style.boxShadow = '0 2px 6px rgba(139, 92, 246, 0.3)';
                    ">
                        ✨
                    </button>
                </div>
            </div>
            
            <style id="commit-message-styles">
                /* First line styling */
                .commit-first-line {
                    font-weight: 600;
                    color: ${isDark ? '#60a5fa' : '#2563eb'};
                }
                
                /* Rest of message styling */
                .commit-body {
                    color: ${isDark ? '#d1d5db' : '#4b5563'};
                }
                
                /* Collapsed file list */
                #file-list-container.collapsed {
                    max-height: 0;
                    overflow: hidden;
                    opacity: 0;
                    padding: 0;
                    margin: 0;
                    border: none;
                    transition: all 0.3s ease;
                }
                
                #file-list-container.expanded {
                    max-height: 400px;
                    opacity: 1;
                    transition: all 0.3s ease;
                }
                
                /* Expanded message area */
                #commit-message-input.expanded {
                    min-height: 300px;
                    max-height: 500px;
                }
                
                /* Toggle button for file list */
                #toggle-file-list {
                    cursor: pointer;
                    user-select: none;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    font-size: 13px;
                    color: ${isDark ? '#9ca3af' : '#6b7280'};
                    margin-bottom: 8px;
                    transition: color 0.2s;
                }
                
                #toggle-file-list:hover {
                    color: ${isDark ? '#d1d5db' : '#374151'};
                }
            </style>
        `;
    },

    /**
     * Create modal body with simplified layout - no diff panel
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
                padding: 8px 16px;
            ">
                <!-- Select All Section with Toggle -->
                <div style="
                    margin-bottom: 8px;
                    padding: 6px 10px;
                    background: ${isDark ? 'rgba(59, 130, 246, 0.08)' : 'rgba(99, 102, 241, 0.04)'};
                    border: 1px solid ${isDark ? 'rgba(59, 130, 246, 0.15)' : 'rgba(99, 102, 241, 0.08)'};
                    border-radius: 5px;
                    flex-shrink: 0;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                ">
                    <label style="
                        display: flex;
                        align-items: center;
                        font-size: 13px;
                        font-weight: 600;
                        cursor: pointer;
                        color: ${isDark ? '#e5e7eb' : '#374151'};
                    ">
                        <input type="checkbox" id="select-all-files" style="
                            margin-right: 8px;
                            width: 14px;
                            height: 14px;
                            accent-color: #3b82f6;
                            cursor: pointer;
                        ">
                        <span>Select All Files</span>
                    </label>
                    
                    <div id="toggle-file-list" title="Toggle file list">
                        <span id="toggle-icon">▼</span>
                        <span id="toggle-text">Hide Files</span>
                    </div>
                </div>

                <!-- File List Container -->
                <div id="file-list-container" class="expanded" style="
                    border: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                    border-radius: 5px;
                    padding: 8px;
                    background: ${isDark ? 'rgba(0, 0, 0, 0.2)' : 'rgba(255, 255, 255, 0.5)'};
                    backdrop-filter: blur(10px);
                    flex: 1;
                    overflow-y: auto;
                    min-height: 300px;
                ">
                    <!-- Files will be populated here -->
                </div>
                
                <!-- Hint Text -->
                <div style="
                    margin-top: 8px;
                    text-align: center;
                    font-size: 12px;
                    color: ${isDark ? '#9ca3af' : '#6b7280'};
                ">
                    Click on a file to view changes in IntelliJ's diff viewer
                </div>
            </div>
        `;
    },

    /**
     * Create modal footer with compact commit and push buttons
     */
    createModalFooter: function(isDark) {
        return `
            <!-- Modal Footer -->
            <div style="
                padding: 6px 16px 8px 16px;
                border-top: 1px solid ${isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.08)'};
                display: flex;
                justify-content: space-between;
                align-items: center;
                background: ${isDark ? 'rgba(0, 0, 0, 0.1)' : 'rgba(255, 255, 255, 0.3)'};
                backdrop-filter: blur(10px);
                flex-shrink: 0;
            ">
                <div style="
                    font-size: 12px;
                    color: ${isDark ? '#9ca3af' : '#6b7280'};
                " id="file-count-display">
                    0 files selected
                </div>
                
                <div style="display: flex; gap: 8px;">
                    <button id="cancel-file-selection" style="
                        background: ${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(243, 244, 246, 0.8)'};
                        color: ${isDark ? '#e5e7eb' : '#374151'};
                        border: 1px solid ${isDark ? 'rgba(156, 163, 175, 0.3)' : 'rgba(209, 213, 219, 0.5)'};
                        padding: 4px 8px;
                        border-radius: 4px;
                        font-size: 13px;
                        font-weight: 500;
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
                        padding: 4px 10px;
                        border-radius: 4px;
                        font-size: 13px;
                        font-weight: 500;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        box-shadow: 0 2px 4px rgba(16, 185, 129, 0.3);
                        opacity: 0.5;
                        display: flex;
                        align-items: center;
                        gap: 4px;
                    " onmouseover="
                        if (!this.disabled) {
                            this.style.transform = 'translateY(-1px)';
                            this.style.boxShadow = '0 3px 8px rgba(16, 185, 129, 0.4)';
                        }
                    " onmouseout="
                        if (!this.disabled) {
                            this.style.transform = 'translateY(0)';
                            this.style.boxShadow = '0 2px 4px rgba(16, 185, 129, 0.3)';
                        }
                    ">
                        <span style="font-size: 11px;">💾</span> Commit
                    </button>
                    <button id="push-btn" style="
                        background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
                        color: white;
                        border: none;
                        padding: 4px 10px;
                        border-radius: 4px;
                        font-size: 13px;
                        font-weight: 500;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        box-shadow: 0 2px 4px rgba(37, 99, 235, 0.3);
                        display: flex;
                        align-items: center;
                        gap: 4px;
                    " onmouseover="
                        this.style.transform = 'translateY(-1px)';
                        this.style.boxShadow = '0 3px 8px rgba(37, 99, 235, 0.4)';
                    " onmouseout="
                        this.style.transform = 'translateY(0)';
                        this.style.boxShadow = '0 2px 4px rgba(37, 99, 235, 0.3)';
                    ">
                        <span style="font-size: 11px;">⬆️</span> Push
                    </button>
                </div>
            </div>
        `;
    },

    /**
     * Create a file item element with improved layout and font sizes
     */
    createFileItem: function(status, filePath, index, isDark) {
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';

        const statusLabel = GitUtils.getStatusLabel(status);
        const statusColor = GitUtils.getStatusColor(status);
        const statusDescription = GitUtils.getStatusDescription(status);
        
        // Show only filename, not full path
        const fileName = filePath.split('/').pop() || filePath;
        const htmlSafeFileName = fileName
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
        
        // Get directory part of the path for display
        let dirPath = '';
        let htmlSafeDirPath = '';
        const pathParts = filePath.split('/');
        if (pathParts.length > 1) {
            pathParts.pop(); // Remove filename
            dirPath = pathParts.join('/');
            // Escape directory path for HTML
            htmlSafeDirPath = dirPath
                .replace(/&/g, '&amp;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#39;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
        }

        // Escape file path for safe use in HTML attributes and JavaScript
        // For JavaScript string in onclick, we need to escape backslashes, single quotes, and newlines
        const escapedFilePath = filePath
            .replace(/\\/g, '\\\\')  // Escape backslashes first
            .replace(/'/g, "\\'")    // Escape single quotes
            .replace(/"/g, '\\"')    // Escape double quotes
            .replace(/\n/g, '\\n')   // Escape newlines
            .replace(/\r/g, '\\r');  // Escape carriage returns
            
        // For HTML attributes, escape quotes and HTML entities
        const htmlSafeFilePath = filePath
            .replace(/&/g, '&amp;')  // Must be first
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
        
        fileItem.innerHTML = `
            <div class="file-item-content" data-file-path="${htmlSafeFilePath}" data-status="${status}" style="
                display: flex;
                align-items: center;
                padding: 8px 10px;
                margin: 4px 0;
                background: ${isDark ? 'rgba(55, 65, 81, 0.6)' : 'rgba(255, 255, 255, 0.8)'};
                border: 1px solid ${isDark ? 'rgba(75, 85, 99, 0.4)' : 'rgba(229, 231, 235, 0.6)'};
                border-radius: 5px;
                transition: all 0.2s ease;
                cursor: pointer;
            " onmouseover="
                this.style.background = '${isDark ? 'rgba(75, 85, 99, 0.8)' : 'rgba(255, 255, 255, 0.95)'}';
                this.style.borderColor = '${isDark ? 'rgba(59, 130, 246, 0.4)' : 'rgba(99, 102, 241, 0.3)'}';
                this.style.transform = 'translateX(2px)';
                this.style.boxShadow = '0 1px 3px rgba(0, 0, 0, 0.1)';
            " onmouseout="
                this.style.background = '${isDark ? 'rgba(55, 65, 81, 0.6)' : 'rgba(255, 255, 255, 0.8)'}';
                this.style.borderColor = '${isDark ? 'rgba(75, 85, 99, 0.4)' : 'rgba(229, 231, 235, 0.6)'}';
                this.style.transform = 'translateX(0)';
                this.style.boxShadow = 'none';
            " onclick="GitUI.openFileDiff('${escapedFilePath}', '${status}')">
                <input type="checkbox" class="file-checkbox" data-file-path="${htmlSafeFilePath}" data-status="${status}" id="file-${index}" style="
                    margin-right: 10px;
                    width: 14px;
                    height: 14px;
                    accent-color: #3b82f6;
                    cursor: pointer;
                " onclick="event.stopPropagation();">
                <span style="
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    font-weight: 600;
                    margin-right: 10px;
                    padding: 2px 6px;
                    border-radius: 4px;
                    font-size: 13px;
                    background: ${statusColor.bg};
                    color: ${statusColor.text};
                    min-width: 14px;
                    text-align: center;
                " title="${statusDescription}">${statusLabel}</span>
                <div style="
                    display: flex;
                    flex-direction: column;
                    flex: 1;
                    min-width: 0;
                ">
                    <label for="file-${index}" style="
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: 13px;
                        cursor: pointer;
                        color: ${isDark ? '#e5e7eb' : '#374151'};
                        font-weight: 500;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    " title="${htmlSafeFilePath}">${htmlSafeFileName}</label>
                    ${dirPath ? `
                    <div style="
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: 11px;
                        color: ${isDark ? '#9ca3af' : '#6b7280'};
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    " title="${htmlSafeDirPath}">${htmlSafeDirPath}</div>
                    ` : ''}
                </div>
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
                padding: 20px 16px;
                font-style: italic;
                background: ${isDark ? 'rgba(55, 65, 81, 0.3)' : 'rgba(255, 255, 255, 0.5)'};
                border-radius: 5px;
                border: 1px dashed ${isDark ? 'rgba(156, 163, 175, 0.3)' : 'rgba(209, 213, 219, 0.5)'};
            ">
                <div style="font-size: 20px; margin-bottom: 6px;">📁</div>
                <div style="font-size: 13px; font-weight: 500; margin-bottom: 4px;">No changed files found</div>
                <div style="font-size: 11px; opacity: 0.7;">
                    Make sure you have uncommitted changes in your repository
                </div>
            </div>
        `;
    },

    /**
     * Show operation status overlay (loading, success, error)
     */
    showOperationStatus: function(type, title, message, showClose = false) {
        const statusOverlay = document.getElementById('operation-status');
        const statusIcon = document.getElementById('operation-status-icon');
        const statusTitle = document.getElementById('operation-status-title');
        const statusMessage = document.getElementById('operation-status-message');
        const closeButton = document.getElementById('operation-status-close');
        
        if (!statusOverlay || !statusIcon || !statusTitle || !statusMessage || !closeButton) {
            console.error('Status overlay elements not found');
            return;
        }
        
        // Set icon based on type
        let icon = '⏳'; // default loading
        let color = '#3b82f6'; // default blue
        
        switch (type) {
            case 'loading':
                icon = '⏳';
                color = '#3b82f6'; // blue
                break;
            case 'success':
                icon = '✅';
                color = '#10b981'; // green
                break;
            case 'error':
                icon = '❌';
                color = '#ef4444'; // red
                break;
            case 'warning':
                icon = '⚠️';
                color = '#f59e0b'; // amber
                break;
        }
        
        // Update content
        statusIcon.innerHTML = icon;
        statusTitle.innerHTML = title || 'Operation in Progress';
        statusMessage.innerHTML = message || 'Please wait...';
        
        // Show/hide close button
        closeButton.style.display = showClose ? 'block' : 'none';
        
        // Set up close button event if showing
        if (showClose) {
            closeButton.onclick = () => this.hideOperationStatus();
        }
        
        // Show the overlay
        statusOverlay.style.display = 'flex';
    },
    
    /**
     * Hide operation status overlay
     */
    hideOperationStatus: function() {
        const statusOverlay = document.getElementById('operation-status');
        if (statusOverlay) {
            statusOverlay.style.display = 'none';
        }
    },
    
    /**
     * Show commit in progress status
     */
    showCommitInProgress: function() {
        this.showOperationStatus(
            'loading',
            'Committing Changes',
            'Please wait while your changes are being committed...',
            false
        );
    },
    
    /**
     * Show commit success status
     */
    showCommitSuccess: function() {
        this.showOperationStatus(
            'success',
            'Commit Successful',
            'Your changes have been committed successfully!',
            true
        );
    },
    
    /**
     * Show commit error status
     */
    showCommitError: function(errorMessage) {
        this.showOperationStatus(
            'error',
            'Commit Failed',
            errorMessage || 'There was an error committing your changes. Please try again.',
            true
        );
    },
    
    /**
     * Show push in progress status
     */
    showPushInProgress: function() {
        this.showOperationStatus(
            'loading',
            'Pushing Changes',
            'Please wait while your changes are being pushed to the remote repository...\n(Click to close)',
            false
        );
    },
    
    /**
     * Show push success status
     */
    showPushSuccess: function() {
        this.showOperationStatus(
            'success',
            'Push Successful',
            'Your changes have been pushed to the remote repository successfully!',
            true
        );
    },
    
    /**
     * Show push error status
     */
    showPushError: function(errorMessage) {
        this.showOperationStatus(
            'error',
            'Push Failed',
            errorMessage || 'There was an error pushing your changes. Please try again.',
            true
        );
    },

    /**
     * Open file diff in IntelliJ's native diff viewer
     */
    openFileDiff: function(filePath, status) {
        console.log(`Opening file diff in IntelliJ: ${filePath} (${status})`);
        
        // Send request to IDE to open diff
        if (window.intellijBridge && window.intellijBridge.callIDE) {
            window.intellijBridge.callIDE('openFileDiffInIDE', {
                filePath: filePath,
                status: status
            }).then(function(response) {
                if (response && response.success) {
                    console.log('Opened diff in IDE successfully');
                } else {
                    console.error('Failed to open diff in IDE:', response);
                    alert('Failed to open diff: ' + (response && response.error ? response.error : 'Unknown error'));
                }
            }).catch(function(error) {
                console.error('Error opening diff in IDE:', error);
                alert('Error opening diff: ' + error.message);
            });
        } else {
            console.error('IntelliJ Bridge not available');
            alert('IntelliJ Bridge not available. Please check the connection.');
        }
    },

    /**
     * Escape HTML entities
     */
    escapeHtml: function(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    },
    
    /**
     * Style commit message with first line highlighting
     */
    styleCommitMessage: function(textarea) {
        // This is just for visual feedback while typing
        // The actual git commit will use the raw text
        const lines = textarea.value.split('\n');
        if (lines.length > 0) {
            // We can't style inside textarea, but we can adjust the size
            textarea.style.height = 'auto';
            textarea.style.height = Math.min(textarea.scrollHeight, 300) + 'px';
        }
    },
    
    /**
     * Clean commit message from markdown formatting
     */
    cleanCommitMessage: function(message) {
        if (!message) return '';
        
        // Remove markdown code block indicators
        message = message.replace(/^```[\w-]*\n?/gm, '');
        message = message.replace(/\n?```$/gm, '');
        
        // Remove any remaining triple backticks
        message = message.replace(/```/g, '');
        
        // Trim extra whitespace
        message = message.trim();
        
        return message;
    },
    
    /**
     * Toggle file list visibility
     */
    toggleFileList: function() {
        const container = document.getElementById('file-list-container');
        const toggleIcon = document.getElementById('toggle-icon');
        const toggleText = document.getElementById('toggle-text');
        const messageInput = document.getElementById('commit-message-input');
        
        if (container && toggleIcon && toggleText && messageInput) {
            if (container.classList.contains('expanded')) {
                // Collapse
                container.classList.remove('expanded');
                container.classList.add('collapsed');
                toggleIcon.textContent = '▶';
                toggleText.textContent = 'Show Files';
                
                // Expand message area
                messageInput.classList.add('expanded');
                
                return true; // Collapsed
            } else {
                // Expand
                container.classList.remove('collapsed');
                container.classList.add('expanded');
                toggleIcon.textContent = '▼';
                toggleText.textContent = 'Hide Files';
                
                // Normal message area
                messageInput.classList.remove('expanded');
                
                return false; // Expanded
            }
        }
        return null; // No change
    }
};

console.log('Git UI module loaded successfully');
