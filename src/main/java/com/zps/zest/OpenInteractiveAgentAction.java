package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.markdown.utils.MarkdownToHtmlConverter;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Action to open the interactive AI agent window.
 */
public class OpenInteractiveAgentAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(OpenInteractiveAgentAction.class);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT; 
    }

    public OpenInteractiveAgentAction() {
        super("Open ZPS - AI Assistant", "Open the interactive AI coding assistant", AllIcons.Actions.StartDebugger);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        try {
            // Get the service and show the window
            InteractiveAgentService service = InteractiveAgentService.getInstance(project);
            service.showAgentWindow();
            
            // Add a welcome message if this is the first time opening
            if (service.getChatHistory().isEmpty()) {
                String[] greetings = {
                        "Hello, Code Wizard! May your keys be mighty!",
                        "Greetings, Bug Slayer! Let’s squash some glitches today!",
                        "Hey, Debug Ninja! Code smart, not hard!",
                        "Welcome, Syntax Sorcerer! May your scripts run smoothly!",
                        "Hello, Algorithm Artist! Ready to craft some magic?",
                        "Greetings, Code Samurai! Cut through that code jungle!",
                        "Hey, Loop Legend! Let’s make some efficient cycles today!",
                        "Welcome, Compile Conqueror! Break those errors wide open!",
                        "Hello, Logic Luminary! Illuminate the darkest code paths!",
                        "Greetings, Pixel Pioneer! Let’s pixelate some great front-ends!"
                };


                Random random = new Random();
                int randomIndex = random.nextInt(greetings.length);
                String randomGreeting = greetings[randomIndex];

                service.addSystemMessage(randomGreeting);   }
        } catch (Exception ex) {
            LOG.error("Error opening interactive agent window", ex);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only if a project is available
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}