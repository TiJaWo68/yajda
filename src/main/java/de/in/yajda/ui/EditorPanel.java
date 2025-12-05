package de.in.yajda.ui;

import java.awt.BorderLayout;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.undo.UndoManager;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * RSyntaxTextArea-based editor panel with basic autocomplete and undo/redo. Implements a minimal "rename symbol within current file" refactoring.
 */
public class EditorPanel extends JPanel {
	private RSyntaxTextArea textArea;
	private UndoManager undoManager = new UndoManager();

	public EditorPanel() {
		super(new BorderLayout());
		textArea = new RSyntaxTextArea(25, 70);
		textArea.setCodeFoldingEnabled(true);
		RTextScrollPane sp = new RTextScrollPane(textArea);
		add(sp, BorderLayout.CENTER);

		// Undo/Redo
		textArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));
		Action undoAction = new AbstractAction("Undo") {
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (undoManager.canUndo())
					undoManager.undo();
			}
		};
		Action redoAction = new AbstractAction("Redo") {
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (undoManager.canRedo())
					undoManager.redo();
			}
		};
		JToolBar tb = new JToolBar();
		tb.setFloatable(false);
		JButton ub = new JButton("Undo");
		ub.addActionListener(undoAction);
		JButton rb = new JButton("Redo");
		rb.addActionListener(redoAction);
		JButton renameBtn = new JButton("Rename symbol");
		renameBtn.addActionListener(e -> {
			String sel = textArea.getSelectedText();
			if (sel == null || sel.isBlank()) {
				JOptionPane.showMessageDialog(this, "Select the symbol to rename first.", "Rename", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			String newName = JOptionPane.showInputDialog(this, "New name for '" + sel + "':");
			if (newName != null && !newName.isEmpty()) {
				renameSymbol(sel, newName);
			}
		});
		tb.add(ub);
		tb.add(rb);
		tb.add(renameBtn);
		add(tb, BorderLayout.NORTH);

		// Basic autocomplete provider (manual)
		DefaultCompletionProvider provider = new DefaultCompletionProvider();
		provider.addCompletion(new BasicCompletion(provider, "native"));
		provider.addCompletion(new BasicCompletion(provider, "nativeLib"));
		AutoCompletion ac = new AutoCompletion(provider);
		ac.install(textArea);
	}

	/**
	 * Set the language for highlighting using a friendly name. This method is called by the UI (MainWindow) and maps to internal syntax styles.
	 */
	public void setLanguage(String language) {
		if (language == null)
			return;
		switch (language) {
		case "BeanShell":
			setSyntax("BeanShell");
			break;
		case "Python":
			setSyntax("Python");
			break;
		case "JavaScript":
		default:
			setSyntax("JavaScript");
			break;
		}
	}

	/**
	 * Backward-compatible method to set syntax directly.
	 */
	public void setSyntax(String syntaxStyle) {
		switch (syntaxStyle) {
		case "BeanShell":
			textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
			break;
		case "Python":
			textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PYTHON);
			break;
		case "JavaScript":
		default:
			textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
			break;
		}
	}

	public void insertSnippet(String snippet) {
		textArea.insert(snippet, textArea.getCaretPosition());
	}

	public String getText() {
		return textArea.getText();
	}

	public void setText(String t) {
		textArea.setText(t);
	}

	/**
	 * Very small rename: replace word boundaries occurrences of oldName with newName in the current document.
	 */
	public void renameSymbol(String oldName, String newName) {
		String txt = textArea.getText();
		Pattern p = Pattern.compile("\\b" + Pattern.quote(oldName) + "\\b");
		Matcher m = p.matcher(txt);
		String replaced = m.replaceAll(newName);
		textArea.setText(replaced);
	}
}