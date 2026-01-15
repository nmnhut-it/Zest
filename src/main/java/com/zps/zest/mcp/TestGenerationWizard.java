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
import org.jetbrains.annotations.NotNull;
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
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Wizard-style dialog for test generation configuration.
 * Guides user through: Class Selection → Method Selection → Context Config.
 */
public class TestGenerationWizard extends DialogWrapper {

    private static final int MAX_RESULTS = 100;
    private static final int MAX_RECENT = 5;
    private static final String PREF_KEY_RECENT = "zest.testgen.recentClasses";

    // Wizard state
    private enum Step { CLASS_SELECTION, METHOD_SELECTION, CONTEXT_CONFIG }
    private Step currentStep = Step.CLASS_SELECTION;

    private final Project project;
    private CardLayout cardLayout;
    private JPanel cardPanel;
    private JButton backButton;
    private JButton nextButton;
    private JLabel stepIndicator;

    // Step 1: Class Selection
    private JTextField searchField;
    private DefaultListModel<ClassItem> classListModel;
    private JBList<ClassItem> classList;
    private JBLabel resultCountLabel;
    private JCheckBox hideTestClassesCheckbox;
    private javax.swing.Timer searchDebounceTimer;
    private volatile boolean isSearching = false;
    private String currentClassName;

    // Step 2: Method Selection
    private JPanel methodsPanel;
    private List<MethodItem> methodItems = new ArrayList<>();
    private JCheckBox selectAllMethodsCheckbox;
    private JRadioButton unitTestRadio;
    private JRadioButton integrationTestRadio;
    private JRadioButton bothTestRadio;
    private JLabel selectedClassLabel;

    // Step 3: Context Config
    private JPanel dependenciesPanel;
    private List<JCheckBox> dependencyCheckboxes = new ArrayList<>();
    private DefaultListModel<String> additionalFilesModel;
    private JList<String> additionalFilesList;
    private JTextField rulesFileField;
    private DefaultListModel<String> exampleFilesModel;
    private JList<String> exampleFilesList;
    private JLabel contextSummaryLabel;

    // Result
    private String selectedClassName;
    private Set<String> selectedMethods = new LinkedHashSet<>();
    private String selectedTestType = "both";

    public TestGenerationWizard(Project project) {
        super(project);
        this.project = project;
        this.currentClassName = detectCurrentClass();
        setTitle("Test Generation Wizard");
        setOKButtonText("Generate");
        init();
    }

    @Override
    protected String getDimensionServiceKey() {
        return "zest.TestGenerationWizard";
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        mainPanel.setPreferredSize(new Dimension(JBUI.scale(700), JBUI.scale(500)));
        mainPanel.setBorder(JBUI.Borders.empty(12));

        // Step indicator at top
        mainPanel.add(createStepIndicator(), BorderLayout.NORTH);

        // Card panel for steps
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(createStep1Panel(), Step.CLASS_SELECTION.name());
        cardPanel.add(createStep2Panel(), Step.METHOD_SELECTION.name());
        cardPanel.add(createStep3Panel(), Step.CONTEXT_CONFIG.name());
        mainPanel.add(cardPanel, BorderLayout.CENTER);

        // Load initial data
        SwingUtilities.invokeLater(() -> {
            searchField.requestFocusInWindow();
            loadClasses("");
        });

        return mainPanel;
    }

