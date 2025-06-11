package com.zps.zest.langchain4j.agent.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.json.JsonMapper;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;

/**
 * Custom Gson mapper for Javalin to avoid Jackson classloader conflicts in IntelliJ plugin.
 */
public class GsonMapper implements JsonMapper {
    
    private final Gson gson;
    
    public GsonMapper() {
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    }
    
    @NotNull
    @Override
    public <T> T fromJsonString(@NotNull String json, @NotNull Type targetType) {
        return gson.fromJson(json, targetType);
    }
    
    @NotNull
    @Override
    public <T> T fromJsonStream(@NotNull InputStream json, @NotNull Type targetType) {
        return gson.fromJson(new InputStreamReader(json), targetType);
    }
    
    @NotNull
    @Override
    public String toJsonString(@NotNull Object obj, @NotNull Type type) {
        return gson.toJson(obj, type);
    }

}
