package com.zps.zest.langchain4j.http;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Builder for creating CancellableJdkHttpClient instances with custom configuration.
 * Delegates to LangChain4j's JdkHttpClient and wraps with cancellation support.
 */
public class CancellableJdkHttpClientBuilder implements HttpClientBuilder {

    private java.net.http.HttpClient.Builder httpClientBuilder;
    private Duration connectTimeout;
    private Duration readTimeout;
    private CancellableJdkHttpClient client;

    private CancellableJdkHttpClientBuilder() {
        this.httpClientBuilder = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2);
    }

    public static CancellableJdkHttpClientBuilder builder() {
        return new CancellableJdkHttpClientBuilder();
    }

    public CancellableJdkHttpClientBuilder httpClientBuilder(java.net.http.HttpClient.Builder builder) {
        this.httpClientBuilder = builder;
        return this;
    }

    public CancellableJdkHttpClientBuilder executor(Executor executor) {
        this.httpClientBuilder.executor(executor);
        return this;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    public CancellableJdkHttpClientBuilder connectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        this.httpClientBuilder.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    public CancellableJdkHttpClientBuilder readTimeout(Duration timeout) {
        this.readTimeout = timeout;
        return this;
    }

    public CancellableJdkHttpClient getClient() {
        if (client == null) {
            HttpClient delegateClient = dev.langchain4j.http.client.jdk.JdkHttpClient.builder()
                    .httpClientBuilder(httpClientBuilder)
                    .connectTimeout(connectTimeout)
                    .readTimeout(readTimeout)
                    .build();

            client = new CancellableJdkHttpClient(delegateClient);
        }
        return client;
    }

    @Override
    public HttpClient build() {
        return getClient();
    }
}
