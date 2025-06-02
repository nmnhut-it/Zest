/**
 * Project Mode Interceptor for Enhanced Project Understanding
 *
 * This interceptor enhances chat requests with project knowledge from the RAG system
 * to provide better context-aware responses about the codebase.
 */

(function() {
  console.log('[Project Mode] Interceptor loaded');

  /**
   * Enhances request body with project knowledge context
   * @param {Object} data - The request data object
   * @returns {boolean} True if enhancement was applied
   */
  window.enhanceWithProjectKnowledge = function(data) {
    // Check if this is a chat completion request
    if (!data.messages || !Array.isArray(data.messages)) {
      return false;
    }

    // Add project mode flag
    data.custom_tool = 'Zest|PROJECT_MODE_CHAT';

    // Get the knowledge collection from the cached data
    const knowledgeCollection = window.__project_knowledge_collection__;
    
    if (!knowledgeCollection) {
      console.warn('[Project Mode] No knowledge collection found. Please wait for it to load or re-index your project.');
      return false;
    }

    // Add the knowledge collection to the request
    if (!data.files) {
      data.files = [];
    }

    // Check if knowledge collection is already added
    const hasKnowledgeCollection = data.files.some(
      file => file.type === 'collection' && file.id === knowledgeCollection.id
    );

    if (!hasKnowledgeCollection) {
      // Add the complete collection object
      data.files.push(knowledgeCollection);
      console.log('[Project Mode] Added knowledge collection:', knowledgeCollection.id);
    }

    // Add a system message that explains the project context is available
    const projectModeSystemMessage = `You have access to the complete codebase of the current project through the RAG system. This includes:

- All classes, interfaces, enums, and annotations with their signatures
- Method signatures with parameters and return types
- Field declarations
- Javadoc documentation for all code elements
- Project structure and dependencies
- Build system information

When answering questions about the code:
1. Search through the available project knowledge to find relevant information
2. Reference specific classes, methods, and fields by their full signatures
3. Consider the project's architecture and dependencies
4. Provide accurate information based on the actual codebase
5. Include relevant javadoc documentation when available

The project knowledge is automatically included in this conversation, so you can directly reference any part of the codebase.`;

    // Check if we need to add or update the system message
    const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
    
    if (window.__zest_mode__ === 'Project Mode') {
      if (systemMsgIndex >= 0) {
        // Append to existing system message
        data.messages[systemMsgIndex].content = projectModeSystemMessage + '\n\n' + data.messages[systemMsgIndex].content;
      } else {
        // Add new system message at the beginning
        data.messages.unshift({
          role: 'system',
          content: projectModeSystemMessage
        });
      }
    }

    // Add project info to the latest user message (similar to Agent Mode)
    if (window.__project_info__) {
      for (let i = data.messages.length - 1; i >= 0; i--) {
        if (data.messages[i].role === 'user') {
          const info = window.__project_info__;
          const contextInfo = `<project_context>
Current Project: ${info.projectName}
Project Path: ${info.projectFilePath}
Current File: ${info.currentOpenFile}
</project_context>\n\n`;
          
          data.messages[i].content = contextInfo + data.messages[i].content;
          break;
        }
      }
    }

    return true;
  };

  /**
   * Loads the knowledge collection from the project configuration
   */
  window.loadProjectKnowledgeCollection = function() {
    if (window.intellijBridge) {
      console.log('[Project Mode] Fetching knowledge collection...');
      window.intellijBridge.callIDE('getProjectKnowledgeCollection', {})
        .then(function(response) {
          if (response && response.success && response.result) {
            window.__project_knowledge_collection__ = response.result;
            console.log('[Project Mode] Loaded knowledge collection:', response.result.id);
            console.log('[Project Mode] Collection contains', response.result.files ? response.result.files.length : 0, 'files');
          } else {
            console.warn('[Project Mode] Failed to load knowledge collection:', response);
            window.__project_knowledge_collection__ = null;
          }
        })
        .catch(function(error) {
          console.error('[Project Mode] Failed to load knowledge collection:', error);
          window.__project_knowledge_collection__ = null;
        });
    }
  };

  /**
   * Clears the cached knowledge collection
   */
  window.clearProjectKnowledgeCollection = function() {
    window.__project_knowledge_collection__ = null;
    console.log('[Project Mode] Cleared knowledge collection cache');
  };

  // Load knowledge collection when the page loads
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      // Only load if we're in Project Mode
      if (window.__zest_mode__ === 'Project Mode') {
        window.loadProjectKnowledgeCollection();
      }
    });
  } else {
    // Only load if we're in Project Mode
    if (window.__zest_mode__ === 'Project Mode') {
      window.loadProjectKnowledgeCollection();
    }
  }

  // Reload when switching to Project Mode
  window.addEventListener('zestModeChanged', function(event) {
    if (event.detail && event.detail.mode === 'Project Mode') {
      console.log('[Project Mode] Mode activated, loading knowledge collection...');
      window.loadProjectKnowledgeCollection();
    } else {
      // Clear the collection when switching away from Project Mode
      window.clearProjectKnowledgeCollection();
    }
  });

  // Also reload when project info changes (e.g., after re-indexing)
  window.addEventListener('projectIndexed', function(event) {
    if (window.__zest_mode__ === 'Project Mode') {
      console.log('[Project Mode] Project re-indexed, reloading knowledge collection...');
      window.loadProjectKnowledgeCollection();
    }
  });

})();
