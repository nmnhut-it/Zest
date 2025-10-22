/**
 * IntelliJ Bridge with Chunked Messaging Support
 *
 * This script initializes the JavaScript bridge between the browser and IntelliJ.
 * It includes support for chunked messaging to handle large messages that exceed
 * JBCef/CEF message size limits (~1.4KB).
 */

window.intellijBridge = {
  // Configuration for chunked messaging
  config: {
    maxChunkSize: 1400,  // Maximum size per chunk (under 1.4KB limit)
    chunkPrefix: '__CHUNK__',
    sessionTimeout: 900000  // 900 seconds (15 minutes) - allows for very large responses and slow networks
  },
  
  // Active chunked message sessions
  activeSessions: new Map(),
  
  /**
   * Main method to call IDE with automatic chunking support
   * @param {string} action - The action to perform
   * @param {Object} data - The data to send
   * @returns {Promise} Promise that resolves when the call is complete
   */
  callIDE: function(action, data) {
    return new Promise((resolve, reject) => {
      try {
        const startTime = performance.now();

        // Create the request
        const request = JSON.stringify({
          action: action,
          data: data || {}
        });

        console.log(`[Bridge] → ${action} (${request.length}b)`);

        // Wrap resolve to add timing
        const timedResolve = (response) => {
          const elapsed = performance.now() - startTime;
          console.log(`[Bridge] ← ${action} completed (${elapsed.toFixed(0)}ms)`);

          // Warn on slow operations
          if (elapsed > 5000) {
            console.warn(`[Bridge] ⚠️ Slow operation: ${action} took ${(elapsed/1000).toFixed(1)}s`);
          }

          // Validate response
          if (response) {
            console.log(`[Bridge] Response:`, {
              success: response.success,
              hasError: !!response.error,
              keys: Object.keys(response).slice(0, 5)
            });
          }

          resolve(response);
        };

        // Check if message needs chunking
        if (request.length > this.config.maxChunkSize) {
          const chunks = this.splitIntoChunks(request);
          console.log(`[Bridge] Using chunking for ${action} (${chunks.length} chunks)`);
          this.sendChunkedMessage(request, timedResolve, reject);
        } else {
          this.sendSingleMessage(request, timedResolve, reject);
        }
      } catch(e) {
        console.error(`[Bridge] ❌ Error in ${action}:`, e);
        reject(e);
      }
    });
  },
  
  /**
   * Sends a single message (under size limit)
   */
  sendSingleMessage: function(request, resolve, reject) {
    try {
      // This is a placeholder that will be replaced by Java code
      // with the actual JBCefJSQuery.inject call
      [[JBCEF_QUERY_INJECT]]
    } catch(e) {
      console.error('Error sending single message:', e);
      reject(e);
    }
  },
  
  /**
   * Sends a large message split into chunks
   */
  sendChunkedMessage: function(request, resolve, reject) {
    try {
      const sessionId = this.generateSessionId();
      const chunks = this.splitIntoChunks(request);
      const totalChunks = chunks.length;

      // Store session info
      this.activeSessions.set(sessionId, {
        resolve: resolve,
        reject: reject,
        startTime: Date.now(),
        totalChunks: totalChunks,
        sentChunks: 0
      });

      // Send chunks sequentially
      this.sendNextChunk(sessionId, chunks, 0);

      // Set timeout for session cleanup
      setTimeout(() => {
        if (this.activeSessions.has(sessionId)) {
          console.warn(`[Bridge] ⏱️ Chunk session timeout: ${sessionId} (${totalChunks} chunks)`);
          const session = this.activeSessions.get(sessionId);
          session.reject(new Error('Chunked message session timed out'));
          this.activeSessions.delete(sessionId);
        }
      }, this.config.sessionTimeout);

    } catch(e) {
      console.error('[Bridge] ❌ Chunking error:', e);
      reject(e);
    }
  },
  
  /**
   * Sends the next chunk in a sequence
   */
  sendNextChunk: function(sessionId, chunks, chunkIndex) {
    if (chunkIndex >= chunks.length) {
      return;
    }

    const session = this.activeSessions.get(sessionId);
    if (!session) {
      console.warn(`[Bridge] ⚠️ Session not found: ${sessionId}`);
      return;
    }

    // Format: __CHUNK__sessionId|chunkIndex|totalChunks|data
    const chunkRequest = `${this.config.chunkPrefix}${sessionId}|${chunkIndex}|${chunks.length}|${chunks[chunkIndex]}`;

    try {
      // Send this chunk using a Promise-based approach to match JCEF pattern
      new Promise((resolve, reject) => {
        try {
          // This will be replaced by Java code with actual JBCefJSQuery.inject call for chunks
          // The chunkRequest variable contains the formatted chunk message
          [[JBCEF_CHUNK_INJECT]]
        } catch(e) {
          console.error('Error in chunk injection:', e);
          reject(e);
        }
      }).then(() => {
        // Update session
        session.sentChunks++;
        
        // Schedule next chunk (small delay to avoid overwhelming)
        setTimeout(() => {
          this.sendNextChunk(sessionId, chunks, chunkIndex + 1);
        }, 10);
      }).catch((error) => {
        console.error(`[Bridge] ❌ Chunk ${chunkIndex + 1}/${chunks.length} failed:`, error.message);
        session.reject(error);
        this.activeSessions.delete(sessionId);
      });

    } catch(e) {
      console.error(`[Bridge] ❌ Chunk send error:`, e);
      session.reject(e);
      this.activeSessions.delete(sessionId);
    }
  },
  
  /**
   * Splits a message into chunks
   */
  splitIntoChunks: function(message) {
    const chunks = [];
    const chunkSize = this.config.maxChunkSize - 50; // Reserve space for chunk metadata
    
    for (let i = 0; i < message.length; i += chunkSize) {
      chunks.push(message.substring(i, i + chunkSize));
    }
    
    return chunks;
  },
  
  /**
   * Generates a unique session ID
   */
  generateSessionId: function() {
    return 'session_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
  },
  
  /**
   * Handles responses from the IDE for chunked messages
   */
  handleChunkedResponse: function(sessionId, response) {
    const session = this.activeSessions.get(sessionId);
    if (session) {
      session.resolve(response);
      this.activeSessions.delete(sessionId);
    }
  },
  
  /**
   * Extracts code from an API response and sends it to the IDE
   * @param {Object} data - Object containing the code, language, and text to replace
   * @returns {Promise} Promise that resolves when the code is sent to the IDE
   */
  extractCodeFromResponse: function(data) {
    return this.callIDE('extractCodeFromResponse', {
      code: data.code,
      language: data.language,
      textToReplace: data.textToReplace || '__##use_selected_text##__'
    });
  },
  
  /**
   * Performs a file replacement using the ReplaceInFileTool
   * @param {Object} data - Object containing the file path, search text, replacement text, and options
   * @returns {Promise} Promise that resolves when the replacement is complete
   */
  replaceInFile: function(data) {
    return this.callIDE('replaceInFile', {
      filePath: data.filePath,
      search: data.search,
      replace: data.replace,
      regex: data.regex || false,
      caseSensitive: data.caseSensitive || true
    });
  },
  
  /**
   * Performs multiple replacements in a file in a single operation
   * @param {Object} data - Object containing the file path and an array of replacements
   * @returns {Promise} Promise that resolves when all replacements are complete
   */
  batchReplaceInFile: function(data) {
    return this.callIDE('batchReplaceInFile', {
      filePath: data.filePath,
      replacements: data.replacements
    });
  },
  
  /**
   * Notifies the IDE about a new chat response received from the API
   * @param {Object} data - Object containing the response content and message ID
   * @returns {Promise} Promise that resolves when the notification is sent to the IDE
   */
  notifyChatResponse: function(data) {
    return this.callIDE('notifyChatResponse', {
      content: data.content,
      id: data.id || ''
    });
  },
  
  /**
   * Shows code diff and replace dialog in the IDE
   * @param {Object} data - Object containing the code, language, and text to replace
   * @returns {Promise} Promise that resolves when the request is sent to the IDE
   */
  showCodeDiffAndReplace: function(data) {
    return this.callIDE('showCodeDiffAndReplace', {
      code: data.code,
      language: data.language,
      textToReplace: data.textToReplace || '__##use_selected_text##__'
    });
  },
  
  /**
   * Gets statistics about the chunked messaging system
   */
  getChunkingStats: function() {
    return {
      maxChunkSize: this.config.maxChunkSize,
      activeSessions: this.activeSessions.size,
      sessionTimeout: this.config.sessionTimeout
    };
  },
  
  /**
   * Cleans up expired sessions
   */
  cleanupExpiredSessions: function() {
    const now = Date.now();
    let cleaned = 0;
    for (const [sessionId, session] of this.activeSessions.entries()) {
      if (now - session.startTime > this.config.sessionTimeout) {
        session.reject(new Error('Session expired'));
        this.activeSessions.delete(sessionId);
        cleaned++;
      }
    }
    if (cleaned > 0) {
      console.log(`[Bridge] 🧹 Cleaned ${cleaned} expired session(s)`);
    }
  }
};

