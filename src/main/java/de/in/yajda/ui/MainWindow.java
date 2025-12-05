package de.in.yajda.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import de.in.yajda.Main;
import de.in.yajda.dll.DllParser;
import de.in.yajda.dll.DllParser.FunctionInfo;
import de.in.yajda.dll.JnaProxyFactory;
import de.in.yajda.script.ScriptManager;

/**
 * Main application window. Modified to load SVG icon via FlatLaf extras (FlatSVGIcon) and set it as the frame icon.
 */
public class MainWindow extends JFrame {
	private JTable functionTable;
	private FunctionTableModel functionTableModel;
	private EditorPanel editorPanel;
	private JTextArea consoleArea;
	private ScriptManager scriptManager;
	private JFileChooser fileChooser = new JFileChooser(".");
	private File currentDll;
	private JnaProxyFactory.ProxyWrapper nativeProxy;
	private JComboBox<String> languageCombo;

	public MainWindow() {
		super("Java-Dll-Analyzer");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(1000, 700);
		setLocationRelativeTo(null);

		initMenu();
		initUi();

		// Load SVG icon (uses FlatLaf extras). If not available or fails, just continue.
		try {
			java.net.URL iconUrl = getClass().getResource("/icon.svg");
			if (iconUrl != null) {
				FlatSVGIcon svgIcon = new FlatSVGIcon(iconUrl);
				Image img = svgIcon.derive(64, 64).getImage();
				if (img != null) {
					setIconImage(img);
				}
			} else {
				System.err.println("Icon resource /icon.svg not found.");
			}
		} catch (Throwable t) {
			System.err.println("Failed to load SVG icon: " + t.getMessage());
		}

		scriptManager = new ScriptManager(this::appendConsole, 5000);
	}

	private void initMenu() {
		JMenuBar mb = new JMenuBar();
		JMenu file = new JMenu("File");
		JMenuItem openDll = new JMenuItem("Open DLL...");
		openDll.addActionListener(e -> onOpenDll());
		JMenuItem openProject = new JMenuItem("Open Project...");
		openProject.addActionListener(e -> onOpenProject());
		JMenuItem saveProject = new JMenuItem("Save Project...");
		saveProject.addActionListener(e -> onSaveProject());
		JMenuItem exit = new JMenuItem("Exit");
		exit.addActionListener(e -> System.exit(0));
		file.add(openDll);
		file.addSeparator();
		file.add(openProject);
		file.add(saveProject);
		file.addSeparator();
		file.add(exit);
		mb.add(file);
		setJMenuBar(mb);
	}

