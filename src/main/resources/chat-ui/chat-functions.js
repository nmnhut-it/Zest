/**
 * Zest Chat UI JavaScript Functions
 * Handles message rendering, streaming, tool calls, and interactive features
 *
 * Architecture: Data-driven rendering
 * - Messages array is the single source of truth
 * - All updates modify the array first, then trigger re-render
 * - Streaming updates the data model progressively
 */

// Configuration
let showAllMessages = false;
const VISIBLE_MESSAGE_COUNT = 3;
const PREVIEW_LENGTH = 100;

// Track collapse state for each message
const messageCollapseState = {};

// Initialize on DOM ready
document.addEventListener('DOMContentLoaded', function() {
    renderMessages();

    // Initialize highlight.js
    if (typeof hljs !== 'undefined') {
        hljs.highlightAll();
        makeCodeBlocksCollapsible();
    }
});

/**
 * Find a message in the array by ID
 */
function findMessage(messageId) {
    return messages.find(msg => msg.id === messageId);
}

/**
 * Update message content in the data array
 */
function updateMessageInArray(messageId, newContent) {
    const message = findMessage(messageId);
    if (message) {
        message.content = newContent;
    }
}

/**
 * Add tool call to message in the data array
 */
function addToolCallToArray(messageId, toolCall) {
    const message = findMessage(messageId);
    if (message) {
        if (!message.toolCalls) {
            message.toolCalls = [];
        }
        message.toolCalls.push(toolCall);
    }
}

/**
 * Update tool call in message in the data array
 */
function updateToolCallInArray(messageId, toolCallId, status, result) {
    const message = findMessage(messageId);
    if (message && message.toolCalls) {
        const toolCall = message.toolCalls.find(tc => tc.id === toolCallId);
        if (toolCall) {
            toolCall.status = status;
            toolCall.result = result;
        }
    }
}

/**
 * Render all messages from the messages array
 */
function renderMessages() {
    const container = document.getElementById('chat-container');
    container.innerHTML = '';

    const totalMessages = messages.length;
    const hiddenCount = Math.max(0, totalMessages - VISIBLE_MESSAGE_COUNT);

    // Add "Show More" button if there are hidden messages
    if (!showAllMessages && hiddenCount > 0) {
        const showMoreDiv = document.createElement('div');
        showMoreDiv.className = 'show-more-container';
        showMoreDiv.innerHTML =
            '<button class="show-more-button" onclick="window.chatFunctions.toggleShowAllMessages()">' +
            '▼ Show ' + hiddenCount + ' older message' + (hiddenCount > 1 ? 's' : '') +
            '</button>';
        container.appendChild(showMoreDiv);

        // Add separator after button
        const separator = document.createElement('div');
        separator.className = 'message-separator';
        container.appendChild(separator);
    }

    // Determine which messages to show
    const startIndex = showAllMessages ? 0 : Math.max(0, totalMessages - VISIBLE_MESSAGE_COUNT);

    for (let i = startIndex; i < messages.length; i++) {
        const message = messages[i];

        // Add separator if needed (skip for first visible message)
        if (i > startIndex && message.showSeparator) {
            const separator = document.createElement('div');
            separator.className = 'message-separator';
            container.appendChild(separator);
        }

        // Create message container
        const messageDiv = document.createElement('div');
        messageDiv.id = message.id;
        messageDiv.className = 'chat-message';

        // Apply collapse state if exists
        if (messageCollapseState[message.id]) {
            messageDiv.classList.add('collapsed');
        }

        // Create header with collapse indicator
        const headerDiv = document.createElement('div');
        headerDiv.className = 'message-header';
        const collapseIcon = messageCollapseState[message.id] ? '▶' : '▼';
        headerDiv.innerHTML = '<span class="collapse-indicator">' + collapseIcon + '</span>' +
                             escapeHtml(message.header + ' - (' + message.timestamp + ')');
        headerDiv.onclick = function() {
            window.chatFunctions.toggleMessageCollapse(message.id);
        };
        messageDiv.appendChild(headerDiv);

        // Create content and render markdown
        const contentDiv = document.createElement('div');
        contentDiv.className = 'message-content';

        // Use marked.js to render markdown (if content exists)
        if (message.content) {
            if (typeof marked !== 'undefined') {
                contentDiv.innerHTML = marked.parse(message.content);
            } else {
                // Fallback to plain text
                contentDiv.textContent = message.content;
            }
        }

        messageDiv.appendChild(contentDiv);

        // Render tool calls if present
        if (message.toolCalls && message.toolCalls.length > 0) {
            message.toolCalls.forEach(function(toolCall) {
                const toolCallDiv = renderToolCall(toolCall);
                contentDiv.appendChild(toolCallDiv);
            });
        }

        // Create preview for collapsed state
        const previewDiv = document.createElement('div');
        previewDiv.className = 'message-preview';
        previewDiv.textContent = generatePreview(message);
        messageDiv.appendChild(previewDiv);

        container.appendChild(messageDiv);
    }
}

