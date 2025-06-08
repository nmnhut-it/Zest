/**
 * Context Injection Toggle for IDE Integration
 *
 * This script adds a toggle button to control context injection in Agent Mode
 */

(function() {
  // Initialize global states
  window.__enable_context_injection__ = window.__enable_context_injection__ !== undefined ? window.__enable_context_injection__ : true;
  window.__enable_project_index__ = window.__enable_project_index__ !== undefined ? window.__enable_project_index__ : false;
  
  console.log('Context toggle script initializing with states:');
  console.log('- Context injection:', window.__enable_context_injection__);
  console.log('- Project index:', window.__enable_project_index__);
  
  // Function to create and inject the context toggle button
  window.injectContextToggleButton = function() {
    console.log('Injecting context toggle button...');
    
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
        if (container && !container.querySelector('.context-toggle-button')) {
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
      
      // Check if we already added the toggle
      if (container.querySelector('.context-toggle-button')) {
        console.log('Toggle button already exists in this container');
        return;
      }
      
      // Create the toggle button container
      const toggleContainer = document.createElement('div');
      toggleContainer.setAttribute('aria-label', 'Toggle context injection');
      toggleContainer.className = 'flex';
      
      // Create the button
      const toggleButton = document.createElement('button');
      toggleButton.type = 'button';
      toggleButton.className = 'context-toggle-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-transparent';
      
      // Set initial state classes
      updateToggleButtonState(toggleButton, window.__enable_context_injection__);
      
      // Create the SVG icon - using a simpler database/stack icon for context
      const svgIcon = `<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" fill="none" viewBox="0 0 24 24" stroke-width="1.75" stroke="currentColor" class="size-5" style="width: 20px; height: 20px;">
        <path stroke-linecap="round" stroke-linejoin="round" d="M20.25 6.375c0 2.278-3.694 4.125-8.25 4.125S3.75 8.653 3.75 6.375m16.5 0c0-2.278-3.694-4.125-8.25-4.125S3.75 4.097 3.75 6.375m16.5 0v11.25c0 2.278-3.694 4.125-8.25 4.125s-8.25-1.847-8.25-4.125V6.375m16.5 0v3.75m-16.5-3.75v3.75m16.5 0v3.75C20.25 16.153 16.556 18 12 18s-8.25-1.847-8.25-4.125v-3.75m16.5 0c0 2.278-3.694 4.125-8.25 4.125s-8.25-1.847-8.25-4.125" />
      </svg>`;
      
      // Set initial icon based on current state
      const initialEmoji = window.__enable_context_injection__ ? '📑' : '📄';
      
      // Create the button content with both SVG and emoji fallback
      toggleButton.innerHTML = `
        <span style="font-size: 16px; line-height: 1;">${initialEmoji}</span>
        <span class="hidden @xl:block whitespace-nowrap overflow-hidden text-ellipsis translate-y-[0.5px]">
          Context${window.__enable_context_injection__ ? ' On' : ' Off'}
        </span>
      `;
      
      // Add click handler with mutual exclusion logic
      toggleButton.addEventListener('click', function() {
        const wasEnabled = window.__enable_context_injection__;
        window.__enable_context_injection__ = !wasEnabled;
        
        // If enabling context injection, disable project index mode
        if (window.__enable_context_injection__) {
          // Check if project index mode is enabled
          if (window.__enable_project_index__) {
            window.__enable_project_index__ = false;
            window.__project_index_was_disabled__ = true;
            
            // Update all project index buttons
            if (window.syncAllProjectIndexButtons) {
              window.syncAllProjectIndexButtons();
            }
            
            console.log('Disabled project index mode due to context injection being enabled');
          }
        }
        
        // Sync all toggle buttons
        syncAllToggleButtons();
        
        console.log('Context injection toggled:', window.__enable_context_injection__ ? 'ON' : 'OFF');
        
        // Show notification
        showContextToggleNotification(window.__enable_context_injection__);
      });
      
      toggleContainer.appendChild(toggleButton);
      container.appendChild(toggleContainer);
      console.log('Context toggle button injected successfully');
    });
    
    if (buttonContainers.length === 0) {
      console.warn('No suitable container found for context toggle button');
    }
  };

  // Function to sync all toggle buttons to current state
  function syncAllToggleButtons() {
    const allButtons = document.querySelectorAll('.context-toggle-button');
    allButtons.forEach(button => {
      updateToggleButtonState(button, window.__enable_context_injection__);
      
      // Update emoji
      const emojiSpan = button.querySelector('span:first-child');
      if (emojiSpan && emojiSpan.style.fontSize === '16px') {
        emojiSpan.textContent = window.__enable_context_injection__ ? '📑' : '📄';
      }
      
      // Update text
      const textSpan = button.querySelector('span:last-child');
      if (textSpan && textSpan.textContent.includes('Context')) {
        textSpan.textContent = `Context${window.__enable_context_injection__ ? ' On' : ' Off'}`;
      }
    });
  }

  // Function to update button appearance based on state
  function updateToggleButtonState(button, isEnabled) {
    if (isEnabled) {
      // Enabled state - green/highlighted like index button
      button.className = 'context-toggle-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-green-500 border-green-500 text-white hover:bg-green-600';
    } else {
      // Disabled state - normal
      button.className = 'context-toggle-button px-1.5 @xl:px-2.5 py-1.5 flex gap-1.5 items-center text-sm rounded-full font-medium transition-colors duration-300 focus:outline-hidden max-w-full overflow-hidden border bg-transparent border-transparent text-gray-600 dark:text-gray-300 border-gray-200 hover:bg-gray-50 dark:hover:bg-gray-800';
    }
    
    // Update emoji if needed
    const emojiSpan = button.querySelector('span:first-child');
    if (emojiSpan && emojiSpan.style.fontSize === '16px') {
      emojiSpan.textContent = isEnabled ? '📑' : '📄';
    }
  }

  // Function to show notification when toggled
  function showContextToggleNotification(isEnabled) {
    // Create notification element
    const notification = document.createElement('div');
    notification.className = 'fixed bottom-4 right-4 bg-gray-800 text-white px-4 py-2 rounded-lg shadow-lg z-50 transition-opacity duration-300';
    notification.textContent = `Context injection ${isEnabled ? 'enabled' : 'disabled'}`;
    
    // Check if we disabled project index
    if (isEnabled && window.__project_index_was_disabled__) {
      notification.textContent += ' (Project index disabled)';
      window.__project_index_was_disabled__ = false;
    }
    
    // Add to body
    document.body.appendChild(notification);
    
    // Remove after 2 seconds
    setTimeout(() => {
      notification.style.opacity = '0';
      setTimeout(() => {
        document.body.removeChild(notification);
      }, 300);
    }, 2000);
  }

  // Initialize the button injection when DOM is ready
  function initializeContextToggle() {
    console.log('Initializing context toggle...');
    
    // Inject initially
    window.injectContextToggleButton();
    
    // Also inject To IDE buttons if available
    if (window.injectToIDEButtons) {
      window.injectToIDEButtons();
    }
    
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
        console.log('DOM changed, re-injecting buttons...');
        window.injectContextToggleButton();
        // Also re-inject To IDE buttons
        if (window.injectToIDEButtons) {
          window.injectToIDEButtons();
        }
      }
    });
    
    observer.observe(document.body, {
      childList: true,
      subtree: true
    });
    
    // Try again after a delay in case the UI is still loading
    setTimeout(() => {
      console.log('Delayed injection attempt...');
      window.injectContextToggleButton();
    }, 2000);
    
    // And once more after a longer delay
    setTimeout(() => {
      console.log('Final injection attempt...');
      window.injectContextToggleButton();
    }, 5000);
  }

  // Wait for DOM to be ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeContextToggle);
  } else {
    // DOM is already ready
    setTimeout(initializeContextToggle, 500);
  }
  
  // Also try on window load
  window.addEventListener('load', () => {
    console.log('Window loaded, attempting button injection...');
    setTimeout(() => {
      window.injectContextToggleButton();
    }, 1000);
  });

  // Add manual trigger for debugging
  window.forceInjectContextToggle = function() {
    console.log('Force injecting context toggle button...');
    window.injectContextToggleButton();
  };

  // Add function to sync button state
  window.syncContextToggleState = function() {
    syncAllToggleButtons();
  };

  // Periodically check and sync button states
  setInterval(() => {
    const buttons = document.querySelectorAll('.context-toggle-button');
    if (buttons.length > 0) {
      // Check if any button is out of sync
      buttons.forEach(button => {
        const emojiSpan = button.querySelector('span:first-child');
        if (emojiSpan && emojiSpan.style.fontSize === '16px') {
          const currentEmoji = emojiSpan.textContent;
          const expectedEmoji = window.__enable_context_injection__ ? '📑' : '📄';
          if (currentEmoji !== expectedEmoji) {
            console.log('Button out of sync, updating...');
            syncAllToggleButtons();
          }
        }
      });
    }
    
    // Enforce mutual exclusion - context injection takes priority
    if (window.__enable_context_injection__ && window.__enable_project_index__) {
      console.warn('Mutual exclusion violation detected! Both context and index are enabled. Context injection takes priority, disabling project index...');
      window.__enable_project_index__ = false;
      if (window.syncAllProjectIndexButtons) {
        window.syncAllProjectIndexButtons();
      }
      // Set a flag to prevent race conditions
      window.__mutual_exclusion_enforced__ = 'context';
      setTimeout(() => { window.__mutual_exclusion_enforced__ = null; }, 1000);
    }
  }, 2000); // Check every 2 seconds

  console.log('Context toggle script initialized. Use window.forceInjectContextToggle() to manually inject.');
})();
