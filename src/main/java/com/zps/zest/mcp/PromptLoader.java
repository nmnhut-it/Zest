package com.zps.zest.mcp;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads MCP prompts from external directories or bundled defaults.
 *
 * Priority order:
 * 1. Project: ${PROJECT}/.zest/prompts/
 * 2. User Dev: ~/.zest/dev-prompts/
 * 3. Bundled: TestGenPrompts constants
 *
 * Usage: PromptLoader.getInstance(project).getPrompt("PLAN_TESTS")
 */
@Service(Service.Level.PROJECT)
public final class PromptLoader {

    private static final Logger LOG = Logger.getInstance(PromptLoader.class);
    private static final Path USER_DEV_PROMPTS_DIR =
        Paths.get(System.getProperty("user.home"), ".zest", "dev-prompts");

    /** Available prompt names that can be loaded externally. */
    public enum PromptName {
        ANALYZE_TESTABILITY("analyze-testability.md"),
        PLAN_TESTS("plan-tests.md"),
        GENERATE_TEST_CODE("generate-test-code.md"),
        ANALYZE_TEST_GAPS("analyze-test-gaps.md"),
        DEFAULT_TESTING_RULES("default-testing-rules.md"),
        FRAMEWORK_SPECIFIC_RULES("framework-specific-rules.md"),
        ORCHESTRATED_TEST_GENERATION("orchestrated-test-generation.md"),
        ORCHESTRATED_TEST_GENERATION_COMPACT("orchestrated-test-generation-compact.md"),
        BITZERO_TEST_GENERATION_V6("bitzero-test-v6.md"),
        BITZERO_TESTING_RULES("bitzero-testing-rules.md");

        private final String fileName;

        PromptName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileName() {
            return fileName;
        }
    }

    /** Source of a loaded prompt. */
    public enum PromptSource {
        PROJECT("Project (.zest/prompts)"),
        USER_DEV("Dev (~/.zest/dev-prompts)"),
        BUNDLED("Bundled (default)");

        private final String displayName;

