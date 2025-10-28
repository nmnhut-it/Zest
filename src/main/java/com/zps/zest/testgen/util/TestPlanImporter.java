package com.zps.zest.testgen.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.model.TestGenerationRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Imports test plans from CSV/JSON files created by QC teams
 */
public class TestPlanImporter {
    private static final Logger LOG = Logger.getInstance(TestPlanImporter.class);
    
    private final Gson gson = new Gson();
    
    /**
     * Import test plan from file (CSV or JSON)
     */
    @NotNull
    public ImportResult importTestPlan(@NotNull String filePath, @NotNull String targetClass, @NotNull String targetMethod) {
        try {
            String fileExtension = getFileExtension(filePath).toLowerCase();
            
            switch (fileExtension) {
                case "csv":
                    return importFromCsv(filePath, targetClass, targetMethod);
                case "json":
                    return importFromJson(filePath, targetClass, targetMethod);
                default:
                    return new ImportResult(false, "Unsupported file format: " + fileExtension, null);
            }
            
        } catch (Exception e) {
            LOG.error("Failed to import test plan from: " + filePath, e);
            return new ImportResult(false, "Import failed: " + e.getMessage(), null);
        }
    }
    
    /**
     * Import from CSV file with flexible column detection
     */
    @NotNull
    private ImportResult importFromCsv(@NotNull String filePath, @NotNull String targetClass, @NotNull String targetMethod) {
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));
            if (lines.isEmpty()) {
                return new ImportResult(false, "CSV file is empty", null);
            }
            
            // Parse header to detect columns
            String[] headers = parseCsvLine(lines.get(0));
            CsvColumnMapping mapping = detectCsvColumns(headers);
            
            if (!mapping.isValid()) {
                return new ImportResult(false, "Could not detect required columns in CSV. Expected: scenario name, description, and optionally: priority, type, inputs, expected outcome", null);
            }
            
            List<TestPlan.TestScenario> scenarios = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            // Parse data rows
            for (int i = 1; i < lines.size(); i++) {
                try {
                    String[] values = parseCsvLine(lines.get(i));
                    if (values.length <= mapping.getMaxIndex()) {
                        warnings.add("Row " + (i + 1) + ": Insufficient columns, skipping");
                        continue;
                    }
                    
                    TestPlan.TestScenario scenario = createScenarioFromCsv(values, mapping, i + 1);
                    if (scenario != null) {
                        scenarios.add(scenario);
                    }
                    
                } catch (Exception e) {
                    warnings.add("Row " + (i + 1) + ": Parse error - " + e.getMessage());
                }
            }
            
            if (scenarios.isEmpty()) {
                return new ImportResult(false, "No valid scenarios found in CSV", null);
            }
            
            TestPlan testPlan = new TestPlan(
                List.of(targetMethod),
                targetClass,
                scenarios,
                Collections.emptyList(), // Dependencies will be analyzed later
                TestPlan.TestScenario.Type.UNIT.equals(scenarios.get(0).getType()) ? 
                    TestGenerationRequest.TestType.UNIT_TESTS : TestGenerationRequest.TestType.AUTO_DETECT,
                "Imported from CSV file: " + filePath + (warnings.isEmpty() ? "" : ". Warnings: " + warnings.size())
            );
            
            return new ImportResult(true, "Successfully imported " + scenarios.size() + " scenarios" + 
                                  (warnings.isEmpty() ? "" : " with " + warnings.size() + " warnings"), testPlan, warnings);
            
        } catch (Exception e) {
            LOG.error("CSV import failed", e);
            return new ImportResult(false, "CSV import failed: " + e.getMessage(), null);
        }
    }
    
    /**
     * Import from JSON file with flexible structure detection
     */
    @NotNull
    private ImportResult importFromJson(@NotNull String filePath, @NotNull String targetClass, @NotNull String targetMethod) {
        try {
            String content = Files.readString(Path.of(filePath));
            JsonElement rootElement = gson.fromJson(content, JsonElement.class);
            
            List<TestPlan.TestScenario> scenarios = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            if (rootElement.isJsonArray()) {
                // Array of test scenarios
                JsonArray scenarioArray = rootElement.getAsJsonArray();
                for (int i = 0; i < scenarioArray.size(); i++) {
                    try {
                        TestPlan.TestScenario scenario = parseJsonScenario(scenarioArray.get(i), i);
                        if (scenario != null) {
                            scenarios.add(scenario);
                        }
                    } catch (Exception e) {
                        warnings.add("Scenario " + (i + 1) + ": " + e.getMessage());
                    }
                }
            } else if (rootElement.isJsonObject()) {
                JsonObject rootObject = rootElement.getAsJsonObject();
                
                // Check for different JSON structures
                if (rootObject.has("testScenarios") || rootObject.has("scenarios") || rootObject.has("tests")) {
                    JsonArray scenarioArray = rootObject.has("testScenarios") ? 
                        rootObject.getAsJsonArray("testScenarios") :
                        rootObject.has("scenarios") ?
                        rootObject.getAsJsonArray("scenarios") :
                        rootObject.getAsJsonArray("tests");
                        
                    for (int i = 0; i < scenarioArray.size(); i++) {
                        try {
                            TestPlan.TestScenario scenario = parseJsonScenario(scenarioArray.get(i), i);
                            if (scenario != null) {
                                scenarios.add(scenario);
                            }
                        } catch (Exception e) {
                            warnings.add("Scenario " + (i + 1) + ": " + e.getMessage());
                        }
                    }
                } else {
                    // Treat root object as single scenario
                    TestPlan.TestScenario scenario = parseJsonScenario(rootElement, 0);
                    if (scenario != null) {
                        scenarios.add(scenario);
                    }
                }
            }
            
            if (scenarios.isEmpty()) {
                return new ImportResult(false, "No valid scenarios found in JSON", null);
            }
            
            TestPlan testPlan = new TestPlan(
                List.of(targetMethod),
                targetClass,
                scenarios,
                Collections.emptyList(),
                scenarios.size() > 1 ? TestGenerationRequest.TestType.AUTO_DETECT : TestGenerationRequest.TestType.UNIT_TESTS,
                "Imported from JSON file: " + filePath + (warnings.isEmpty() ? "" : ". Warnings: " + warnings.size())
            );
            
            return new ImportResult(true, "Successfully imported " + scenarios.size() + " scenarios" + 
                                  (warnings.isEmpty() ? "" : " with " + warnings.size() + " warnings"), testPlan, warnings);
                                  
        } catch (Exception e) {
            LOG.error("JSON import failed", e);
            return new ImportResult(false, "JSON import failed: " + e.getMessage(), null);
        }
    }
    
    private String[] parseCsvLine(@NotNull String line) {
        // Simple CSV parser - handles quoted fields and escaped quotes
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
                    currentField.append('"');
                    i++; // Skip next quote
                } else {
                    // Toggle quote mode
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString().trim());
                currentField.setLength(0);
            } else {
                currentField.append(c);
            }
        }
        
        // Add last field
        fields.add(currentField.toString().trim());
        
        return fields.toArray(new String[0]);
    }
    
    private CsvColumnMapping detectCsvColumns(@NotNull String[] headers) {
        CsvColumnMapping mapping = new CsvColumnMapping();

        for (int i = 0; i < headers.length; i++) {
            String header = headers[i].toLowerCase().trim();

            // Detect scenario name column
            if (mapping.nameColumn == -1 && (header.contains("name") || header.contains("scenario") || header.contains("title"))) {
                mapping.nameColumn = i;
            }
            // Detect description column
            else if (mapping.descriptionColumn == -1 && (header.contains("description") || header.contains("desc") || header.contains("detail"))) {
                mapping.descriptionColumn = i;
            }
            // Detect priority column
            else if (mapping.priorityColumn == -1 && (header.contains("priority") || header.contains("importance"))) {
                mapping.priorityColumn = i;
            }
            // Detect type column
            else if (mapping.typeColumn == -1 && (header.contains("type") || header.contains("category") || header.contains("kind"))) {
                mapping.typeColumn = i;
            }
            // Detect input column
            else if (mapping.inputsColumn == -1 && (header.contains("input") || header.contains("data") || header.contains("parameter"))) {
                mapping.inputsColumn = i;
            }
            // Detect expected outcome column
            else if (mapping.expectedColumn == -1 && (header.contains("expected") || header.contains("result") || header.contains("outcome"))) {
                mapping.expectedColumn = i;
            }
            // Detect prerequisites column
            else if (mapping.prerequisitesColumn == -1 && (header.contains("prerequisite") || header.contains("precondition") || header.contains("require"))) {
                mapping.prerequisitesColumn = i;
            }
            // Detect setup steps column
            else if (mapping.setupStepsColumn == -1 && (header.contains("setup") || header.contains("given") || header.contains("arrange"))) {
                mapping.setupStepsColumn = i;
            }
            // Detect teardown steps column
            else if (mapping.teardownStepsColumn == -1 && (header.contains("teardown") || header.contains("cleanup") || header.contains("after"))) {
                mapping.teardownStepsColumn = i;
            }
            // Detect isolation strategy column
            else if (mapping.isolationColumn == -1 && (header.contains("isolation") || header.contains("independent") || header.contains("shared"))) {
                mapping.isolationColumn = i;
            }
        }

        return mapping;
    }
    
    private TestPlan.TestScenario createScenarioFromCsv(@NotNull String[] values, @NotNull CsvColumnMapping mapping, int rowNumber) {
        try {
            String name = mapping.nameColumn >= 0 && mapping.nameColumn < values.length ? 
                values[mapping.nameColumn] : "Test Scenario " + rowNumber;
            String description = mapping.descriptionColumn >= 0 && mapping.descriptionColumn < values.length ? 
                values[mapping.descriptionColumn] : "Imported test scenario";
                
            if (name.isEmpty()) {
                name = "Test Scenario " + rowNumber;
            }
            if (description.isEmpty()) {
                description = "Imported test scenario";
            }
            
            // Parse priority
            TestPlan.TestScenario.Priority priority = TestPlan.TestScenario.Priority.MEDIUM;
            if (mapping.priorityColumn >= 0 && mapping.priorityColumn < values.length) {
                priority = parsePriority(values[mapping.priorityColumn]);
            }
            
            // Parse type
            TestPlan.TestScenario.Type type = TestPlan.TestScenario.Type.UNIT;
            if (mapping.typeColumn >= 0 && mapping.typeColumn < values.length) {
                type = parseType(values[mapping.typeColumn]);
            }
            
            // Parse inputs
            List<String> inputs = new ArrayList<>();
            if (mapping.inputsColumn >= 0 && mapping.inputsColumn < values.length) {
                String inputsStr = values[mapping.inputsColumn];
                if (!inputsStr.isEmpty()) {
                    inputs = Arrays.asList(inputsStr.split("[,;|]")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                }
            }
            if (inputs.isEmpty()) {
                inputs.add("default input");
            }
            
            // Parse expected outcome
            String expected = mapping.expectedColumn >= 0 && mapping.expectedColumn < values.length ?
                values[mapping.expectedColumn] : "Should execute successfully";
            if (expected.isEmpty()) {
                expected = "Should execute successfully";
            }

            // Parse prerequisites
            List<String> prerequisites = new ArrayList<>();
            if (mapping.prerequisitesColumn >= 0 && mapping.prerequisitesColumn < values.length) {
                String prerequisitesStr = values[mapping.prerequisitesColumn];
                if (!prerequisitesStr.isEmpty()) {
                    prerequisites = Arrays.asList(prerequisitesStr.split("[,;|]")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                }
            }

            // Parse setup steps
            List<String> setupSteps = new ArrayList<>();
            if (mapping.setupStepsColumn >= 0 && mapping.setupStepsColumn < values.length) {
                String setupStr = values[mapping.setupStepsColumn];
                if (!setupStr.isEmpty()) {
                    setupSteps = Arrays.asList(setupStr.split("[,;|]")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                }
            }

            // Parse teardown steps
            List<String> teardownSteps = new ArrayList<>();
            if (mapping.teardownStepsColumn >= 0 && mapping.teardownStepsColumn < values.length) {
                String teardownStr = values[mapping.teardownStepsColumn];
                if (!teardownStr.isEmpty()) {
                    teardownSteps = Arrays.asList(teardownStr.split("[,;|]")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                }
            }

            // Parse isolation strategy
            TestPlan.TestScenario.TestIsolation isolation = TestPlan.TestScenario.TestIsolation.INDEPENDENT;
            if (mapping.isolationColumn >= 0 && mapping.isolationColumn < values.length) {
                isolation = parseIsolation(values[mapping.isolationColumn]);
            }

            return new TestPlan.TestScenario(name, description, type, inputs, expected, priority,
                                            prerequisites, setupSteps, teardownSteps, isolation);
            
        } catch (Exception e) {
            LOG.warn("Failed to create scenario from CSV row " + rowNumber, e);
            return null;
        }
    }
    
    private TestPlan.TestScenario parseJsonScenario(@NotNull JsonElement element, int index) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Scenario must be a JSON object");
        }
        
        JsonObject obj = element.getAsJsonObject();
        
        // Extract name (required)
        String name = getJsonString(obj, Arrays.asList("name", "title", "scenario", "testName"), "Test Scenario " + (index + 1));
        
        // Extract description (required)
        String description = getJsonString(obj, Arrays.asList("description", "desc", "details", "summary"), "Imported test scenario");
        
        // Extract type
        TestPlan.TestScenario.Type type = TestPlan.TestScenario.Type.UNIT;
        String typeStr = getJsonString(obj, Arrays.asList("type", "category", "kind"), "");
        if (!typeStr.isEmpty()) {
            type = parseType(typeStr);
        }
        
        // Extract priority
        TestPlan.TestScenario.Priority priority = TestPlan.TestScenario.Priority.MEDIUM;
        String priorityStr = getJsonString(obj, Arrays.asList("priority", "importance"), "");
        if (!priorityStr.isEmpty()) {
            priority = parsePriority(priorityStr);
        }
        
        // Extract inputs
        List<String> inputs = new ArrayList<>();
        if (obj.has("inputs") || obj.has("input") || obj.has("data") || obj.has("parameters")) {
            JsonElement inputElement = obj.has("inputs") ? obj.get("inputs") :
                                    obj.has("input") ? obj.get("input") :
                                    obj.has("data") ? obj.get("data") : obj.get("parameters");
            
            if (inputElement.isJsonArray()) {
                JsonArray inputArray = inputElement.getAsJsonArray();
                for (JsonElement input : inputArray) {
                    inputs.add(input.getAsString());
                }
            } else if (inputElement.isJsonPrimitive()) {
                inputs.add(inputElement.getAsString());
            }
        }
        if (inputs.isEmpty()) {
            inputs.add("default input");
        }
        
        // Extract expected outcome
        String expected = getJsonString(obj, Arrays.asList("expected", "expectedResult", "expectedOutcome", "result"), "Should execute successfully");

        // Extract prerequisites
        List<String> prerequisites = extractListFromJson(obj, Arrays.asList("prerequisites", "preconditions", "requirements"));

        // Extract setup steps
        List<String> setupSteps = extractListFromJson(obj, Arrays.asList("setupSteps", "setup", "given", "arrange"));

        // Extract teardown steps
        List<String> teardownSteps = extractListFromJson(obj, Arrays.asList("teardownSteps", "teardown", "cleanup", "after"));

        // Extract isolation strategy
        TestPlan.TestScenario.TestIsolation isolation = TestPlan.TestScenario.TestIsolation.INDEPENDENT;
        String isolationStr = getJsonString(obj, Arrays.asList("isolationStrategy", "isolation", "testIsolation"), "");
        if (!isolationStr.isEmpty()) {
            isolation = parseIsolation(isolationStr);
        }

        return new TestPlan.TestScenario(name, description, type, inputs, expected, priority,
                                        prerequisites, setupSteps, teardownSteps, isolation);
    }

    private List<String> extractListFromJson(@NotNull JsonObject obj, @NotNull List<String> possibleKeys) {
        List<String> result = new ArrayList<>();
        for (String key : possibleKeys) {
            if (obj.has(key)) {
                JsonElement element = obj.get(key);
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    for (JsonElement item : array) {
                        if (item.isJsonPrimitive()) {
                            result.add(item.getAsString());
                        }
                    }
                    return result;
                } else if (element.isJsonPrimitive()) {
                    // Single string, split by common delimiters
                    String str = element.getAsString();
                    return Arrays.asList(str.split("[,;|]")).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                }
            }
        }
        return result;
    }
    
    private String getJsonString(@NotNull JsonObject obj, @NotNull List<String> possibleKeys, @NotNull String defaultValue) {
        for (String key : possibleKeys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                String value = obj.get(key).getAsString().trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return defaultValue;
    }
    
    private TestPlan.TestScenario.Priority parsePriority(@NotNull String priorityStr) {
        String lower = priorityStr.toLowerCase().trim();
        switch (lower) {
            case "high":
            case "critical":
            case "urgent":
            case "1":
                return TestPlan.TestScenario.Priority.HIGH;
            case "low":
            case "minor":
            case "3":
                return TestPlan.TestScenario.Priority.LOW;
            default:
                return TestPlan.TestScenario.Priority.MEDIUM;
        }
    }
    
    private TestPlan.TestScenario.Type parseType(@NotNull String typeStr) {
        String lower = typeStr.toLowerCase().trim();
        if (lower.contains("integration") || lower.contains("e2e") || lower.contains("end-to-end")) {
            return TestPlan.TestScenario.Type.INTEGRATION;
        }
        if (lower.contains("edge") || lower.contains("boundary") || lower.contains("corner")) {
            return TestPlan.TestScenario.Type.EDGE_CASE;
        }
        if (lower.contains("error") || lower.contains("exception") || lower.contains("negative")) {
            return TestPlan.TestScenario.Type.ERROR_HANDLING;
        }
        return TestPlan.TestScenario.Type.UNIT;
    }

    private TestPlan.TestScenario.TestIsolation parseIsolation(@NotNull String isolationStr) {
        String lower = isolationStr.toLowerCase().trim();
        if (lower.contains("shared") || lower.contains("fixture")) {
            return TestPlan.TestScenario.TestIsolation.SHARED_FIXTURE;
        }
        if (lower.contains("reset") || lower.contains("between")) {
            return TestPlan.TestScenario.TestIsolation.RESET_BETWEEN;
        }
        if (lower.contains("separate") || lower.contains("instance") || lower.contains("fresh")) {
            return TestPlan.TestScenario.TestIsolation.SEPARATE_INSTANCE;
        }
        return TestPlan.TestScenario.TestIsolation.INDEPENDENT;
    }
    
    private String getFileExtension(@NotNull String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        return lastDot >= 0 ? filePath.substring(lastDot + 1) : "";
    }
    
    // Helper classes
    
    private static class CsvColumnMapping {
        int nameColumn = -1;
        int descriptionColumn = -1;
        int priorityColumn = -1;
        int typeColumn = -1;
        int inputsColumn = -1;
        int expectedColumn = -1;
        int prerequisitesColumn = -1;
        int setupStepsColumn = -1;
        int teardownStepsColumn = -1;
        int isolationColumn = -1;

        boolean isValid() {
            return nameColumn >= 0 && descriptionColumn >= 0;
        }

        int getMaxIndex() {
            return Math.max(
                Math.max(Math.max(Math.max(nameColumn, descriptionColumn), Math.max(priorityColumn, typeColumn)),
                         Math.max(inputsColumn, expectedColumn)),
                Math.max(Math.max(prerequisitesColumn, setupStepsColumn), Math.max(teardownStepsColumn, isolationColumn))
            );
        }
    }
    
    public static class ImportResult {
        private final boolean success;
        private final String message;
        private final TestPlan testPlan;
        private final List<String> warnings;
        
        public ImportResult(boolean success, String message, @Nullable TestPlan testPlan) {
            this(success, message, testPlan, Collections.emptyList());
        }
        
        public ImportResult(boolean success, String message, @Nullable TestPlan testPlan, List<String> warnings) {
            this.success = success;
            this.message = message;
            this.testPlan = testPlan;
            this.warnings = warnings != null ? warnings : Collections.emptyList();
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        @Nullable public TestPlan getTestPlan() { return testPlan; }
        public List<String> getWarnings() { return warnings; }
        
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}