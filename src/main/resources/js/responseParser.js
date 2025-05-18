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
            console.log('Parsing response data for code blocks:', responseData);
            
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
            console.log('Extracted code blocks:', codeBlocks);
            
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
        
        // Extract replacement blocks with format: replace_in_file:path/to/file.java
        const replaceBlockRegex = /replace_in_file:(.*?)\n```(\w*)\n([\s\S]*?)```\n```(\w*)\n([\s\S]*?)```/g;
        
        // Extract batch replacement blocks with format: batch_replace_in_file:path/to/file.java
        const batchReplaceBlockRegex = /batch_replace_in_file:(.*?)\n((?:```\w*\n[\s\S]*?```\n```\w*\n[\s\S]*?```\n)+)/g;
        
        // First check for batch replacement patterns
        let batchReplaceMatch;
        while ((batchReplaceMatch = batchReplaceBlockRegex.exec(content)) !== null) {
            const filePath = batchReplaceMatch[1].trim();
            const allReplacements = batchReplaceMatch[2];
            
            // Process each replacement pair within the batch
            const replacements = [];
            const innerReplaceRegex = /```(\w*)\n([\s\S]*?)```\n```(\w*)\n([\s\S]*?)```/g;
            
            let innerMatch;
            while ((innerMatch = innerReplaceRegex.exec(allReplacements)) !== null) {
                replacements.push({
                    searchLanguage: innerMatch[1].trim().toLowerCase() || 'text',
                    searchCode: innerMatch[2],
                    replaceLanguage: innerMatch[3].trim().toLowerCase() || 'text',
                    replaceCode: innerMatch[4]
                });
            }
            
            codeBlocks.push({
                type: 'batch_replacement',
                filePath: filePath,
                replacements: replacements,
                fullMatch: batchReplaceMatch[0]
            });
        }
        
        // Then check for single replacement patterns
        let replaceMatch;
        while ((replaceMatch = replaceBlockRegex.exec(content)) !== null) {
            const filePath = replaceMatch[1].trim();
            const searchLanguage = replaceMatch[2].trim().toLowerCase() || 'text';
            const searchCode = replaceMatch[3];
            const replaceLanguage = replaceMatch[4].trim().toLowerCase() || 'text';
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
        
        // Finally extract regular code blocks
        let match;
        while ((match = codeBlockRegex.exec(content)) !== null) {
            // Skip blocks that were already captured as part of replacement blocks
            let isPartOfReplacement = false;
            for (const block of codeBlocks) {
                if ((block.type === 'replacement' || block.type === 'batch_replacement') && 
                    (content.indexOf(match[0]) >= content.indexOf(block.fullMatch) && 
                     content.indexOf(match[0]) <= content.indexOf(block.fullMatch) + block.fullMatch.length)) {
                    isPartOfReplacement = true;
                    break;
                }
            }
            
            if (!isPartOfReplacement) {
                const language = match[1].trim().toLowerCase() || 'text';
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
        
        // Check for replacement blocks first
        const replacementBlocks = codeBlocks.filter(block => block.type === 'replacement');
        if (replacementBlocks.length > 0) {
            console.log('Found replacement blocks:', replacementBlocks);
            
            // Process the first replacement block
            const replaceBlock = replacementBlocks[0];
            
            // If we have the replace in file tool bridge function
            if (window.intellijBridge && window.intellijBridge.replaceInFile) {
                console.log('Sending replacement to IDE:', replaceBlock);
                window.intellijBridge.replaceInFile({
                    filePath: replaceBlock.filePath,
                    search: replaceBlock.searchCode,
                    replace: replaceBlock.replaceCode,
                    regex: false,
                    caseSensitive: true
                }).then(function() {
                    console.log('Replacement sent to IntelliJ successfully');
                }).catch(function(error) {
                    console.error('Failed to send replacement to IntelliJ:', error);
                });
                return;
            }
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
            console.log('Sending extracted code to IDE:', selectedBlock);
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