/**
 * Re-render a single message from the messages array (data-driven)
 */
function reRenderMessage(messageId) {
    const message = findMessage(messageId);
    if (!message) return;

    const messageElement = document.getElementById(messageId);
    if (!messageElement) return;

    // Clear streaming state if any
    if (messageElement._streamingState) {
        delete messageElement._streamingState;
    }

    // Find and preserve the header
    const headerDiv = messageElement.querySelector('.message-header');
    const headerHtml = headerDiv ? headerDiv.outerHTML : '';

    // Create new content div
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';

    // Render markdown content if present
    if (message.content) {
        if (typeof marked !== 'undefined') {
            contentDiv.innerHTML = marked.parse(message.content);
        } else {
            contentDiv.textContent = message.content;
        }
    }

    // Render tool calls from data model
    if (message.toolCalls && message.toolCalls.length > 0) {
        message.toolCalls.forEach(function(toolCall) {
            const toolCallDiv = renderToolCall(toolCall);
            contentDiv.appendChild(toolCallDiv);
        });
    }

    // Replace message content (preserve structure)
    messageElement.innerHTML = headerHtml;
    messageElement.appendChild(contentDiv);

    // Re-apply syntax highlighting and interactivity
    if (typeof hljs !== 'undefined') {
        contentDiv.querySelectorAll('pre code').forEach(function(block) {
            hljs.highlightElement(block);
        });
    }
    makeCodeBlocksCollapsible();
}

/**
 * Render a single tool call
 */