// Global settings
window.shouldAutomaticallyCopy = false;

// Periodic cleanup of expired sessions
setInterval(() => {
  window.intellijBridge.cleanupExpiredSessions();
}, 30000); // Clean up every 30 seconds
// Add this to your bridge JavaScript file

// Extract and send auth cookie
(function() {
  function getCookieValue(name) {
    const value = `${document.cookie}`;
    const parts = value.split(`; ${name}=`);
    if (parts.length === 2) return parts.pop().split(';').shift();
    return '';
  }
/**
     * Get auth token from cookie
     */
   function _getAuthTokenFromCookie () {
        // OpenWebUI typically stores the token in a cookie named 'token' or similar
        const cookies = document.cookie.split(';');
        for (const cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === 'token' || name === 'auth-token' || name === 'authorization') {
                return decodeURIComponent(value);
            }
        }

        // Also check localStorage as some implementations use it
        const localToken = localStorage.getItem('token') || localStorage.getItem('auth-token');
        if (localToken) {
            return localToken;
        }

        return null;
    }
  function checkAndSendAuthCookie() {
    const authCookie = _getAuthTokenFromCookie();
    if (authCookie && window.intellijBridge) {
      window.intellijBridge.callIDE('auth', {token: authCookie});
      return true;
    }
    return false;
  }

  // Try immediately and on DOM load
  if (!checkAndSendAuthCookie()) {
    document.addEventListener('DOMContentLoaded', checkAndSendAuthCookie);

    // Try a few more times with timeout
    let attempts = 0;
    const interval = setInterval(() => {
      if (checkAndSendAuthCookie() || ++attempts > 10) {
        clearInterval(interval);
      }
    }, 3000);
  }
})();

// Initialize with debug info
console.log('✅ intellijBridge initialized');
console.log('[Bridge] Configuration:', {
  maxChunkSize: window.intellijBridge.config.maxChunkSize + 'b',
  sessionTimeout: (window.intellijBridge.config.sessionTimeout / 1000) + 's'
});

// Test bridge availability (non-blocking)
setTimeout(() => {
  if (window.intellijBridge && window.intellijBridge.callIDE) {
    console.log('[Bridge] Testing connection...');
    window.intellijBridge.callIDE('ping', {test: true})
      .then(response => {
        console.log('[Bridge] ✅ Connection test successful');
      })
      .catch(error => {
        console.warn('[Bridge] ⚠️ Connection test failed (might be normal for Git UI):', error.message);
      });
  }
}, 500);
