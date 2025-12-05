package de.in.yajda.ui;

import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.undo.UndoManager;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * RSyntaxTextArea-based editor panel with basic autocomplete and undo/redo. Implements a minimal "rename symbol within current file"
 * refactoring.
 *
 * Changes: - Undo/Redo/Rename are provided via context menu (right-click) and keyboard shortcuts. - Autocompletion provider can be updated
 * dynamically (DLL functions, BeanShell symbols). - Ctrl+Space triggers completion (handled by AutoCompletion itself). - Word-at-caret
 * detection implemented without relying on org.fife Utilities class.
 */
public class EditorPanel extends JPanel {
	private RSyntaxTextArea textArea;
	private UndoManager undoManager = new UndoManager();

	private final DefaultCompletionProvider completionProvider;
	private final AutoCompletion ac;

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
		Action renameAction = new AbstractAction("Rename symbol") {
			public void actionPerformed(java.awt.event.ActionEvent e) {
				String sel = textArea.getSelectedText();
				if (sel == null || sel.isBlank()) {
					sel = getWordAtCaret();
				}
				if (sel == null || sel.isBlank()) {
					JOptionPane.showMessageDialog(EditorPanel.this, "Select the symbol to rename first.", "Rename", JOptionPane.INFORMATION_MESSAGE);
					return;
				}
				String target = JOptionPane.showInputDialog(EditorPanel.this, "New name for '" + sel + "':");
				if (target != null && !target.isEmpty()) {
					renameSymbol(sel, target);
				}
			}
		};

		// Context menu (right click)
		JPopupMenu popup = new JPopupMenu();
		JMenuItem undoItem = new JMenuItem("Undo");
		undoItem.addActionListener(undoAction);
		JMenuItem redoItem = new JMenuItem("Redo");
		redoItem.addActionListener(redoAction);
		JMenuItem renameItem = new JMenuItem("Rename symbol");
		renameItem.addActionListener(renameAction);
		popup.add(undoItem);
		popup.add(redoItem);
		popup.addSeparator();
		popup.add(renameItem);

		textArea.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent e) {
				if (e.isPopupTrigger())
					popup.show(e.getComponent(), e.getX(), e.getY());
			}

			public void mouseReleased(MouseEvent e) {
				if (e.isPopupTrigger())
					popup.show(e.getComponent(), e.getX(), e.getY());
			}
		});

		// Keyboard shortcuts (Eclipse-like)
		// Ctrl+Z -> Undo
		textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "undo");
		textArea.getActionMap().put("undo", new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				undoAction.actionPerformed(e);
			}
		});
		// Ctrl+Y -> Redo
		textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "redo");
		textArea.getActionMap().put("redo", new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				redoAction.actionPerformed(e);
			}
		});
		// Alt+Shift+R -> Rename (Eclipse default for rename refactor)
		textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "rename");
		textArea.getActionMap().put("rename", new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				renameAction.actionPerformed(e);
			}
		});

		// Autocomplete provider setup
		completionProvider = new DefaultCompletionProvider();
		// Add some basic completions to begin with
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "native"));
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "nativeLib"));
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "print"));
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "println"));

		ac = new AutoCompletion(completionProvider);
		ac.setParameterAssistanceEnabled(true);
		ac.setAutoActivationEnabled(false); // do not trigger on typing automatically
		ac.install(textArea);

		// Do NOT try to call ac.showCompletion() — some library versions don't expose that.
		// The AutoCompletion.install(...) registers Ctrl+Space for showing completions.
		// Keep no custom mapping here so the library's default binding is used.
	}

	/**
	 * Find the Java-like word at the caret position. Scans left/right for Java identifier parts.
	 */
	private String getWordAtCaret() {
		try {
			int pos = textArea.getCaretPosition();
			String txt = textArea.getText();
			if (txt == null || txt.isEmpty())
				return "";
			int len = txt.length();
			if (pos > len)
				pos = len;
			// find start
			int start = pos;
			while (start > 0) {
				char c = txt.charAt(start - 1);
				if (!Character.isJavaIdentifierPart(c))
					break;
				start--;
			}
			int end = pos;
			while (end < len) {
				char c = txt.charAt(end);
				if (!Character.isJavaIdentifierPart(c))
					break;
				end++;
			}
			if (start == end)
				return "";
			return txt.substring(start, end);
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Set the language for highlighting using a friendly name.
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
		String replaced = txt.replaceAll("\\b" + Pattern.quote(oldName) + "\\b", newName);
		textArea.setText(replaced);
	}

	/**
	 * Update the autocomplete suggestions. This replaces existing dynamic completions. Typical usage: pass DLL function names and available
	 * BeanShell globals.
	 */
	public void updateCompletions(Collection<String> names) {
		// Remove all completions and add baseline ones
		completionProvider.clear();
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "native"));
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "nativeLib"));
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "print"));
		completionProvider.addCompletion(new BasicCompletion(completionProvider, "println"));
		// Add provided names
		if (names != null) {
			for (String n : names) {
				if (n == null || n.isBlank())
					continue;
				String label = n;
				if (!n.endsWith("()"))
					label = n + "()";
				completionProvider.addCompletion(new BasicCompletion(completionProvider, label));
			}
		}
	}
}