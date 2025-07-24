// Test file for Block Rewrite Metrics Tracking
// This file tests that metrics are properly tracked for block rewrite operations

// Test scenarios:
// 1. Track rewrite request when user triggers block rewrite
// 2. Track response when LLM returns rewritten code
// 3. Track view event when diff is displayed
// 4. Track accept event when user presses TAB
// 5. Track reject event when user presses ESC
// 6. Track cancel event for various cancellation scenarios

class BlockRewriteMetricsTest {
    constructor() {
        this.metricsEvents = [];
    }

    // Simulate a successful block rewrite flow
    async testSuccessfulRewriteFlow() {
        const rewriteId = "rewrite_123";
        const methodName = "calculateTotal";
        
        // 1. Request event
        this.trackEvent({
            type: "request",
            rewriteId: rewriteId,
            methodName: methodName,
            language: "javascript",
            fileType: "js",
            model: "local-model-mini"
        });
        
        // 2. Response event (after 2 seconds)
        await this.delay(2000);
        this.trackEvent({
            type: "response",
            rewriteId: rewriteId,
            responseTime: 2000,
            success: true,
            content: "function calculateTotal() { /* optimized */ }"
        });
        
        // 3. View event (after parsing)
        await this.delay(500);
        this.trackEvent({
            type: "view",
            rewriteId: rewriteId,
            diffChanges: 15,
            confidence: 0.85
        });
        
        // 4. Accept event (user presses TAB)
        await this.delay(3000);
        this.trackEvent({
            type: "tab",
            rewriteId: rewriteId,
            userAction: "tab",
            viewToAcceptTime: 3000
        });
        
        console.log("Successful rewrite flow completed");
        this.printMetricsSummary();
    }
    
    // Simulate a rejected rewrite flow
    async testRejectedRewriteFlow() {
        const rewriteId = "rewrite_456";
        const methodName = "processData";
        
        // 1. Request event
        this.trackEvent({
            type: "request",
            rewriteId: rewriteId,
            methodName: methodName,
            language: "typescript",
            fileType: "ts",
            model: "local-model-mini"
        });
        
        // 2. Response event
        await this.delay(1500);
        this.trackEvent({
            type: "response",
            rewriteId: rewriteId,
            responseTime: 1500,
            success: true,
            content: "function processData(): void { /* rewritten */ }"
        });
        
        // 3. View event
        await this.delay(300);
        this.trackEvent({
            type: "view",
            rewriteId: rewriteId,
            diffChanges: 8,
            confidence: 0.72
        });
        
        // 4. Reject event (user presses ESC)
        await this.delay(2000);
        this.trackEvent({
            type: "esc",
            rewriteId: rewriteId,
            reason: "esc_pressed"
        });
        
        console.log("Rejected rewrite flow completed");
        this.printMetricsSummary();
    }
    
    // Simulate a cancelled rewrite (timeout)
    async testTimeoutFlow() {
        const rewriteId = "rewrite_789";
        const methodName = "complexAlgorithm";
        
        // 1. Request event
        this.trackEvent({
            type: "request",
            rewriteId: rewriteId,
            methodName: methodName,
            language: "java",
            fileType: "java",
            model: "local-model-mini"
        });
        
        // 2. Cancel event (timeout after 20 seconds)
        await this.delay(20000);
        this.trackEvent({
            type: "anykey",
            rewriteId: rewriteId,
            reason: "timeout"
        });
        
        console.log("Timeout flow completed");
        this.printMetricsSummary();
    }
    
    // Helper methods
    trackEvent(event) {
        const timestamp = Date.now();
        this.metricsEvents.push({
            ...event,
            timestamp: timestamp,
            elapsed: this.metricsEvents.length > 0 
                ? timestamp - this.metricsEvents[0].timestamp 
                : 0
        });
        
        console.log(`Tracked ${event.type} event for ${event.rewriteId}`);
    }
    
    delay(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
    
    printMetricsSummary() {
        console.log("\n=== Metrics Summary ===");
        console.log(`Total events tracked: ${this.metricsEvents.length}`);
        
        const eventTypes = {};
        this.metricsEvents.forEach(event => {
            eventTypes[event.type] = (eventTypes[event.type] || 0) + 1;
        });
        
        console.log("Event breakdown:");
        Object.entries(eventTypes).forEach(([type, count]) => {
            console.log(`  ${type}: ${count}`);
        });
        
        console.log("\nDetailed events:");
        this.metricsEvents.forEach((event, index) => {
            console.log(`${index + 1}. ${event.type} at ${event.elapsed}ms - ${event.rewriteId}`);
        });
    }
}

// Run tests
async function runTests() {
    const tester = new BlockRewriteMetricsTest();
    
    console.log("=== Running Block Rewrite Metrics Tests ===\n");
    
    console.log("Test 1: Successful Rewrite Flow");
    await tester.testSuccessfulRewriteFlow();
    
    console.log("\nTest 2: Rejected Rewrite Flow");
    await tester.testRejectedRewriteFlow();
    
    console.log("\nTest 3: Timeout Flow");
    // Note: This would take 20 seconds, so commenting out for quick testing
    // await tester.testTimeoutFlow();
    
    console.log("\n=== All tests completed ===");
}

// Export for testing
module.exports = {
    BlockRewriteMetricsTest,
    runTests
};

// Run if executed directly
if (require.main === module) {
    runTests().catch(console.error);
}