    @Override
    protected JComponent createSouthPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(8, 0, 0, 0));

        // Navigation buttons on the right
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));

        backButton = new JButton("< Back");
        backButton.setEnabled(false);
        backButton.addActionListener(e -> goBack());
        buttonPanel.add(backButton);

        nextButton = new JButton("Next >");
        nextButton.addActionListener(e -> goNext());
        buttonPanel.add(nextButton);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> doCancelAction());
        buttonPanel.add(cancelButton);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel createStepIndicator() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(20), 0));
        panel.setBorder(JBUI.Borders.emptyBottom(8));

        stepIndicator = new JLabel();
        updateStepIndicator();
        panel.add(stepIndicator);

        return panel;
    }

    private void updateStepIndicator() {
        String step1 = currentStep == Step.CLASS_SELECTION ? "<b>1. Select Class</b>" : "1. Select Class";
        String step2 = currentStep == Step.METHOD_SELECTION ? "<b>2. Select Methods</b>" : "2. Select Methods";
        String step3 = currentStep == Step.CONTEXT_CONFIG ? "<b>3. Configure Context</b>" : "3. Configure Context";

        String html = String.format("<html><span style='color:%s'>%s</span> → <span style='color:%s'>%s</span> → <span style='color:%s'>%s</span></html>",
                currentStep == Step.CLASS_SELECTION ? "#2196F3" : "#888888", step1,
                currentStep == Step.METHOD_SELECTION ? "#2196F3" : "#888888", step2,
                currentStep == Step.CONTEXT_CONFIG ? "#2196F3" : "#888888", step3);

        stepIndicator.setText(html);
    }

    // ==================== Step 1: Class Selection ====================

    private JPanel createStep1Panel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(12)));

        // Header
        JLabel headerLabel = new JLabel("<html><h3>Select a Java Class</h3>Choose the class you want to generate tests for.</html>");
        panel.add(headerLabel, BorderLayout.NORTH);

        // Main content
        JPanel contentPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));

        // Quick select buttons
        contentPanel.add(createQuickSelectPanel(), BorderLayout.NORTH);

        // Search and results
        contentPanel.add(createSearchPanel(), BorderLayout.CENTER);

        panel.add(contentPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createQuickSelectPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Quick Select", TitledBorder.LEFT, TitledBorder.TOP));

        if (currentClassName != null) {
            String simpleName = getSimpleClassName(currentClassName);
            JButton currentBtn = new JButton("Current: " + simpleName, AllIcons.Nodes.Class);
            currentBtn.setToolTipText(currentClassName);
            currentBtn.addActionListener(e -> {
                selectedClassName = currentClassName;
                loadMethodsAndAdvance();
            });
            panel.add(currentBtn);
        } else {
            panel.add(new JBLabel("No class open in editor", AllIcons.General.Information, SwingConstants.LEFT));
        }

        // Recent classes
        List<String> recent = getRecentClasses();
        if (!recent.isEmpty()) {
            panel.add(new JLabel(" | Recent:"));
            for (String r : recent) {
                if (r.equals(currentClassName)) continue;
                JButton btn = new JButton(getSimpleClassName(r));
                btn.setFont(btn.getFont().deriveFont(11f));
                btn.setToolTipText(r);
                btn.addActionListener(e -> {
                    selectedClassName = r;
                    loadMethodsAndAdvance();
                });
                panel.add(btn);
            }
        }

        return panel;
    }

    private JPanel createSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));

        // Search bar
        JPanel searchBar = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        searchBar.add(new JBLabel("Search:", AllIcons.Actions.Search, SwingConstants.LEFT), BorderLayout.WEST);

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Type class name...");
        setupSearchListeners();
        searchBar.add(searchField, BorderLayout.CENTER);

        hideTestClassesCheckbox = new JCheckBox("Hide tests", true);
        hideTestClassesCheckbox.addActionListener(e -> loadClasses(searchField.getText()));
        searchBar.add(hideTestClassesCheckbox, BorderLayout.EAST);

        panel.add(searchBar, BorderLayout.NORTH);

        // Results list
        classListModel = new DefaultListModel<>();
        classList = new JBList<>(classListModel);
        classList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        classList.setCellRenderer(new ClassItemRenderer());
        setupListListeners();

        JBScrollPane scrollPane = new JBScrollPane(classList);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Search Results", TitledBorder.LEFT, TitledBorder.TOP));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Result count
        resultCountLabel = new JBLabel("", AllIcons.General.Information, SwingConstants.LEFT);
        resultCountLabel.setFont(resultCountLabel.getFont().deriveFont(11f));
        panel.add(resultCountLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void setupSearchListeners() {
        searchDebounceTimer = new javax.swing.Timer(250, e -> loadClasses(searchField.getText()));
        searchDebounceTimer.setRepeats(false);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchDebounceTimer.restart(); }
            public void removeUpdate(DocumentEvent e) { searchDebounceTimer.restart(); }
            public void changedUpdate(DocumentEvent e) { searchDebounceTimer.restart(); }
        });

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && classListModel.size() > 0) {
                    ClassItem item = classListModel.get(0);
                    if (!item.isPlaceholder()) {
                        selectedClassName = item.qualifiedName;
                        loadMethodsAndAdvance();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    classList.requestFocusInWindow();
                    if (classListModel.size() > 0) classList.setSelectedIndex(0);
                }
            }
        });
    }

    private void setupListListeners() {
        classList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ClassItem item = classList.getSelectedValue();
                    if (item != null && !item.isPlaceholder()) {
                        selectedClassName = item.qualifiedName;
                        loadMethodsAndAdvance();
                    }
                }
            }
        });

        classList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    ClassItem item = classList.getSelectedValue();
                    if (item != null && !item.isPlaceholder()) {
                        selectedClassName = item.qualifiedName;
                        loadMethodsAndAdvance();
                    }
                }
            }
        });
    }

    private void loadClasses(String filter) {
        if (isSearching) return;
        isSearching = true;

        classListModel.clear();
        classListModel.addElement(ClassItem.placeholder("Searching..."));
        resultCountLabel.setText("Searching...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<ClassItem> results = ApplicationManager.getApplication().runReadAction(
                    (Computable<List<ClassItem>>) () -> searchClasses(filter));

            SwingUtilities.invokeLater(() -> {
                isSearching = false;
                classListModel.clear();

                if (results.isEmpty()) {
                    classListModel.addElement(ClassItem.placeholder("No classes found"));
                    resultCountLabel.setText("No results");
                } else {
                    for (ClassItem item : results) {
                        classListModel.addElement(item);
                    }
                    resultCountLabel.setText(results.size() + (results.size() >= MAX_RESULTS ? "+" : "") + " classes");
                    classList.setSelectedIndex(0);
                }
            });
        });
    }

    private List<ClassItem> searchClasses(String filter) {
        List<ClassItem> results = new ArrayList<>();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        boolean hideTests = hideTestClassesCheckbox.isSelected();
        String lowerFilter = filter.toLowerCase();

        for (String className : cache.getAllClassNames()) {
            if (results.size() >= MAX_RESULTS) break;
            if (!filter.isEmpty() && !className.toLowerCase().contains(lowerFilter)) continue;

            PsiClass[] classes = cache.getClassesByName(className, scope);
            for (PsiClass psiClass : classes) {
                if (results.size() >= MAX_RESULTS) break;
                String qualifiedName = psiClass.getQualifiedName();
                if (qualifiedName == null) continue;
                if (hideTests && isTestClass(qualifiedName, psiClass)) continue;

                String packageName = extractPackage(qualifiedName);
                results.add(new ClassItem(className, packageName, qualifiedName));
            }
        }
        return results;
    }

    // ==================== Step 2: Method Selection ====================

    private JPanel createStep2Panel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(12)));

        // Header with class name
        selectedClassLabel = new JLabel("<html><h3>Select Methods to Test</h3></html>");
        panel.add(selectedClassLabel, BorderLayout.NORTH);

        // Methods list
        JPanel methodsOuterPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));

        // Select all checkbox
        selectAllMethodsCheckbox = new JCheckBox("Select All Methods", true);
        selectAllMethodsCheckbox.setFont(selectAllMethodsCheckbox.getFont().deriveFont(Font.BOLD));
        selectAllMethodsCheckbox.addActionListener(e -> {
            boolean selected = selectAllMethodsCheckbox.isSelected();
            for (MethodItem item : methodItems) {
                item.checkbox.setSelected(selected);
            }
        });
        methodsOuterPanel.add(selectAllMethodsCheckbox, BorderLayout.NORTH);

        // Methods panel with checkboxes
        methodsPanel = new JPanel();
        methodsPanel.setLayout(new BoxLayout(methodsPanel, BoxLayout.Y_AXIS));
        JBScrollPane scrollPane = new JBScrollPane(methodsPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(JBColor.border()));
        methodsOuterPanel.add(scrollPane, BorderLayout.CENTER);

        panel.add(methodsOuterPanel, BorderLayout.CENTER);

        // Test type selection at bottom
        panel.add(createTestTypePanel(), BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTestTypePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Test Type", TitledBorder.LEFT, TitledBorder.TOP));

        ButtonGroup group = new ButtonGroup();
        bothTestRadio = new JRadioButton("Both (unit + integration)", true);
        unitTestRadio = new JRadioButton("Unit tests only");
        integrationTestRadio = new JRadioButton("Integration tests only");

        group.add(bothTestRadio);
        group.add(unitTestRadio);
        group.add(integrationTestRadio);

        panel.add(bothTestRadio);
        panel.add(unitTestRadio);
        panel.add(integrationTestRadio);

        return panel;
    }

    private void loadMethodsForSelectedClass() {
        methodsPanel.removeAll();
        methodItems.clear();

        // Show loading
        JLabel loadingLabel = new JLabel("Loading methods...", AllIcons.Process.Step_1, SwingConstants.LEFT);
        loadingLabel.setBorder(JBUI.Borders.empty(8));
        methodsPanel.add(loadingLabel);
        methodsPanel.revalidate();
        methodsPanel.repaint();

        selectedClassLabel.setText("<html><h3>Select Methods to Test</h3>Class: <code>" + selectedClassName + "</code></html>");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<MethodInfo> methods = ApplicationManager.getApplication().runReadAction(
                    (Computable<List<MethodInfo>>) () -> {
                        List<MethodInfo> result = new ArrayList<>();
                        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                        PsiClass psiClass = facade.findClass(selectedClassName, GlobalSearchScope.allScope(project));
                        if (psiClass != null) {
                            for (PsiMethod method : psiClass.getMethods()) {
                                if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                                    String signature = buildMethodSignature(method);
                                    String returnType = method.getReturnType() != null ?
                                            method.getReturnType().getPresentableText() : "void";
                                    result.add(new MethodInfo(method.getName(), signature, returnType));
                                }
                            }
                        }
                        return result;
                    });

            SwingUtilities.invokeLater(() -> {
                methodsPanel.removeAll();
                methodItems.clear();

                if (methods.isEmpty()) {
                    JLabel noMethodsLabel = new JLabel("No public methods found");
                    noMethodsLabel.setForeground(JBColor.GRAY);
                    noMethodsLabel.setBorder(JBUI.Borders.empty(8));
                    methodsPanel.add(noMethodsLabel);
                } else {
                    for (MethodInfo info : methods) {
                        JPanel row = createMethodRow(info);
                        methodsPanel.add(row);
                    }
                }

                selectAllMethodsCheckbox.setSelected(true);
                methodsPanel.revalidate();
                methodsPanel.repaint();
            });
        });
    }

    private JPanel createMethodRow(MethodInfo info) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        row.setBorder(JBUI.Borders.empty(4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(28)));

        JCheckBox checkbox = new JCheckBox(info.name, true);
        checkbox.setFont(checkbox.getFont().deriveFont(Font.BOLD));
        checkbox.addActionListener(e -> updateSelectAllState());
        row.add(checkbox, BorderLayout.WEST);

        JLabel signatureLabel = new JLabel(info.signature);
        signatureLabel.setForeground(JBColor.GRAY);
        signatureLabel.setFont(signatureLabel.getFont().deriveFont(11f));
        row.add(signatureLabel, BorderLayout.CENTER);

        JLabel returnLabel = new JLabel(info.returnType);
        returnLabel.setForeground(new JBColor(new Color(0, 128, 0), new Color(100, 200, 100)));
        returnLabel.setFont(returnLabel.getFont().deriveFont(Font.ITALIC, 11f));
        row.add(returnLabel, BorderLayout.EAST);

        MethodItem item = new MethodItem(info.name, checkbox);
        methodItems.add(item);

        return row;
    }

    private String buildMethodSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getType().getPresentableText());
            sb.append(" ").append(params[i].getName());
        }
        sb.append(")");
        return sb.toString();
    }

    private void updateSelectAllState() {
        boolean allSelected = methodItems.stream().allMatch(m -> m.checkbox.isSelected());
        selectAllMethodsCheckbox.setSelected(allSelected);
    }

    // ==================== Step 3: Context Configuration ====================

    private JPanel createStep3Panel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(12)));

        // Header
        JLabel headerLabel = new JLabel("<html><h3>Configure Context (Optional)</h3>Add dependencies and examples to improve test quality.</html>");
        panel.add(headerLabel, BorderLayout.NORTH);

        // Content with tabs
        JTabbedPane tabs = new JTabbedPane();

        // Dependencies tab
        tabs.addTab("Dependencies", AllIcons.Nodes.Related, createDependenciesTab());

        // Rules & Examples tab
        tabs.addTab("Rules & Examples", AllIcons.FileTypes.Text, createRulesTab());

        panel.add(tabs, BorderLayout.CENTER);

        // Summary at bottom
        contextSummaryLabel = new JLabel();
        contextSummaryLabel.setBorder(JBUI.Borders.empty(8));
        updateContextSummary();
        panel.add(contextSummaryLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createDependenciesTab() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setBorder(JBUI.Borders.empty(8));

        JLabel infoLabel = new JLabel("<html>Related project classes found in this class's fields, methods, and inheritance.<br>Select which ones to include as context for test generation.</html>");
        infoLabel.setForeground(JBColor.GRAY);
        panel.add(infoLabel, BorderLayout.NORTH);

        dependenciesPanel = new JPanel();
        dependenciesPanel.setLayout(new GridBagLayout());
        dependenciesPanel.setBorder(JBUI.Borders.empty(4));
        JBScrollPane scrollPane = new JBScrollPane(dependenciesPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Additional files section
        JPanel filesPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        filesPanel.setBorder(BorderFactory.createTitledBorder("Additional Files"));

        additionalFilesModel = new DefaultListModel<>();
        additionalFilesList = new JBList<>(additionalFilesModel);
        additionalFilesList.setVisibleRowCount(3);
        filesPanel.add(new JBScrollPane(additionalFilesList), BorderLayout.CENTER);

        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("Add...", AllIcons.General.Add);
        addBtn.addActionListener(e -> addAdditionalFile());
        JButton removeBtn = new JButton("Remove", AllIcons.General.Remove);
        removeBtn.addActionListener(e -> {
            int idx = additionalFilesList.getSelectedIndex();
            if (idx >= 0) additionalFilesModel.remove(idx);
            updateContextSummary();
        });
        fileButtons.add(addBtn);
        fileButtons.add(removeBtn);
        filesPanel.add(fileButtons, BorderLayout.SOUTH);

        panel.add(filesPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createRulesTab() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        panel.setBorder(JBUI.Borders.empty(8));

        // Rules file
        JPanel rulesPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        rulesPanel.setBorder(BorderFactory.createTitledBorder("Rules File"));
        rulesFileField = new JTextField();
        rulesFileField.setEditable(false);
        detectRulesFile();
        rulesPanel.add(rulesFileField, BorderLayout.CENTER);
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> browseRulesFile());
        rulesPanel.add(browseBtn, BorderLayout.EAST);
        panel.add(rulesPanel, BorderLayout.NORTH);

        // Example files
        JPanel examplesPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        examplesPanel.setBorder(BorderFactory.createTitledBorder("Example Test Files"));
        exampleFilesModel = new DefaultListModel<>();
        detectExampleFiles();
        exampleFilesList = new JBList<>(exampleFilesModel);
        examplesPanel.add(new JBScrollPane(exampleFilesList), BorderLayout.CENTER);

        JPanel exampleButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addExBtn = new JButton("Add...", AllIcons.General.Add);
        addExBtn.addActionListener(e -> addExampleFile());
        JButton removeExBtn = new JButton("Remove", AllIcons.General.Remove);
        removeExBtn.addActionListener(e -> {
            int idx = exampleFilesList.getSelectedIndex();
            if (idx >= 0) exampleFilesModel.remove(idx);
            updateContextSummary();
        });
        exampleButtons.add(addExBtn);
        exampleButtons.add(removeExBtn);
        examplesPanel.add(exampleButtons, BorderLayout.SOUTH);

        panel.add(examplesPanel, BorderLayout.CENTER);

        return panel;
    }

    private void loadDependenciesForSelectedClass() {
        dependenciesPanel.removeAll();
        dependencyCheckboxes.clear();

        JLabel loadingLabel = new JLabel("Analyzing dependencies...", AllIcons.Process.Step_1, SwingConstants.LEFT);
        dependenciesPanel.add(loadingLabel);
        dependenciesPanel.revalidate();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<DependencyInfo> deps = ApplicationManager.getApplication().runReadAction(
                    (Computable<List<DependencyInfo>>) () -> {
                        List<DependencyInfo> result = new ArrayList<>();
                        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                        PsiClass psiClass = facade.findClass(selectedClassName, GlobalSearchScope.allScope(project));
                        if (psiClass != null) {
                            Set<PsiClass> related = new HashSet<>();
                            com.zps.zest.core.ClassAnalyzer.collectRelatedClasses(psiClass, related);
                            for (PsiClass r : related) {
                                if (r.equals(psiClass)) continue;
                                String qName = r.getQualifiedName();
                                if (qName == null) continue;
                                boolean isLib = isLibraryClass(qName);
                                result.add(new DependencyInfo(qName, r.getName(), isLib));
                            }
                        }
                        return result;
                    });

            SwingUtilities.invokeLater(() -> {
                dependenciesPanel.removeAll();
                dependencyCheckboxes.clear();

                GridBagConstraints gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = 0;
                gbc.anchor = GridBagConstraints.NORTHWEST;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                gbc.weightx = 1.0;
                gbc.insets = JBUI.insets(2, 0);

                if (deps.isEmpty()) {
                    JLabel emptyLabel = new JLabel("<html><i>This class only uses standard Java libraries.<br>No project dependencies to include.</i></html>");
                    emptyLabel.setForeground(JBColor.GRAY);
                    emptyLabel.setBorder(JBUI.Borders.empty(8));
                    dependenciesPanel.add(emptyLabel, gbc);
                } else {
                    deps.sort((a, b) -> {
                        if (a.isLibrary != b.isLibrary) return a.isLibrary ? 1 : -1;
                        return a.simpleName.compareTo(b.simpleName);
                    });
                    for (DependencyInfo dep : deps) {
                        JCheckBox cb = new JCheckBox(dep.simpleName + (dep.isLibrary ? " [lib]" : ""), !dep.isLibrary);
                        cb.setToolTipText(dep.qualifiedName);
                        if (dep.isLibrary) cb.setForeground(JBColor.GRAY);
                        cb.addActionListener(e -> updateContextSummary());
                        dependencyCheckboxes.add(cb);
                        dependenciesPanel.add(cb, gbc);
                        gbc.gridy++;
                    }
                }
                // Add filler to push items to top
                gbc.weighty = 1.0;
                gbc.fill = GridBagConstraints.BOTH;
                dependenciesPanel.add(Box.createVerticalGlue(), gbc);

                dependenciesPanel.revalidate();
                dependenciesPanel.repaint();
                updateContextSummary();
            });
        });
    }

    private void updateContextSummary() {
        int deps = (int) dependencyCheckboxes.stream().filter(JCheckBox::isSelected).count();
        int files = additionalFilesModel.size();
        int examples = exampleFilesModel.size();
        boolean hasRules = rulesFileField.getText() != null && !rulesFileField.getText().contains("not found");

        int tokens = 2000 + (deps * 500) + (files * 1000) + (examples * 800);
        contextSummaryLabel.setText(String.format(
                "<html><b>Context:</b> %d dependencies, %d files, %s, %d examples | <i>Est. ~%,d tokens</i></html>",
                deps, files, hasRules ? "1 rules file" : "default rules", examples, tokens));
    }

    // ==================== Navigation ====================

    private void goBack() {
        switch (currentStep) {
            case METHOD_SELECTION:
                currentStep = Step.CLASS_SELECTION;
                break;
            case CONTEXT_CONFIG:
                currentStep = Step.METHOD_SELECTION;
                break;
        }
        updateNavigation();
    }

    private void goNext() {
        switch (currentStep) {
            case CLASS_SELECTION:
                ClassItem item = classList.getSelectedValue();
                if (item != null && !item.isPlaceholder()) {
                    selectedClassName = item.qualifiedName;
                    loadMethodsAndAdvance();
                }
                break;
            case METHOD_SELECTION:
                collectSelectedMethods();
                currentStep = Step.CONTEXT_CONFIG;
                loadDependenciesForSelectedClass();
                updateNavigation();
                break;
            case CONTEXT_CONFIG:
                collectResults();
                doOKAction();
                break;
        }
    }

    private void loadMethodsAndAdvance() {
        addToRecentClasses(selectedClassName);
        currentStep = Step.METHOD_SELECTION;
        loadMethodsForSelectedClass();
        updateNavigation();
    }

    private void updateNavigation() {
        cardLayout.show(cardPanel, currentStep.name());
        updateStepIndicator();

        backButton.setEnabled(currentStep != Step.CLASS_SELECTION);
        nextButton.setText(currentStep == Step.CONTEXT_CONFIG ? "Generate" : "Next >");
    }

    private void collectSelectedMethods() {
        selectedMethods.clear();
        for (MethodItem item : methodItems) {
            if (item.checkbox.isSelected()) {
                selectedMethods.add(item.name);
            }
        }
        if (unitTestRadio.isSelected()) selectedTestType = "unit";
        else if (integrationTestRadio.isSelected()) selectedTestType = "integration";
        else selectedTestType = "both";
    }

    private void collectResults() {
        collectSelectedMethods();
    }

    // ==================== Helper Methods ====================

    @Nullable
    private String detectCurrentClass() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            Editor editor = editorManager.getSelectedTextEditor();
            if (editor == null) return null;

            VirtualFile file = editorManager.getSelectedFiles().length > 0 ? editorManager.getSelectedFiles()[0] : null;
            if (file == null || !file.getName().endsWith(".java")) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (!(psiFile instanceof PsiJavaFile)) return null;

            PsiJavaFile javaFile = (PsiJavaFile) psiFile;
            int offset = editor.getCaretModel().getOffset();
            PsiElement element = psiFile.findElementAt(offset);
            PsiClass classAtCursor = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (classAtCursor != null && classAtCursor.getQualifiedName() != null) {
                return classAtCursor.getQualifiedName();
            }

            PsiClass[] classes = javaFile.getClasses();
            if (classes.length > 0 && classes[0].getQualifiedName() != null) {
                return classes[0].getQualifiedName();
            }
            return null;
        });
    }

    private boolean isTestClass(String qualifiedName, PsiClass psiClass) {
        if (qualifiedName.endsWith("Test") || qualifiedName.endsWith("Tests") ||
            qualifiedName.contains(".test.") || qualifiedName.contains(".tests.")) {
            return true;
        }
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null && (name.contains("Test") || name.contains("RunWith"))) return true;
        }
        return false;
    }

    private boolean isLibraryClass(String qualifiedName) {
        return qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.") ||
               qualifiedName.startsWith("org.slf4j.") || qualifiedName.startsWith("org.apache.") ||
               qualifiedName.startsWith("com.google.") || qualifiedName.startsWith("org.jetbrains.");
    }

    private List<String> getRecentClasses() {
        Preferences prefs = Preferences.userNodeForPackage(TestGenerationWizard.class);
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
        recent.add(className);
        recent.addAll(getRecentClasses());
        List<String> toStore = new ArrayList<>();
        int count = 0;
        for (String s : recent) {
            if (count++ >= MAX_RECENT) break;
            toStore.add(s);
        }
        Preferences prefs = Preferences.userNodeForPackage(TestGenerationWizard.class);
        prefs.put(PREF_KEY_RECENT, String.join("|", toStore));
    }

    private void detectRulesFile() {
        String basePath = project.getBasePath();
        if (basePath != null) {
            java.nio.file.Path rulesPath = java.nio.file.Paths.get(basePath, ".zest", "test-examples", "rules.md");
            if (java.nio.file.Files.exists(rulesPath)) {
                rulesFileField.setText(rulesPath.toString());
            } else {
                rulesFileField.setText("(not found - will use defaults)");
            }
        }
    }

    private void detectExampleFiles() {
        String basePath = project.getBasePath();
        if (basePath != null) {
            java.nio.file.Path examplesDir = java.nio.file.Paths.get(basePath, ".zest", "test-examples");
            if (java.nio.file.Files.exists(examplesDir)) {
                try (var files = java.nio.file.Files.list(examplesDir)) {
                    files.filter(p -> p.toString().endsWith(".java"))
                         .forEach(p -> exampleFilesModel.addElement(p.toString()));
                } catch (Exception e) { /* ignore */ }
            }
        }
    }

    private void browseRulesFile() {
        var descriptor = new com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
                .withFileFilter(file -> file.getName().endsWith(".md"));
        var file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null);
        if (file != null) {
            rulesFileField.setText(file.getPath());
            updateContextSummary();
        }
    }

    private void addAdditionalFile() {
        var descriptor = new com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
                .withFileFilter(file -> file.getName().endsWith(".java"));
        var files = com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null);
        for (var file : files) {
            if (!additionalFilesModel.contains(file.getPath())) {
                additionalFilesModel.addElement(file.getPath());
            }
        }
        updateContextSummary();
    }

    private void addExampleFile() {
        var descriptor = new com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
                .withFileFilter(file -> file.getName().endsWith(".java") || file.getName().endsWith(".md"));
        var files = com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null);
        for (var file : files) {
            if (!exampleFilesModel.contains(file.getPath())) {
                exampleFilesModel.addElement(file.getPath());
            }
        }
        updateContextSummary();
    }

    private static String getSimpleClassName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    private static String extractPackage(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(0, lastDot) : "";
    }

    // ==================== Result Getters ====================

    @Nullable
    public String getSelectedClassName() { return selectedClassName; }

    public String getSelectedTestType() { return selectedTestType; }

    public Set<String> getSelectedMethods() { return selectedMethods; }

    public List<String> getIncludedDependencies() {
        List<String> deps = new ArrayList<>();
        for (JCheckBox cb : dependencyCheckboxes) {
            if (cb.isSelected()) deps.add(cb.getToolTipText());
        }
        return deps;
    }

    public List<String> getAdditionalFiles() {
        List<String> files = new ArrayList<>();
        for (int i = 0; i < additionalFilesModel.size(); i++) {
            files.add(additionalFilesModel.get(i));
        }
        return files;
    }

    public String getRulesFile() {
        String text = rulesFileField.getText();
        return (text != null && !text.contains("not found")) ? text : null;
    }

    public List<String> getExampleFiles() {
        List<String> files = new ArrayList<>();
        for (int i = 0; i < exampleFilesModel.size(); i++) {
            files.add(exampleFilesModel.get(i));
        }
        return files;
    }

    // ==================== Static Entry Point ====================

    /**
     * Shows the wizard and returns the selection result, or null if cancelled.
     */
    @Nullable
    public static SelectionResult showAndGetSelection(Project project) {
        TestGenerationWizard wizard = new TestGenerationWizard(project);
        if (wizard.showAndGet()) {
            String className = wizard.getSelectedClassName();
            if (className != null) {
                return new SelectionResult(
                        className,
                        wizard.getSelectedTestType(),
                        wizard.getSelectedMethods(),
                        wizard.getIncludedDependencies(),
                        wizard.getAdditionalFiles(),
                        wizard.getRulesFile(),
                        wizard.getExampleFiles()
                );
            }
        }
        return null;
    }

    /**
     * Result of the wizard containing all user selections.
     */
    public static class SelectionResult {
        public final String className;
        public final String testType;
        public final Set<String> selectedMethods;
        public final List<String> includedDependencies;
        public final List<String> additionalFiles;
        public final String rulesFile;
        public final List<String> exampleFiles;

        public SelectionResult(String className, String testType, Set<String> selectedMethods,
                               List<String> includedDependencies, List<String> additionalFiles,
                               String rulesFile, List<String> exampleFiles) {
            this.className = className;
            this.testType = testType;
            this.selectedMethods = selectedMethods;
            this.includedDependencies = includedDependencies;
            this.additionalFiles = additionalFiles;
            this.rulesFile = rulesFile;
            this.exampleFiles = exampleFiles;
        }

        public String getMethodFilter() {
            return selectedMethods.isEmpty() ? "" : String.join(",", selectedMethods);
        }
    }

    // ==================== Inner Classes ====================

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

        private ClassItem(String message, boolean placeholder) {
            this.simpleName = message;
            this.packageName = "";
            this.qualifiedName = "";
            this.placeholder = placeholder;
        }

        static ClassItem placeholder(String message) { return new ClassItem(message, true); }
        boolean isPlaceholder() { return placeholder; }
    }

    private static class MethodInfo {
        final String name;
        final String signature;
        final String returnType;

        MethodInfo(String name, String signature, String returnType) {
            this.name = name;
            this.signature = signature;
            this.returnType = returnType;
        }
    }

    private static class MethodItem {
        final String name;
        final JCheckBox checkbox;

        MethodItem(String name, JCheckBox checkbox) {
            this.name = name;
            this.checkbox = checkbox;
        }
    }

    private static class DependencyInfo {
        final String qualifiedName;
        final String simpleName;
        final boolean isLibrary;

        DependencyInfo(String qualifiedName, String simpleName, boolean isLibrary) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.isLibrary = isLibrary;
        }
    }

    private static class ClassItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JPanel panel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            panel.setBorder(JBUI.Borders.empty(4, 8));

            if (value instanceof ClassItem) {
                ClassItem item = (ClassItem) value;
                if (item.isPlaceholder()) {
                    JLabel label = new JLabel(item.simpleName, AllIcons.General.Information, SwingConstants.LEFT);
                    label.setForeground(JBColor.GRAY);
                    label.setFont(label.getFont().deriveFont(Font.ITALIC));
                    panel.add(label, BorderLayout.CENTER);
                } else {
                    JLabel nameLabel = new JLabel(item.simpleName, AllIcons.Nodes.Class, SwingConstants.LEFT);
                    nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
                    panel.add(nameLabel, BorderLayout.WEST);

                    if (!item.packageName.isEmpty()) {
                        JLabel pkgLabel = new JLabel(item.packageName);
                        pkgLabel.setForeground(JBColor.GRAY);
                        pkgLabel.setFont(pkgLabel.getFont().deriveFont(11f));
                        panel.add(pkgLabel, BorderLayout.CENTER);
                    }
                }
            }

            panel.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            panel.setOpaque(true);
            return panel;
        }
    }
}
