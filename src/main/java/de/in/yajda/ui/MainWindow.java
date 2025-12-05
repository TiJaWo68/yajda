package de.in.yajda.ui;

import java.awt.BorderLayout;
import java.awt.Image;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import de.in.yajda.Main;
import de.in.yajda.dll.DllParser;
import de.in.yajda.dll.DllParser.FunctionInfo;
import de.in.yajda.dll.HeaderParser;
import de.in.yajda.dll.HeaderParser.HeaderInfo;
import de.in.yajda.dll.JnaProxyFactory;
import de.in.yajda.script.ScriptManager;

/**
 * Slimmed MainWindow that composes smaller components: - FunctionListPanel - EditorPanel (existing) - TopControlPanel - ConsolePanel
 *
 * The behavior is unchanged; functionality is moved into the smaller components.
 */
public class MainWindow extends JFrame {
	private final FunctionListPanel functionListPanel;
	private final EditorPanel editorPanel;
	private final TopControlPanel topControlPanel;
	private final ConsolePanel consolePanel;

	private ScriptManager scriptManager;
	private JFileChooser fileChooser = new JFileChooser(".");
	private File currentDll;
	private File currentHeader;
	private JnaProxyFactory.ProxyWrapper nativeProxy;

	public MainWindow() {
		super("yajda - Java DLL Analyzer");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(1000, 700);
		setLocationRelativeTo(null);

		// panels
		functionListPanel = new FunctionListPanel();
		editorPanel = new EditorPanel();
		topControlPanel = new TopControlPanel();
		consolePanel = new ConsolePanel();

		initMenu();
		layoutUi();
		initBehaviors();

		// Load icon
		try {
			java.net.URL iconUrl = getClass().getResource("/icon.svg");
			if (iconUrl != null) {
				FlatSVGIcon svgIcon = new FlatSVGIcon(iconUrl);
				Image img = svgIcon.derive(64, 64).getImage();
				if (img != null)
					setIconImage(img);
			}
		} catch (Throwable t) {
			// ignore
		}

		scriptManager = new ScriptManager(consolePanel::append, 5000);
	}

	private void layoutUi() {
		// left = function list
		JPanel left = new JPanel(new BorderLayout());
		left.add(functionListPanel, BorderLayout.CENTER);

		// center = editor with top controls and console below
		JPanel center = new JPanel(new BorderLayout());
		center.add(topControlPanel, BorderLayout.NORTH);
		center.add(editorPanel, BorderLayout.CENTER);

		JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, consolePanel);
		rightSplit.setResizeWeight(0.7);

		JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
		mainSplit.setResizeWeight(0.3);

