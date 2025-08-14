package com.zps.zest.langchain4j.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.langchain4j.spi.json.JsonCodec;
import dev.langchain4j.spi.json.JsonCodecFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.logging.Logger;

/**
 * Custom JSON Codec implementation for Zest plugin.
 * This codec is designed to work within IntelliJ plugin environment
 * and avoid conflicts with other Jackson configurations.
 */
public class ZestJsonCodec implements JsonCodecFactory {
    
    private static final Logger LOGGER = Logger.getLogger(ZestJsonCodec.class.getName());
    
    private final ObjectMapper objectMapper;
    
    public ZestJsonCodec() {
        this.objectMapper = createCustomObjectMapper();
    }
    
    private ObjectMapper createCustomObjectMapper() {
        return JsonMapper.builder()
                // Configure basic features
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.INDENT_OUTPUT, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                
                // Include non-null values only
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                
                // Add modules for better type support
                .addModule(new JavaTimeModule())
                .addModule(new Jdk8Module())
                .addModule(new ParameterNamesModule())
                .addModule(createCustomModule())
                
                // Build the mapper
                .build();
    }
    
    private SimpleModule createCustomModule() {
        SimpleModule module = new SimpleModule("ZestJsonModule");
        
        // Add any custom serializers/deserializers here if needed
        // For example, if you need special handling for certain IntelliJ types
        
        return module;
    }
    
    @Override
    public <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            LOGGER.severe("Failed to deserialize JSON to " + clazz.getName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to deserialize JSON", e);
        }
    }
    
    @Override
    public <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            JavaType javaType = objectMapper.constructType(type);
            return objectMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            LOGGER.severe("Failed to deserialize JSON to " + type.getTypeName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to deserialize JSON", e);
        }
    }
    
    @Override
    public <T> T fromJson(InputStream inputStream, Class<T> clazz) {
        if (inputStream == null) {
            return null;
        }
        
        try {
            return objectMapper.readValue(inputStream, clazz);
        } catch (IOException e) {
            LOGGER.severe("Failed to deserialize InputStream to " + clazz.getName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to deserialize InputStream", e);
        }
    }
    
    @Override
    public <T> T fromJson(InputStream inputStream, Type type) {
        if (inputStream == null) {
            return null;
        }
        
        try {
            JavaType javaType = objectMapper.constructType(type);
            return objectMapper.readValue(inputStream, javaType);
        } catch (IOException e) {
            LOGGER.severe("Failed to deserialize InputStream to " + type.getTypeName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to deserialize InputStream", e);
        }
    }
    
    @Override
    public String toJson(Object object) {
        if (object == null) {
            return null;
        }
        
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            LOGGER.severe("Failed to serialize object of type " + object.getClass().getName() + ": " + e.getMessage());
            throw new RuntimeException("Failed to serialize object", e);
        }
    }
    
    @Override
    public InputStream toInputStream(Object object, Type type) {
        if (object == null) {
            return null;
        }
        
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(object);
            return new ByteArrayInputStream(bytes);
        } catch (JsonProcessingException e) {
            LOGGER.severe("Failed to serialize object to InputStream: " + e.getMessage());
            throw new RuntimeException("Failed to serialize object to InputStream", e);
        }
    }
    
    /**
     * Get the underlying ObjectMapper for advanced use cases.
     * Use with caution as direct modifications might affect the codec behavior.
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
    
    /**
     * Utility method to pretty print JSON
     */
    public String toPrettyJson(Object object) {
        if (object == null) {
            return null;
        }
        
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            LOGGER.severe("Failed to serialize object to pretty JSON: " + e.getMessage());
            throw new RuntimeException("Failed to serialize object to pretty JSON", e);
        }
    }
}
