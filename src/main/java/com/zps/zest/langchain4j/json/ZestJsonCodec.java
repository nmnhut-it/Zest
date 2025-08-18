package com.zps.zest.langchain4j.json;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.internal.Json;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Custom JSON Codec implementation using Gson for IntelliJ plugin environment.
 * This avoids Jackson conflicts and uses Gson which is often bundled with IntelliJ.
 */
public class ZestJsonCodec implements Json.JsonCodec {
    
    private final Gson gson;
    
    public ZestJsonCodec() {
        this.gson = new GsonBuilder()
                // Configure Gson for better compatibility
                .serializeNulls()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                
                // Add custom type adapters for common Java 8 time types
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                
                // Create the Gson instance
                .create();
    }
    
    @Override
    public String toJson(Object o) {
        if (o == null) {
            return null;
        }
        return gson.toJson(o);
    }
    
    @Override
    public <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return gson.fromJson(json, type);
    }
    
    @Override
    public <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return gson.fromJson(json, type);
    }
    
    // Custom adapter for LocalDateTime
    private static class LocalDateTimeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        
        @Override
        public JsonElement serialize(LocalDateTime src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(formatter.format(src));
        }
        
        @Override
        public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) 
                throws JsonParseException {
            return LocalDateTime.parse(json.getAsString(), formatter);
        }
    }
    
    // Custom adapter for LocalDate
    private static class LocalDateAdapter implements JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;
        
        @Override
        public JsonElement serialize(LocalDate src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(formatter.format(src));
        }
        
        @Override
        public LocalDate deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) 
                throws JsonParseException {
            return LocalDate.parse(json.getAsString(), formatter);
        }
    }
}
