package de.in.yajda.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Compact top control area for language selection and Run button.
 * Exposes simple hooks to register run/listener and to query/change language.
 */
public class TopControlPanel extends JPanel {
    private final JComboBox<String> languageCombo;
    private final JButton runButton;

    public TopControlPanel() {
        super(new FlowLayout(FlowLayout.LEFT));
        languageCombo = new JComboBox<>(new String[]{"BeanShell", "Python", "JavaScript"});
        languageCombo.setSelectedItem("BeanShell");
        runButton = new JButton("Run Script");
        add(new JLabel("Script language:"));
        add(languageCombo);
        add(runButton);
    }

    public void addRunListener(ActionListener l) {
        runButton.addActionListener(l);
    }

    public void addLanguageChangeListener(ActionListener l) {
        languageCombo.addActionListener(l);
    }

    public String getSelectedLanguage() {
        return (String) languageCombo.getSelectedItem();
    }

    public void setSelectedLanguage(String lang) {
        languageCombo.setSelectedItem(lang);
    }

    public JButton getRunButton() {
        return runButton;
    }
}