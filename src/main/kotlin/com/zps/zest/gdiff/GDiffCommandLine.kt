package com.zps.zest.gdiff

import java.io.File

/**
 * Command-line interface for GDiff functionality
 * Can be used for testing and standalone usage
 */
object GDiffCommandLine {
    
    @JvmStatic
    fun main(args: Array<String>) {
        when {
            args.isEmpty() -> {
                printUsage()
                runDemoExamples()
            }
            args.size == 1 && args[0] == "--demo" -> {
                runDemoExamples()
            }
            args.size == 2 -> {
                compareFiles(args[0], args[1])
            }
            args.size >= 3 && args[0] == "--config" -> {
                compareFilesWithConfig(args)
            }
            else -> {
                printUsage()
            }
        }
    }
    
    private fun printUsage() {
        println("""
            GDiff - Multi-Language Diff Utility
            
            Usage:
              gdiff --demo                               Run demonstration examples
              gdiff <file1> <file2>                     Compare two files
              gdiff --config <options> <file1> <file2>  Compare with configuration
              
            Configuration options:
              --ignore-case         Ignore case differences
              --ignore-whitespace   Ignore whitespace differences
              --context=N           Set context lines for unified diff (default: 3)
              
            Examples:
              gdiff file1.txt file2.txt
              gdiff --config --ignore-case --ignore-whitespace source.java target.java
              gdiff --demo
        """.trimIndent())
    }
    
    private fun compareFiles(file1Path: String, file2Path: String) {
        val file1 = File(file1Path)
        val file2 = File(file2Path)
        
        if (!file1.exists()) {
            println("Error: File not found: $file1Path")
            return
        }
        
        if (!file2.exists()) {
            println("Error: File not found: $file2Path")
            return
        }
        
        val gdiff = GDiff()
        
        try {
            val result = gdiff.diffFiles(file1, file2)
            printComparisonResult(result, file1.name, file2.name)
            
            // Also generate unified diff
            val unifiedDiff = gdiff.generateUnifiedDiff(
                file1.readText(),
                file2.readText(),
                file1.name,
                file2.name
            )
            
            if (unifiedDiff.isNotBlank()) {
                println("\n" + "=".repeat(60))
                println("UNIFIED DIFF:")
                println("=".repeat(60))
                println(unifiedDiff)
            }
            
        } catch (e: Exception) {
            println("Error comparing files: ${e.message}")
        }
    }
    
    private fun compareFilesWithConfig(args: Array<String>) {
        var ignoreCase = false
        var ignoreWhitespace = false
        var contextLines = 3
        val files = mutableListOf<String>()
        
        var i = 1 // Skip "--config"
        while (i < args.size) {
            when {
                args[i] == "--ignore-case" -> ignoreCase = true
                args[i] == "--ignore-whitespace" -> ignoreWhitespace = true
                args[i].startsWith("--context=") -> {
                    contextLines = args[i].substringAfter("=").toIntOrNull() ?: 3
                }
                else -> files.add(args[i])
            }
            i++
        }
        
        if (files.size != 2) {
            println("Error: Expected exactly 2 files to compare")
            printUsage()
            return
        }
        
        val config = GDiff.DiffConfig(
            ignoreCase = ignoreCase,
            ignoreWhitespace = ignoreWhitespace,
            contextLines = contextLines
        )
        
        println("Configuration:")
        println("  Ignore case: $ignoreCase")
        println("  Ignore whitespace: $ignoreWhitespace")
        println("  Context lines: $contextLines")
        println()
        
        val file1 = File(files[0])
        val file2 = File(files[1])
        
        if (!file1.exists() || !file2.exists()) {
            println("Error: One or both files not found")
            return
        }
        
        val gdiff = GDiff()
        
        try {
            val result = gdiff.diffFiles(file1, file2, config)
            printComparisonResult(result, file1.name, file2.name)
            
        } catch (e: Exception) {
            println("Error comparing files: ${e.message}")
        }
    }
    
    private fun printComparisonResult(result: GDiff.DiffResult, file1Name: String, file2Name: String) {
        println("Comparing: $file1Name vs $file2Name")
        println("=".repeat(60))
        
        if (result.identical) {
            println("✅ Files are identical!")
            return
        }
        
        val stats = result.getStatistics()
        println("📊 Statistics:")
        println("   Added lines: ${stats.additions}")
        println("   Deleted lines: ${stats.deletions}")
        println("   Modified lines: ${stats.modifications}")
        println("   Total changes: ${stats.totalChanges}")
        println()
        
        println("📝 Changes:")
        result.changes.filter { it.type != GDiff.ChangeType.EQUAL }.forEach { change ->
            val icon = when (change.type) {
                GDiff.ChangeType.INSERT -> "➕"
                GDiff.ChangeType.DELETE -> "➖"
                GDiff.ChangeType.CHANGE -> "🔄"
                else -> "  "
            }
            
            println("$icon Line ${change.sourceLineNumber}: ${change.type}")
            
            if (change.sourceLines.isNotEmpty()) {
                change.sourceLines.forEach { line ->
                    println("   - $line")
                }
            }
            
            if (change.targetLines.isNotEmpty()) {
                change.targetLines.forEach { line ->
                    println("   + $line")
                }
            }
            println()
        }
    }
    
    private fun runDemoExamples() {
        println("🚀 Running GDiff Demo Examples")
        println("=".repeat(60))
        
        try {
            GDiffExample.runAllExamples()
        } catch (e: Exception) {
            println("Error running demo: ${e.message}")
        }
    }
}
