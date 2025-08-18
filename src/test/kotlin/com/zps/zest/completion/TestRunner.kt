package com.zps.zest.completion

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Test suite runner for all completion service tests
 * Run this to execute the complete test suite
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    ZestInlineCompletionServiceTest::class,
    CompletionRefactoringTest::class
    // Add other test classes here as needed
)
class CompletionServiceTestSuite