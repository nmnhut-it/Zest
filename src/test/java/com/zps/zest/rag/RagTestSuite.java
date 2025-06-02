package com.zps.zest.rag;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Test suite for all RAG system tests using JUnit Platform Suite.
 */
@Suite
@SelectClasses({
    RagAgentTest.class,
    SignatureExtractorTest.class,
    RagSystemIntegrationTest.class
})
public class RagTestSuite {
    // This class is empty, it's just a holder for the suite annotations
}
