package com.zps.zest.browser.utils;

import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Utility class for executing git commands consistently across the application.
 * Centralizes git command execution logic to avoid duplication.
 */
public class GitCommandExecutor {
    private static final Logger LOG = Logger.getInstance(GitCommandExecutor.class);

    /**
     * Executes a git command and returns the output.
     *
     * @param workingDir The working directory where the command should be executed
     * @param command The git command to execute
     * @return The command output as a string
     * @throws IOException If the command execution fails
     * @throws InterruptedException If the command is interrupted
     */
    public static String execute(String workingDir, String command) throws IOException, InterruptedException {
        return execute(workingDir, command, false);
    }

    /**
     * Executes a git command and returns the output.
     *
     * @param workingDir The working directory where the command should be executed
     * @param command The git command to execute
     * @param expectNonZeroExit Whether non-zero exit codes are expected (e.g., for git check-ignore)
     * @return The command output as a string
     * @throws IOException If the command execution fails
     * @throws InterruptedException If the command is interrupted
     */
    public static String execute(String workingDir, String command, boolean expectNonZeroExit) throws IOException, InterruptedException {
        LOG.info("Executing git command: " + command + " in directory: " + workingDir);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new java.io.File(workingDir));

        // Set up the command based on OS
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        // Read the output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        // Wait for the process to complete
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Read error stream if the command failed
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }

            String errorMessage = "Git command exited with code " + exitCode + ": " + error.toString();
            // Only log as error if non-zero exit wasn't expected
            // For commands like git check-ignore, exit code 1 is normal behavior
            if (!expectNonZeroExit) {
                LOG.error(errorMessage);
            } else {
                LOG.debug("Git command exited with expected non-zero code " + exitCode);
            }
            throw new IOException(errorMessage);
        }

        String result = output.toString();
        LOG.info("Git command completed successfully, output length: " + result.length());
        return result;
    }

    /**
     * Executes a git command and returns the output, with exception converted to a generic Exception.
     * This is a convenience method for compatibility with existing code.
     * 
     * @param workingDir The working directory where the command should be executed
     * @param command The git command to execute
     * @return The command output as a string
     * @throws Exception If the command execution fails
     */
    public static String executeWithGenericException(String workingDir, String command) throws Exception {
        return executeWithGenericException(workingDir, command, false);
    }

    /**
     * Executes a git command and returns the output, with exception converted to a generic Exception.
     * This is a convenience method for compatibility with existing code.
     *
     * @param workingDir The working directory where the command should be executed
     * @param command The git command to execute
     * @param expectNonZeroExit Whether non-zero exit codes are expected (e.g., for git check-ignore)
     * @return The command output as a string
     * @throws Exception If the command execution fails
     */
    public static String executeWithGenericException(String workingDir, String command, boolean expectNonZeroExit) throws Exception {
        try {
            return execute(workingDir, command, expectNonZeroExit);
        } catch (IOException | InterruptedException e) {
            throw new Exception("Git command execution failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Properly escapes a file path for use in git commands.
     * Handles paths with spaces and special characters.
     * 
     * @param filePath The file path to escape
     * @return The properly escaped file path for use in shell commands
     */
    public static String escapeFilePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return filePath;
        }
        
        // For Windows and Unix shells, we need to handle spaces and special characters
        // Use single quotes on Unix and escape quotes on Windows
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            // On Windows, double quotes work better, but we need to escape any existing quotes
            return "\"" + filePath.replace("\"", "\\\"") + "\"";
        } else {
            // On Unix systems, single quotes work better for paths with spaces
            // but we need to handle single quotes in the path
            if (filePath.contains("'")) {
                // If path contains single quotes, use double quotes and escape any double quotes
                return "\"" + filePath.replace("\"", "\\\"") + "\"";
            } else {
                return "'" + filePath + "'";
            }
        }
    }
}
