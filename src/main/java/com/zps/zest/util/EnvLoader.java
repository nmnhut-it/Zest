package com.zps.zest.util;

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for loading environment variables from .env file
 */
public class EnvLoader {
    private static final Logger LOG = Logger.getInstance(EnvLoader.class);
    private static Map<String, String> envVars = new HashMap<>();
    private static boolean loaded = false;

    /**
     * Load environment variables from .env file in plugin directory
     */
    public static void loadEnv(String ignored) {
        if (loaded) {
            LOG.info("Environment variables already loaded");
            return;
        }
        
        // Look for .env in the plugin's directory (D:\Zest\.env)
        Path pluginEnvFile = Paths.get("D:\\Zest", ".env");
        
        // Also try relative to the plugin jar location
        Path relativeEnvFile = Paths.get(System.getProperty("user.dir"), ".env");
        
        Path envFile = null;
        if (Files.exists(pluginEnvFile)) {
            envFile = pluginEnvFile;
            LOG.info("Found .env file in plugin directory: " + envFile.toAbsolutePath());
        } else if (Files.exists(relativeEnvFile)) {
            envFile = relativeEnvFile;
            LOG.info("Found .env file in current directory: " + envFile.toAbsolutePath());
        } else {
            LOG.warn("No .env file found. Checked: " + pluginEnvFile.toAbsolutePath() + " and " + relativeEnvFile.toAbsolutePath());
            return;
        }

        try {
            Files.readAllLines(envFile).forEach(line -> {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    return;
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    
                    // Remove quotes if present
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    
                    envVars.put(key, value);
                    LOG.info("Loaded env var: " + key);
                }
            });
            loaded = true;
            LOG.info("Successfully loaded " + envVars.size() + " environment variables from .env");
        } catch (IOException e) {
            LOG.error("Failed to load .env file", e);
        }
    }

    /**
     * Get an environment variable value
     */
    public static String getEnv(String key) {
        return envVars.get(key);
    }

    /**
     * Get an environment variable with fallback to system env
     */
    public static String getEnv(String key, String defaultValue) {
        String value = envVars.get(key);
        if (value != null) {
            return value;
        }
        value = System.getProperty(key);
        if (value != null) return value;

        // Fallback to system environment
        value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Check if environment variables are loaded
     */
    public static boolean isLoaded() {
        return loaded;
    }
}