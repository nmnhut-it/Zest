package com.zps.zest.langchain4j.json;

import dev.langchain4j.spi.json.JsonCodecFactory;

/**
 * Custom JSON Codec Factory for Zest plugin to avoid Jackson conflicts in IntelliJ environment.
 */
public class ZestJsonCodecFactory implements JsonCodecFactory {

    @Override
    public dev.langchain4j.internal.Json.JsonCodec create() {
        return new ZestJsonCodec();
    }
}
