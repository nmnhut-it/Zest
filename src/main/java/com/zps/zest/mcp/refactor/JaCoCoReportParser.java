package com.zps.zest.mcp.refactor;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Parses JaCoCo coverage data using JaCoCo's own APIs.
 *
 * This provides more accurate parsing than manual XML parsing,
 * and can read both .exec files and XML reports.
 */
public class JaCoCoReportParser {
    private static final Logger LOG = Logger.getInstance(JaCoCoReportParser.class);

    /**
     * Parse coverage for a specific class using JaCoCo APIs.
     *
     * Looks for .exec file first (binary format), falls back to XML.
     * Returns detailed coverage information.
     */
    @Nullable
    public static JsonObject parseCoverageWithJaCoCoAPI(
            @NotNull String projectPath,
            @NotNull String className) {

        // Try to find .exec file (binary coverage data)
        Path execFile = findJaCoCoExecFile(projectPath);

        if (execFile != null && Files.exists(execFile)) {
            try {
                return parseFromExecFile(execFile, projectPath, className);
            } catch (Exception e) {
                LOG.warn("Error parsing .exec file, falling back to XML", e);
            }
        }

        // Fall back to XML parsing (already implemented)
        return null;
    }

    @Nullable
    private static Path findJaCoCoExecFile(@NotNull String projectPath) {
        String[] possiblePaths = {
            "build/jacoco/test.exec",  // Gradle default
            "target/jacoco.exec"       // Maven default
        };

        for (String relativePath : possiblePaths) {
            Path execPath = Paths.get(projectPath, relativePath);
            if (Files.exists(execPath)) {
                return execPath;
            }
        }

        return null;
    }

    @Nullable
    private static JsonObject parseFromExecFile(
            @NotNull Path execFile,
            @NotNull String projectPath,
            @NotNull String className) throws IOException {

        // Load execution data
        ExecFileLoader execFileLoader = new ExecFileLoader();
        execFileLoader.load(execFile.toFile());

        // Find compiled class files
        File classesDir = findClassesDirectory(projectPath);
        if (classesDir == null || !classesDir.exists()) {
            LOG.warn("Could not find classes directory for coverage analysis");
            return null;
        }

        // Analyze coverage
        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(
            execFileLoader.getExecutionDataStore(),
            coverageBuilder
        );

        analyzer.analyzeAll(classesDir);

        // Find coverage for specific class
        String classNameInternal = className.replace('.', '/');
        for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
            if (classCoverage.getName().equals(classNameInternal)) {
                return convertToJson(classCoverage);
            }
        }

        return null;
    }

    @Nullable
    private static File findClassesDirectory(@NotNull String projectPath) {
        String[] possiblePaths = {
            "build/classes/java/main",      // Gradle
            "build/classes/kotlin/main",    // Gradle Kotlin
            "target/classes"                // Maven
        };

        for (String relativePath : possiblePaths) {
            File classesDir = new File(projectPath, relativePath);
            if (classesDir.exists()) {
                return classesDir;
            }
        }

        return null;
    }

    @NotNull
    private static JsonObject convertToJson(@NotNull IClassCoverage coverage) {
        JsonObject result = new JsonObject();

        // Line coverage
        int totalLines = coverage.getLineCounter().getTotalCount();
        int coveredLines = coverage.getLineCounter().getCoveredCount();
        int missedLines = coverage.getLineCounter().getMissedCount();

        result.addProperty("totalLines", totalLines);
        result.addProperty("coveredLines", coveredLines);
        result.addProperty("missedLines", missedLines);

        if (totalLines > 0) {
            double percentage = (double) coveredLines / totalLines * 100;
            result.addProperty("linePercentage", String.format("%.2f%%", percentage));
        }

        // Branch coverage
        int totalBranches = coverage.getBranchCounter().getTotalCount();
        int coveredBranches = coverage.getBranchCounter().getCoveredCount();

        result.addProperty("totalBranches", totalBranches);
        result.addProperty("coveredBranches", coveredBranches);

        if (totalBranches > 0) {
            double percentage = (double) coveredBranches / totalBranches * 100;
            result.addProperty("branchPercentage", String.format("%.2f%%", percentage));
        }

        // Method coverage
        int totalMethods = coverage.getMethodCounter().getTotalCount();
        int coveredMethods = coverage.getMethodCounter().getCoveredCount();

        result.addProperty("totalMethods", totalMethods);
        result.addProperty("coveredMethods", coveredMethods);

        // Instruction coverage (bytecode level)
        int totalInstructions = coverage.getInstructionCounter().getTotalCount();
        int coveredInstructions = coverage.getInstructionCounter().getCoveredCount();

        result.addProperty("totalInstructions", totalInstructions);
        result.addProperty("coveredInstructions", coveredInstructions);

        result.addProperty("source", "JaCoCo .exec file");

        return result;
    }
}
