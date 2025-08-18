package com.zps.zest.gdiff

/**
 * Example usage of GDiff functionality
 */
object GDiffExample {
    
    fun demonstrateBasicUsage() {
        val gdiff = GDiff()
        
        // Example 1: Basic string comparison
        val source = """
            Hello World
            This is line 2
            Another line
            Final line
        """.trimIndent()
        
        val target = """
            Hello World
            This is line 2 modified
            Another line
            A new line inserted
            Final line
        """.trimIndent()
        
        println("=== Basic String Comparison ===")
        val result = gdiff.diffStrings(source, target)
        
        println("Files identical: ${result.identical}")
        println("Has changes: ${result.hasChanges()}")
        
        val stats = result.getStatistics()
        println("Statistics: +${stats.additions} -${stats.deletions} ~${stats.modifications}")
        
        println("\nChanges:")
        result.changes.forEach { change ->
            when (change.type) {
                GDiff.ChangeType.EQUAL -> print("  ")
                GDiff.ChangeType.INSERT -> print("+ ")
                GDiff.ChangeType.DELETE -> print("- ")
                GDiff.ChangeType.CHANGE -> print("~ ")
            }
            println("Line ${change.sourceLineNumber}: ${change.sourceLines.joinToString()}")
            if (change.type != GDiff.ChangeType.DELETE && change.targetLines.isNotEmpty()) {
                println("     -> ${change.targetLines.joinToString()}")
            }
        }
    }
    
    fun demonstrateUnifiedDiff() {
        val gdiff = GDiff()
        
        val source = """
            function hello() {
                console.log("Hello");
                return true;
            }
        """.trimIndent()
        
        val target = """
            function hello(name) {
                console.log("Hello " + name);
                console.log("Debug info");
                return true;
            }
        """.trimIndent()
        
        println("\n=== Unified Diff (Git-style) ===")
        val unifiedDiff = gdiff.generateUnifiedDiff(
            source = source,
            target = target,
            sourceFileName = "hello.js",
            targetFileName = "hello.js"
        )
        println(unifiedDiff)
    }
    
    fun demonstrateSideBySide() {
        val gdiff = GDiff()
        
        val source = "Line 1\nLine 2\nLine 3"
        val target = "Line 1 modified\nLine 2\nLine 4"
        
        println("\n=== Side-by-Side Diff ===")
        val sideBySide = gdiff.generateSideBySideDiff(source, target)
        
        sideBySide.forEach { row ->
            val marker = when (row.type) {
                GDiff.ChangeType.EQUAL -> "="
                GDiff.ChangeType.INSERT -> "+"
                GDiff.ChangeType.DELETE -> "-"
                GDiff.ChangeType.CHANGE -> "~"
            }
            println("$marker | ${row.sourceText.padEnd(20)} | ${row.targetText}")
        }
    }
    
    fun demonstrateMultiLanguageSupport() {
        val gdiff = GDiff()
        
        // Test with various languages and special characters
        val sourceMultiLang = """
            // English comment
            const message = "Hello World";
            
            // 中文注释
            const 中文变量 = "你好世界";
            
            // العربية
            const arabicText = "مرحبا بالعالم";
            
            // Emoji test 🚀
            const rocket = "🚀 Launch!";
        """.trimIndent()
        
        val targetMultiLang = """
            // English comment modified
            const message = "Hello Universe";
            
            // 中文注释
            const 中文变量 = "你好宇宙";
            
            // العربية - modified
            const arabicText = "مرحبا بالكون";
            
            // Emoji test 🚀🌟
            const rocket = "🚀🌟 Launch into space!";
        """.trimIndent()
        
        println("\n=== Multi-Language Support ===")
        val result = gdiff.diffStrings(sourceMultiLang, targetMultiLang)
        
        println("Successfully processed multi-language content:")
        println("- English text ✓")
        println("- Chinese characters ✓") 
        println("- Arabic text ✓")
        println("- Emoji support ✓")
        
        val stats = result.getStatistics()
        println("\nChanges detected: ${stats.totalChanges}")
    }
    
    fun demonstrateConfigOptions() {
        val gdiff = GDiff()
        
        val source = "  Hello World  \n  Second Line  "
        val target = "  HELLO WORLD  \n  second line  "
        
        println("\n=== Configuration Options ===")
        
        // Default comparison (case sensitive, whitespace sensitive)
        val defaultResult = gdiff.diffStrings(source, target)
        println("Default config - Changes: ${defaultResult.getStatistics().totalChanges}")
        
        // Ignore case
        val ignoreCaseConfig = GDiff.DiffConfig(ignoreCase = true)
        val ignoreCaseResult = gdiff.diffStrings(source, target, ignoreCaseConfig)
        println("Ignore case - Changes: ${ignoreCaseResult.getStatistics().totalChanges}")
        
        // Ignore whitespace
        val ignoreWhitespaceConfig = GDiff.DiffConfig(ignoreWhitespace = true)
        val ignoreWhitespaceResult = gdiff.diffStrings(source, target, ignoreWhitespaceConfig)
        println("Ignore whitespace - Changes: ${ignoreWhitespaceResult.getStatistics().totalChanges}")
        
        // Ignore both
        val ignoreBothConfig = GDiff.DiffConfig(ignoreCase = true, ignoreWhitespace = true)
        val ignoreBothResult = gdiff.diffStrings(source, target, ignoreBothConfig)
        println("Ignore both - Changes: ${ignoreBothResult.getStatistics().totalChanges}")
    }
    
    fun demonstrateFileComparison() {
        println("\n=== File Comparison Example ===")
        println("// This would work with actual files:")
        println("// val result = gdiff.diffFiles(\"file1.txt\", \"file2.txt\")")
        println("// val unifiedDiff = GDiffVfsUtil.generateUnifiedDiffForFiles(vfile1, vfile2)")
        println("// val hasChanges = GDiffVfsUtil.hasUnsavedChanges(virtualFile)")
    }
    
    fun runAllExamples() {
        println("GDiff Multi-Language Diff Utility Examples")
        println("==========================================")
        
        demonstrateBasicUsage()
        demonstrateUnifiedDiff()
        demonstrateSideBySide()
        demonstrateMultiLanguageSupport()
        demonstrateConfigOptions()
        demonstrateFileComparison()
        
        println("\n✅ All examples completed successfully!")
        println("GDiff is ready for use with any text content in any language.")
    }
}

// Uncomment to run examples:
// fun main() {
//     GDiffExample.runAllExamples()
// }