		getContentPane().add(mainSplit, BorderLayout.CENTER);
	}

	private void initMenu() {
		JMenuBar mb = new JMenuBar();
		JMenu file = new JMenu("File");
		JMenuItem openDll = new JMenuItem("Open DLL...");
		openDll.addActionListener(e -> onOpenDll());
		JMenuItem openHeader = new JMenuItem("Load Header...");
		openHeader.addActionListener(e -> onOpenHeader());
		JMenuItem openProject = new JMenuItem("Open Project...");
		openProject.addActionListener(e -> onOpenProject());
		JMenuItem saveProject = new JMenuItem("Save Project...");
		saveProject.addActionListener(e -> onSaveProject());
		JMenuItem exit = new JMenuItem("Exit");
		exit.addActionListener(e -> System.exit(0));
		file.add(openDll);
		file.add(openHeader);
		file.addSeparator();
		file.add(openProject);
		file.add(saveProject);
		file.addSeparator();
		file.add(exit);
		mb.add(file);
		setJMenuBar(mb);
	}

	private void initBehaviors() {
		// double click on function -> insert snippet into editor
		functionListPanel.addFunctionDoubleClickListener(fi -> {
			String snippet = makeSnippet(fi);
			editorPanel.insertSnippet(snippet);
		});

		// language change -> update editor highlighting
		topControlPanel.addLanguageChangeListener(e -> {
			String lang = topControlPanel.getSelectedLanguage();
			editorPanel.setLanguage(lang);
		});

		// run button -> execute script
		topControlPanel.addRunListener(e -> onRunScript());
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
			functionListPanel.setFunctions(functions);
			currentDll = dll;
			consolePanel.append("Loaded DLL: " + dll.getAbsolutePath());

			// update completions
			updateEditorCompletionsFromFunctions(functions);

			if (!Main.IS_WINDOWS) {
				consolePanel.append("Platform is not Windows — skipping JNA proxy creation.");
				nativeProxy = null;
				scriptManager.setNativeProxy(null);
			} else {
				try {
					JnaProxyFactory factory = new JnaProxyFactory(dll.getAbsolutePath());
					nativeProxy = factory.createNativeProxy();
					scriptManager.setNativeProxy(nativeProxy);
				} catch (Throwable t) {
					consolePanel.append("Failed to create JNA proxy: " + t.getMessage());
					nativeProxy = null;
					scriptManager.setNativeProxy(null);
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to load DLL: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void onOpenHeader() {
		fileChooser.setFileFilter(new FileNameExtensionFilter("C Header", "h", "hpp"));
		int r = fileChooser.showOpenDialog(this);
		if (r != JFileChooser.APPROVE_OPTION)
			return;
		File header = fileChooser.getSelectedFile();
		if (header == null || !header.exists())
			return;
		try {
			HeaderParser hp = new HeaderParser();
			Map<String, HeaderInfo> infos = hp.parseHeader(header);
			consolePanel.append("Loaded header: " + header.getAbsolutePath() + " (" + infos.size() + " prototypes)");

			currentHeader = header;

			// merge with existing exports
			List<FunctionInfo> existing = functionListPanel.getFunctions();
			List<FunctionInfo> merged = new ArrayList<>();
			if (existing.isEmpty()) {
				for (Map.Entry<String, HeaderInfo> e : infos.entrySet()) {
					HeaderInfo hi = e.getValue();
					merged.add(new FunctionInfo(e.getKey(), hi.returnType != null ? hi.returnType : "unknown",
							hi.paramTypes != null ? hi.paramTypes : new ArrayList<>()));
				}
			} else {
				for (FunctionInfo fi : existing) {
					HeaderInfo hi = infos.get(fi.name);
					if (hi != null) {
						merged.add(new FunctionInfo(fi.name, hi.returnType != null ? hi.returnType : fi.returnType,
								hi.paramTypes != null ? hi.paramTypes : fi.paramTypes));
					} else {
						merged.add(fi);
					}
				}
			}
			functionListPanel.setFunctions(merged);

			// update completions
			updateEditorCompletionsFromFunctions(merged);
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to load header: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void onOpenProject() {
		fileChooser.setFileFilter(new FileNameExtensionFilter("JDAN Project JSON", "json"));
		int r = fileChooser.showOpenDialog(this);
		if (r != JFileChooser.APPROVE_OPTION)
			return;
		File f = fileChooser.getSelectedFile();
		try (FileReader rd = new FileReader(f)) {
			JSONParser p = new JSONParser();
			JSONObject obj = (JSONObject) p.parse(rd);
			String dllPath = (String) obj.get("dllPath");
			String lang = (String) obj.get("scriptLanguage");
			String scriptContent = (String) obj.get("scriptContent");
			String headerPath = (String) obj.get("headerFile");
			if (dllPath != null && !dllPath.isEmpty()) {
				File dll = new File(dllPath);
				if (dll.exists()) {
					DllParser parser = new DllParser();
					List<FunctionInfo> functions = parser.parseExports(dll);
					functionListPanel.setFunctions(functions);
					currentDll = dll;
					// update completions
					updateEditorCompletionsFromFunctions(functions);
					try {
						JnaProxyFactory factory = new JnaProxyFactory(dll.getAbsolutePath());
						nativeProxy = factory.createNativeProxy();
						scriptManager.setNativeProxy(nativeProxy);
					} catch (Throwable t) {
						consolePanel.append("Failed to create JNA proxy: " + t.getMessage());
						nativeProxy = null;
						scriptManager.setNativeProxy(null);
					}
				} else {
					consolePanel.append("DLL from project not found: " + dllPath);
				}
			}
			if (headerPath != null && !headerPath.isEmpty()) {
				File hf = new File(headerPath);
				if (hf.exists()) {
					HeaderParser hp = new HeaderParser();
					Map<String, HeaderInfo> infos = hp.parseHeader(hf);
					// merge as in onOpenHeader
					List<FunctionInfo> existing = functionListPanel.getFunctions();
					List<FunctionInfo> merged = new ArrayList<>();
					if (existing.isEmpty()) {
						for (Map.Entry<String, HeaderInfo> e : infos.entrySet()) {
							HeaderInfo hi = e.getValue();
							merged.add(new FunctionInfo(e.getKey(), hi.returnType != null ? hi.returnType : "unknown",
									hi.paramTypes != null ? hi.paramTypes : new ArrayList<>()));
						}
					} else {
						for (FunctionInfo fi : existing) {
							HeaderInfo hi = infos.get(fi.name);
							if (hi != null) {
								merged.add(new FunctionInfo(fi.name, hi.returnType != null ? hi.returnType : fi.returnType,
										hi.paramTypes != null ? hi.paramTypes : fi.paramTypes));
							} else {
								merged.add(fi);
							}
						}
					}
					functionListPanel.setFunctions(merged);
					currentHeader = hf;
					updateEditorCompletionsFromFunctions(merged);
				} else {
					consolePanel.append("Header file from project not found: " + headerPath);
				}
			}
			if (lang != null)
				topControlPanel.setSelectedLanguage(lang);
			if (scriptContent != null && !scriptContent.isEmpty()) {
				editorPanel.setText(scriptContent);
			} else {
				String scriptFile = (String) obj.get("scriptFile");
				if (scriptFile != null && !scriptFile.isEmpty()) {
					File sf = new File(scriptFile);
					if (sf.exists()) {
						String txt = Files.readString(sf.toPath());
						editorPanel.setText(txt);
					} else {
						consolePanel.append("Script file from project not found: " + scriptFile);
					}
				}
			}
			consolePanel.append("Project loaded: " + f.getAbsolutePath());
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
		if (f != null && !f.getName().toLowerCase().endsWith(".json")) {
			f = new File(f.getParentFile(), f.getName() + ".json");
		}
		JSONObject obj = new JSONObject();
		obj.put("dllPath", currentDll != null ? currentDll.getAbsolutePath() : "");
		obj.put("scriptLanguage", topControlPanel.getSelectedLanguage());
		obj.put("headerFile", currentHeader != null ? currentHeader.getAbsolutePath() : "");
		// embed script content
		obj.put("scriptContent", editorPanel.getText());
		obj.put("scriptFile", ""); // kept empty as script is embedded
		try (FileWriter w = new FileWriter(f)) {
			w.write(obj.toJSONString());
			consolePanel.append("Project saved: " + f.getAbsolutePath());
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Failed to save project: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void updateEditorCompletionsFromFunctions(List<FunctionInfo> functions) {
		Set<String> names = new LinkedHashSet<>();
		// basic tokens
		names.add("print");
		names.add("println");
		names.add("int");
		names.add("long");
		names.add("float");
		names.add("double");
		names.add("native");
		names.add("nativeLib");
		if (functions != null) {
			for (FunctionInfo fi : functions)
				names.add(fi.name);
		}
		editorPanel.updateCompletions(names);
	}

	private String makeSnippet(FunctionInfo fi) {
		StringBuilder sb = new StringBuilder();
		boolean hasReturn = fi.returnType != null && !fi.returnType.isEmpty() && !"void".equalsIgnoreCase(fi.returnType);
		if (hasReturn)
			sb.append("[result=]");
		sb.append(fi.name).append("(");
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
		return sb.toString();
	}

	private void onRunScript() {
		String scriptText = editorPanel.getText();
		String language = topControlPanel.getSelectedLanguage();
		consolePanel.append("Running script (" + language + ")...");
		scriptManager.executeScript(scriptText, language, result -> {
			if (result.timedOut) {
				consolePanel.append("*** Script timed out after " + scriptManager.getTimeoutMs() + " ms");
			} else if (result.threw != null) {
				consolePanel.append("*** Script threw exception: " + result.threw);
				if (result.output != null)
					consolePanel.append(result.output);
			} else {
				consolePanel.append("*** Script finished. Result: " + result.result);
				if (result.output != null)
					consolePanel.append(result.output);
			}
		});
	}
}