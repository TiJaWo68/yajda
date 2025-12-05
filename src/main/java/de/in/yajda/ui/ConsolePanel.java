package de.in.yajda.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Simple console panel with appendable text area.
 */
public class ConsolePanel extends JPanel {
    private final JTextArea area;

    public ConsolePanel() {
        super(new BorderLayout());
        area = new JTextArea();
        area.setEditable(false);
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(400, 150));
        add(sp, BorderLayout.CENTER);
    }

    public void append(String s) {
        SwingUtilities.invokeLater(() -> {
            area.append(s + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    public JTextArea getTextArea() {
        return area;
    }
}