/**
 * File API for Research Agent
 * Provides file system operations for the research agent with security and performance optimizations
 */

const fs = require('fs').promises;
const path = require('path');
const { minimatch } = require('minimatch');

class FileAPI {
    constructor(config = {}) {
        this.config = {
            maxFileSize: 10 * 1024 * 1024, // 10MB default
            encoding: 'utf8',
            excludePatterns: [
                '.git/**',
                '**/node_modules/**',
                '**/dist/**',
                '**/build/**',
                '**/.idea/**',
                '**/coverage/**',
                '**/.next/**',
                '**/.nuxt/**',
                '**/vendor/**',
                '**/__pycache__/**',
                '**/*.pyc',
                '**/.DS_Store',
                '**/Thumbs.db'
            ],
            fileExtensions: ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs', '.json'],
            ...config
        };
        
        // Cache for file listings to improve performance
        this.directoryCache = new Map();
        this.cacheTimeout = 60000; // 1 minute
    }

    /**
     * Normalize path for consistent handling
     */
    normalizePath(filePath) {
        return path.normalize(filePath).replace(/\\/g, '/');
    }

    /**
     * Check if a path should be excluded based on patterns
     */
    isExcluded(filePath, additionalExcludes = []) {
        const normalizedPath = this.normalizePath(filePath);
        const allExcludes = [...this.config.excludePatterns, ...additionalExcludes];
        
        return allExcludes.some(pattern => {
            return minimatch(normalizedPath, pattern, { dot: true });
        });
    }

    /**
     * Check if file has valid extension
     */
    hasValidExtension(filePath, extensions = null) {
        const validExtensions = extensions || this.config.fileExtensions;
        if (!validExtensions || validExtensions.length === 0) return true;
        
        const ext = path.extname(filePath).toLowerCase();
        return validExtensions.includes(ext);
    }

    /**
     * List files recursively with filtering
     */
    async listFilesRecursive(dirPath, options = {}) {
        const {
            exclude = [],
            extensions = null,
            maxDepth = Infinity,
            includeDirectories = false,
            currentDepth = 0
        } = options;

        const results = [];
        
        try {
            // Check cache first
            const cacheKey = `${dirPath}_${JSON.stringify(options)}`;
            const cached = this.getFromCache(cacheKey);
            if (cached) return cached;

            const entries = await fs.readdir(dirPath, { withFileTypes: true });
            
            for (const entry of entries) {
                const fullPath = path.join(dirPath, entry.name);
                const normalizedPath = this.normalizePath(fullPath);
                
                // Skip if excluded
                if (this.isExcluded(normalizedPath, exclude)) {
                    continue;
                }
                
                if (entry.isDirectory()) {
                    if (includeDirectories) {
                        results.push({
                            path: normalizedPath,
                            type: 'directory',
                            name: entry.name
                        });
                    }
                    
                    // Recurse if within depth limit
                    if (currentDepth < maxDepth) {
                        const subFiles = await this.listFilesRecursive(fullPath, {
                            ...options,
                            currentDepth: currentDepth + 1
                        });
                        results.push(...subFiles);
                    }
                } else if (entry.isFile()) {
                    // Check extension
                    if (this.hasValidExtension(fullPath, extensions)) {
                        results.push({
                            path: normalizedPath,
                            type: 'file',
                            name: entry.name
                        });
                    }
                }
            }
            
            // Cache results
            this.setCache(cacheKey, results);
            
            return results;
            
        } catch (error) {
            console.error(`Error listing files in ${dirPath}:`, error);
            throw error;
        }
    }

    /**
     * Read file with size check
     */
    async readFile(filePath, encoding = null) {
        try {
            const stats = await fs.stat(filePath);
            
            // Check file size
            if (stats.size > this.config.maxFileSize) {
                throw new Error(`File too large: ${stats.size} bytes (max: ${this.config.maxFileSize})`);
            }
            
            const content = await fs.readFile(filePath, encoding || this.config.encoding);
            return content;
            
        } catch (error) {
            console.error(`Error reading file ${filePath}:`, error);
            throw error;
        }
    }

    /**
     * Read multiple files in parallel
     */
    async readMultipleFiles(filePaths, encoding = null) {
        const results = await Promise.allSettled(
            filePaths.map(filePath => this.readFile(filePath, encoding))
        );
        
        return results.map((result, index) => ({
            path: filePaths[index],
            status: result.status,
            content: result.status === 'fulfilled' ? result.value : null,
            error: result.status === 'rejected' ? result.reason.message : null
        }));
    }

