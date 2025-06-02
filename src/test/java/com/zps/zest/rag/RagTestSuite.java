package com.zps.zest.rag;

import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * Test suite for all RAG system tests using JUnit 4.
 */
public class RagTestSuite {
    
    public static Test suite() {
        TestSuite suite = new TestSuite("RAG System Tests");
        
        // Add test classes
        suite.addTestSuite(CodeSignatureTest.class);
        suite.addTestSuite(RagAgentTest.class);
        suite.addTestSuite(SignatureExtractorTest.class);
        suite.addTestSuite(RagSystemIntegrationTest.class);
        
        return suite;
    }
}
