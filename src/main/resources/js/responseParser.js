/**
 * Response Parser for IDE Integration
 *
 * This script extracts code blocks from API response data
 * and sends them back to the IDE.
 */

(function() {
    // Keep track of the last processed response ID to avoid duplicates
    window.__last_processed_response_id__ = null;

    /**
     * Extracts code blocks from an API response
     * @param {Object} responseData - The parsed JSON response data
     * @returns {Array} Array of extracted code blocks with metadata
     */
    window.parseResponseForCode = function(responseData) {
        try {
//            console.log('Parsing response data for code blocks:', responseData);
            
            // Return early if no messages in the response
            if (!responseData || !responseData.messages || !Array.isArray(responseData.messages)) {
                console.log('No messages found in response data');
                return [];
            }

            // Find the latest assistant message
            const assistantMessages = responseData.messages.filter(msg => msg.role === 'assistant');
            if (assistantMessages.length === 0) {
                console.log('No assistant messages found');
                return [];
            }

            // Get the latest assistant message
            const latestMessage = assistantMessages[assistantMessages.length - 1];
            
            // Skip if we've already processed this message
            if (window.__last_processed_response_id__ === latestMessage.id) {
                console.log('Already processed this response ID:', latestMessage.id);
                return [];
            }

            // Update the last processed ID
            window.__last_processed_response_id__ = latestMessage.id;
            
            // Extract code blocks from the message content
            const codeBlocks = extractCodeBlocks(latestMessage.content);
//            console.log('Extracted code blocks:', codeBlocks);
            
            return codeBlocks;
        } catch (e) {
            console.error('Error parsing response for code:', e);
            return [];
        }
    };

    /**
     * Extracts code blocks from a message string
     * @param {string} content - The message content
     * @returns {Array} Array of extracted code blocks with language info
     */
    function extractCodeBlocks(content) {
        if (!content || typeof content !== 'string') {
            return [];
        }

        const codeBlocks = [];
        const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
        
        // --- UPDATED REGEX BELOW ---
        // This regex now supports optional language tags and optional whitespace/newlines.
        const replaceBlockRegex = /replace_in_file:(.*?)\s*\n\s*```\s*(\w*)\s*\n([\s\S]*?)```\s*\n\s*```\s*(\w*)\s*\n([\s\S]*?)```/g;

        // First check for replacement patterns
        let replaceMatch;
        while ((replaceMatch = replaceBlockRegex.exec(content)) !== null) {
            const filePath = replaceMatch[1].trim();
            const searchLanguage = (replaceMatch[2] || '').trim().toLowerCase() || 'text';
            const searchCode = replaceMatch[3];
            const replaceLanguage = (replaceMatch[4] || '').trim().toLowerCase() || 'text';
            const replaceCode = replaceMatch[5];

            codeBlocks.push({
                type: 'replacement',
                filePath: filePath,
                searchLanguage: searchLanguage,
                searchCode: searchCode,
                replaceLanguage: replaceLanguage,
                replaceCode: replaceCode,
                fullMatch: replaceMatch[0]
            });
        }

        // Then extract regular code blocks
        let match;
        while ((match = codeBlockRegex.exec(content)) !== null) {
            // Skip blocks that were already captured as part of replacement blocks
            let isPartOfReplacement = false;
            for (const block of codeBlocks) {
                if (block.type === 'replacement' &&
                    (content.indexOf(match[0]) >= content.indexOf(block.fullMatch) &&
                     content.indexOf(match[0]) <= content.indexOf(block.fullMatch) + block.fullMatch.length)) {
                    isPartOfReplacement = true;
                    break;
                }
            }

            if (!isPartOfReplacement) {
                const language = (match[1] || '').trim().toLowerCase() || 'text';
                const code = match[2];
                codeBlocks.push({
                    type: 'code',
                    language: language,
                    code: code,
                    fullMatch: match[0]
                });
            }
        }
        
        return codeBlocks;
    }

    /**
     * Processes extracted code blocks and sends them to the IDE
     * @param {Array} codeBlocks - The extracted code blocks
     */
    window.processExtractedCode = function(codeBlocks) {
        if (!codeBlocks || codeBlocks.length === 0) {
            console.log('No code blocks to process');
            return;
        }
        
        // Check for replacement blocks
        const replacementBlocks = codeBlocks.filter(block => block.type === 'replacement');
        if (replacementBlocks.length > 0) {
            console.log('Found replacement blocks:', replacementBlocks);
            
            // Group replacements by file path
            const replacementsByFile = {};
            replacementBlocks.forEach(block => {
                if (!replacementsByFile[block.filePath]) {
                    replacementsByFile[block.filePath] = [];
                }
                replacementsByFile[block.filePath].push({
                    search: block.searchCode,
                    replace: block.replaceCode,
                    regex: false,
                    caseSensitive: true
                });
            });
            
            // Process each file's replacements as a batch
            for (const filePath in replacementsByFile) {
                const replacements = replacementsByFile[filePath];
                
                // If we have only one replacement for this file, use single replacement
                if (replacements.length === 1) {
                    if (window.intellijBridge && window.intellijBridge.replaceInFile) {
                        console.log('Sending single replacement to IDE for:', filePath);
                        window.intellijBridge.replaceInFile({
                            filePath: filePath,
                            search: replacements[0].search,
                            replace: replacements[0].replace,
                            regex: replacements[0].regex,
                            caseSensitive: replacements[0].caseSensitive
                        }).then(function() {
                            console.log('Replacement sent to IntelliJ successfully');
                        }).catch(function(error) {
                            console.error('Failed to send replacement to IntelliJ:', error);
                        });
                    }
                } 
                // If we have multiple replacements for this file, use batch replacement
                else if (window.intellijBridge && window.intellijBridge.batchReplaceInFile) {
                    console.log('Sending batch replacement to IDE for:', filePath, 'with', replacements.length, 'replacements');
                    window.intellijBridge.batchReplaceInFile({
                        filePath: filePath,
                        replacements: replacements
                    }).then(function() {
                        console.log('Batch replacement sent to IntelliJ successfully');
                    }).catch(function(error) {
                        console.error('Failed to send batch replacement to IntelliJ:', error);
                    });
                }
            }
            
            // We've handled the replacements, so return early
            return;
        }
        else {
            console.log('No replacement blocks found');
        }
        
        // If no replacement blocks or no replacement tool, fall back to regular code extraction
        // Find the most appropriate code block to extract
        // Prioritize Java, JavaScript, TypeScript, etc.
        const priorityLanguages = ['java', 'javascript', 'typescript', 'js', 'ts', 'kotlin', 'scala', 'xml', 'json', 'html', 'css'];
        
        // Filter to just regular code blocks
        const regularCodeBlocks = codeBlocks.filter(block => block.type === 'code' || !block.type);
        
        // First, try to find a code block with a priority language
        let selectedBlock = null;
        for (const lang of priorityLanguages) {
            selectedBlock = regularCodeBlocks.find(block => block.language === lang);
            if (selectedBlock) break;
        }
        
        // If no priority language found, take the last code block
        if (!selectedBlock && regularCodeBlocks.length > 0) {
            selectedBlock = regularCodeBlocks[regularCodeBlocks.length - 1];
        }
        
        // If we found a code block, send it to the IDE
        if (selectedBlock && window.intellijBridge && window.intellijBridge.extractCodeFromResponse) {
//            console.log('Sending extracted code to IDE:', selectedBlock);
            window.intellijBridge.extractCodeFromResponse({
                code: selectedBlock.code,
                language: selectedBlock.language,
                textToReplace: window.__text_to_replace_ide___ || '__##use_selected_text##__'
            }).then(function() {
                console.log('Code sent to IntelliJ successfully');
                window.__text_to_replace_ide___ = null;
            }).catch(function(error) {
                console.error('Failed to send code to IntelliJ:', error);
            });
        }
    };

    console.log('Response parser initialized');
})();