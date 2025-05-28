/**
 * LLM Provider for OpenWebUI Integration
 * Handles communication with OpenWebUI API for AI-powered features
 */

(function() {
    'use strict';

    window.LLMProvider = {
        config: {
            defaultModel: 'Qwen2.5-Coder-7B',
            temperature: 0.7,
            maxTokens: 200,
            stream: false
        },
        
        // Usage types for tracking
        UsageTypes: {
            CHAT_GIT_COMMIT_MESSAGE: 'CHAT_GIT_COMMIT_MESSAGE',
            CODE_GENERATION: 'CODE_GENERATION',
            CODE_REVIEW: 'CODE_REVIEW',
            CODE_EXPLANATION: 'CODE_EXPLANATION',
            DOCUMENTATION: 'DOCUMENTATION',
            DEBUGGING: 'DEBUGGING',
            GENERAL_CHAT: 'GENERAL_CHAT'
        },
        
        /**
         * Initialize the LLM Provider
         */
        initialize: function(customConfig = {}) {
            this.config = { ...this.config, ...customConfig };
            console.log('LLM Provider initialized with config:', this.config);
        },
        
        /**
         * Get authentication token from cookie
         */
        getAuthTokenFromCookie: function() {
            const cookies = document.cookie.split(';');
            for (let cookie of cookies) {
                const [name, value] = cookie.trim().split('=');
                if (name === 'auth_token' || name === 'token') {
                    return decodeURIComponent(value);
                }
            }
            
            // Try localStorage as fallback
            const localToken = localStorage.getItem('auth_token') || localStorage.getItem('token');
            if (localToken) {
                return localToken;
            }
            
            return null;
        },
        
        /**
         * Main API call method
         */
        callAPI: async function(prompt, options = {}) {
            const {
                model = this.config.defaultModel,
                temperature = this.config.temperature,
                maxTokens = this.config.maxTokens,
                stream = this.config.stream,
                usageType = this.UsageTypes.GENERAL_CHAT,
                systemPrompt = null,
                messages = null
            } = options;
            
            // Set usage type for tracking
            window.__zest_usage__ = usageType;
            
            // Get current page URL to determine API endpoint
            const currentUrl = window.location.origin;
            const apiUrl = `${currentUrl}/api/chat/completions`;
            
            console.log('Calling OpenWebUI API at:', apiUrl);
            console.log('Usage type:', usageType);
            
            // Get auth token
            const authToken = this.getAuthTokenFromCookie();
            if (!authToken) {
                throw new Error('No authentication token found. Please ensure you are logged in.');
            }
            
            // Build messages array
            let messageArray = [];
            
            if (systemPrompt) {
                messageArray.push({
                    role: "system",
                    content: systemPrompt
                });
            }
            
            if (messages) {
                messageArray = [...messageArray, ...messages];
            } else {
                messageArray.push({
                    role: "user",
                    content: prompt
                });
            }
            
            const payload = {
                model: model,
                messages: messageArray,
                stream: stream,
                temperature: temperature,
                max_tokens: maxTokens
            };
            
            try {
                const response = await fetch(apiUrl, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${authToken}`
                    },
                    body: JSON.stringify(payload)
                });
                
                if (!response.ok) {
                    const errorText = await response.text();
                    throw new Error(`API request failed: ${response.status} ${response.statusText} - ${errorText}`);
                }
                
                const data = await response.json();
                
                if (data.choices && data.choices.length > 0 && data.choices[0].message) {
                    return data.choices[0].message.content.trim();
                } else {
                    throw new Error('Invalid API response format');
                }
            } catch (error) {
                console.error('LLM API call failed:', error);
                throw error;
            } finally {
                // Clear usage type
                delete window.__zest_usage__;
            }
        },
        
        /**
         * Specialized method for generating git commit messages
         */
        generateCommitMessage: async function(diff, options = {}) {
            const systemPrompt = `You are a helpful assistant that generates concise, clear, and conventional git commit messages based on code changes. 
Follow these guidelines:
- Use present tense ("Add feature" not "Added feature")
- Keep the first line under 50 characters
- Capitalize the first letter
- No period at the end of the subject line
- Use conventional commit format when appropriate (feat:, fix:, docs:, style:, refactor:, test:, chore:)`;
            
            const prompt = `Generate a git commit message for the following changes:\n\n${diff}`;
            
            return this.callAPI(prompt, {
                ...options,
                systemPrompt: systemPrompt,
                usageType: this.UsageTypes.CHAT_GIT_COMMIT_MESSAGE,
                temperature: 0.5, // Lower temperature for more consistent commit messages
                maxTokens: 100 // Commit messages should be concise
            });
        },
        
        /**
         * Generate code based on description
         */
        generateCode: async function(description, language = 'javascript', options = {}) {
            const systemPrompt = `You are an expert ${language} developer. Generate clean, well-documented, and efficient code based on the user's requirements. Include appropriate error handling and follow best practices for ${language}.`;
            
            const prompt = `Generate ${language} code for: ${description}`;
            
            return this.callAPI(prompt, {
                ...options,
                systemPrompt: systemPrompt,
                usageType: this.UsageTypes.CODE_GENERATION,
                temperature: 0.7,
                maxTokens: 1000
            });
        },
        
        /**
         * Review code and provide feedback
         */
        reviewCode: async function(code, language = 'javascript', options = {}) {
            const systemPrompt = `You are a senior code reviewer. Analyze the provided ${language} code for:
- Potential bugs or errors
- Performance issues
- Security vulnerabilities
- Code style and best practices
- Suggestions for improvement
Provide constructive feedback with specific examples.`;
            
            const prompt = `Review this ${language} code:\n\n\`\`\`${language}\n${code}\n\`\`\``;
            
            return this.callAPI(prompt, {
                ...options,
                systemPrompt: systemPrompt,
                usageType: this.UsageTypes.CODE_REVIEW,
                temperature: 0.3, // Lower temperature for more focused analysis
                maxTokens: 800
            });
        },
        
        /**
         * Explain code functionality
         */
        explainCode: async function(code, language = 'javascript', options = {}) {
            const systemPrompt = `You are a patient programming teacher. Explain the provided code in clear, simple terms that a beginner could understand. Break down complex concepts and explain what each part does.`;
            
            const prompt = `Explain this ${language} code:\n\n\`\`\`${language}\n${code}\n\`\`\``;
            
            return this.callAPI(prompt, {
                ...options,
                systemPrompt: systemPrompt,
                usageType: this.UsageTypes.CODE_EXPLANATION,
                temperature: 0.5,
                maxTokens: 600
            });
        },
        
        /**
         * Generate documentation
         */
        generateDocumentation: async function(code, language = 'javascript', options = {}) {
            const systemPrompt = `You are a technical writer. Generate comprehensive documentation for the provided code including:
- Overview of functionality
- Parameters/arguments description
- Return values
- Usage examples
- Any important notes or warnings
Use appropriate documentation format for ${language} (JSDoc, docstrings, etc.)`;
            
            const prompt = `Generate documentation for this ${language} code:\n\n\`\`\`${language}\n${code}\n\`\`\``;
            
            return this.callAPI(prompt, {
                ...options,
                systemPrompt: systemPrompt,
                usageType: this.UsageTypes.DOCUMENTATION,
                temperature: 0.3,
                maxTokens: 800
            });
        },
        
        /**
         * Help with debugging
         */
        debugCode: async function(code, error, language = 'javascript', options = {}) {
            const systemPrompt = `You are a debugging expert. Analyze the provided code and error message to:
- Identify the root cause of the issue
- Explain why the error occurs
- Provide a corrected version of the code
- Suggest preventive measures`;
            
            const prompt = `Debug this ${language} code that produces the following error:\n\nError: ${error}\n\nCode:\n\`\`\`${language}\n${code}\n\`\`\``;
            
            return this.callAPI(prompt, {
                ...options,
                systemPrompt: systemPrompt,
                usageType: this.UsageTypes.DEBUGGING,
                temperature: 0.3,
                maxTokens: 1000
            });
        },
        
        /**
         * Stream API call (for real-time responses)
         */
        streamAPI: async function(prompt, onChunk, options = {}) {
            const {
                model = this.config.defaultModel,
                temperature = this.config.temperature,
                maxTokens = this.config.maxTokens,
                usageType = this.UsageTypes.GENERAL_CHAT,
                systemPrompt = null
            } = options;
            
            window.__zest_usage__ = usageType;
            
            const currentUrl = window.location.origin;
            const apiUrl = `${currentUrl}/api/chat/completions`;
            
            const authToken = this.getAuthTokenFromCookie();
            if (!authToken) {
                throw new Error('No authentication token found.');
            }
            
            let messages = [];
            if (systemPrompt) {
                messages.push({ role: "system", content: systemPrompt });
            }
            messages.push({ role: "user", content: prompt });
            
            const payload = {
                model: model,
                messages: messages,
                stream: true,
                temperature: temperature,
                max_tokens: maxTokens
            };
            
            try {
                const response = await fetch(apiUrl, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${authToken}`
                    },
                    body: JSON.stringify(payload)
                });
                
                if (!response.ok) {
                    throw new Error(`API request failed: ${response.status}`);
                }
                
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.startsWith('data: ')) {
                            const data = line.slice(6);
                            if (data === '[DONE]') continue;
                            
                            try {
                                const parsed = JSON.parse(data);
                                if (parsed.choices?.[0]?.delta?.content) {
                                    onChunk(parsed.choices[0].delta.content);
                                }
                            } catch (e) {
                                console.error('Error parsing SSE data:', e);
                            }
                        }
                    }
                }
            } finally {
                delete window.__zest_usage__;
            }
        },
        
        /**
         * List available models
         */
        getAvailableModels: async function() {
            const currentUrl = window.location.origin;
            const apiUrl = `${currentUrl}/api/models`;
            
            const authToken = this.getAuthTokenFromCookie();
            if (!authToken) {
                throw new Error('No authentication token found.');
            }
            
            try {
                const response = await fetch(apiUrl, {
                    headers: {
                        'Authorization': `Bearer ${authToken}`
                    }
                });
                
                if (!response.ok) {
                    throw new Error(`Failed to fetch models: ${response.status}`);
                }
                
                const data = await response.json();
                return data.models || [];
            } catch (error) {
                console.error('Failed to fetch models:', error);
                return [];
            }
        },
        
        /**
         * Update configuration
         */
        updateConfig: function(newConfig) {
            this.config = { ...this.config, ...newConfig };
            console.log('LLM Provider config updated:', this.config);
        }
    };
    
    // Auto-initialize
    window.LLMProvider.initialize();
    
    // Make it available to IntelliJ Bridge
    if (window.intellijBridge) {
        window.intellijBridge.llmProvider = window.LLMProvider;
    }
    
    console.log('LLM Provider loaded and initialized');
})();
