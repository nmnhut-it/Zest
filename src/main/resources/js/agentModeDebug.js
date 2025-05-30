/**
 * Debug utilities for Agent Mode context enhancement
 */

(function() {
    'use strict';

    // Test context enhancement
    window.testAgentModeContext = async function(query) {
        console.log('=== Testing Agent Mode Context Enhancement ===');
        console.log('Query:', query || 'Create a button handler');
        
        if (!window.intellijBridge) {
            console.error('IntelliJ bridge not available!');
            return;
        }
        
        const testQuery = query || 'Create a button handler for submitting the form';
        
        try {
            // Show notification
            if (window.notifyContextCollection) {
                window.notifyContextCollection();
            }
            
            console.log('Calling buildEnhancedPrompt...');
            const startTime = Date.now();
            
            const response = await window.intellijBridge.callIDE('buildEnhancedPrompt', {
                userQuery: testQuery,
                currentFile: window.__project_info__ ? window.__project_info__.currentOpenFile : 'test.js'
            });
            
            const duration = Date.now() - startTime;
            console.log('Response received in', duration, 'ms');
            console.log('Response:', response);
            
            if (response && response.success) {
                console.log('✓ Success!');
                console.log('Prompt length:', response.prompt.length);
                console.log('First 500 chars:', response.prompt.substring(0, 500));
                
                // Parse the prompt to show context info
                const contextMatch = response.prompt.match(/# PROJECT CONTEXT[\s\S]*?# USER REQUEST/);
                if (contextMatch) {
                    console.log('\n--- Extracted Context ---');
                    console.log(contextMatch[0]);
                }
                
                if (window.notifyContextComplete) {
                    window.notifyContextComplete();
                }
            } else {
                console.error('✗ Failed:', response);
                if (window.notifyContextError) {
                    window.notifyContextError();
                }
            }
            
        } catch (error) {
            console.error('Error testing context:', error);
            if (window.notifyContextError) {
                window.notifyContextError();
            }
        }
    };

    // Test keyword generation
    window.testKeywordGeneration = async function(query) {
        console.log('=== Testing Keyword Generation ===');
        
        if (!window.intellijBridge) {
            console.error('IntelliJ bridge not available!');
            return;
        }
        
        // This would need a direct API endpoint, so for now just test the full flow
        console.log('Keywords are generated as part of buildEnhancedPrompt');
        console.log('Run testAgentModeContext() to see the full flow');
    };

    // Test git search
    window.testGitSearch = async function(keyword) {
        console.log('=== Testing Git Search ===');
        console.log('Keyword:', keyword || 'button');
        
        if (!window.intellijBridge) {
            console.error('IntelliJ bridge not available!');
            return;
        }
        
        try {
            const response = await window.intellijBridge.callIDE('findCommitByMessage', {
                text: keyword || 'button'
            });
            
            console.log('Git search response:', response);
            
            if (response && response.success) {
                console.log('Found', response.count, 'commits');
                if (response.commits) {
                    response.commits.forEach((commit, i) => {
                        console.log(`  ${i + 1}. ${commit.message} (${commit.date})`);
                    });
                }
            }
            
        } catch (error) {
            console.error('Error searching git:', error);
        }
    };

    // Test file search
    window.testFileSearch = async function(keyword) {
        console.log('=== Testing File Search ===');
        console.log('Keyword:', keyword || 'handleClick');
        
        if (!window.intellijBridge) {
            console.error('IntelliJ bridge not available!');
            return;
        }
        
        try {
            const response = await window.intellijBridge.callIDE('findFunctions', {
                functionName: keyword || 'handleClick',
                path: '/'
            });
            
            console.log('Function search response:', response);
            
            if (response && response.success && response.results) {
                console.log('Found functions in', response.results.length, 'files');
                response.results.forEach((file, i) => {
                    console.log(`  ${i + 1}. ${file.file} (${file.functions.length} functions)`);
                    file.functions.forEach(func => {
                        console.log(`     - ${func.name} at line ${func.line}`);
                    });
                });
            }
            
        } catch (error) {
            console.error('Error searching files:', error);
        }
    };

    // Debug info
    window.agentModeDebugInfo = function() {
        console.log('=== Agent Mode Debug Info ===');
        console.log('Current mode:', window.__zest_mode__);
        console.log('System prompt loaded:', !!window.__injected_system_prompt__);
        console.log('IntelliJ bridge available:', !!window.intellijBridge);
        console.log('Project info:', window.__project_info__);
        console.log('Notification functions:', {
            notifyContextCollection: !!window.notifyContextCollection,
            notifyContextComplete: !!window.notifyContextComplete,
            notifyContextError: !!window.notifyContextError
        });
    };

    // Log initialization
    console.log('%c🔍 Agent Mode Debug Tools Loaded!', 'color: #4CAF50; font-size: 14px; font-weight: bold;');
    console.log('Available commands:');
    console.log('  window.testAgentModeContext("your query") - Test full context enhancement');
    console.log('  window.testGitSearch("keyword") - Test git commit search');
    console.log('  window.testFileSearch("functionName") - Test function search');
    console.log('  window.agentModeDebugInfo() - Show current state');

})();
