package com.zps.zest.explanation.tools.testing

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates HTML test reports for RipgrepCodeTool testing sessions.
 * Reports include test parameters, results, execution times, and pass/fail status.
 */
class RipgrepTestReporter(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(RipgrepTestReporter::class.java)
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    data class TestResult(
        val testName: String,
        val query: String,
        val filePattern: String?,
        val excludePattern: String?,
        val mode: String,
        val contextLines: Int,
        val beforeLines: Int,
        val afterLines: Int,
        val output: String,
        val executionTime: Long,
        val success: Boolean
    )

    /**
     * Generate an HTML report for a list of test results.
     * Returns the File object of the generated report, or null if failed.
     */
    fun generateReport(results: List<TestResult>): File? {
        try {
            val timestamp = LocalDateTime.now()
            val fileName = "ripgrep_test_report_${timestamp.format(TIMESTAMP_FORMAT)}.html"

            // Create reports directory in project
            val reportsDir = File(project.basePath, "ripgrep_test_reports")
            if (!reportsDir.exists()) {
                reportsDir.mkdirs()
            }

            val reportFile = File(reportsDir, fileName)
            val htmlContent = generateHtmlContent(results, timestamp)

            reportFile.writeText(htmlContent)
            LOG.info("Generated test report: ${reportFile.absolutePath}")

            return reportFile
        } catch (e: Exception) {
            LOG.error("Failed to generate test report", e)
            return null
        }
    }

    private fun generateHtmlContent(results: List<TestResult>, timestamp: LocalDateTime): String {
        val totalTests = results.size
        val passedTests = results.count { it.success }
        val failedTests = totalTests - passedTests
        val totalTime = results.sumOf { it.executionTime }
        val averageTime = if (totalTests > 0) totalTime / totalTests else 0

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>RipgrepCodeTool Test Report - ${timestamp.format(DISPLAY_FORMAT)}</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            line-height: 1.6;
            color: #333;
            background: #f5f5f5;
            padding: 20px;
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
            background: white;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }

        header {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 30px;
            border-radius: 8px 8px 0 0;
        }

        h1 {
            font-size: 28px;
            margin-bottom: 10px;
        }

        .timestamp {
            opacity: 0.9;
            font-size: 14px;
        }

        .summary {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 20px;
            padding: 30px;
            background: #fafafa;
            border-bottom: 1px solid #e0e0e0;
        }

        .summary-card {
            background: white;
            padding: 20px;
            border-radius: 8px;
            text-align: center;
            box-shadow: 0 1px 3px rgba(0,0,0,0.1);
        }

        .summary-card .number {
            font-size: 36px;
            font-weight: bold;
            margin-bottom: 5px;
        }

        .summary-card .label {
            color: #666;
            font-size: 14px;
        }

        .summary-card.success .number {
            color: #4caf50;
        }

        .summary-card.failure .number {
            color: #f44336;
        }

        .summary-card.info .number {
            color: #2196f3;
        }

        .test-results {
            padding: 30px;
        }

        .test-case {
            margin-bottom: 30px;
            border: 1px solid #e0e0e0;
            border-radius: 8px;
            overflow: hidden;
        }

        .test-header {
            padding: 15px 20px;
            background: #f5f5f5;
            border-bottom: 1px solid #e0e0e0;
            display: flex;
            justify-content: space-between;
            align-items: center;
            cursor: pointer;
        }

        .test-header:hover {
            background: #eeeeee;
        }

        .test-name {
            font-weight: 600;
            font-size: 16px;
            display: flex;
            align-items: center;
            gap: 10px;
        }

        .test-status {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 4px;
            font-size: 12px;
            font-weight: 500;
            text-transform: uppercase;
        }

        .test-status.pass {
            background: #e8f5e9;
            color: #2e7d32;
        }

        .test-status.fail {
            background: #ffebee;
            color: #c62828;
        }

        .test-details {
            padding: 20px;
            display: none;
        }

        .test-details.expanded {
            display: block;
        }

        .detail-grid {
            display: grid;
            grid-template-columns: 150px 1fr;
            gap: 15px;
            margin-bottom: 20px;
        }

        .detail-label {
            font-weight: 600;
            color: #666;
        }

        .detail-value {
            font-family: 'Courier New', monospace;
            background: #f5f5f5;
            padding: 8px;
            border-radius: 4px;
            word-break: break-all;
        }

        .output-section {
            margin-top: 20px;
        }

        .output-header {
            font-weight: 600;
            margin-bottom: 10px;
            color: #666;
        }

        .output-content {
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 15px;
            border-radius: 4px;
            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
            font-size: 13px;
            line-height: 1.5;
            white-space: pre-wrap;
            word-wrap: break-word;
            max-height: 400px;
            overflow-y: auto;
        }

        .execution-time {
            color: #ff9800;
            font-weight: 500;
        }

        footer {
            padding: 20px 30px;
            background: #fafafa;
            border-top: 1px solid #e0e0e0;
            text-align: center;
            color: #666;
            font-size: 14px;
            border-radius: 0 0 8px 8px;
        }

        .expand-icon {
            transition: transform 0.3s;
        }

        .test-header.expanded .expand-icon {
            transform: rotate(90deg);
        }

        @media print {
            body {
                background: white;
            }

            .container {
                box-shadow: none;
            }

            .test-details {
                display: block !important;
            }
        }
    </style>
    <script>
        function toggleTest(index) {
            const header = document.getElementById('test-header-' + index);
            const details = document.getElementById('test-details-' + index);

            header.classList.toggle('expanded');
            details.classList.toggle('expanded');
        }

        function expandAll() {
            document.querySelectorAll('.test-header').forEach(h => h.classList.add('expanded'));
            document.querySelectorAll('.test-details').forEach(d => d.classList.add('expanded'));
        }

        function collapseAll() {
            document.querySelectorAll('.test-header').forEach(h => h.classList.remove('expanded'));
            document.querySelectorAll('.test-details').forEach(d => d.classList.remove('expanded'));
        }
    </script>
</head>
<body>
    <div class="container">
        <header>
            <h1>RipgrepCodeTool Test Report</h1>
            <div class="timestamp">Generated: ${timestamp.format(DISPLAY_FORMAT)}</div>
            <div class="timestamp">Project: ${project.name}</div>
        </header>

        <div class="summary">
            <div class="summary-card">
                <div class="number">${totalTests}</div>
                <div class="label">Total Tests</div>
            </div>
            <div class="summary-card success">
                <div class="number">${passedTests}</div>
                <div class="label">Passed</div>
            </div>
            <div class="summary-card failure">
                <div class="number">${failedTests}</div>
                <div class="label">Failed</div>
            </div>
            <div class="summary-card info">
                <div class="number">${String.format("%.1f", passedTests.toDouble() / totalTests * 100)}%</div>
                <div class="label">Pass Rate</div>
            </div>
            <div class="summary-card info">
                <div class="number">${totalTime}ms</div>
                <div class="label">Total Time</div>
            </div>
            <div class="summary-card info">
                <div class="number">${averageTime}ms</div>
                <div class="label">Average Time</div>
            </div>
        </div>

        <div style="padding: 20px 30px; display: flex; gap: 10px;">
            <button onclick="expandAll()" style="padding: 8px 16px; border: 1px solid #ddd; background: white; border-radius: 4px; cursor: pointer;">Expand All</button>
            <button onclick="collapseAll()" style="padding: 8px 16px; border: 1px solid #ddd; background: white; border-radius: 4px; cursor: pointer;">Collapse All</button>
        </div>

        <div class="test-results">
            ${results.mapIndexed { index, result -> generateTestCaseHtml(index, result) }.joinToString("\n")}
        </div>

        <footer>
            <p>RipgrepCodeTool Test Report - Zest Plugin</p>
            <p>Generated by automated test runner</p>
        </footer>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun generateTestCaseHtml(index: Int, result: TestResult): String {
        val statusClass = if (result.success) "pass" else "fail"
        val statusText = if (result.success) "PASS" else "FAIL"

        // Escape HTML in output
        val escapedOutput = result.output
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        return """
            <div class="test-case">
                <div class="test-header" id="test-header-${index}" onclick="toggleTest(${index})">
                    <div class="test-name">
                        <span class="expand-icon">▶</span>
                        ${result.testName}
                    </div>
                    <div style="display: flex; align-items: center; gap: 15px;">
                        <span class="execution-time">${result.executionTime}ms</span>
                        <span class="test-status ${statusClass}">${statusText}</span>
                    </div>
                </div>
                <div class="test-details" id="test-details-${index}">
                    <div class="detail-grid">
                        <div class="detail-label">Mode:</div>
                        <div class="detail-value">${result.mode}</div>

                        <div class="detail-label">Query:</div>
                        <div class="detail-value">${result.query.ifEmpty { "(empty)" }}</div>

                        ${if (result.filePattern != null) """
                        <div class="detail-label">File Pattern:</div>
                        <div class="detail-value">${result.filePattern}</div>
                        """ else ""}

                        ${if (result.excludePattern != null) """
                        <div class="detail-label">Exclude Pattern:</div>
                        <div class="detail-value">${result.excludePattern}</div>
                        """ else ""}

                        ${if (result.contextLines > 0) """
                        <div class="detail-label">Context Lines:</div>
                        <div class="detail-value">${result.contextLines}</div>
                        """ else ""}

                        ${if (result.beforeLines > 0) """
                        <div class="detail-label">Before Lines:</div>
                        <div class="detail-value">${result.beforeLines}</div>
                        """ else ""}

                        ${if (result.afterLines > 0) """
                        <div class="detail-label">After Lines:</div>
                        <div class="detail-value">${result.afterLines}</div>
                        """ else ""}

                        <div class="detail-label">Execution Time:</div>
                        <div class="detail-value">${result.executionTime}ms</div>
                    </div>

                    <div class="output-section">
                        <div class="output-header">Output:</div>
                        <div class="output-content">${escapedOutput}</div>
                    </div>
                </div>
            </div>
        """.trimIndent()
    }
}