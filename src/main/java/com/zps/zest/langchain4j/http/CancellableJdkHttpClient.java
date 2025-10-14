package com.zps.zest.langchain4j.http;

import com.intellij.openapi.diagnostic.Logger;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellable HTTP client wrapper for aborting ongoing LLM requests.
 * Implements LangChain4j HttpClient interface with cancellation support.
 */
public class CancellableJdkHttpClient implements HttpClient {
    private static final Logger LOG = Logger.getInstance(CancellableJdkHttpClient.class);

    private final HttpClient delegateClient;
    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public CancellableJdkHttpClient(HttpClient delegateClient) {
        this.delegateClient = delegateClient;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        if (cancelled.get()) {
            throw new CancellationException("HTTP client is cancelled");
        }

        Thread currentThread = Thread.currentThread();
        activeThreads.add(currentThread);

        try {
            return delegateClient.execute(request);
        } finally {
            activeThreads.remove(currentThread);
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        if (cancelled.get()) {
            listener.onError(new CancellationException("HTTP client is cancelled"));
            return;
        }

        Thread currentThread = Thread.currentThread();
        activeThreads.add(currentThread);

        try {
            delegateClient.execute(request, parser, listener);
        } finally {
            activeThreads.remove(currentThread);
        }
    }

    /**
     * Cancel all active requests by interrupting their threads
     */
    public void cancelAll() {
        LOG.info("Cancelling all active HTTP requests (" + activeThreads.size() + " active threads)");
        cancelled.set(true);

        for (Thread thread : activeThreads) {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }

        activeThreads.clear();
        LOG.info("All HTTP request threads interrupted and cancelled");
    }

    /**
     * Reset cancellation state for new requests
     */
    public void reset() {
        cancelled.set(false);
        activeThreads.clear();
        LOG.info("HTTP client reset - ready for new requests");
    }

    /**
     * Check if client is cancelled
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Get count of active requests
     */
    public int getActiveRequestCount() {
        return activeThreads.size();
    }

    /**
     * Exception thrown when client is cancelled
     */
    public static class CancellationException extends RuntimeException {
        public CancellationException(String message) {
            super(message);
        }
    }
}
