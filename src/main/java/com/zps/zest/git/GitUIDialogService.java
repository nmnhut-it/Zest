package com.zps.zest.git;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing Git UI dialogs per project.
 * Ensures only one dialog per project and handles cleanup.
 */
@Service(Service.Level.APP)
public final class GitUIDialogService {
    private static final Logger LOG = Logger.getInstance(GitUIDialogService.class);

    private final Map<Project, GitUIDialog> activeDialogs = new ConcurrentHashMap<>();

    public GitUIDialogService() {
        ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerListener() {
            @Override
            public void projectClosed(@NotNull Project project) {
                GitUIDialog dialog = activeDialogs.remove(project);
                if (dialog != null && !dialog.isDisposed()) {
                    dialog.dispose();
                    LOG.info("Cleaned up Git UI dialog for closed project: " + project.getName());
                }
            }
        });
    }

    public static GitUIDialogService getInstance() {
        return ApplicationManager.getApplication().getService(GitUIDialogService.class);
    }

    /**
     * Opens Git UI dialog for the project.
     * @param project The project
     * @param withAutoActions Whether to enable auto-actions (file selection and commit message generation)
     */
    public void openGitUI(Project project, boolean withAutoActions) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                GitUIDialog existingDialog = activeDialogs.get(project);
                if (existingDialog != null && !existingDialog.isDisposed()) {
                    existingDialog.toFront();
                    if (withAutoActions) {
                        existingDialog.refreshAndInjectAutoActions();
                    }
                    LOG.info("Reused existing Git UI dialog for project: " + project.getName());
                    return;
                }

                GitUIDialog newDialog = new GitUIDialog(project, withAutoActions);
                activeDialogs.put(project, newDialog);
                newDialog.show();

                LOG.info("Created new Git UI dialog for project: " + project.getName() +
                    (withAutoActions ? " with auto-actions" : ""));

            } catch (Exception ex) {
                LOG.error("Failed to open Git UI dialog", ex);
            }
        });
    }
}
