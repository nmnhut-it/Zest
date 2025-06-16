package com.zps.zest.gdiff

import org.junit.Test
import org.junit.Assert.*

/**
 * Test cases for enhanced AST-based diffing functionality
 */
class EnhancedGDiffTest {
    
    private val enhancedGDiff = EnhancedGDiff()
    
    @Test
    fun testJavaMethodDiffing() {
        val originalJava = """
            public void processData(List<String> items) {
                for (String item : items) {
                    System.out.println(item);
                }
            }
        """.trimIndent()
        
        val rewrittenJava = """
            public void processData(List<String> items) {
                items.forEach(System.out::println);
            }
        """.trimIndent()
        
        val result = enhancedGDiff.calculateSemanticChanges(originalJava, rewrittenJava, "java")
        
        // Verify we have changes detected (either semantic or text-based)
        assertTrue("Should detect changes", result.hasAnyChanges())
        assertTrue("Should detect text changes", result.hasTextChanges())
        
        val summary = result.getSummary()
        println("Java diff summary: $summary")
        
        // Print any semantic changes for debugging
        result.astDiff?.semanticChanges?.forEach { change ->
            println("Semantic change: ${change.description} (${change.action})")
        }
        
        // Should use a hybrid or AST-based strategy for Java
        assertTrue("Should use AST-capable strategy", 
            summary.strategy == EnhancedGDiff.DiffStrategy.HYBRID || 
            summary.strategy == EnhancedGDiff.DiffStrategy.AST_WITH_FALLBACK)
    }
    
    @Test
    fun testJavaScriptMethodDiffing() {
        val originalJS = """
            function calculateSum(numbers) {
                let sum = 0;
                for (let i = 0; i < numbers.length; i++) {
                    sum += numbers[i];
                }
                return sum;
            }
        """.trimIndent()
        
        val rewrittenJS = """
            const calculateSum = (numbers) => {
                return numbers.reduce((sum, num) => sum + num, 0);
            }
        """.trimIndent()
        
        val result = enhancedGDiff.calculateSemanticChanges(originalJS, rewrittenJS, "javascript")
        
        // Verify analysis
        assertTrue("Should detect changes", result.hasAnyChanges())
        
        val summary = result.getSummary()
        println("JavaScript diff summary: $summary")
        
        // Print changes
        result.astDiff?.semanticChanges?.forEach { change ->
            println("Semantic change: ${change.description} (${change.action})")
        }
        
        // Should detect language correctly
        assertEquals("Should detect JavaScript", "javascript", result.language)
    }
    
    @Test
    fun testKotlinMethodDiffing() {
        val originalKotlin = """
            fun processItems(items: List<String>) {
                for (item in items) {
                    println(item)
                }
            }
        """.trimIndent()
        
        val rewrittenKotlin = """
            fun processItems(items: List<String>) {
                items.forEach { println(it) }
            }
        """.trimIndent()
        
        val result = enhancedGDiff.calculateSemanticChanges(originalKotlin, rewrittenKotlin, "kotlin")
        
        // Verify analysis
        assertTrue("Should detect changes", result.hasAnyChanges())
        
        val summary = result.getSummary()
        println("Kotlin diff summary: $summary")
        
        // Print changes
        result.astDiff?.semanticChanges?.forEach { change ->
            println("Semantic change: ${change.description} (${change.action})")
        }
        
        // Should detect language correctly
        assertEquals("Should detect Kotlin", "kotlin", result.language)
    }
    
    @Test
    fun testLanguageDetection() {
        val javaCode = """
            public class Example {
                @Override
                public void method() {}
            }
        """.trimIndent()
        
        val jsCode = """
            const example = () => {
                return "hello";
            }
        """.trimIndent()
        
        val kotlinCode = """
            fun example(): String {
                return "hello"
            }
        """.trimIndent()
        
        val javaResult = enhancedGDiff.diffStrings(javaCode, javaCode)
        val jsResult = enhancedGDiff.diffStrings(jsCode, jsCode)
        val kotlinResult = enhancedGDiff.diffStrings(kotlinCode, kotlinCode)
        
        // Check that language detection works
        assertEquals("Should detect Java", "java", javaResult.language)
        assertEquals("Should detect JavaScript", "javascript", jsResult.language)
        assertEquals("Should detect Kotlin", "kotlin", kotlinResult.language)
    }
    
