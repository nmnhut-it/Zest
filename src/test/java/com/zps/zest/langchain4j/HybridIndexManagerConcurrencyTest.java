package com.zps.zest.langchain4j;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test for concurrent indexing behavior in HybridIndexManager.
 */
public class HybridIndexManagerConcurrencyTest extends BasePlatformTestCase {

    @Test
    public void testConcurrentIndexingRequests() throws Exception {
        // Get the hybrid index manager
        HybridIndexManager indexManager = getProject().getService(HybridIndexManager.class);
        assertNotNull("HybridIndexManager should be available", indexManager);

        // Clear any existing index
        indexManager.clearIndices();
        assertFalse("Should have no index initially", indexManager.hasIndex());

        // Create a latch to synchronize multiple threads
        CountDownLatch startLatch = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        // Start multiple concurrent indexing requests
        int numThreads = 5;
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Try to start indexing
                    System.out.println("Thread " + threadId + " requesting indexing");
                    CompletableFuture<Boolean> indexFuture = indexManager.indexProjectAsync(false);
                    
                    // All threads should get the same future
                    return indexFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            });
            futures.add(future);
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all futures to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        allFutures.get(60, TimeUnit.SECONDS);

        // Verify all futures completed successfully
        for (CompletableFuture<Boolean> future : futures) {
            assertTrue("All indexing requests should complete successfully", future.get());
        }

        // Verify that indexing actually happened
        assertTrue("Should have index after concurrent requests", indexManager.hasIndex());
        
        // Verify we're not still indexing
        assertFalse("Should not be indexing anymore", indexManager.isIndexing());
        assertNull("Should have no current indexing future", indexManager.getCurrentIndexingFuture());
    }

    @Test
    public void testIndexingStatusTracking() throws Exception {
        HybridIndexManager indexManager = getProject().getService(HybridIndexManager.class);
        assertNotNull("HybridIndexManager should be available", indexManager);

        // Clear any existing index
        indexManager.clearIndices();

        // Check initial status
        var status = indexManager.getIndexingStatus();
        assertFalse("Should not be indexing initially", (Boolean) status.get("isIndexing"));
        assertFalse("Should not have index initially", (Boolean) status.get("hasIndex"));
        assertFalse("Should not be in progress initially", (Boolean) status.get("inProgress"));

        // Start indexing
        CompletableFuture<Boolean> indexFuture = indexManager.indexProjectAsync(false);
        
        // Check status during indexing
        status = indexManager.getIndexingStatus();
        assertTrue("Should be indexing", (Boolean) status.get("isIndexing"));
        assertTrue("Should be in progress", (Boolean) status.get("inProgress"));

        // Wait for completion
        Boolean result = indexFuture.get(30, TimeUnit.SECONDS);
        assertTrue("Indexing should complete successfully", result);

        // Check final status
        status = indexManager.getIndexingStatus();
        assertFalse("Should not be indexing after completion", (Boolean) status.get("isIndexing"));
        assertFalse("Should not be in progress after completion", (Boolean) status.get("inProgress"));
        
        // Note: hasIndex might still be false if no files were indexed in the test project
        // This is okay for the test - we're mainly testing the concurrency behavior
    }
}