    /**
     * Search for text in files
     */
    async searchInFiles(dirPath, searchText, options = {}) {
        const {
            caseSensitive = false,
            wholeWord = false,
            regex = false,
            exclude = [],
            extensions = null,
            maxResults = 1000,
            contextLines = 3
        } = options;

        const results = [];
        let totalMatches = 0;
        
        // Build search pattern
        let searchPattern;
        if (regex) {
            searchPattern = new RegExp(searchText, caseSensitive ? 'g' : 'gi');
        } else {
            const escapedText = searchText.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            const pattern = wholeWord ? `\\b${escapedText}\\b` : escapedText;
            searchPattern = new RegExp(pattern, caseSensitive ? 'g' : 'gi');
        }
        
        // Get all files
        const files = await this.listFilesRecursive(dirPath, {
            exclude,
            extensions
        });
        
        // Search each file
        for (const file of files) {
            if (file.type !== 'file') continue;
            
            try {
                const content = await this.readFile(file.path);
                const lines = content.split('\n');
                const fileMatches = [];
                
                lines.forEach((line, lineIndex) => {
                    const matches = Array.from(line.matchAll(searchPattern));
                    
                    if (matches.length > 0) {
                        fileMatches.push({
                            line: lineIndex + 1,
                            column: matches[0].index + 1,
                            text: line.trim(),
                            matches: matches.map(m => ({
                                text: m[0],
                                index: m.index
                            })),
                            context: this.getContext(lines, lineIndex, contextLines)
                        });
                        
                        totalMatches += matches.length;
                    }
                });
                
                if (fileMatches.length > 0) {
                    results.push({
                        file: file.path,
                        matches: fileMatches
                    });
                    
                    if (totalMatches >= maxResults) {
                        break;
                    }
                }
                
            } catch (error) {
                // Skip files that can't be read
                console.warn(`Skipping file ${file.path}: ${error.message}`);
            }
        }
        
        return {
            query: searchText,
            options,
            totalMatches,
            results
        };
    }

