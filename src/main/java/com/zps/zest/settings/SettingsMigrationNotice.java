package com.zps.zest.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity to handle settings migration from old properties file.
 */
public class SettingsMigrationNotice implements ProjectActivity {
    
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // Run migration on project open
        SettingsMigrator.migrateIfNeeded(project);
        return Unit.INSTANCE;
    }
}
