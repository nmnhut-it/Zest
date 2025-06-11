package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages .gitignore files for index directories to ensure
 * indexed data is not tracked by version control.
 */
public class GitIgnoreManager {
    private static final Logger LOG = Logger.getInstance(GitIgnoreManager.class);
    private static final String GITIGNORE_FILENAME = ".gitignore";
    
    /**
     * Ensures a .gitignore file exists in the given directory with appropriate patterns.
     * 
     * @param directory The directory to add .gitignore to
     * @param patterns Additional patterns to include (beyond the default "ignore all")
     */
    public static void ensureGitIgnore(Path directory, String... patterns) {
        try {
            // Ensure directory exists
            Files.createDirectories(directory);
            
            Path gitignorePath = directory.resolve(GITIGNORE_FILENAME);
            
            // Default patterns - ignore everything in this directory
            Set<String> allPatterns = new HashSet<>(Arrays.asList(
                "# Ignore all files in this directory",
                "*",
                "# But not this .gitignore file",
                "!.gitignore"
            ));
            
            // Add any custom patterns
            if (patterns != null && patterns.length > 0) {
                allPatterns.addAll(Arrays.asList(patterns));
            }
            
            // Check if .gitignore already exists
            if (Files.exists(gitignorePath)) {
                // Read existing content
                List<String> existingLines = Files.readAllLines(gitignorePath);
                Set<String> existingPatterns = new HashSet<>(existingLines);
                
                // Check which patterns are missing
                Set<String> missingPatterns = new HashSet<>();
                for (String pattern : allPatterns) {
                    if (!existingPatterns.contains(pattern)) {
                        missingPatterns.add(pattern);
                    }
                }
                
                if (!missingPatterns.isEmpty()) {
                    // Append missing patterns to the end of the file
                    List<String> linesToWrite = new ArrayList<>(existingLines);
                    
                    // Add a blank line if file doesn't end with one
                    if (!existingLines.isEmpty() && !existingLines.get(existingLines.size() - 1).isEmpty()) {
                        linesToWrite.add("");
                    }
                    
                    // Add missing patterns
                    linesToWrite.addAll(missingPatterns);
                    
                    Files.write(gitignorePath, linesToWrite, 
                        StandardOpenOption.CREATE, 
                        StandardOpenOption.TRUNCATE_EXISTING);
                    LOG.info("Updated .gitignore in: " + directory + " (added " + missingPatterns.size() + " patterns)");
                }
            } else {
                // Create new .gitignore
                Files.write(gitignorePath, allPatterns, 
                    StandardOpenOption.CREATE_NEW);
                LOG.info("Created .gitignore in: " + directory);
            }
            
        } catch (IOException e) {
            LOG.error("Failed to create/update .gitignore in directory: " + directory, e);
        }
    }
    
    /**
     * Ensures .gitignore files exist in all parent directories up to the project root.
     * This prevents intermediate directories from being tracked.
     * 
     * @param leafDirectory The deepest directory in the hierarchy
     * @param projectRoot The project root directory (stop here)
     */
    public static void ensureGitIgnoreHierarchy(Path leafDirectory, Path projectRoot) {
        Path current = leafDirectory;
        
        while (current != null && !current.equals(projectRoot) && current.startsWith(projectRoot)) {
            ensureGitIgnore(current);
            current = current.getParent();
        }
    }
}