function renderToolCall(toolCall) {
    const statusIcon = toolCall.status === 'executing' ? '⏳' :
                     toolCall.status === 'complete' ? '✅' : '❌';
    const statusText = toolCall.status === 'executing' ? 'Executing...' :
                     toolCall.status === 'complete' ? 'Complete' : 'Error';

    const toolCallDiv = document.createElement('div');
    toolCallDiv.className = 'tool-call';
    toolCallDiv.id = toolCall.id;

    const toolHeader = document.createElement('div');
    toolHeader.className = 'tool-header';
    toolHeader.innerHTML =
        '<span class="tool-icon">' + statusIcon + '</span>' +
        '<span class="tool-name">' + escapeHtml(toolCall.toolName) + '</span>' +
        '<span class="tool-status">' + statusText + '</span>';
    toolCallDiv.appendChild(toolHeader);

    const toolArgs = document.createElement('div');
    toolArgs.className = 'tool-args';
    toolArgs.innerHTML = '<code>' + escapeHtml(toolCall.arguments) + '</code>';
    toolCallDiv.appendChild(toolArgs);

    if (toolCall.result && toolCall.status === 'complete') {
        const toolResult = document.createElement('div');
        toolResult.className = 'tool-result';
        toolResult.innerHTML =
            '<div class="tool-result-header">📄 Result:</div>' +
            '<pre class="tool-result-content">' + escapeHtml(toolCall.result) + '</pre>';
        toolCallDiv.appendChild(toolResult);
    }

    return toolCallDiv;
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Generate preview text for collapsed messages
 */
function generatePreview(message) {
    if (!message.content) {
        if (message.toolCalls && message.toolCalls.length > 0) {
            return 'Tool calls: ' + message.toolCalls.length;
        }
        return '(No content)';
    }

    const cleanText = message.content
        .replace(/```[\s\S]*?```/g, '[code block]') // Replace code blocks
        .replace(/`[^`]+`/g, '[code]') // Replace inline code
        .replace(/\n+/g, ' ') // Replace newlines with spaces
        .replace(/\s+/g, ' ') // Normalize whitespace
        .trim();

    if (cleanText.length <= PREVIEW_LENGTH) {
        return cleanText;
    }

    return cleanText.substring(0, PREVIEW_LENGTH) + '...';
}

/**
 * Add copy buttons to code blocks
 */
function addCopyButtons() {
    document.querySelectorAll('pre code').forEach(function(block) {
        const pre = block.parentNode;
        if (pre.querySelector('.copy-button')) return;

        const button = document.createElement('button');
        button.className = 'copy-button';
        button.textContent = 'Copy';
        button.onclick = function() {
            navigator.clipboard.writeText(block.textContent).then(function() {
                button.textContent = 'Copied!';
                setTimeout(function() {
                    button.textContent = 'Copy';
                }, 2000);
            });
        };

        pre.style.position = 'relative';
        pre.appendChild(button);
    });
}

/**
 * Update collapse all button text based on current state
 */
function updateCollapseAllButton() {
    const button = document.getElementById('collapse-all-btn');
    if (!button) return;

    const allCollapsed = messages.every(msg => messageCollapseState[msg.id]);
    if (allCollapsed) {
        button.textContent = '▼ Expand All';
    } else {
        button.textContent = '▲ Collapse All';
    }
}

/**
 * Make code blocks collapsible
 */
function makeCodeBlocksCollapsible() {
    document.querySelectorAll('pre code').forEach(function(codeElement) {
        const pre = codeElement.parentNode;
        if (pre.querySelector('.collapse-button')) return;

        const codeText = codeElement.textContent || '';
        const lines = codeText.split('\n');
        let lineCount = lines.length;
        if (lines.length > 0 && lines[lines.length - 1].trim() === '') {
            lineCount--;
        }

        // Create placeholder for collapsed state
        const placeholder = document.createElement('div');
        placeholder.className = 'code-placeholder';
        placeholder.textContent = '▶ Show code ~ ' + lineCount + ' line' + (lineCount !== 1 ? 's' : '');
        placeholder.style.display = 'none';

        // Create collapse button
        const collapseButton = document.createElement('button');
        collapseButton.className = 'collapse-button';
        collapseButton.textContent = '▼ Hide';

        pre.style.position = 'relative';
        pre.parentNode.insertBefore(placeholder, pre);
        pre.appendChild(collapseButton);

        // Start collapsed by default
        pre.classList.add('collapsed');
        placeholder.style.display = 'inline-block';
        collapseButton.style.display = 'none';

        // Toggle function
        const toggle = function() {
            if (pre.classList.contains('collapsed')) {
                pre.classList.remove('collapsed');
                placeholder.style.display = 'none';
                collapseButton.style.display = 'block';
            } else {
                pre.classList.add('collapsed');
                placeholder.style.display = 'inline-block';
                collapseButton.style.display = 'none';
            }
        };

        placeholder.onclick = toggle;
        collapseButton.onclick = toggle;
    });
}

/**
 * Enhanced chat functions exposed to Kotlin
 */
window.chatFunctions = {
    toggleShowAllMessages: function() {
        showAllMessages = !showAllMessages;
        renderMessages();
        if (typeof hljs !== 'undefined') {
            hljs.highlightAll();
            makeCodeBlocksCollapsible();
        }
    },

    scrollToMessage: function(messageId) {
        const element = document.getElementById(messageId);
        if (element) {
            element.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    },

    addMessage: function(messageId, html) {
        const container = document.getElementById('chat-container');
        const messageDiv = document.createElement('div');
        messageDiv.id = messageId;
        messageDiv.className = 'chat-message';
        messageDiv.innerHTML = html;
        container.appendChild(messageDiv);

        if (typeof hljs !== 'undefined') {
            hljs.highlightAll();
            makeCodeBlocksCollapsible();
        }

        this.scrollToMessage(messageId);
    },

    updateMessage: function(messageId, newContent) {
        const messageElement = document.getElementById(messageId);
        if (messageElement) {
            const contentDiv = messageElement.querySelector('.message-content');
            if (contentDiv) {
                contentDiv.innerHTML = '<pre style="white-space: pre-wrap; font-family: inherit; margin: 0; background: none; border: none; padding: 0;">' + this.escapeHtml(newContent) + '</pre>';
            }
        }
    },

    updateMessageStreaming: function(messageId, newChunk) {
        const messageElement = document.getElementById(messageId);
        if (!messageElement) return;

        const contentDiv = messageElement.querySelector('.message-content');
        if (!contentDiv) return;

        // Initialize streaming state
        if (!messageElement._streamingState) {
            messageElement._streamingState = {
                contentBuffer: '',
                displayedContent: '',
                wordQueue: [],
                isProcessing: false,
                lastChunkTime: Date.now(),
                chunkInterval: 2000,
                adaptiveSpeed: 500,
                lastScrollTime: 0,
                isFinalized: false,
                finalContent: null
            };
        }

        const state = messageElement._streamingState;
        const currentTime = Date.now();

        // Calculate adaptive speed
        if (state.lastChunkTime > 0) {
            const timeSinceLastChunk = currentTime - state.lastChunkTime;
            state.chunkInterval = timeSinceLastChunk;

            if (timeSinceLastChunk < 500) {
                state.adaptiveSpeed = Math.max(100, Math.min(200, timeSinceLastChunk / 3));
            } else if (timeSinceLastChunk < 2000) {
                state.adaptiveSpeed = Math.max(300, Math.min(500, timeSinceLastChunk / 2));
            } else {
                state.adaptiveSpeed = Math.max(800, Math.min(1500, timeSinceLastChunk / 2));
            }
        }
        state.lastChunkTime = currentTime;

        state.contentBuffer += newChunk;
        const words = newChunk.split(/(\s+)/);
        state.wordQueue.push(...words);

        if (state.wordQueue.length > 50) {
            state.adaptiveSpeed = Math.max(50, state.adaptiveSpeed / 2);
        }

        if (!state.isProcessing) {
            this.processWordQueue(messageId);
        }

        // Throttled scrolling
        if (currentTime - state.lastScrollTime > 300) {
            state.lastScrollTime = currentTime;
            messageElement.scrollIntoView({
                behavior: 'smooth',
                block: 'end',
                inline: 'nearest'
            });
        }
    },

    processWordQueue: function(messageId) {
        const messageElement = document.getElementById(messageId);
        if (!messageElement || !messageElement._streamingState) return;

        const state = messageElement._streamingState;
        const contentDiv = messageElement.querySelector('.message-content');

        if (state.wordQueue.length === 0) {
            state.isProcessing = false;

            if (state.isFinalized && state.finalContent) {
                this.convertToMarkdown(messageId, state.finalContent);
            }
            return;
        }

        state.isProcessing = true;
        const nextWord = state.wordQueue.shift();
        state.displayedContent += nextWord;

        contentDiv.innerHTML = '<pre style="white-space: pre-wrap; font-family: inherit; margin: 0; background: none; border: none; padding: 0;">' + this.escapeHtml(state.displayedContent) + '<span class="streaming-cursor">▎</span></pre>';

        const now = Date.now();
        if (now - state.lastScrollTime > 500) {
            state.lastScrollTime = now;
            messageElement.scrollIntoView({
                behavior: 'smooth',
                block: 'end',
                inline: 'nearest'
            });
        }

        const isSpace = /^\s+$/.test(nextWord);
        const baseDelay = isSpace ? Math.max(50, state.adaptiveSpeed / 5) : state.adaptiveSpeed;
        const randomFactor = 0.8 + (Math.random() * 0.4);
        const delay = Math.floor(baseDelay * randomFactor);

        setTimeout(() => {
            this.processWordQueue(messageId);
        }, delay);
    },

    finalizeMessage: function(messageId, finalContent) {
        const messageElement = document.getElementById(messageId);
        if (!messageElement) return;

        const state = messageElement._streamingState;
        if (state) {
            state.isFinalized = true;
            state.finalContent = finalContent;

            const remainingWords = finalContent.split(/(\s+)/).slice(state.displayedContent.split(/(\s+)/).length);
            if (remainingWords.length > 0) {
                state.wordQueue.push(...remainingWords);
                state.adaptiveSpeed = Math.min(50, state.adaptiveSpeed / 4);

                if (!state.isProcessing) {
                    this.processWordQueue(messageId);
                }
            } else {
                this.convertToMarkdown(messageId, finalContent);
            }
        } else {
            this.convertToMarkdown(messageId, finalContent);
        }
    },

    convertToMarkdown: function(messageId, content) {
        // Update data model first
        updateMessageInArray(messageId, content);

        // Re-render from data (includes tool calls from array)
        reRenderMessage(messageId);

        // Scroll to message
        setTimeout(() => {
            const messageElement = document.getElementById(messageId);
            if (messageElement) {
                messageElement.scrollIntoView({
                    behavior: 'smooth',
                    block: 'end',
                    inline: 'nearest'
                });
            }
        }, 100);
    },

    // Expose array update functions to Kotlin
    updateMessageInArray: updateMessageInArray,
    addToolCallToArray: addToolCallToArray,
    updateToolCallInArray: updateToolCallInArray,
    reRenderMessage: reRenderMessage,

    escapeHtml: function(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    },

    addToolCall: function(messageId, toolName, toolArgs, status, toolCallId) {
        const messageElement = document.getElementById(messageId);
        if (!messageElement) return;

        const contentDiv = messageElement.querySelector('.message-content');
        if (!contentDiv) return;

        const statusIcon = status === 'executing' ? '⏳' : status === 'complete' ? '✅' : '❌';
        const statusText = status === 'executing' ? 'Executing...' : status === 'complete' ? 'Complete' : 'Error';

        const toolCallHtml =
            '<div class="tool-call" id="' + toolCallId + '">' +
                '<div class="tool-header">' +
                    '<span class="tool-icon">' + statusIcon + '</span>' +
                    '<span class="tool-name">' + this.escapeHtml(toolName) + '</span>' +
                    '<span class="tool-status">' + statusText + '</span>' +
                '</div>' +
                '<div class="tool-args">' +
                    '<code>' + this.escapeHtml(toolArgs || '...') + '</code>' +
                '</div>' +
            '</div>';

        const currentContent = contentDiv.innerHTML;
        contentDiv.innerHTML = currentContent + toolCallHtml;
    },

    updateToolCall: function(toolCallId, status, result) {
        const toolElement = document.getElementById(toolCallId);
        if (!toolElement) {
            console.log('Element for tool call id not found', toolCallId);
            return;
        }

        const statusIcon = status === 'complete' ? '✅' : '❌';
        const statusText = status === 'complete' ? 'Complete' : 'Error';

        const statusSpan = toolElement.querySelector('.tool-status');
        const iconSpan = toolElement.querySelector('.tool-icon');

        if (statusSpan) statusSpan.textContent = statusText;
        if (iconSpan) iconSpan.textContent = statusIcon;

        if (result && status === 'complete') {
            const resultHtml =
                '<div class="tool-result">' +
                    '<div class="tool-result-header">📄 Result:</div>' +
                    '<pre class="tool-result-content">' + this.escapeHtml(result) + '</pre>' +
                '</div>';
            toolElement.innerHTML += resultHtml;
        }
    },

    clearMessages: function() {
        document.getElementById('chat-container').innerHTML = '';
    },

    notifyJava: function(action, data) {
        if (window.intellijBridge) {
            window.intellijBridge.callIDE(action, data || '');
        }
    },

    toggleMessageCollapse: function(messageId) {
        const messageElement = document.getElementById(messageId);
        if (!messageElement) return;

        const isCollapsed = messageElement.classList.contains('collapsed');
        messageCollapseState[messageId] = !isCollapsed;

        if (isCollapsed) {
            messageElement.classList.remove('collapsed');
        } else {
            messageElement.classList.add('collapsed');
        }

        // Update collapse indicator
        const indicator = messageElement.querySelector('.collapse-indicator');
        if (indicator) {
            indicator.textContent = messageCollapseState[messageId] ? '▶' : '▼';
        }

        // Update button state if needed
        updateCollapseAllButton();
    },

    collapseAllMessages: function() {
        messages.forEach(function(message) {
            messageCollapseState[message.id] = true;
            const messageElement = document.getElementById(message.id);
            if (messageElement) {
                messageElement.classList.add('collapsed');
                const indicator = messageElement.querySelector('.collapse-indicator');
                if (indicator) indicator.textContent = '▶';
            }
        });
        updateCollapseAllButton();
    },

    expandAllMessages: function() {
        messages.forEach(function(message) {
            messageCollapseState[message.id] = false;
            const messageElement = document.getElementById(message.id);
            if (messageElement) {
                messageElement.classList.remove('collapsed');
                const indicator = messageElement.querySelector('.collapse-indicator');
                if (indicator) indicator.textContent = '▼';
            }
        });
        updateCollapseAllButton();
    },

    toggleCollapseAll: function() {
        const allCollapsed = messages.every(msg => messageCollapseState[msg.id]);
        if (allCollapsed) {
            this.expandAllMessages();
        } else {
            this.collapseAllMessages();
        }
    }
};

// Notify Java that chat is ready
if (window.intellijBridge) {
    window.intellijBridge.callIDE('chat-ready', '');
}
