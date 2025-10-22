package com.zps.zest.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.zps.zest.testgen.model.TestGenerationConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Project-level settings for Zest Plugin.
 * These settings are specific to each project.
 */
@State(
    name = "com.zps.zest.settings.ZestProjectSettings",
    storages = @Storage("zest-plugin.xml")
)
public class ZestProjectSettings implements PersistentStateComponent<ZestProjectSettings> {

    // Removed unused settings (context injection, docs search, RAG, MCP, proxy server)
    public int maxIterations = 3;

    // Tool API Server (for MCP/OpenWebUI integration)
    public boolean toolServerEnabled = true;

    // Migration tracking
    public int promptVersion = 0;

    // Test Generation Configuration
    public int testGenTestsPerMethod = 5;
    public String testGenTestTypeFilters = "UNIT,INTEGRATION,EDGE_CASE,ERROR_HANDLING";
    public String testGenPriorityFilters = "HIGH,MEDIUM";
    public String testGenCoverageTargets = "HAPPY_PATH,EDGE_CASES,ERROR_HANDLING";
    
    public static ZestProjectSettings getInstance(Project project) {
        return project.getService(ZestProjectSettings.class);
    }
    
    @Nullable
    @Override
    public ZestProjectSettings getState() {
        return this;
    }
    
    @Override
    public void loadState(@NotNull ZestProjectSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    @NotNull
    public TestGenerationConfig getTestGenerationConfig() {
        Set<TestGenerationConfig.TestTypeFilter> testTypes = parseEnumSet(
            testGenTestTypeFilters,
            TestGenerationConfig.TestTypeFilter.class,
            TestGenerationConfig.getDefaultTestTypes()
        );

        Set<TestGenerationConfig.PriorityFilter> priorities = parseEnumSet(
            testGenPriorityFilters,
            TestGenerationConfig.PriorityFilter.class,
            TestGenerationConfig.getDefaultPriorities()
        );

        Set<TestGenerationConfig.CoverageTarget> coverageTargets = parseEnumSet(
            testGenCoverageTargets,
            TestGenerationConfig.CoverageTarget.class,
            TestGenerationConfig.getDefaultCoverageTargets()
        );

        return new TestGenerationConfig(
            testGenTestsPerMethod,
            testTypes,
            priorities,
            coverageTargets
        );
    }

    public void setTestGenerationConfig(@NotNull TestGenerationConfig config) {
        this.testGenTestsPerMethod = config.getTestsPerMethod();
        this.testGenTestTypeFilters = enumSetToString(config.getTestTypeFilters());
        this.testGenPriorityFilters = enumSetToString(config.getPriorityFilters());
        this.testGenCoverageTargets = enumSetToString(config.getCoverageTargets());
    }

    private <E extends Enum<E>> Set<E> parseEnumSet(String value, Class<E> enumClass, Set<E> defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Enum.valueOf(enumClass, s))
                .collect(Collectors.toSet());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private <E extends Enum<E>> String enumSetToString(Set<E> enumSet) {
        if (enumSet == null || enumSet.isEmpty()) {
            return "";
        }
        return enumSet.stream()
            .map(Enum::name)
            .collect(Collectors.joining(","));
    }
}