	private void initUi() {
		JPanel leftPanel = new JPanel(new BorderLayout());
		functionTableModel = new FunctionTableModel(Collections.emptyList());
		functionTable = new JTable(functionTableModel);
		functionTable.setFillsViewportHeight(true);
		functionTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					int r = functionTable.getSelectedRow();
					if (r >= 0) {
						FunctionInfo fi = functionTableModel.getFunctionAt(r);
						String snippet = makeSnippet(fi);
						editorPanel.insertSnippet(snippet);
					}
				}
			}
		});
		leftPanel.add(new JScrollPane(functionTable), BorderLayout.CENTER);
		leftPanel.setPreferredSize(new Dimension(380, 600));

		editorPanel = new EditorPanel();
		editorPanel.setSyntax(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT); // default placeholder
		JPanel centerPanel = new JPanel(new BorderLayout());
		centerPanel.add(editorPanel, BorderLayout.CENTER);

		// top controls: script language and Run button
		JPanel topControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
		languageCombo = new JComboBox<>(new String[] { "BeanShell", "Python", "JavaScript" });
		languageCombo.setSelectedItem("BeanShell");
		languageCombo.addActionListener(e -> {
			String lang = (String) languageCombo.getSelectedItem();
			if ("BeanShell".equals(lang)) {
				editorPanel.setLanguage("BeanShell");
			} else if ("Python".equals(lang)) {
				editorPanel.setLanguage("Python");
			} else {
				editorPanel.setLanguage("JavaScript");
			}
		});
		JButton runBtn = new JButton("Run Script");
		runBtn.addActionListener(e -> onRunScript());
		topControls.add(new JLabel("Script language:"));
		topControls.add(languageCombo);
		topControls.add(runBtn);

		centerPanel.add(topControls, BorderLayout.NORTH);

		consoleArea = new JTextArea();
		consoleArea.setEditable(false);
		JScrollPane consoleScroll = new JScrollPane(consoleArea);
		consoleScroll.setPreferredSize(new Dimension(400, 150));

		JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerPanel, consoleScroll);
		rightSplit.setResizeWeight(0.7);

		JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplit);
		mainSplit.setResizeWeight(0.3);

		getContentPane().add(mainSplit, BorderLayout.CENTER);
	}

	private void onOpenDll() {
		fileChooser.setFileFilter(new FileNameExtensionFilter("Windows DLL", "dll"));
		int r = fileChooser.showOpenDialog(this);
		if (r != JFileChooser.APPROVE_OPTION)
			return;
		File dll = fileChooser.getSelectedFile();
		if (dll == null || !dll.exists())
			return;
		try {
			DllParser parser = new DllParser();
			List<FunctionInfo> functions = parser.parseExports(dll);
			functionTableModel.setFunctions(functions);
			currentDll = dll;
			appendConsole("Loaded DLL: " + dll.getAbsolutePath());

			if (!Main.IS_WINDOWS) {
				// On non-Windows platforms do not create JNA proxies; inform the user.
				appendConsole("Platform is not Windows — skipping JNA proxy creation. Native calls will not be available.");
				nativeProxy = null;
				scriptManager.setNativeProxy(null);
			} else {
				// Create proxy for scripting (Windows only)
				try {
					JnaProxyFactory factory = new JnaProxyFactory(dll.getAbsolutePath());
					nativeProxy = factory.createNativeProxy();
					scriptManager.setNativeProxy(nativeProxy);
				} catch (Throwable t) {
					appendConsole("Failed to create JNA proxy: " + t.getMessage());
					nativeProxy = null;
					scriptManager.setNativeProxy(null);
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to load DLL: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void onOpenProject() {
		fileChooser.setFileFilter(new FileNameExtensionFilter("JDAN Project JSON", "json"));
		int r = fileChooser.showOpenDialog(this);
		if (r != JFileChooser.APPROVE_OPTION)
			return;
		File f = fileChooser.getSelectedFile();
		try (Reader rd = new FileReader(f)) {
			JSONParser p = new JSONParser();
			JSONObject obj = (JSONObject) p.parse(rd);
			String dllPath = (String) obj.get("dllPath");
			String lang = (String) obj.get("scriptLanguage");
			String scriptFile = (String) obj.get("scriptFile");
			if (dllPath != null) {
				File dll = new File(dllPath);
				if (dll.exists()) {
					fileChooser.setCurrentDirectory(dll.getParentFile());
					// reuse open
					DllParser parser = new DllParser();
					List<FunctionInfo> functions = parser.parseExports(dll);
					functionTableModel.setFunctions(functions);
					currentDll = dll;
					JnaProxyFactory factory = new JnaProxyFactory(dll.getAbsolutePath());
					nativeProxy = factory.createNativeProxy();
					scriptManager.setNativeProxy(nativeProxy);
				} else {
					appendConsole("DLL from project not found: " + dllPath);
				}
			}
			if (lang != null) {
				languageCombo.setSelectedItem(lang);
			}
			if (scriptFile != null) {
				File sf = new File(scriptFile);
				if (sf.exists()) {
					String txt = Files.readString(sf.toPath());
					editorPanel.setText(txt);
				} else {
					appendConsole("Script file from project not found: " + scriptFile);
				}
			}
			appendConsole("Project loaded: " + f.getAbsolutePath());
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to load project: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void onSaveProject() {
		fileChooser.setFileFilter(new FileNameExtensionFilter("JDAN Project JSON", "json"));
		int r = fileChooser.showSaveDialog(this);
		if (r != JFileChooser.APPROVE_OPTION)
			return;
		File f = fileChooser.getSelectedFile();
		JSONObject obj = new JSONObject();
		obj.put("dllPath", currentDll != null ? currentDll.getAbsolutePath() : "");
		obj.put("scriptLanguage", languageCombo.getSelectedItem());
		// Save current script to scripts/main.bsh by default if not specified
		try {
			File scriptsDir = new File("scripts");
			if (!scriptsDir.exists())
				scriptsDir.mkdirs();
			File sf = new File(scriptsDir, "main.bsh");
			Files.writeString(sf.toPath(), editorPanel.getText());
			obj.put("scriptFile", sf.getPath());
			try (Writer w = new FileWriter(f)) {
				w.write(obj.toJSONString());
			}
			appendConsole("Project saved: " + f.getAbsolutePath());
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to save project: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private String makeSnippet(FunctionInfo fi) {
		StringBuilder sb = new StringBuilder();
		boolean hasReturn = fi.returnType != null && !fi.returnType.isEmpty() && !"void".equalsIgnoreCase(fi.returnType);
		if (hasReturn) {
			sb.append("[result=]");
		}
		sb.append(fi.name);
		sb.append("(");
		for (int i = 0; i < fi.paramTypes.size(); i++) {
			if (i > 0)
				sb.append(", ");
			sb.append("arg").append(i);
			String t = fi.paramTypes.get(i);
			if (t != null && !t.isEmpty() && !"unknown".equalsIgnoreCase(t)) {
				sb.append(" /*").append(t).append("*/");
			}
		}
		sb.append(");");
		// trailing comment
		if (fi.returnType != null && !fi.returnType.isEmpty() && !fi.returnType.equalsIgnoreCase("unknown")) {
			sb.append(" // ").append(fi.returnType).append(" ").append(fi.name).append("(");
			for (int i = 0; i < fi.paramTypes.size(); i++) {
				if (i > 0)
					sb.append(", ");
				String t = fi.paramTypes.get(i);
				sb.append(t != null ? t : "unknown").append(" arg").append(i);
			}
			sb.append(")");
		} else {
			sb.append(" // signature unknown: ").append(fi.name);
		}
		return sb.toString();
	}

	private void onRunScript() {
		String scriptText = editorPanel.getText();
		String language = (String) languageCombo.getSelectedItem();
		appendConsole("Running script (" + language + ")...");
		// execute and wait with callback
		scriptManager.executeScript(scriptText, language, result -> {
			if (result.timedOut) {
				appendConsole("*** Script timed out after " + scriptManager.getTimeoutMs() + " ms");
			} else {
				if (result.threw != null) {
					appendConsole("*** Script threw exception: " + result.threw);
				} else {
					appendConsole("*** Script finished. Result: " + result.result);
				}
				if (result.output != null)
					appendConsole(result.output);
			}
		});
	}

	public void appendConsole(String s) {
		SwingUtilities.invokeLater(() -> {
			consoleArea.append(s + "\n");
			consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
		});
	}

	// Table model for functions
	static class FunctionTableModel extends AbstractTableModel {
		private final String[] cols = { "Name", "Return", "Params" };
		private List<FunctionInfo> functions;

		FunctionTableModel(List<FunctionInfo> functions) {
			this.functions = new ArrayList<>(functions);
		}

		public void setFunctions(List<FunctionInfo> f) {
			this.functions = new ArrayList<>(f);
			fireTableDataChanged();
		}

		public FunctionInfo getFunctionAt(int row) {
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
			return switch (columnIndex) {
			case 0 -> fi.name;
			case 1 -> fi.returnType != null ? fi.returnType : "unknown";
			case 2 -> fi.paramTypes != null ? String.join(", ", fi.paramTypes) : "";
			default -> "";
			};
		}
	}
}