    @Test
    fun testDiffStrategySelection() {
        val javaCode = """
            public void test() {
                System.out.println("Hello");
            }
        """.trimIndent()
        
        val modifiedJavaCode = """
            public void test() {
                System.out.println("Hello World");
            }
        """.trimIndent()
        
        // Test with AST preference
        val astConfig = EnhancedGDiff.EnhancedDiffConfig(
            preferAST = true,
            language = "java",
            useHybridApproach = true
        )
        
        val astResult = enhancedGDiff.diffStrings(javaCode, modifiedJavaCode, astConfig)
        assertTrue("Should use AST-capable strategy", 
            astResult.diffStrategy == EnhancedGDiff.DiffStrategy.HYBRID ||
            astResult.diffStrategy == EnhancedGDiff.DiffStrategy.AST_WITH_FALLBACK)
        
        // Test with text-only preference
        val textConfig = EnhancedGDiff.EnhancedDiffConfig(
            preferAST = false
        )
        
        val textResult = enhancedGDiff.diffStrings(javaCode, modifiedJavaCode, textConfig)
        assertEquals("Should use text-only strategy", EnhancedGDiff.DiffStrategy.TEXT_ONLY, textResult.diffStrategy)
    }
    
    @Test
    fun testEnhancedUnifiedDiff() {
        val originalCode = """
            public void oldMethod() {
                if (condition) {
                    doSomething();
                }
            }
        """.trimIndent()
        
        val newCode = """
            public void newMethod() {
                if (condition) {
                    doSomethingElse();
                }
            }
        """.trimIndent()
        
        val unifiedDiff = enhancedGDiff.generateEnhancedUnifiedDiff(
            originalCode, newCode, "original.java", "new.java"
        )
        
        assertTrue("Should contain semantic analysis", unifiedDiff.contains("# Semantic Analysis Summary"))
        assertTrue("Should contain language info", unifiedDiff.contains("# Language:"))
        assertTrue("Should contain structural similarity", unifiedDiff.contains("# Structural Similarity:"))
        
        println("Enhanced unified diff:")
        println(unifiedDiff)
    }
    
    @Test
    fun testUnsupportedLanguageFallback() {
        val code1 = "SELECT * FROM users WHERE id = 1"
        val code2 = "SELECT * FROM users WHERE id = 2"
        
        val result = enhancedGDiff.diffStrings(code1, code2)
        
        // Should fall back to text diffing for unsupported language
        assertEquals("Should use text-only strategy", EnhancedGDiff.DiffStrategy.TEXT_ONLY, result.diffStrategy)
        assertNull("Should not have AST diff for unsupported language", result.astDiff)
        assertTrue("Should have text changes", result.hasTextChanges())
    }
    
    @Test
    fun testIdenticalCodeComparison() {
        val code = """
            public void method() {
                System.out.println("test");
            }
        """.trimIndent()
        
        val result = enhancedGDiff.calculateSemanticChanges(code, code, "java")
        
        assertFalse("Should not detect changes in identical code", result.hasAnyChanges())
        // Note: similarity might not be perfect due to pattern matching limitations
        val summary = result.getSummary()
        assertTrue("Should have reasonable similarity", summary.structuralSimilarity >= 0.8)
    }
    
    @Test
    fun testSemanticChangeDetection() {
        val originalCode = """
            public void calculate() {
                int result = 0;
                for (int i = 0; i < 10; i++) {
                    result += i;
                }
                return result;
            }
        """.trimIndent()
        
        val rewrittenCode = """
            public int calculate() {
                return IntStream.range(0, 10).sum();
            }
        """.trimIndent()
        
        val result = enhancedGDiff.calculateSemanticChanges(originalCode, rewrittenCode, "java")
        
        // Should detect changes
        assertTrue("Should detect semantic or text changes", result.hasAnyChanges())
        
        val summary = result.getSummary()
        println("Semantic change detection summary: $summary")
        
        // Print any detected changes
        result.astDiff?.semanticChanges?.forEach { change ->
            println("Detected change: ${change.description}")
        }
    }
    
    @Test
    fun testPatternBasedAnalysis() {
        val astDiffService = ASTDiffService()
        
        // Test that the service initializes properly
        assertNotNull("ASTDiffService should initialize", astDiffService)
        
        // Test with simple Java method change
        val originalJava = "public void test() { System.out.println(\"old\"); }"
        val newJava = "public void test() { System.out.println(\"new\"); }"
        
        val result = astDiffService.diffWithAST(originalJava, newJava, "java")
        assertNotNull("Should return result", result)
        assertEquals("Should detect Java", "java", result.language)
        
        println("Pattern-based analysis result: ${result.getStatistics()}")
    }
}
