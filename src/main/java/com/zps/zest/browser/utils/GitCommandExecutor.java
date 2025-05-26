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
            LOG.error(errorMessage);
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
        try {
            return execute(workingDir, command);
        } catch (IOException | InterruptedException e) {
            throw new Exception("Git command execution failed: " + e.getMessage(), e);
        }
    }
}
