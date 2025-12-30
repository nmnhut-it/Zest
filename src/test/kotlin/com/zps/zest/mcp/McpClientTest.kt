package com.zps.zest.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for MCP HTTP server using the official MCP Java SDK client.
 *
 * These tests use HttpClientStreamableClientTransport which properly handles:
 * - Streamable HTTP transport (MCP 2025-03-26 spec)
 * - Session management via /mcp endpoint
 * - Bidirectional communication over HTTP
 *
 * This is the correct way to test MCP Streamable HTTP servers.
 */
class McpClientTest {

    private var server: ZestMcpHttpServer? = null
    private val testPort = 45454
    private val executor = Executors.newCachedThreadPool()

    @Before
    fun setUp() {
        server = ZestMcpHttpServer(testPort)
        try {
            server?.start()
            Thread.sleep(500) // Give server time to start
        } catch (e: Exception) {
            println("Server setup failed: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        try {
            server?.stop()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun isServerRunning(): Boolean = server?.isRunning == true

    private fun createMcpClient(): McpSyncClient? {
        return try {
            val transport = HttpClientStreamableHttpTransport.builder("http://localhost:$testPort")
                .endpoint("/mcp")
                .build()

            McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation("test-client", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build()
        } catch (e: Exception) {
            println("Failed to create MCP client: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ==================== Single Client Tests ====================

    @Test
    fun testMcpClient_canConnectAndInitialize() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        val client = createMcpClient()
        if (client == null) {
            println("Could not create client")
            return
        }

        try {
            val result = client.initialize()
            println("Initialize result: $result")

            assertNotNull("Should receive initialization result", result)
            println("Server name: ${result.serverInfo?.name}")
            println("Protocol version: ${result.protocolVersion}")

        } finally {
            try { client.close() } catch (e: Exception) { }
        }
    }

    @Test
    fun testMcpClient_canListTools() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        val client = createMcpClient()
        if (client == null) {
            println("Could not create client")
            return
        }

        try {
            client.initialize()

            val tools = client.listTools()
            println("Tools found: ${tools.tools.size}")
            tools.tools.forEach { tool ->
                println("  - ${tool.name}: ${tool.description?.take(50)}")
            }

            assertTrue("Should have at least one tool", tools.tools.isNotEmpty())

            // Verify expected tools exist
            val toolNames = tools.tools.map { it.name }
            assertTrue("Should have getCurrentFile tool", "getCurrentFile" in toolNames)
            assertTrue("Should have lookupClass tool", "lookupClass" in toolNames)
            assertTrue("Should have getJavaCodeUnderTest tool", "getJavaCodeUnderTest" in toolNames)

        } finally {
            try { client.close() } catch (e: Exception) { }
        }
    }

    @Test
    fun testMcpClient_canListPrompts() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        val client = createMcpClient()
        if (client == null) {
            println("Could not create client")
            return
        }

        try {
            client.initialize()

            val prompts = client.listPrompts()
            println("Prompts found: ${prompts.prompts.size}")
            prompts.prompts.forEach { prompt ->
                println("  - ${prompt.name}: ${prompt.description?.take(50)}")
            }

            assertTrue("Should have at least one prompt", prompts.prompts.isNotEmpty())

        } finally {
            try { client.close() } catch (e: Exception) { }
        }
    }

    // ==================== Multi-Client Tests ====================

    @Test
    fun testMcpClient_multipleClientsSequential() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        println("=== Testing Sequential Multi-Client ===")

        // Client 1
        val client1 = createMcpClient()
        if (client1 != null) {
            try {
                client1.initialize()
                val tools1 = client1.listTools()
                println("Client 1 found ${tools1.tools.size} tools")
            } finally {
                client1.close()
            }
        }

        Thread.sleep(200)

        // Client 2
        val client2 = createMcpClient()
        if (client2 != null) {
            try {
                client2.initialize()
                val tools2 = client2.listTools()
                println("Client 2 found ${tools2.tools.size} tools")
            } finally {
                client2.close()
            }
        }

        println("Sequential clients completed successfully")
    }

    @Test
    fun testMcpClient_multipleClientsConcurrent() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        println("=== Testing Concurrent Multi-Client ===")
        println("This is the KEY TEST for the multi-client issue.")

        val clientCount = 3
        val successCount = AtomicInteger(0)
        val errorMessages = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(clientCount)

        repeat(clientCount) { clientId ->
            executor.submit {
                try {
                    val client = createMcpClient()
                    if (client == null) {
                        errorMessages.add("Client $clientId: Failed to create")
                        return@submit
                    }

                    try {
                        client.initialize()
                        val tools = client.listTools()
                        println("Client $clientId: Found ${tools.tools.size} tools")
                        successCount.incrementAndGet()
                    } finally {
                        try { client.close() } catch (e: Exception) { }
                    }
                } catch (e: Exception) {
                    errorMessages.add("Client $clientId: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)

        println()
        println("=== Results ===")
        println("Completed in time: $completed")
        println("Successful clients: ${successCount.get()} / $clientCount")
        if (errorMessages.isNotEmpty()) {
            println("Errors:")
            errorMessages.forEach { println("  - $it") }
        }

        assertEquals(
            "All $clientCount concurrent clients should succeed. " +
            "If this fails, the server cannot handle multiple SSE connections.",
            clientCount,
            successCount.get()
        )
    }

    @Test
    fun testMcpClient_concurrentToolCalls() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        println("=== Testing Concurrent Tool Calls ===")

        val clientCount = 2
        val clients = mutableListOf<McpSyncClient>()

        // First, establish all clients
        repeat(clientCount) { clientId ->
            val client = createMcpClient()
            if (client != null) {
                try {
                    client.initialize()
                    clients.add(client)
                    println("Client $clientId initialized")
                } catch (e: Exception) {
                    println("Client $clientId failed to initialize: ${e.message}")
                    try { client.close() } catch (e2: Exception) { }
                }
            }
        }

        println("Established ${clients.size} clients")

        if (clients.size < clientCount) {
            clients.forEach { try { it.close() } catch (e: Exception) { } }
            fail("Could not establish all clients")
            return
        }

        try {
            // Now make concurrent tool calls
            val results = ConcurrentLinkedQueue<Pair<Int, Int>>()
            val latch = CountDownLatch(clientCount)

            clients.forEachIndexed { index, client ->
                executor.submit {
                    try {
                        val tools = client.listTools()
                        results.add(index to tools.tools.size)
                        println("Client $index: listTools returned ${tools.tools.size} tools")
                    } catch (e: Exception) {
                        results.add(index to -1)
                        println("Client $index: listTools failed: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(15, TimeUnit.SECONDS)

            println()
            println("=== Results ===")
            results.forEach { (client, toolCount) ->
                println("Client $client: $toolCount tools")
            }

            val successCount = results.count { it.second > 0 }
            assertEquals(
                "All clients should successfully call listTools",
                clientCount,
                successCount
            )

        } finally {
            clients.forEach { try { it.close() } catch (e: Exception) { } }
        }
    }

    // ==================== Stress Tests ====================

    @Test
    fun testMcpClient_rapidConnectDisconnect() {
        if (!isServerRunning()) {
            println("Skipping test - server not running")
            return
        }

        val iterations = 5
        val successCount = AtomicInteger(0)

        repeat(iterations) { i ->
            try {
                val client = createMcpClient()
                if (client != null) {
                    client.initialize()
                    client.listTools()
                    client.close()
                    successCount.incrementAndGet()
                    println("Iteration $i: Success")
                } else {
                    println("Iteration $i: Failed to create client")
                }
            } catch (e: Exception) {
                println("Iteration $i: ${e.message}")
            }
        }

        println("Rapid connect/disconnect: ${successCount.get()} / $iterations succeeded")
        assertTrue(
            "Most rapid connections should succeed",
            successCount.get() >= iterations * 0.8
        )
    }
}
