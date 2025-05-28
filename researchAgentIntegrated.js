/**
 * Research Agent for IntelliJ JCEF Browser - Integrated with FileAPI
 * Facilitates text search and JavaScript syntax analysis across folders recursively
 */

(function() {
    'use strict';

    // Import or use global FileAPI
    const FileAPI = window.FileAPI || (typeof require !== 'undefined' ? require('./fileAPI.js') : null);
    
    if (!FileAPI) {
        console.error('FileAPI not found! Please ensure fileAPI.js is loaded.');
        return;
    }

    // Define Research Agent Role
    const ResearchAgentRole = {
        id: 'research_agent',
        name: 'Research Agent',
        description: 'Searches code, analyzes JavaScript syntax, and finds function usage across projects',
        capabilities: ['search_text', 'find_syntax', 'analyze_usage', 'find_references', 'extract_signatures'],
        priority: 8
    };

    /**
     * Research Agent Class
     */
    class ResearchAgent extends window.AgentFramework.Agent {
        constructor(config = {}) {
            super(ResearchAgentRole, config);
            
            // Initialize FileAPI instance
            this.fileAPI = new FileAPI({
                maxFileSize: config.maxFileSize || 10 * 1024 * 1024,
                excludePatterns: config.excludePatterns || undefined,
                fileExtensions: config.fileExtensions || undefined
            });
            
            // Cache for search results
            this.searchCache = new Map();
            this.cacheTimeout = 300000; // 5 minutes
        }

        /**
         * Initialize the research agent
         */
        async initialize() {
            await super.initialize();
            console.log('Research Agent initialized with FileAPI integration');
            return this;
        }

        /**
         * Handle text search across folders
         */
        async handle_search_text(task) {
            const { searchText, folderPath = '/', options = {} } = task.data;
            
            console.log(`Searching for "${searchText}" in ${folderPath}`);
            
            // Check cache first
            const cacheKey = `text_${searchText}_${folderPath}_${JSON.stringify(options)}`;
            const cached = this.getCachedResult(cacheKey);
            if (cached) return cached;
            
            try {
                // Use FileAPI to search
                const results = await this.fileAPI.searchInFiles(folderPath, searchText, {
                    caseSensitive: options.caseSensitive || false,
                    wholeWord: options.wholeWord || false,
                    regex: options.regex || false,
                    exclude: options.excludePatterns || [],
                    extensions: options.fileTypes || null,
                    maxResults: options.maxResults || 1000,
                    contextLines: options.contextLines || 3
                });
                
                // Format results for framework
                const formattedResults = {
                    query: results.query,
                    totalMatches: results.totalMatches,
                    files: results.results.map(r => ({
                        path: r.file,
                        matchCount: r.matches.length,
                        matches: r.matches.map(m => ({
                            line: m.line,
                            column: m.column,
                            text: m.text,
                            context: m.context,
                            matchDetails: m.matches
                        }))
                    })),
                    timestamp: Date.now()
                };
                
                // Cache results
                this.setCachedResult(cacheKey, formattedResults);
                
                // Send results to IDE if needed
                if (options.showInIDE) {
                    await window.intellijBridge.callIDE('showSearchResults', {
                        query: searchText,
                        results: formattedResults
                    });
                }
                
                return formattedResults;
                
            } catch (error) {
                console.error('Text search failed:', error);
                throw error;
            }
        }

        /**
         * Handle JavaScript syntax search
         */
        async handle_find_syntax(task) {
            const { functionName, folderPath = '/', options = {} } = task.data;
            
            console.log(`Finding syntax for function "${functionName}" in ${folderPath}`);
            
            // Check cache
            const cacheKey = `syntax_${functionName}_${folderPath}_${JSON.stringify(options)}`;
            const cached = this.getCachedResult(cacheKey);
            if (cached) return cached;
            
            try {
                // Use FileAPI to find functions
                const results = await this.fileAPI.findFunctions(folderPath, functionName, {
                    exclude: options.excludePatterns || [],
                    includeArrow: options.includeArrow !== false,
                    includeClass: options.includeClass !== false,
                    includeExports: options.includeExports !== false
                });
                
                // Analyze and format results
                const analysis = {
                    functionName: functionName,
                    totalOccurrences: results.reduce((sum, r) => sum + r.functions.length, 0),
                    definitions: [],
                    variations: new Set(),
                    files: {}
                };
                
                for (const result of results) {
                    analysis.files[result.file] = result.functions;
                    
                    for (const func of result.functions) {
                        analysis.definitions.push({
                            file: result.file,
                            line: func.line,
                            endLine: func.endLine,
                            signature: func.signature,
                            preview: func.body
                        });
                        
                        analysis.variations.add(func.signature);
                    }
                }
                
                analysis.variations = Array.from(analysis.variations);
                
                // Cache results
                this.setCachedResult(cacheKey, analysis);
                
                return analysis;
                
            } catch (error) {
                console.error('Syntax search failed:', error);
                throw error;
            }
        }

        /**
         * Handle function usage analysis
         */
        async handle_analyze_usage(task) {
            const { functionName, folderPath = '/', options = {} } = task.data;
            
            console.log(`Analyzing usage of "${functionName}" in ${folderPath}`);
            
            try {
                // Search for function calls
                const callPattern = `\\b${functionName}\\s*\\(`;
                const usageResults = await this.fileAPI.searchInFiles(folderPath, callPattern, {
                    regex: true,
                    caseSensitive: options.caseSensitive || false,
                    exclude: options.excludePatterns || [],
                    extensions: options.fileTypes || null,
                    contextLines: 5
                });
                
                // Search for imports
                const importPattern = `import.*\\b${functionName}\\b.*from`;
                const importResults = await this.fileAPI.searchInFiles(folderPath, importPattern, {
                    regex: true,
                    exclude: options.excludePatterns || []
                });
                
                // Create comprehensive usage report
                const report = {
                    functionName: functionName,
                    summary: {
                        totalFiles: usageResults.results.length,
                        totalCalls: usageResults.totalMatches,
                        totalImports: importResults.totalMatches,
                        usageTypes: {
                            calls: usageResults.totalMatches,
                            imports: importResults.totalMatches
                        }
                    },
                    usage: {
                        calls: usageResults.results,
                        imports: importResults.results
                    },
                    graph: this.createUsageGraph(usageResults.results, importResults.results),
                    timestamp: Date.now()
                };
                
                return report;
                
            } catch (error) {
                console.error('Usage analysis failed:', error);
                throw error;
            }
        }

        /**
         * Handle finding all references
         */
        async handle_find_references(task) {
            const { identifier, folderPath = '/', options = {} } = task.data;
            
            console.log(`Finding references to "${identifier}" in ${folderPath}`);
            
            try {
                // Search for all occurrences of the identifier
                const results = await this.fileAPI.searchInFiles(folderPath, identifier, {
                    wholeWord: true,
                    caseSensitive: options.caseSensitive || false,
                    exclude: options.excludePatterns || [],
                    extensions: options.fileTypes || null,
                    contextLines: 3
                });
                
                // Analyze each match to determine reference type
                const references = {
                    declarations: [],
                    calls: [],
                    imports: [],
                    exports: [],
                    properties: [],
                    other: []
                };
                
                for (const fileResult of results.results) {
                    for (const match of fileResult.matches) {
                        const refType = this.classifyReference(match.text, identifier);
                        references[refType].push({
                            file: fileResult.file,
                            line: match.line,
                            column: match.column,
                            text: match.text,
                            context: match.context
                        });
                    }
                }
                
                // Create reference map
                const referenceMap = {
                    identifier: identifier,
                    summary: {
                        totalReferences: results.totalMatches,
                        byType: Object.entries(references).map(([type, refs]) => ({
                            type,
                            count: refs.length
                        }))
                    },
                    references: references,
                    callGraph: this.createCallGraph(identifier, references),
                    timestamp: Date.now()
                };
                
                return referenceMap;
                
            } catch (error) {
                console.error('Reference search failed:', error);
                throw error;
            }
        }

        /**
         * Handle extracting function signatures
         */
        async handle_extract_signatures(task) {
            const { folderPath = '/', options = {} } = task.data;
            
            console.log(`Extracting function signatures from ${folderPath}`);
            
            try {
                // Find all functions (no specific name)
                const results = await this.fileAPI.findFunctions(folderPath, null, {
                    exclude: options.excludePatterns || [],
                    includeArrow: true,
                    includeClass: true,
                    includeExports: true
                });
                
                // Create signature catalog
                const catalog = {
                    totalFiles: results.length,
                    totalFunctions: 0,
                    byType: {},
                    byFile: {},
                    index: []
                };
                
                for (const result of results) {
                    catalog.totalFunctions += result.functions.length;
                    catalog.byFile[result.file] = result.functions;
                    
                    for (const func of result.functions) {
                        // Classify function type
                        const funcType = this.classifyFunctionType(func.signature);
                        
                        if (!catalog.byType[funcType]) {
                            catalog.byType[funcType] = [];
                        }
                        
                        catalog.byType[funcType].push({
                            name: func.name,
                            file: result.file,
                            line: func.line,
                            signature: func.signature
                        });
                        
                        // Add to searchable index
                        catalog.index.push({
                            name: func.name,
                            file: result.file,
                            line: func.line,
                            signature: func.signature,
                            type: funcType
                        });
                    }
                }
                
                // Sort index alphabetically
                catalog.index.sort((a, b) => a.name.localeCompare(b.name));
                
                return catalog;
                
            } catch (error) {
                console.error('Signature extraction failed:', error);
                throw error;
            }
        }

        /**
         * Handle directory tree request
         */
        async handle_get_directory_tree(task) {
            const { folderPath = '/', options = {} } = task.data;
            
            console.log(`Getting directory tree for ${folderPath}`);
            
            try {
                const tree = await this.fileAPI.getDirectoryTree(folderPath, {
                    maxDepth: options.maxDepth || 5,
                    exclude: options.excludePatterns || [],
                    extensions: options.fileTypes || null
                });
                
                return {
                    root: folderPath,
                    tree: tree,
                    timestamp: Date.now()
                };
                
            } catch (error) {
                console.error('Directory tree failed:', error);
                throw error;
            }
        }

        /**
         * Classify reference type based on context
         */
        classifyReference(text, identifier) {
            const patterns = {
                declarations: [
                    new RegExp(`(?:const|let|var|function)\\s+${identifier}\\b`),
                    new RegExp(`class\\s+${identifier}\\b`)
                ],
                calls: [
                    new RegExp(`${identifier}\\s*\\(`),
                    new RegExp(`\\.${identifier}\\s*\\(`)
                ],
                imports: [
                    new RegExp(`import.*\\b${identifier}\\b.*from`),
                    new RegExp(`require.*['"]\\..*${identifier}`)
                ],
                exports: [
                    new RegExp(`export.*\\b${identifier}\\b`),
                    new RegExp(`module\\.exports.*${identifier}`)
                ],
                properties: [
                    new RegExp(`\\.${identifier}\\b(?!\\s*\\()`),
                    new RegExp(`\\[['"]${identifier}['"]\\]`)
                ]
            };
            
            for (const [type, typePatterns] of Object.entries(patterns)) {
                for (const pattern of typePatterns) {
                    if (pattern.test(text)) {
                        return type;
                    }
                }
            }
            
            return 'other';
        }

        /**
         * Classify function type
         */
        classifyFunctionType(signature) {
            if (signature.includes('export')) return 'exported';
            if (signature.includes('async')) return 'async';
            if (signature.includes('=>')) return 'arrow';
            if (signature.includes('function')) return 'regular';
            if (signature.match(/^\s*[a-zA-Z_$][a-zA-Z0-9_$]*\s*\(/)) return 'method';
            return 'other';
        }

        /**
         * Create usage graph for visualization
         */
        createUsageGraph(callResults, importResults) {
            const nodes = [];
            const edges = [];
            
            // Add target function node
            nodes.push({
                id: 'target',
                type: 'function',
                label: 'Target Function'
            });
            
            // Add file nodes for calls
            callResults.forEach((result, index) => {
                const fileId = `call_file_${index}`;
                nodes.push({
                    id: fileId,
                    type: 'file',
                    label: path.basename(result.file),
                    fullPath: result.file,
                    usageCount: result.matches.length
                });
                
                edges.push({
                    source: fileId,
                    target: 'target',
                    type: 'calls',
                    weight: result.matches.length
                });
            });
            
            // Add file nodes for imports
            importResults.forEach((result, index) => {
                const fileId = `import_file_${index}`;
                nodes.push({
                    id: fileId,
                    type: 'file',
                    label: path.basename(result.file),
                    fullPath: result.file,
                    importCount: result.matches.length
                });
                
                edges.push({
                    source: fileId,
                    target: 'target',
                    type: 'imports',
                    weight: result.matches.length
                });
            });
            
            return { nodes, edges };
        }

        /**
         * Create call graph
         */
        createCallGraph(identifier, references) {
            const graph = {
                root: identifier,
                declarations: references.declarations.map(ref => ({
                    file: ref.file,
                    line: ref.line,
                    type: 'declaration'
                })),
                callers: references.calls.map(ref => ({
                    file: ref.file,
                    line: ref.line,
                    context: ref.context
                })),
                imports: references.imports.map(ref => ({
                    file: ref.file,
                    line: ref.line
                }))
            };
            
            return graph;
        }

        /**
         * Cache management
         */
        getCachedResult(key) {
            const cached = this.searchCache.get(key);
            if (cached && Date.now() - cached.timestamp < this.cacheTimeout) {
                console.log(`Cache hit for ${key}`);
                return cached.data;
            }
            this.searchCache.delete(key);
            return null;
        }

        setCachedResult(key, data) {
            this.searchCache.set(key, {
                data: data,
                timestamp: Date.now()
            });
            
            // Clean old cache entries
            this.cleanCache();
        }

        cleanCache() {
            const now = Date.now();
            for (const [key, value] of this.searchCache) {
                if (now - value.timestamp > this.cacheTimeout) {
                    this.searchCache.delete(key);
                }
            }
        }

        /**
         * Export search results
         */
        async exportResults(results, format = 'json') {
            const exportData = {
                timestamp: Date.now(),
                agent: this.role.name,
                results: results
            };
            
            switch (format) {
                case 'json':
                    return JSON.stringify(exportData, null, 2);
                    
                case 'csv':
                    return this.convertToCSV(exportData);
                    
                case 'markdown':
                    return this.convertToMarkdown(exportData);
                    
                default:
                    throw new Error(`Unsupported export format: ${format}`);
            }
        }

        /**
         * Convert results to CSV
         */
        convertToCSV(data) {
            let csv = 'File,Line,Type,Text\n';
            
            if (data.results.files) {
                for (const file of data.results.files) {
                    for (const match of file.matches) {
                        csv += `"${file.path}",${match.line},"${match.type || 'match'}","${match.text.replace(/"/g, '""')}"\n`;
                    }
                }
            }
            
            return csv;
        }

        /**
         * Convert results to Markdown
         */
        convertToMarkdown(data) {
            let markdown = `# Search Results\n\n`;
            markdown += `**Timestamp:** ${new Date(data.timestamp).toISOString()}\n\n`;
            
            if (data.results.summary) {
                markdown += `## Summary\n\n`;
                for (const [key, value] of Object.entries(data.results.summary)) {
                    markdown += `- **${key}:** ${value}\n`;
                }
                markdown += '\n';
            }
            
            if (data.results.files) {
                markdown += `## Results by File\n\n`;
                for (const file of data.results.files) {
                    markdown += `### ${file.path}\n\n`;
                    for (const match of file.matches) {
                        markdown += `- **Line ${match.line}:** \`${match.text}\`\n`;
                        if (match.context) {
                            markdown += '  ```\n';
                            match.context.before.forEach(line => markdown += `  ${line}\n`);
                            markdown += `> ${match.context.current}\n`;
                            match.context.after.forEach(line => markdown += `  ${line}\n`);
                            markdown += '  ```\n';
                        }
                    }
                    markdown += '\n';
                }
            }
            
            return markdown;
        }
    }

    // Register the Research Agent role with the framework
    if (window.AgentFramework && window.AgentFramework.AgentRoles) {
        window.AgentFramework.AgentRoles.RESEARCH = ResearchAgentRole;
        
        // Add to agent manager
        window.AgentFramework.AgentManager.createAgent = (function(originalCreate) {
            return async function(roleId, config) {
                if (roleId === 'RESEARCH') {
                    const agent = new ResearchAgent(config);
                    await agent.initialize();
                    this.agents.set(agent.id, agent);
                    return agent;
                }
                return originalCreate.call(this, roleId, config);
            };
        })(window.AgentFramework.AgentManager.createAgent);
        
        console.log('Research Agent registered with Agent Framework (FileAPI integrated)');
    }

    // Export for direct use
    window.ResearchAgent = ResearchAgent;
    window.ResearchAgentRole = ResearchAgentRole;

    // Integration with IntelliJ Bridge
    if (window.intellijBridge) {
        window.intellijBridge.researchAgent = {
            create: async (config) => {
                const agent = new ResearchAgent(config);
                await agent.initialize();
                return agent;
            },
            search: async (searchText, folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_search_text({
                    data: { searchText, folderPath, options }
                });
            },
            findSyntax: async (functionName, folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_find_syntax({
                    data: { functionName, folderPath, options }
                });
            },
            analyzeUsage: async (functionName, folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_analyze_usage({
                    data: { functionName, folderPath, options }
                });
            },
            findReferences: async (identifier, folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_find_references({
                    data: { identifier, folderPath, options }
                });
            },
            getDirectoryTree: async (folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_get_directory_tree({
                    data: { folderPath, options }
                });
            }
        };
        
        console.log('Research Agent integrated with IntelliJ Bridge');
    }

    // Usage examples in console
    console.log('%c📚 Research Agent with FileAPI Loaded!', 'color: #4CAF50; font-size: 14px; font-weight: bold;');
    console.log('Example usage:');
    console.log('  const agent = await window.AgentFramework.createAgent("RESEARCH");');
    console.log('  agent.queueTask({ type: "search_text", data: { searchText: "TODO", folderPath: "/src" } });');
    console.log('  agent.queueTask({ type: "find_syntax", data: { functionName: "handleClick", folderPath: "/" } });');
    console.log('  agent.queueTask({ type: "analyze_usage", data: { functionName: "useState", folderPath: "/src" } });');
    console.log('  agent.queueTask({ type: "get_directory_tree", data: { folderPath: "/src", options: { maxDepth: 3 } } });');

})();
