// Test JavaScript file for JCEF resource loading
console.log('🎉 External JavaScript file loaded successfully!');

// Add a global test function
window.externalJSTest = function() {
    console.log('External JS test function called');
    return 'External JavaScript is working!';
};

// Test if we can modify the DOM from external JS
document.addEventListener('DOMContentLoaded', function() {
    const testDiv = document.createElement('div');
    testDiv.className = 'test-result success';
    testDiv.textContent = '✅ External JS can modify DOM';
    testDiv.style.marginTop = '10px';
    
    const container = document.querySelector('.test-section');
    if (container) {
        container.appendChild(testDiv);
    }
});
