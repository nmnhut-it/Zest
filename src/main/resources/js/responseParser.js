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
        
        let match;
        while ((match = codeBlockRegex.exec(content)) !== null) {
            const language = match[1].trim().toLowerCase() || 'text';
            const code = match[2];
            
            codeBlocks.push({
                language: language,
                code: code,
                fullMatch: match[0]
            });
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
        
        // Find the most appropriate code block to extract
        // Prioritize Java, JavaScript, TypeScript, etc.
        const priorityLanguages = ['java', 'javascript', 'typescript', 'js', 'ts', 'kotlin', 'scala', 'xml', 'json', 'html', 'css'];
        
        // First, try to find a code block with a priority language
        let selectedBlock = null;
        for (const lang of priorityLanguages) {
            selectedBlock = codeBlocks.find(block => block.language === lang);
            if (selectedBlock) break;
        }
        
        // If no priority language found, take the last code block
        if (!selectedBlock && codeBlocks.length > 0) {
            selectedBlock = codeBlocks[codeBlocks.length - 1];
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