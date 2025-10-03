/**
 * Zest Chat UI JavaScript Functions
 * Handles message rendering, streaming, tool calls, and interactive features
 */

// Configuration
let showAllMessages = false;
const VISIBLE_MESSAGE_COUNT = 3;

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

        // Create header
        const headerDiv = document.createElement('div');
        headerDiv.className = 'message-header';
        headerDiv.textContent = message.header + ' - (' + message.timestamp + ')';
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

        container.appendChild(messageDiv);
    }
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
 * Make code blocks collapsible
 */
function makeCodeBlocksCollapsible() {
    document.querySelectorAll('pre code').forEach(function(codeElement) {
        const pre = codeElement.parentNode;
        if (pre.querySelector('.collapse-button')) return;

        const codeText = codeElement.textContent || '';
        const lines = codeText.split('\\n');
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
        const messageElement = document.getElementById(messageId);
        if (messageElement) {
            if (messageElement._streamingState) {
                delete messageElement._streamingState;
            }

            const contentDiv = messageElement.querySelector('.message-content');
            if (contentDiv && typeof marked !== 'undefined') {
                contentDiv.innerHTML = marked.parse(content);
                if (typeof hljs !== 'undefined') {
                    contentDiv.querySelectorAll('pre code').forEach(function(block) {
                        hljs.highlightElement(block);
                    });
                }
                makeCodeBlocksCollapsible();
            }

            setTimeout(() => {
                messageElement.scrollIntoView({
                    behavior: 'smooth',
                    block: 'end',
                    inline: 'nearest'
                });
            }, 100);
        }
    },

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
    }
};

// Notify Java that chat is ready
if (window.intellijBridge) {
    window.intellijBridge.callIDE('chat-ready', '');
}
