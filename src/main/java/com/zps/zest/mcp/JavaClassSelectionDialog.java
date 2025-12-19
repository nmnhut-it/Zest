package com.zps.zest.mcp;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Enhanced dialog for selecting Java classes for test generation.
 * Features: current class detection, recent classes, real-time search,
 * custom rendering, keyboard navigation, and polished UI.
 */
public class JavaClassSelectionDialog extends DialogWrapper {
    private static final int MAX_RESULTS = 100;
    private static final int MAX_RECENT = 5;
    private static final String PREF_KEY_RECENT = "zest.testgen.recentClasses";

    private final Project project;
    private JBList<ClassItem> classList;
    private DefaultListModel<ClassItem> classListModel;
    private JTextField searchField;
    private JBLabel resultCountLabel;
    private JCheckBox hideTestClassesCheckbox;
    private JPanel quickSelectPanel;
    private String selectedClassName;
    private Timer searchDebounceTimer;
    private String currentClassName;

    public JavaClassSelectionDialog(Project project) {
        super(project);
        this.project = project;
        this.currentClassName = detectCurrentClass();
        setTitle("Select Java Class for Test Generation");
        init();
        loadClasses("");
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        mainPanel.setPreferredSize(new Dimension(650, 550));
        mainPanel.setBorder(JBUI.Borders.empty(8));

        // Top section: Quick Select
        mainPanel.add(createQuickSelectPanel(), BorderLayout.NORTH);

        // Center section: Search + Results
        mainPanel.add(createSearchResultsPanel(), BorderLayout.CENTER);

        // Focus search field on open
        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());

