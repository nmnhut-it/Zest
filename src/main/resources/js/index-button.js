/**
 * Project Index Button for IDE Integration
 *
 * This script adds a button to trigger project indexing in OpenWebUI
 */

(function() {
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
        const isIndexed = response && response.isIndexed;
        updateIndexButtonState(indexButton, isIndexed);
      }).catch(error => {
        console.error('Failed to get project index status:', error);
        updateIndexButtonState(indexButton, false);
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
        // Check current index status
        window.intellijBridge.callIDE("projectIndexStatus",{
        }).then(response => {
          const isIndexed = response && response.isIndexed;
          
          if (!isIndexed) {
            // Not indexed, start indexing
            if (confirm('Index this project for intelligent code search? This may take a few minutes.')) {
              startProjectIndexing();
            }
          } else {
            // Already indexed, offer to refresh
            if (confirm('Project is already indexed. Do you want to refresh the index?')) {
              startProjectIndexing(true);
            }
          }
        }).catch(error => {
          console.error('Failed to get project index status:', error);
          // Default to asking to index
          if (confirm('Index this project for intelligent code search?')) {
            startProjectIndexing();
          }
        });
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
        textSpan.textContent = 'Indexed ✓';
      }
    } else {
      // Not indexed state - normal
      button.className = 'project-index-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-transparent border-transparent text-gray-600 dark:text-gray-300 border-gray-200 hover:bg-gray-50 dark:hover:bg-gray-800';
      
      // Update text
      const textSpan = button.querySelector('span:last-child');
      if (textSpan) {
        textSpan.textContent = 'Index Project';
      }
    }
  }

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
        updateIndexButtonState(button, false);
      });
    });
  }

  // Function to poll indexing status
  function pollIndexingStatus() {
    const pollInterval = setInterval(() => {
      window.intellijBridge.callIDE("projectIndexStatus",{
      }).then(response => {
        if (response && response.isIndexed && !response.isIndexing) {
          // Indexing complete
          clearInterval(pollInterval);
          
          const buttons = document.querySelectorAll('.project-index-button');
          buttons.forEach(button => {
            button.disabled = false;
            updateIndexButtonState(button, true);
          });
          
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

  console.log('Project index button script initialized. Use window.forceInjectProjectIndexButton() to manually inject.');
})();
