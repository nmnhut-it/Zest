/**
 * Project Index Button for IDE Integration
 *
 * This script adds a button to trigger project indexing in OpenWebUI
 */

(function() {
  // Initialize global states from saved configuration
  function initializeStates() {
    if (window.intellijBridge) {
      window.intellijBridge.callIDE('getButtonStates', {}).then(response => {
        if (response && response.success) {
          window.__enable_context_injection__ = response.contextInjectionEnabled;
          window.__enable_project_index__ = response.projectIndexEnabled;
          console.log('Loaded button states from configuration:');
          console.log('- Context injection:', window.__enable_context_injection__);
          console.log('- Project index:', window.__enable_project_index__);
          
          // Update any existing buttons
          if (window.syncAllProjectIndexButtons) {
            window.syncAllProjectIndexButtons();
          }
        } else {
          // Fallback to defaults if load fails
          window.__enable_context_injection__ = window.__enable_context_injection__ !== undefined ? window.__enable_context_injection__ : true;
          window.__enable_project_index__ = window.__enable_project_index__ !== undefined ? window.__enable_project_index__ : false;
        }
      }).catch(error => {
        console.error('Failed to load button states:', error);
        // Fallback to defaults
        window.__enable_context_injection__ = window.__enable_context_injection__ !== undefined ? window.__enable_context_injection__ : true;
        window.__enable_project_index__ = window.__enable_project_index__ !== undefined ? window.__enable_project_index__ : false;
      });
    } else {
      // No bridge available, use defaults
      window.__enable_context_injection__ = window.__enable_context_injection__ !== undefined ? window.__enable_context_injection__ : true;
      window.__enable_project_index__ = window.__enable_project_index__ !== undefined ? window.__enable_project_index__ : false;
    }
  }
  
  // Initialize states on load
  initializeStates();
  
  // Function to create and inject the index button
  window.injectProjectIndexButton = function() {
    console.log('Injecting project index button...');
    
    // Try multiple selectors to find the button container
    let buttonContainers = document.querySelectorAll('.flex.gap-1.items-center.overflow-x-auto.scrollbar-none.flex-1');
    
    // If not found, try a more general selector
    if (buttonContainers.length === 0) {
      console.log('Primary selector not found, trying alternative selectors...');
      // Look for containers that have the Web Search button
      const searchButtons = document.querySelectorAll('[aria-label="Search the internet"]');
      const containers = [];
      searchButtons.forEach(searchButton => {
        const container = searchButton.closest('.flex.gap-1.items-center');
        if (container && !container.querySelector('.project-index-button')) {
          containers.push(container);
        }
      });
      buttonContainers = containers;
    }
    
    // If still not found, look for any flex container with gap-1
    if (!buttonContainers || buttonContainers.length === 0) {
      console.log('Looking for flex containers with gap-1...');
      const allFlexContainers = document.querySelectorAll('.flex.gap-1');
      buttonContainers = Array.from(allFlexContainers).filter(container => {
        // Check if this container has tool buttons
        return container.querySelector('[aria-label="Search the internet"]') || 
               container.querySelector('[aria-label="Execute code for analysis"]');
      });
    }
    
    console.log(`Found ${buttonContainers.length} potential button containers`);
    
    buttonContainers.forEach((container, index) => {
      console.log(`Processing container ${index}:`, container);
      
      // Check if we already added the button
      if (container.querySelector('.project-index-button')) {
        console.log('Index button already exists in this container');
        return;
      }
      
      // Create the button container
      const buttonContainer = document.createElement('div');
      buttonContainer.setAttribute('aria-label', 'Index project in OpenWebUI');
      buttonContainer.className = 'flex';
      
      // Create the button
      const indexButton = document.createElement('button');
      indexButton.type = 'button';
      indexButton.className = 'project-index-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-transparent';
      
      // Check if project is indexed
      window.intellijBridge.callIDE("projectIndexStatus",{
      }).then(response => {
        console.log('Project index status response:', response);
        
        // Handle both direct response and nested response formats
        let isIndexed = false;
        if (response) {
          if (typeof response === 'string') {
            try {
              response = JSON.parse(response);
            } catch (e) {
              console.error('Failed to parse response:', e);
            }
          }
          
          // Check for isIndexed in different places
          if (response.isIndexed !== undefined) {
            isIndexed = response.isIndexed;
          } else if (response.result && response.result.isIndexed !== undefined) {
            isIndexed = response.result.isIndexed;
          }
        }
        
        console.log('Is indexed:', isIndexed);
        console.log('Context injection enabled:', window.__enable_context_injection__);
        console.log('Current project index state:', window.__enable_project_index__);
        
        // Determine the correct initial state based on:
        // 1. Whether project is indexed in backend
        // 2. Whether context injection is enabled (mutual exclusion)
        // 3. Current frontend state
        
        if (window.__enable_context_injection__) {
          // Context injection is enabled, so project index must be disabled
          console.log('Context injection is enabled, forcing project index to be disabled');
          window.__enable_project_index__ = false;
          updateIndexButtonState(indexButton, false);
        } else if (isIndexed && window.__enable_project_index__ === undefined) {
          // Project is indexed and state not yet initialized
          console.log('Project is indexed and no state set, enabling project index');
          window.__enable_project_index__ = true;
          updateIndexButtonState(indexButton, true);
        } else if (window.__enable_project_index__ !== undefined) {
          // Use existing state
          console.log('Using existing project index state:', window.__enable_project_index__);
          updateIndexButtonState(indexButton, window.__enable_project_index__);
        } else {
          // Default state
          console.log('Setting default state (not indexed)');
          window.__enable_project_index__ = false;
          updateIndexButtonState(indexButton, false);
        }
      }).catch(error => {
        console.error('Failed to get project index status:', error);
        
        // On error, check context injection and set safe defaults
        if (window.__enable_context_injection__) {
          window.__enable_project_index__ = false;
        } else if (window.__enable_project_index__ === undefined) {
          window.__enable_project_index__ = false;
        }
        updateIndexButtonState(indexButton, window.__enable_project_index__);
      });
      
      // Create the button content
      indexButton.innerHTML = `
        <span style="font-size: 16px; line-height: 1;">🗂️</span>
        <span class="hidden @xl:block whitespace-nowrap overflow-hidden text-ellipsis translate-y-[0.5px]">
          Index Project
        </span>
      `;
      
      // Add click handler
      indexButton.addEventListener('click', function() {
        console.log('Index button clicked. Current state:', window.__enable_project_index__);
        
        // Check the current frontend state, not backend status
        if (!window.__enable_project_index__) {
          // Currently OFF, user wants to turn it ON
          
          // Check if context injection is enabled
          if (window.__enable_context_injection__) {
            alert('Please disable Context Injection first before enabling Project Index mode.');
            // Ensure button state is correct after alert
            updateIndexButtonState(indexButton, false);
            return;
          }
          
          // Check if project needs indexing first
          window.intellijBridge.callIDE("projectIndexStatus",{}).then(response => {
            let isIndexed = false;
            if (response) {
              if (typeof response === 'string') {
                try {
                  response = JSON.parse(response);
                } catch (e) {
                  console.error('Failed to parse response:', e);
                }
              }
              
              if (response.isIndexed !== undefined) {
                isIndexed = response.isIndexed;
              } else if (response.result && response.result.isIndexed !== undefined) {
                isIndexed = response.result.isIndexed;
              }
            }
            
            if (!isIndexed) {
              // Not indexed yet, need to index first
              if (confirm('Index this project for intelligent code search? This may take a few minutes.')) {
                window.__enable_project_index__ = true;
                startProjectIndexing();
              } else {
                // User cancelled, ensure button state is correct
                updateIndexButtonState(indexButton, false);
              }
            } else {
              // Already indexed, just enable the mode
              window.__enable_project_index__ = true;
              updateIndexButtonState(indexButton, true);
              showIndexingNotification('enabled');
              
              // Save state to configuration
              if (window.intellijBridge) {
                window.intellijBridge.callIDE('setProjectIndexEnabled', {
                  enabled: true
                }).catch(error => {
                  console.error('Failed to save project index state:', error);
                });
              }
            }
          }).catch(error => {
            console.error('Failed to get project index status:', error);
            if (confirm('Index this project for intelligent code search?')) {
              window.__enable_project_index__ = true;
              startProjectIndexing();
            } else {
              // User cancelled, ensure button state is correct
              updateIndexButtonState(indexButton, false);
            }
          });
        } else {
          // Currently ON, user wants to turn it OFF
          if (confirm('Project index is currently active. Do you want to turn it off?')) {
            window.__enable_project_index__ = false;
            updateIndexButtonState(indexButton, false);
            showIndexingNotification('disabled');
            
            // Save state to configuration
            if (window.intellijBridge) {
              window.intellijBridge.callIDE('setProjectIndexEnabled', {
                enabled: false
              }).catch(error => {
                console.error('Failed to save project index state:', error);
              });
            }
          }
        }
      });
      
      buttonContainer.appendChild(indexButton);
      container.appendChild(buttonContainer);
      console.log('Project index button injected successfully');
    });
    
    if (buttonContainers.length === 0) {
      console.warn('No suitable container found for project index button');
    }
  };

  // Function to update button appearance based on index state
  function updateIndexButtonState(button, isIndexed) {
    if (isIndexed) {
      // Indexed state - green/highlighted
      button.className = 'project-index-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-green-500 border-green-500 text-white hover:bg-green-600';
      
      // Update text
      const textSpan = button.querySelector('span:last-child');
      if (textSpan) {
        textSpan.textContent = 'Index On';
      }
    } else {
      // Not indexed state - normal
      button.className = 'project-index-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-transparent border-transparent text-gray-600 dark:text-gray-300 border-gray-200 hover:bg-gray-50 dark:hover:bg-gray-800';
      
      // Update text
      const textSpan = button.querySelector('span:last-child');
      if (textSpan) {
        textSpan.textContent = 'Index Off';
      }
    }
  }
  
  // Function to sync all project index buttons
  window.syncAllProjectIndexButtons = function() {
    const allButtons = document.querySelectorAll('.project-index-button');
    allButtons.forEach(button => {
      updateIndexButtonState(button, window.__enable_project_index__);
    });
  };

  // Function to start project indexing
  function startProjectIndexing(forceRefresh = false) {
    // Show loading state
    const buttons = document.querySelectorAll('.project-index-button');
    buttons.forEach(button => {
      button.disabled = true;
      const textSpan = button.querySelector('span:last-child');
      if (textSpan) {
        textSpan.textContent = 'Indexing...';
      }
    });
    
    // Show notification
    showIndexingNotification('started');
    
    // Call IDE to start indexing
    window.intellijBridge.callIDE("indexProject", { forceRefresh: forceRefresh }).then(response => {
      console.log('Project indexing started:', response);
      
      // Poll for completion
      pollIndexingStatus();
    }).catch(error => {
      console.error('Failed to start project indexing:', error);
      showIndexingNotification('error');
      
      // Reset button state
      buttons.forEach(button => {
        button.disabled = false;
        updateIndexButtonState(button, window.__enable_project_index__);
      });
    });
  }

  // Function to poll indexing status
  function pollIndexingStatus() {
    const pollInterval = setInterval(() => {
      window.intellijBridge.callIDE("projectIndexStatus",{
      }).then(response => {
        console.log('Polling index status:', response);
        
        // Handle both direct response and nested response formats
        let isIndexed = false;
        let isIndexing = false;
        
        if (response) {
          if (typeof response === 'string') {
            try {
              response = JSON.parse(response);
            } catch (e) {
              console.error('Failed to parse response:', e);
            }
          }
          
          // Check for values in different places
          if (response.isIndexed !== undefined) {
            isIndexed = response.isIndexed;
            isIndexing = response.isIndexing || false;
          } else if (response.result) {
            isIndexed = response.result.isIndexed || false;
            isIndexing = response.result.isIndexing || false;
          }
        }
        
        if (isIndexed && !isIndexing) {
          // Indexing complete
          clearInterval(pollInterval);
          
          const buttons = document.querySelectorAll('.project-index-button');
          buttons.forEach(button => {
            button.disabled = false;
            updateIndexButtonState(button, true);
          });
          
          // Update global state
          window.__enable_project_index__ = true;
          
          showIndexingNotification('complete');
        }
      }).catch(error => {
        console.error('Error polling index status:', error);
        clearInterval(pollInterval);
      });
    }, 2000); // Poll every 2 seconds
    
    // Stop polling after 5 minutes
    setTimeout(() => {
      clearInterval(pollInterval);
    }, 300000);
  }

  // Function to show indexing notifications
  function showIndexingNotification(status) {
    let message;
    let bgColor;
    
    switch(status) {
      case 'started':
        message = 'Project indexing started. This may take a few minutes...';
        bgColor = 'bg-blue-600';
        break;
      case 'complete':
        message = 'Project indexing complete! Your code is now searchable.';
        bgColor = 'bg-green-600';
        break;
      case 'error':
        message = 'Failed to index project. Please try again.';
        bgColor = 'bg-red-600';
        break;
      case 'disabled':
        message = 'Project index mode disabled.';
        bgColor = 'bg-gray-600';
        break;
      case 'enabled':
        message = 'Project index mode enabled.';
        bgColor = 'bg-green-600';
        break;
    }
    
    // Create notification element
    const notification = document.createElement('div');
    notification.className = `fixed bottom-4 right-4 ${bgColor} text-white px-4 py-2 rounded-lg shadow-lg z-50 transition-opacity duration-300`;
    notification.textContent = message;
    
    // Add to body
    document.body.appendChild(notification);
    
    // Remove after 5 seconds
    setTimeout(() => {
      notification.style.opacity = '0';
      setTimeout(() => {
        document.body.removeChild(notification);
      }, 300);
    }, 5000);
  }

  // Initialize the button injection when DOM is ready
  function initializeProjectIndexButton() {
    console.log('Initializing project index button...');
    
    // Inject initially
    window.injectProjectIndexButton();
    
    // Re-inject if DOM changes (for dynamic content)
    const observer = new MutationObserver((mutations) => {
      // Check if the button containers are modified
      const shouldReinject = mutations.some(mutation => {
        return Array.from(mutation.addedNodes).some(node => {
          return node.nodeType === 1 && (
            node.classList?.contains('flex') ||
            node.querySelector?.('.flex.gap-1') ||
            node.querySelector?.('[aria-label="Search the internet"]')
          );
        });
      });
      
      if (shouldReinject) {
        console.log('DOM changed, re-injecting project index button...');
        window.injectProjectIndexButton();
      }
    });
    
    observer.observe(document.body, {
      childList: true,
      subtree: true
    });
    
    // Try again after a delay in case the UI is still loading
    setTimeout(() => {
      console.log('Delayed injection attempt for project index button...');
      window.injectProjectIndexButton();
    }, 2000);
    
    // And once more after a longer delay
    setTimeout(() => {
      console.log('Final injection attempt for project index button...');
      window.injectProjectIndexButton();
    }, 5000);
  }

  // Wait for DOM to be ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeProjectIndexButton);
  } else {
    // DOM is already ready
    setTimeout(initializeProjectIndexButton, 500);
  }
  
  // Also try on window load
  window.addEventListener('load', () => {
    console.log('Window loaded, attempting project index button injection...');
    setTimeout(() => {
      window.injectProjectIndexButton();
    }, 1000);
  });

  // Add manual trigger for debugging
  window.forceInjectProjectIndexButton = function() {
    console.log('Force injecting project index button...');
    window.injectProjectIndexButton();
  };

  // Force sync all button states
  window.forceIndexButtonSync = function() {
    console.log('Force syncing all index button states to:', window.__enable_project_index__);
    const allButtons = document.querySelectorAll('.project-index-button');
    allButtons.forEach(button => {
      updateIndexButtonState(button, window.__enable_project_index__);
    });
  };

  // Periodically check for mutual exclusion
  setInterval(() => {
    // Only enforce if context-toggle.js hasn't already enforced it
    if (window.__enable_context_injection__ && window.__enable_project_index__ && window.__mutual_exclusion_enforced__ !== 'context') {
      // This should rarely happen since context-toggle.js handles it
      console.warn('Mutual exclusion backup check: Both enabled, following context priority...');
      window.__enable_project_index__ = false;
      window.syncAllProjectIndexButtons();
    }
    
    // Also ensure button states are correct
    const buttons = document.querySelectorAll('.project-index-button');
    buttons.forEach(button => {
      const textSpan = button.querySelector('span:last-child');
      if (textSpan) {
        const currentText = textSpan.textContent;
        const expectedText = window.__enable_project_index__ ? 'Index On' : 'Index Off';
        if (currentText !== expectedText && currentText !== 'Indexing...') {
          updateIndexButtonState(button, window.__enable_project_index__);
        }
      }
    });
  }, 2100); // Check slightly after context-toggle to avoid conflicts

  console.log('Project index button script initialized. Use window.forceInjectProjectIndexButton() to manually inject.');
  
  // Re-initialize states when page becomes visible (in case config was changed elsewhere)
  document.addEventListener('visibilitychange', function() {
    if (!document.hidden) {
      initializeStates();
    }
  });
})();
