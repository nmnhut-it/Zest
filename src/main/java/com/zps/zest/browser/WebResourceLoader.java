package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Utility class for loading web resources.
 */
public class WebResourceLoader {
    private static final Logger LOG = Logger.getInstance(WebResourceLoader.class);
    
    /**
     * Loads a web resource from the classpath.
     *
     * @param resourcePath The path to the resource
     * @return The content of the resource as a string, or null if an error occurs
     */
    public static String loadResourceAsString(String resourcePath) {
        try {
            URL resourceUrl = WebResourceLoader.class.getResource(resourcePath);
            if (resourceUrl == null) {
                LOG.error("Resource not found: " + resourcePath);
                return null;
            }
            
            try (InputStream in = resourceUrl.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.error("Error loading resource: " + resourcePath, e);
            return null;
        }
    }
    
    /**
     * Creates a temporary file from a classpath resource.
     *
     * @param resourcePath The path to the resource
     * @param prefix The prefix for the temporary file
     * @param suffix The suffix for the temporary file
     * @return The path to the temporary file, or null if an error occurs
     */
    public static Path createTempFileFromResource(String resourcePath, String prefix, String suffix) {
        try {
            URL resourceUrl = WebResourceLoader.class.getResource(resourcePath);
            if (resourceUrl == null) {
                LOG.error("Resource not found: " + resourcePath);
                return null;
            }
            
            Path tempFile = Files.createTempFile(prefix, suffix);
            
            try (InputStream in = resourceUrl.openStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // Ensure the file is deleted when the JVM exits
            tempFile.toFile().deleteOnExit();
            
            return tempFile;
        } catch (IOException e) {
            LOG.error("Error creating temporary file from resource: " + resourcePath, e);
            return null;
        }
    }
}