        PromptSource(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final Project project;
    private final Map<PromptName, String> cache = new ConcurrentHashMap<>();
    private final Map<PromptName, PromptSource> sourceCache = new ConcurrentHashMap<>();

    public PromptLoader(Project project) {
        this.project = project;
    }

    public static PromptLoader getInstance(Project project) {
        return project.getService(PromptLoader.class);
    }

    /**
     * Get a prompt by name, loading from external dirs first, then bundled.
     */
    public String getPrompt(PromptName name) {
        return cache.computeIfAbsent(name, this::loadPrompt);
    }

    /**
     * Static method to get a prompt without project context.
     * Only checks user-dev directory and bundled (no project-specific).
     * Use this for MCP prompts where project isn't available.
     */
    public static String getPromptStatic(PromptName name) {
        // Check user dev directory
        Path devPath = USER_DEV_PROMPTS_DIR.resolve(name.getFileName());
        if (Files.exists(devPath)) {
            try {
                String content = Files.readString(devPath);
                LOG.info("Loaded prompt " + name + " from dev: " + devPath);
                return content;
            } catch (IOException e) {
                LOG.warn("Failed to read dev prompt: " + devPath, e);
            }
        }

        // Fall back to bundled
        return getBundledPromptStatic(name);
    }

    private static String getBundledPromptStatic(PromptName name) {
        return switch (name) {
            case ANALYZE_TESTABILITY -> TestGenPrompts.ANALYZE_TESTABILITY;
            case PLAN_TESTS -> TestGenPrompts.PLAN_TESTS;
            case GENERATE_TEST_CODE -> TestGenPrompts.GENERATE_TEST_CODE;
            case ANALYZE_TEST_GAPS -> TestGenPrompts.ANALYZE_TEST_GAPS;
            case DEFAULT_TESTING_RULES -> TestGenPrompts.DEFAULT_TESTING_RULES;
            case FRAMEWORK_SPECIFIC_RULES -> TestGenPrompts.FRAMEWORK_SPECIFIC_RULES;
            case ORCHESTRATED_TEST_GENERATION -> TestGenPrompts.ORCHESTRATED_TEST_GENERATION;
            case ORCHESTRATED_TEST_GENERATION_COMPACT -> TestGenPrompts.ORCHESTRATED_TEST_GENERATION_COMPACT;
            case BITZERO_TEST_GENERATION_V6 -> loadBundledResource("prompts/bitzero-test-v6.md");
            case BITZERO_TESTING_RULES -> loadBundledResource("prompts/bitzero-testing-rules.md");
        };
    }

    /**
     * Get the source of a prompt.
     */
    public PromptSource getSource(PromptName name) {
        // Ensure prompt is loaded
        getPrompt(name);
        return sourceCache.getOrDefault(name, PromptSource.BUNDLED);
    }

    /**
     * Clear cache to reload prompts from disk.
     */
    public void clearCache() {
        cache.clear();
        sourceCache.clear();
        LOG.info("Prompt cache cleared");
    }

    /**
     * Get path to project prompts directory.
     */
    public Path getProjectPromptsDir() {
        String basePath = project.getBasePath();
        return basePath != null
            ? Paths.get(basePath, ".zest", "prompts")
            : USER_DEV_PROMPTS_DIR;
    }

    /**
     * Get path to user dev prompts directory.
     */
    public static Path getDevPromptsDir() {
        return USER_DEV_PROMPTS_DIR;
    }

    /**
     * Initialize dev prompts directory with all bundled prompts.
     * Returns list of created files.
     */
    public java.util.List<String> initializeDevPrompts() {
        java.util.List<String> created = new java.util.ArrayList<>();

        try {
            Files.createDirectories(USER_DEV_PROMPTS_DIR);

            for (PromptName name : PromptName.values()) {
                Path targetPath = USER_DEV_PROMPTS_DIR.resolve(name.getFileName());
                if (!Files.exists(targetPath)) {
                    String content = getBundledPrompt(name);
                    Files.writeString(targetPath, content);
                    created.add(name.getFileName());
                }
            }

            if (!created.isEmpty()) {
                LOG.info("Initialized dev prompts: " + created);
            }
        } catch (IOException e) {
            LOG.error("Failed to initialize dev prompts", e);
        }

        return created;
    }

    /**
     * Get info about where each prompt is loaded from.
     */
    public Map<PromptName, PromptSource> getPromptSourceInfo() {
        Map<PromptName, PromptSource> info = new java.util.EnumMap<>(PromptName.class);
        for (PromptName name : PromptName.values()) {
            getPrompt(name); // Ensure loaded
            info.put(name, sourceCache.getOrDefault(name, PromptSource.BUNDLED));
        }
        return info;
    }

    private String loadPrompt(PromptName name) {
        // 1. Check project directory
        Path projectPath = getProjectPromptsDir().resolve(name.getFileName());
        if (Files.exists(projectPath)) {
            try {
                String content = Files.readString(projectPath);
                sourceCache.put(name, PromptSource.PROJECT);
                LOG.info("Loaded prompt " + name + " from project: " + projectPath);
                return content;
            } catch (IOException e) {
                LOG.warn("Failed to read project prompt: " + projectPath, e);
            }
        }

        // 2. Check user dev directory
        Path devPath = USER_DEV_PROMPTS_DIR.resolve(name.getFileName());
        if (Files.exists(devPath)) {
            try {
                String content = Files.readString(devPath);
                sourceCache.put(name, PromptSource.USER_DEV);
                LOG.info("Loaded prompt " + name + " from dev: " + devPath);
                return content;
            } catch (IOException e) {
                LOG.warn("Failed to read dev prompt: " + devPath, e);
            }
        }

        // 3. Fall back to bundled
        sourceCache.put(name, PromptSource.BUNDLED);
        return getBundledPrompt(name);
    }

    private String getBundledPrompt(PromptName name) {
        return switch (name) {
            case ANALYZE_TESTABILITY -> TestGenPrompts.ANALYZE_TESTABILITY;
            case PLAN_TESTS -> TestGenPrompts.PLAN_TESTS;
            case GENERATE_TEST_CODE -> TestGenPrompts.GENERATE_TEST_CODE;
            case ANALYZE_TEST_GAPS -> TestGenPrompts.ANALYZE_TEST_GAPS;
            case DEFAULT_TESTING_RULES -> TestGenPrompts.DEFAULT_TESTING_RULES;
            case FRAMEWORK_SPECIFIC_RULES -> TestGenPrompts.FRAMEWORK_SPECIFIC_RULES;
            case ORCHESTRATED_TEST_GENERATION -> TestGenPrompts.ORCHESTRATED_TEST_GENERATION;
            case ORCHESTRATED_TEST_GENERATION_COMPACT -> TestGenPrompts.ORCHESTRATED_TEST_GENERATION_COMPACT;
            case BITZERO_TEST_GENERATION_V6 -> loadBundledResource("prompts/bitzero-test-v6.md");
            case BITZERO_TESTING_RULES -> loadBundledResource("prompts/bitzero-testing-rules.md");
        };
    }

    /**
     * Load a bundled resource from the classpath.
     */
    private static String loadBundledResource(String resourcePath) {
        try (var is = PromptLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            LOG.warn("Failed to load bundled resource: " + resourcePath, e);
        }
        return "# Resource not found: " + resourcePath;
    }
}
