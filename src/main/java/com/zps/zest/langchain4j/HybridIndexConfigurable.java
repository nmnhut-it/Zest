package com.zps.zest.langchain4j;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Configuration UI for Hybrid Index settings.
 */
public class HybridIndexConfigurable implements Configurable {
    private final Project project;
    private final HybridIndexSettings settings;
    
    private JBCheckBox useDiskStorageCheckBox;
    private JSpinner nameIndexCacheSizeSpinner;
    private JSpinner semanticIndexCacheSizeSpinner;
    private JSpinner structuralIndexCacheSizeSpinner;
    private JSpinner maxMemoryUsageSpinner;
    private JBCheckBox autoPersistCheckBox;
    private JSpinner autoPersistIntervalSpinner;
    
    public HybridIndexConfigurable(Project project) {
        this.project = project;
        this.settings = HybridIndexSettings.getInstance(project);
    }
    
    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Hybrid Code Search Index";
    }
    
    @Nullable
    @Override
    public String getHelpTopic() {
        return "zest.hybrid.index.settings";
    }
    
    @Nullable
    @Override
    public JComponent createComponent() {
        useDiskStorageCheckBox = new JBCheckBox("Use disk-based storage (recommended for large projects)");
        useDiskStorageCheckBox.setToolTipText("Enable disk-based storage to reduce memory usage");
        
        nameIndexCacheSizeSpinner = new JSpinner(new SpinnerNumberModel(10000, 100, 100000, 1000));
        semanticIndexCacheSizeSpinner = new JSpinner(new SpinnerNumberModel(1000, 100, 10000, 100));
        structuralIndexCacheSizeSpinner = new JSpinner(new SpinnerNumberModel(5000, 100, 50000, 500));
        maxMemoryUsageSpinner = new JSpinner(new SpinnerNumberModel(500, 50, 2000, 50));
        
        autoPersistCheckBox = new JBCheckBox("Enable automatic persistence");
        autoPersistIntervalSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 60, 1));
        
        // Enable/disable cache settings based on disk storage
        useDiskStorageCheckBox.addActionListener(e -> updateUIState());
        autoPersistCheckBox.addActionListener(e -> updateUIState());
        
        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel("Storage Configuration").withFont(JBUI.Fonts.label().deriveFont(Font.BOLD)))
            .addComponent(useDiskStorageCheckBox)
            .addVerticalGap(10)
            
            .addComponent(new JBLabel("Cache Sizes (for disk-based storage)").withFont(JBUI.Fonts.label().deriveFont(Font.BOLD)))
            .addLabeledComponent("Name index cache size:", nameIndexCacheSizeSpinner)
            .addLabeledComponent("Semantic index cache size:", semanticIndexCacheSizeSpinner)
            .addLabeledComponent("Structural index cache size:", structuralIndexCacheSizeSpinner)
            .addVerticalGap(10)
            
            .addComponent(new JBLabel("Memory Configuration").withFont(JBUI.Fonts.label().deriveFont(Font.BOLD)))
            .addLabeledComponent("Maximum memory usage (MB):", maxMemoryUsageSpinner)
            .addVerticalGap(10)
            
            .addComponent(new JBLabel("Persistence Configuration").withFont(JBUI.Fonts.label().deriveFont(Font.BOLD)))
            .addComponent(autoPersistCheckBox)
            .addLabeledComponent("Auto-persist interval (minutes):", autoPersistIntervalSpinner)
            .addVerticalGap(20)
            
            .addComponent(new JBLabel(
                "<html><i>Note: Changes will take effect after reindexing the project.<br>" +
                "Disk-based storage is recommended for projects with more than 10,000 files.</i></html>"))
            
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        
        return panel;
    }
    
    private void updateUIState() {
        boolean diskBased = useDiskStorageCheckBox.isSelected();
        nameIndexCacheSizeSpinner.setEnabled(diskBased);
        semanticIndexCacheSizeSpinner.setEnabled(diskBased);
        structuralIndexCacheSizeSpinner.setEnabled(diskBased);
        
        boolean autoPersist = autoPersistCheckBox.isSelected();
        autoPersistIntervalSpinner.setEnabled(diskBased && autoPersist);
    }
    
    @Override
    public boolean isModified() {
        return settings.isUseDiskStorage() != useDiskStorageCheckBox.isSelected() ||
               settings.getNameIndexCacheSize() != (int) nameIndexCacheSizeSpinner.getValue() ||
               settings.getSemanticIndexCacheSize() != (int) semanticIndexCacheSizeSpinner.getValue() ||
               settings.getStructuralIndexCacheSize() != (int) structuralIndexCacheSizeSpinner.getValue() ||
               settings.getMaxMemoryUsageMB() != (int) maxMemoryUsageSpinner.getValue() ||
               settings.isAutoPersist() != autoPersistCheckBox.isSelected() ||
               settings.getAutoPersistIntervalMinutes() != (int) autoPersistIntervalSpinner.getValue();
    }
    
    @Override
    public void apply() {
        settings.setUseDiskStorage(useDiskStorageCheckBox.isSelected());
        settings.setNameIndexCacheSize((int) nameIndexCacheSizeSpinner.getValue());
        settings.setSemanticIndexCacheSize((int) semanticIndexCacheSizeSpinner.getValue());
        settings.setStructuralIndexCacheSize((int) structuralIndexCacheSizeSpinner.getValue());
        settings.setMaxMemoryUsageMB((int) maxMemoryUsageSpinner.getValue());
        settings.setAutoPersist(autoPersistCheckBox.isSelected());
        settings.setAutoPersistIntervalMinutes((int) autoPersistIntervalSpinner.getValue());
    }
    
    @Override
    public void reset() {
        useDiskStorageCheckBox.setSelected(settings.isUseDiskStorage());
        nameIndexCacheSizeSpinner.setValue(settings.getNameIndexCacheSize());
        semanticIndexCacheSizeSpinner.setValue(settings.getSemanticIndexCacheSize());
        structuralIndexCacheSizeSpinner.setValue(settings.getStructuralIndexCacheSize());
        maxMemoryUsageSpinner.setValue(settings.getMaxMemoryUsageMB());
        autoPersistCheckBox.setSelected(settings.isAutoPersist());
        autoPersistIntervalSpinner.setValue(settings.getAutoPersistIntervalMinutes());
        
        updateUIState();
    }
}
