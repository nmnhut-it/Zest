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
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Enhanced dialog for selecting Java classes for test generation.
 * Features: current class detection, recent classes, real-time search,
 * test type selection, method filtering, and polished UI.
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

    // Test options
    private JRadioButton unitTestRadio;
    private JRadioButton integrationTestRadio;
    private JRadioButton bothTestRadio;
    private JPanel methodsPanel;
    private List<JCheckBox> methodCheckboxes = new ArrayList<>();
    private JCheckBox selectAllMethodsCheckbox;

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
        mainPanel.setPreferredSize(new Dimension(750, 650));
        mainPanel.setBorder(JBUI.Borders.empty(8));

        // Top section: Quick Select
        mainPanel.add(createQuickSelectPanel(), BorderLayout.NORTH);

        // Center: Split pane with class search on left, options on right
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(createSearchResultsPanel());
        splitPane.setRightComponent(createTestOptionsPanel());
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.6);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // Focus search field on open
        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());

        return mainPanel;
    }

    private JPanel createTestOptionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setBorder(JBUI.Borders.empty(0, 8, 0, 0));

        // Test Type section
        JPanel testTypePanel = new JPanel();
        testTypePanel.setLayout(new BoxLayout(testTypePanel, BoxLayout.Y_AXIS));
        testTypePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Test Type",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));

        ButtonGroup testTypeGroup = new ButtonGroup();
        bothTestRadio = new JRadioButton("Both (unit + integration)", true);
        unitTestRadio = new JRadioButton("Unit tests only");
        integrationTestRadio = new JRadioButton("Integration tests only");

        testTypeGroup.add(bothTestRadio);
        testTypeGroup.add(unitTestRadio);
        testTypeGroup.add(integrationTestRadio);

        testTypePanel.add(bothTestRadio);
        testTypePanel.add(Box.createVerticalStrut(4));
        testTypePanel.add(unitTestRadio);
        testTypePanel.add(Box.createVerticalStrut(4));
        testTypePanel.add(integrationTestRadio);
        testTypePanel.add(Box.createVerticalStrut(8));

        // Help text
        JBLabel helpLabel = new JBLabel("<html><small>Unit: fast, isolated<br>Integration: with real DB/HTTP</small></html>");
        helpLabel.setForeground(JBColor.GRAY);
        testTypePanel.add(helpLabel);

        panel.add(testTypePanel, BorderLayout.NORTH);

        // Methods section
        JPanel methodsOuterPanel = new JPanel(new BorderLayout());
        methodsOuterPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Methods to Test",
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));

        // Select All checkbox
        selectAllMethodsCheckbox = new JCheckBox("Select All", true);
        selectAllMethodsCheckbox.addActionListener(e -> {
            boolean selected = selectAllMethodsCheckbox.isSelected();
            for (JCheckBox cb : methodCheckboxes) {
                cb.setSelected(selected);
            }
        });
        methodsOuterPanel.add(selectAllMethodsCheckbox, BorderLayout.NORTH);

        // Methods list (populated when class is selected)
        methodsPanel = new JPanel();
        methodsPanel.setLayout(new BoxLayout(methodsPanel, BoxLayout.Y_AXIS));
        JBScrollPane methodsScroll = new JBScrollPane(methodsPanel);
        methodsScroll.setPreferredSize(new Dimension(280, 300));
        methodsOuterPanel.add(methodsScroll, BorderLayout.CENTER);

        // Placeholder text
        JBLabel placeholderLabel = new JBLabel("Select a class to see methods");
        placeholderLabel.setForeground(JBColor.GRAY);
        methodsPanel.add(placeholderLabel);

        panel.add(methodsOuterPanel, BorderLayout.CENTER);

        return panel;
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
        // Selection listener - also loads methods
        classList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ClassItem item = classList.getSelectedValue();
                if (item != null && !item.isPlaceholder()) {
                    selectedClassName = item.qualifiedName;
                    loadMethodsForClass(item.qualifiedName);
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

    private void loadMethodsForClass(String qualifiedName) {
        methodsPanel.removeAll();
        methodCheckboxes.clear();

        List<String> methods = ApplicationManager.getApplication().runReadAction(
                (Computable<List<String>>) () -> {
                    List<String> result = new ArrayList<>();
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass psiClass = facade.findClass(qualifiedName, GlobalSearchScope.allScope(project));
                    if (psiClass != null) {
                        for (PsiMethod method : psiClass.getMethods()) {
                            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                                result.add(method.getName());
                            }
                        }
                    }
                    return result;
                }
        );

        if (methods.isEmpty()) {
            JBLabel noMethodsLabel = new JBLabel("No public methods found");
            noMethodsLabel.setForeground(JBColor.GRAY);
            methodsPanel.add(noMethodsLabel);
        } else {
            for (String methodName : methods) {
                JCheckBox cb = new JCheckBox(methodName, true);
                cb.addActionListener(e -> updateSelectAllState());
                methodCheckboxes.add(cb);
                methodsPanel.add(cb);
            }
        }

        selectAllMethodsCheckbox.setSelected(true);
        methodsPanel.revalidate();
        methodsPanel.repaint();
    }

    private void updateSelectAllState() {
        boolean allSelected = methodCheckboxes.stream().allMatch(JCheckBox::isSelected);
        selectAllMethodsCheckbox.setSelected(allSelected);
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

    public String getSelectedTestType() {
        if (unitTestRadio.isSelected()) return "unit";
        if (integrationTestRadio.isSelected()) return "integration";
        return "both";
    }

    public Set<String> getSelectedMethods() {
        Set<String> selected = new LinkedHashSet<>();
        for (JCheckBox cb : methodCheckboxes) {
            if (cb.isSelected()) {
                selected.add(cb.getText());
            }
        }
        return selected;
    }

    /**
     * Result of the class selection dialog.
     */
    public static class SelectionResult {
        public final String className;
        public final String testType;
        public final Set<String> selectedMethods;

        public SelectionResult(String className, String testType, Set<String> selectedMethods) {
            this.className = className;
            this.testType = testType;
            this.selectedMethods = selectedMethods;
        }

        public String getMethodFilter() {
            return selectedMethods.isEmpty() ? "" : String.join(",", selectedMethods);
        }
    }

    /**
     * Shows the dialog and returns the selected class name, or null if cancelled.
     * @deprecated Use {@link #showAndGetSelection(Project)} instead.
     */
    @Nullable
    @Deprecated
    public static String showAndGetClassName(Project project) {
        SelectionResult result = showAndGetSelection(project);
        return result != null ? result.className : null;
    }

    /**
     * Shows the dialog and returns the full selection result, or null if cancelled.
     */
    @Nullable
    public static SelectionResult showAndGetSelection(Project project) {
        JavaClassSelectionDialog dialog = new JavaClassSelectionDialog(project);
        if (dialog.showAndGet()) {
            String className = dialog.getSelectedClassName();
            if (className != null) {
                return new SelectionResult(
                        className,
                        dialog.getSelectedTestType(),
                        dialog.getSelectedMethods()
                );
            }
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
