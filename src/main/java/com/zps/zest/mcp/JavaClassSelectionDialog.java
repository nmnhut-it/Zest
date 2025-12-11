package com.zps.zest.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * GUI dialog for selecting Java classes/methods for test generation.
 * Provides interactive browsing of project Java classes.
 */
public class JavaClassSelectionDialog extends DialogWrapper {
    private final Project project;
    private JList<String> classList;
    private DefaultListModel<String> classListModel;
    private JTextField searchField;
    private String selectedClassName;

    public JavaClassSelectionDialog(Project project) {
        super(project);
        this.project = project;
        setTitle("Select Java Class for Test Generation");
        init();
        loadClasses("");
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setPreferredSize(new Dimension(600, 500));

        // Search field
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.add(new JLabel("Search:"), BorderLayout.WEST);
        searchField = new JTextField();
        searchField.addActionListener(e -> loadClasses(searchField.getText()));
        searchPanel.add(searchField, BorderLayout.CENTER);

        JButton searchButton = new JButton("Search");
        searchButton.addActionListener(e -> loadClasses(searchField.getText()));
        searchPanel.add(searchButton, BorderLayout.EAST);

        panel.add(searchPanel, BorderLayout.NORTH);

        // Class list
        classListModel = new DefaultListModel<>();
        classList = new JList<>(classListModel);
        classList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        classList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectedClassName = classList.getSelectedValue();
            }
        });

        JScrollPane scrollPane = new JScrollPane(classList);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Help text
        JLabel helpLabel = new JLabel("Tip: Enter partial class name to filter (e.g., \"Service\" or \"Controller\")");
        helpLabel.setFont(helpLabel.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(helpLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void loadClasses(String filter) {
        classListModel.clear();

        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        // Get all class names
        String[] allClassNames = cache.getAllClassNames();

        // Filter and add to list
        int count = 0;
        for (String className : allClassNames) {
            if (count >= 100) break; // Limit to 100 results

            if (filter.isEmpty() || className.toLowerCase().contains(filter.toLowerCase())) {
                PsiClass[] classes = cache.getClassesByName(className, scope);
                for (PsiClass psiClass : classes) {
                    if (psiClass.getQualifiedName() != null) {
                        classListModel.addElement(psiClass.getQualifiedName());
                        count++;
                        if (count >= 100) break;
                    }
                }
            }
        }

        if (classListModel.isEmpty()) {
            classListModel.addElement("(No classes found matching: \"" + filter + "\")");
        }
    }

    @Nullable
    public String getSelectedClassName() {
        return selectedClassName;
    }

    /**
     * Shows the dialog and returns the selected class name, or null if cancelled.
     */
    @Nullable
    public static String showAndGetClassName(Project project) {
        JavaClassSelectionDialog dialog = new JavaClassSelectionDialog(project);
        if (dialog.showAndGet()) {
            return dialog.getSelectedClassName();
        }
        return null;
    }
}