        return mainPanel;
    }

    private JPanel createQuickSelectPanel() {
        quickSelectPanel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        quickSelectPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Quick Select",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));

        // Current class button
        if (currentClassName != null) {
            String simpleClassName = getSimpleClassName(currentClassName);
            JButton currentClassBtn = new JButton("Current: " + simpleClassName, AllIcons.Nodes.Class);
            currentClassBtn.setToolTipText(currentClassName);
            currentClassBtn.addActionListener(e -> selectAndClose(currentClassName));
            buttonsPanel.add(currentClassBtn);
        } else {
            JBLabel noCurrentLabel = new JBLabel("No class open in editor", AllIcons.General.Information, SwingConstants.LEFT);
            noCurrentLabel.setForeground(JBColor.GRAY);
            buttonsPanel.add(noCurrentLabel);
        }

        quickSelectPanel.add(buttonsPanel, BorderLayout.NORTH);

        // Recent classes
        List<String> recentClasses = getRecentClasses();
        if (!recentClasses.isEmpty()) {
            JPanel recentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)));
            recentPanel.add(new JBLabel("Recent:", AllIcons.Vcs.History, SwingConstants.LEFT));

            for (String recent : recentClasses) {
                if (recent.equals(currentClassName)) continue; // Don't duplicate current
                String simpleName = getSimpleClassName(recent);
                JButton recentBtn = new JButton(simpleName);
                recentBtn.setToolTipText(recent);
                recentBtn.setFont(recentBtn.getFont().deriveFont(Font.PLAIN, 11f));
                recentBtn.addActionListener(e -> selectAndClose(recent));
                recentPanel.add(recentBtn);
            }
            quickSelectPanel.add(recentPanel, BorderLayout.SOUTH);
        }

        return quickSelectPanel;
    }

    private JPanel createSearchResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));

        // Search bar
        JPanel searchPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        searchPanel.setBorder(JBUI.Borders.emptyBottom(4));

        JBLabel searchLabel = new JBLabel("Search:", AllIcons.Actions.Search, SwingConstants.LEFT);
        searchPanel.add(searchLabel, BorderLayout.WEST);

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type class name to filter...");
        setupSearchFieldListeners();
        searchPanel.add(searchField, BorderLayout.CENTER);

        // Filter checkbox
        hideTestClassesCheckbox = new JCheckBox("Hide test classes", true);
        hideTestClassesCheckbox.addActionListener(e -> loadClasses(searchField.getText()));
        searchPanel.add(hideTestClassesCheckbox, BorderLayout.EAST);

        panel.add(searchPanel, BorderLayout.NORTH);

        // Results list with custom renderer
        classListModel = new DefaultListModel<>();
        classList = new JBList<>(classListModel);
        classList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        classList.setCellRenderer(new ClassItemRenderer());
        setupListListeners();

        JBScrollPane scrollPane = new JBScrollPane(classList);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Search Results",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Bottom: result count + help
        JPanel bottomPanel = new JPanel(new BorderLayout());
        resultCountLabel = new JBLabel("", AllIcons.General.Information, SwingConstants.LEFT);
        resultCountLabel.setFont(resultCountLabel.getFont().deriveFont(11f));
        bottomPanel.add(resultCountLabel, BorderLayout.WEST);

        JBLabel helpLabel = new JBLabel("Tip: Double-click or press Enter to select");
        helpLabel.setFont(helpLabel.getFont().deriveFont(Font.ITALIC, 11f));
        helpLabel.setForeground(JBColor.GRAY);
        bottomPanel.add(helpLabel, BorderLayout.EAST);

        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void setupSearchFieldListeners() {
        // Real-time search with debounce
        searchDebounceTimer = new Timer(250, e -> loadClasses(searchField.getText()));
        searchDebounceTimer.setRepeats(false);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { restartDebounce(); }
            @Override
            public void removeUpdate(DocumentEvent e) { restartDebounce(); }
            @Override
            public void changedUpdate(DocumentEvent e) { restartDebounce(); }

            private void restartDebounce() {
                searchDebounceTimer.restart();
            }
        });

        // Enter key in search field selects first item
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && classListModel.size() > 0) {
                    ClassItem first = classListModel.get(0);
                    if (!first.isPlaceholder()) {
                        selectAndClose(first.qualifiedName);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    classList.requestFocusInWindow();
                    if (classListModel.size() > 0) {
                        classList.setSelectedIndex(0);
                    }
                }
            }
        });
    }

    private void setupListListeners() {
        // Selection listener
        classList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ClassItem item = classList.getSelectedValue();
                if (item != null && !item.isPlaceholder()) {
                    selectedClassName = item.qualifiedName;
                }
            }
        });

        // Double-click to select
        classList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ClassItem item = classList.getSelectedValue();
                    if (item != null && !item.isPlaceholder()) {
                        selectAndClose(item.qualifiedName);
                    }
                }
            }
        });

        // Enter key to select
        classList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ClassItem item = classList.getSelectedValue();
                    if (item != null && !item.isPlaceholder()) {
                        selectAndClose(item.qualifiedName);
                    }
                }
            }
        });
    }

    private void loadClasses(String filter) {
        classListModel.clear();

        List<ClassItem> results = ApplicationManager.getApplication().runReadAction(
                (Computable<List<ClassItem>>) () -> searchClasses(filter)
        );

        int totalFound = results.size();
        boolean truncated = totalFound >= MAX_RESULTS;

        for (ClassItem item : results) {
            classListModel.addElement(item);
        }

        // Update result count label
        if (totalFound == 0) {
            classListModel.addElement(ClassItem.placeholder("No classes found matching \"" + filter + "\""));
            resultCountLabel.setText("No results");
        } else if (truncated) {
            resultCountLabel.setText("Showing " + MAX_RESULTS + "+ classes (type more to narrow)");
        } else {
            resultCountLabel.setText(totalFound + " classes found");
        }

        // Auto-select first result
        if (classListModel.size() > 0 && !classListModel.get(0).isPlaceholder()) {
            classList.setSelectedIndex(0);
        }
    }

    private List<ClassItem> searchClasses(String filter) {
        List<ClassItem> results = new ArrayList<>();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        boolean hideTests = hideTestClassesCheckbox.isSelected();

        String[] allClassNames = cache.getAllClassNames();
        String lowerFilter = filter.toLowerCase();

        for (String className : allClassNames) {
            if (results.size() >= MAX_RESULTS) break;

            if (!filter.isEmpty() && !className.toLowerCase().contains(lowerFilter)) {
                continue;
            }

            PsiClass[] classes = cache.getClassesByName(className, scope);
            for (PsiClass psiClass : classes) {
                if (results.size() >= MAX_RESULTS) break;

                String qualifiedName = psiClass.getQualifiedName();
                if (qualifiedName == null) continue;

                // Filter test classes if checkbox is checked
                if (hideTests && isTestClass(qualifiedName, psiClass)) {
                    continue;
                }

                String packageName = extractPackage(qualifiedName);
                results.add(new ClassItem(className, packageName, qualifiedName));
            }
        }

        return results;
    }

    private boolean isTestClass(String qualifiedName, PsiClass psiClass) {
        // Check name patterns
        if (qualifiedName.endsWith("Test") || qualifiedName.endsWith("Tests") ||
            qualifiedName.endsWith("Spec") || qualifiedName.contains(".test.") ||
            qualifiedName.contains(".tests.")) {
            return true;
        }

        // Check for test annotations
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null && (name.contains("Test") || name.contains("RunWith"))) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private String detectCurrentClass() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            Editor editor = editorManager.getSelectedTextEditor();
            if (editor == null) return null;

            VirtualFile file = editorManager.getSelectedFiles().length > 0
                    ? editorManager.getSelectedFiles()[0] : null;
            if (file == null || !file.getName().endsWith(".java")) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (!(psiFile instanceof PsiJavaFile)) return null;

            PsiJavaFile javaFile = (PsiJavaFile) psiFile;
            PsiClass[] classes = javaFile.getClasses();

            // Try to find class at cursor position
            int offset = editor.getCaretModel().getOffset();
            PsiElement element = psiFile.findElementAt(offset);
            PsiClass classAtCursor = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (classAtCursor != null && classAtCursor.getQualifiedName() != null) {
                return classAtCursor.getQualifiedName();
            }

            // Fall back to first class in file
            if (classes.length > 0 && classes[0].getQualifiedName() != null) {
                return classes[0].getQualifiedName();
            }

            return null;
        });
    }

    private void selectAndClose(String className) {
        selectedClassName = className;
        addToRecentClasses(className);
        doOKAction();
    }

    private List<String> getRecentClasses() {
        Preferences prefs = Preferences.userNodeForPackage(JavaClassSelectionDialog.class);
        String stored = prefs.get(PREF_KEY_RECENT, "");
        List<String> result = new ArrayList<>();
        if (!stored.isEmpty()) {
            for (String s : stored.split("\\|")) {
                if (!s.isEmpty()) result.add(s);
            }
        }
        return result;
    }

    private void addToRecentClasses(String className) {
        LinkedHashSet<String> recent = new LinkedHashSet<>();
        recent.add(className); // Add new one first
        recent.addAll(getRecentClasses());

        // Keep only MAX_RECENT
        List<String> toStore = new ArrayList<>();
        int count = 0;
        for (String s : recent) {
            if (count++ >= MAX_RECENT) break;
            toStore.add(s);
        }

        Preferences prefs = Preferences.userNodeForPackage(JavaClassSelectionDialog.class);
        prefs.put(PREF_KEY_RECENT, String.join("|", toStore));
    }

    private static String getSimpleClassName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(0, lastDot) : "";
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

    // ========== Inner Classes ==========

    /** Represents a class item in the list. */
    private static class ClassItem {
        final String simpleName;
        final String packageName;
        final String qualifiedName;
        final boolean placeholder;

        ClassItem(String simpleName, String packageName, String qualifiedName) {
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.qualifiedName = qualifiedName;
            this.placeholder = false;
        }

        private ClassItem(String message) {
            this.simpleName = message;
            this.packageName = "";
            this.qualifiedName = "";
            this.placeholder = true;
        }

        static ClassItem placeholder(String message) {
            return new ClassItem(message);
        }

        boolean isPlaceholder() {
            return placeholder;
        }

        @Override
        public String toString() {
            return qualifiedName;
        }
    }

    /** Custom cell renderer for better visual hierarchy. */
    private static class ClassItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {

            JPanel panel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            panel.setBorder(JBUI.Borders.empty(4, 8));

            if (value instanceof ClassItem) {
                ClassItem item = (ClassItem) value;

                if (item.isPlaceholder()) {
                    JBLabel label = new JBLabel(item.simpleName, AllIcons.General.Information, SwingConstants.LEFT);
                    label.setForeground(JBColor.GRAY);
                    label.setFont(label.getFont().deriveFont(Font.ITALIC));
                    panel.add(label, BorderLayout.CENTER);
                } else {
                    // Class name (bold)
                    JBLabel nameLabel = new JBLabel(item.simpleName, AllIcons.Nodes.Class, SwingConstants.LEFT);
                    nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
                    panel.add(nameLabel, BorderLayout.WEST);

                    // Package name (gray, smaller)
                    if (!item.packageName.isEmpty()) {
                        JBLabel packageLabel = new JBLabel(item.packageName);
                        packageLabel.setForeground(JBColor.GRAY);
                        packageLabel.setFont(packageLabel.getFont().deriveFont(11f));
                        panel.add(packageLabel, BorderLayout.CENTER);
                    }
                }
            }

            if (isSelected) {
                panel.setBackground(list.getSelectionBackground());
                panel.setOpaque(true);
            } else {
                panel.setBackground(list.getBackground());
                panel.setOpaque(true);
            }

            return panel;
        }
    }
}
