package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles chunked messaging for large JavaScript messages that exceed JBCef/CEF message size limits.
 * This class manages message reassembly from chunks sent by the JavaScript bridge.
 */
public class ChunkedMessageHandler {
    private static final Logger LOG = Logger.getInstance(ChunkedMessageHandler.class);
    private static final long CHUNK_EXPIRY_TIME_MS = 300000; // 300 seconds (5 minutes) - allows for large messages and slow networks

    private final ConcurrentMap<String, ChunkedMessage> pendingMessages = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    
    public ChunkedMessageHandler() {
        // Schedule cleanup of expired chunks every 30 seconds
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredChunks, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Processes a potentially chunked message.
     * @param query The message query (either single message or chunk)
     * @return ProcessResult indicating if message is complete and the assembled content
     */
    public ProcessResult processChunkedMessage(String query) {
        try {
            // Check if this is a chunked message by looking for chunk markers
            if (isChunkedMessage(query)) {
                return handleChunkedMessage(query);
            } else {
                // Not a chunked message, return as complete
                return new ProcessResult(true, query);
            }
        } catch (Exception e) {
            LOG.error("Error processing chunked message", e);
            return new ProcessResult(false, "Error processing message: " + e.getMessage());
        }
    }
    
    /**
     * Checks if a message is part of a chunked sequence.
     */
    private boolean isChunkedMessage(String query) {
        return query.startsWith("__CHUNK__");
    }
    
    /**
     * Handles a chunked message by parsing and reassembling chunks.
     */
    private ProcessResult handleChunkedMessage(String query) {
        try {
            // Parse chunk format: __CHUNK__sessionId|chunkIndex|totalChunks|data
            int prefixLength = "__CHUNK__".length();
            if (query.length() <= prefixLength) {
                LOG.error("Chunk message too short: " + query);
                return new ProcessResult(false, "Invalid chunk format - message too short");
            }
            
            String[] parts = query.substring(prefixLength).split("\\|", 4);
            if (parts.length != 4) {
                LOG.error("Invalid chunk format: " + query);
                return new ProcessResult(false, "Invalid chunk format");
            }
            
            String sessionId = parts[0];
            int chunkIndex = Integer.parseInt(parts[1]);
            int totalChunks = Integer.parseInt(parts[2]);
            String chunkData = parts[3];
            
            LOG.info("Received chunk " + (chunkIndex + 1) + "/" + totalChunks + " for session " + sessionId);
            
            // Get or create chunked message
            ChunkedMessage chunkedMessage = pendingMessages.computeIfAbsent(sessionId, 
                k -> new ChunkedMessage(sessionId, totalChunks));
            
            // Add this chunk
            boolean isComplete = chunkedMessage.addChunk(chunkIndex, chunkData);
            
            if (isComplete) {
                // Remove from pending and return complete message
                pendingMessages.remove(sessionId);
                String assembledMessage = chunkedMessage.getAssembledMessage();
                LOG.info("Successfully assembled message from " + totalChunks + " chunks for session " + sessionId);
                return new ProcessResult(true, assembledMessage);
            } else {
                // Still waiting for more chunks
                return new ProcessResult(false, "Waiting for chunk " + (chunkedMessage.getReceivedChunks() + 1) + "/" + totalChunks);
            }
            
        } catch (Exception e) {
            LOG.error("Error handling chunked message", e);
            return new ProcessResult(false, "Error handling chunked message: " + e.getMessage());
        }
    }
    
    /**
     * Cleans up expired chunks to prevent memory leaks.
     */
    private void cleanupExpiredChunks() {
        long currentTime = System.currentTimeMillis();
        int cleaned = 0;
        
        for (String sessionId : pendingMessages.keySet()) {
            ChunkedMessage message = pendingMessages.get(sessionId);
            if (message != null && (currentTime - message.getCreatedTime()) > CHUNK_EXPIRY_TIME_MS) {
                pendingMessages.remove(sessionId);
                cleaned++;
                LOG.info("Cleaned up expired chunk session: " + sessionId);
            }
        }
        
        if (cleaned > 0) {
            LOG.info("Cleaned up " + cleaned + " expired chunk sessions");
        }
    }
    
    /**
     * Disposes of resources used by the chunked message handler.
     */
    public void dispose() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pendingMessages.clear();
    }
    
    /**
     * Represents a chunked message being assembled.
     */
    private static class ChunkedMessage {
        private final String sessionId;
        private final int totalChunks;
        private final String[] chunks;
        private final long createdTime;
        private int receivedChunks = 0;
        
        public ChunkedMessage(String sessionId, int totalChunks) {
            this.sessionId = sessionId;
            this.totalChunks = totalChunks;
            this.chunks = new String[totalChunks];
            this.createdTime = System.currentTimeMillis();
        }
        
        public synchronized boolean addChunk(int index, String data) {
            if (index < 0 || index >= totalChunks) {
                LOG.error("Invalid chunk index " + index + " for session " + sessionId);
                return false;
            }
            
            if (chunks[index] == null) {
                chunks[index] = data;
                receivedChunks++;
            }
            
            return receivedChunks == totalChunks;
        }
        
        public synchronized String getAssembledMessage() {
            if (receivedChunks != totalChunks) {
                return null;
            }
            
            StringBuilder assembled = new StringBuilder();
            for (String chunk : chunks) {
                if (chunk != null) {
                    assembled.append(chunk);
                }
            }
            return assembled.toString();
        }
        
        public long getCreatedTime() {
            return createdTime;
        }
        
        public int getReceivedChunks() {
            return receivedChunks;
        }
    }
    
    /**
     * Result of processing a chunked message.
     */
    public static class ProcessResult {
        private final boolean complete;
        private final String assembledMessage;
        
        public ProcessResult(boolean complete, String assembledMessage) {
            this.complete = complete;
            this.assembledMessage = assembledMessage;
        }
        
        public boolean isComplete() {
            return complete;
        }
        
        public String getAssembledMessage() {
            return assembledMessage;
        }
    }
}