    /**
     * Find JavaScript functions
     */
    async findFunctions(dirPath, functionName = null, options = {}) {
        const {
            exclude = [],
            includeArrow = true,
            includeClass = true,
            includeExports = true
        } = options;

        const patterns = [
            // Traditional function declaration
            /function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)/g,
            // Async function
            /async\s+function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)/g
        ];

        if (includeArrow) {
            // Arrow functions assigned to variables
            patterns.push(
                /(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(?:async\s*)?\([^)]*\)\s*=>/g,
                /(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(?:async\s*)?[a-zA-Z_$][a-zA-Z0-9_$]*\s*=>/g
            );
        }

        if (includeClass) {
            // Class methods
            patterns.push(
                /(?:async\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)\s*{/g,
                /([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(?:async\s*)?\([^)]*\)\s*=>/g
            );
        }

        if (includeExports) {
            // Exported functions
            patterns.push(
                /export\s+(?:default\s+)?(?:async\s+)?function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)/g,
                /export\s+(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(?:async\s*)?\(/g
            );
        }

        const results = [];
        const files = await this.listFilesRecursive(dirPath, {
            exclude,
            extensions: ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs']
        });

        for (const file of files) {
            if (file.type !== 'file') continue;

            try {
                const content = await this.readFile(file.path);
                const functions = this.extractFunctions(content, patterns, functionName);
                
                if (functions.length > 0) {
                    results.push({
                        file: file.path,
                        functions
                    });
                }
            } catch (error) {
                console.warn(`Error processing ${file.path}: ${error.message}`);
            }
        }

        return results;
    }

    /**
     * Extract functions from content
     */
    extractFunctions(content, patterns, targetName = null) {
        const functions = [];
        const lines = content.split('\n');
        const foundFunctions = new Set();

        for (const pattern of patterns) {
            pattern.lastIndex = 0;
            let match;

            while ((match = pattern.exec(content)) !== null) {
                const funcName = match[1];
                
                // Skip if already found or doesn't match target
                if (foundFunctions.has(funcName)) continue;
                if (targetName && funcName !== targetName) continue;

                foundFunctions.add(funcName);
                
                const position = match.index;
                const lineNumber = content.substring(0, position).split('\n').length;
                
                // Extract function body
                const { body, endLine } = this.extractFunctionBody(content, position);
                
                functions.push({
                    name: funcName,
                    line: lineNumber,
                    endLine,
                    signature: this.extractSignature(body),
                    body: body.substring(0, 500) + (body.length > 500 ? '...' : ''),
                    fullMatch: match[0]
                });
            }
        }

        return functions;
    }

    /**
     * Extract function body starting from position
     */
    extractFunctionBody(content, startPos) {
        let braceCount = 0;
        let inString = false;
        let stringChar = '';
        let escaped = false;
        let foundFirstBrace = false;
        let endPos = startPos;

        for (let i = startPos; i < content.length; i++) {
            const char = content[i];

            if (escaped) {
                escaped = false;
                continue;
            }

            if (char === '\\') {
                escaped = true;
                continue;
            }

            if (inString) {
                if (char === stringChar) {
                    inString = false;
                }
            } else {
                if (char === '"' || char === "'" || char === '`') {
                    inString = true;
                    stringChar = char;
                } else if (char === '{') {
                    foundFirstBrace = true;
                    braceCount++;
                } else if (char === '}' && foundFirstBrace) {
                    braceCount--;
                    if (braceCount === 0) {
                        endPos = i + 1;
                        break;
                    }
                }
            }
        }

        const body = content.substring(startPos, endPos);
        const endLine = content.substring(0, endPos).split('\n').length;
        
        return { body, endLine };
    }

    /**
     * Extract function signature
     */
    extractSignature(functionBody) {
        const lines = functionBody.split('\n');
        let signature = '';
        
        for (const line of lines) {
            signature += line + ' ';
            if (line.includes('{')) {
                break;
            }
        }
        
        return signature.trim().replace(/\s+/g, ' ');
    }

    /**
     * Get context lines
     */
    getContext(lines, lineIndex, contextSize = 3) {
        return {
            before: lines.slice(Math.max(0, lineIndex - contextSize), lineIndex),
            current: lines[lineIndex],
            after: lines.slice(lineIndex + 1, Math.min(lines.length, lineIndex + contextSize + 1))
        };
    }

    /**
     * Get directory tree structure
     */
    async getDirectoryTree(dirPath, options = {}) {
        const {
            maxDepth = 5,
            exclude = [],
            extensions = null,
            currentDepth = 0
        } = options;

        const name = path.basename(dirPath);
        const normalizedPath = this.normalizePath(dirPath);
        
        if (this.isExcluded(normalizedPath, exclude)) {
            return null;
        }

        try {
            const stats = await fs.stat(dirPath);
            
            if (!stats.isDirectory()) {
                if (this.hasValidExtension(dirPath, extensions)) {
                    return {
                        name,
                        type: 'file',
                        path: normalizedPath
                    };
                }
                return null;
            }

            if (currentDepth >= maxDepth) {
                return {
                    name,
                    type: 'directory',
                    path: normalizedPath,
                    children: []
                };
            }

            const entries = await fs.readdir(dirPath, { withFileTypes: true });
            const children = [];

            for (const entry of entries) {
                const childPath = path.join(dirPath, entry.name);
                const child = await this.getDirectoryTree(childPath, {
                    ...options,
                    currentDepth: currentDepth + 1
                });
                
                if (child) {
                    children.push(child);
                }
            }

            return {
                name,
                type: 'directory',
                path: normalizedPath,
                children: children.sort((a, b) => {
                    // Directories first, then files
                    if (a.type !== b.type) {
                        return a.type === 'directory' ? -1 : 1;
                    }
                    return a.name.localeCompare(b.name);
                })
            };

        } catch (error) {
            console.error(`Error processing ${dirPath}:`, error);
            return null;
        }
    }

    /**
     * Cache management
     */
    getFromCache(key) {
        const cached = this.directoryCache.get(key);
        if (cached && Date.now() - cached.timestamp < this.cacheTimeout) {
            return cached.data;
        }
        this.directoryCache.delete(key);
        return null;
    }

    setCache(key, data) {
        this.directoryCache.set(key, {
            data,
            timestamp: Date.now()
        });
        
        // Limit cache size
        if (this.directoryCache.size > 100) {
            const firstKey = this.directoryCache.keys().next().value;
            this.directoryCache.delete(firstKey);
        }
    }

    clearCache() {
        this.directoryCache.clear();
    }

    /**
     * Get file stats
     */
    async getFileStats(filePath) {
        try {
            const stats = await fs.stat(filePath);
            return {
                size: stats.size,
                created: stats.birthtime,
                modified: stats.mtime,
                accessed: stats.atime,
                isDirectory: stats.isDirectory(),
                isFile: stats.isFile(),
                permissions: stats.mode.toString(8).slice(-3)
            };
        } catch (error) {
            throw new Error(`Cannot access file: ${error.message}`);
        }
    }
}

// Export for Node.js
if (typeof module !== 'undefined' && module.exports) {
    module.exports = FileAPI;
}

// Export for browser/ES modules
if (typeof window !== 'undefined') {
    window.FileAPI = FileAPI;
}
