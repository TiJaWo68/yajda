package de.in.yajda.ui;

import de.in.yajda.dll.DllParser.FunctionInfo;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left-hand panel: search field + functions table.
 * Exposes simple API to set functions and register a double-click callback.
 */
public class FunctionListPanel extends JPanel {
    private final JTextField searchField;
    private final JTable table;
    private final FunctionTableModel model;
    private final TableRowSorter<FunctionTableModel> sorter;

    public FunctionListPanel() {
        super(new BorderLayout());
        JPanel searchPanel = new JPanel(new BorderLayout(4,4));
        searchField = new JTextField();
        searchField.setToolTipText("Filter functions (name, return type, params)");
        searchPanel.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        searchPanel.add(new JLabel("Search: "), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        add(searchPanel, BorderLayout.NORTH);

        model = new FunctionTableModel(Collections.emptyList());
        table = new JTable(model);
        table.setFillsViewportHeight(true);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        add(new JScrollPane(table), BorderLayout.CENTER);
        setPreferredSize(new Dimension(380, 600));

        // Wire search
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                String txt = searchField.getText();
                applyFilter(txt);
            }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
    }

    public void setFunctions(List<FunctionInfo> functions) {
        model.setFunctions(functions);
    }

    public List<FunctionInfo> getFunctions() {
        return model.getFunctions();
    }

    public void addFunctionDoubleClickListener(Consumer<FunctionInfo> listener) {
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.getSelectedRow();
                    if (viewRow >= 0) {
                        int modelRow = table.convertRowIndexToModel(viewRow);
                        FunctionInfo fi = model.getFunctionAt(modelRow);
                        if (fi != null) listener.accept(fi);
                    }
                }
            }
        });
    }

    private void applyFilter(String text) {
        if (text == null || text.trim().isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        final String lc = text.trim().toLowerCase();
        RowFilter<FunctionTableModel,Integer> rf = new RowFilter<FunctionTableModel,Integer>() {
            public boolean include(Entry<? extends FunctionTableModel, ? extends Integer> entry) {
                FunctionTableModel m = entry.getModel();
                int row = entry.getIdentifier();
                FunctionInfo fi = m.getFunctionAt(row);
                if (fi == null) return false;
                if (fi.name != null && fi.name.toLowerCase().contains(lc)) return true;
                if (fi.returnType != null && fi.returnType.toLowerCase().contains(lc)) return true;
                if (fi.paramTypes != null) {
                    for (String p : fi.paramTypes) {
                        if (p != null && p.toLowerCase().contains(lc)) return true;
                    }
                }
                return false;
            }
        };
        sorter.setRowFilter(rf);
    }

    // Simple table model encapsulated here
    static class FunctionTableModel extends AbstractTableModel {
        private final String[] cols = {"Name", "Return", "Params"};
        private List<FunctionInfo> functions;

        FunctionTableModel(List<FunctionInfo> functions) {
            this.functions = new ArrayList<>(functions);
        }

        void setFunctions(List<FunctionInfo> f) {
            this.functions = new ArrayList<>(f);
            fireTableDataChanged();
        }

        List<FunctionInfo> getFunctions() {
            return new ArrayList<>(functions);
        }

        FunctionInfo getFunctionAt(int row) {
            return functions.get(row);
        }

        @Override
        public int getRowCount() {
            return functions.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int column) {
            return cols[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FunctionInfo fi = functions.get(rowIndex);
            switch (columnIndex) {
                case 0: return fi.name;
                case 1: return fi.returnType != null ? fi.returnType : "unknown";
                case 2: return fi.paramTypes != null ? String.join(", ", fi.paramTypes) : "";
                default: return "";
            }
        }
    }
}