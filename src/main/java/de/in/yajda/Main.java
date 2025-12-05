package de.in.yajda;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.formdev.flatlaf.FlatDarkLaf;

import de.in.utils.Log4jTools;
import de.in.utils.Version;
import de.in.yajda.ui.MainWindow;

public class Main {
	// Expose platform flags for other components
	public static final boolean IS_WINDOWS;
	public static final boolean IS_64BIT;

	private static final Logger LOGGER = LogManager.getLogger(Main.class);
	private static final String GROUPID = "de.in.yajda";
	private static final String ARTIFACTID = "java-dll-analyzer";

	static {
		String os = System.getProperty("os.name", "").toLowerCase();
		String dataModel = System.getProperty("sun.arch.data.model", "");
		IS_WINDOWS = os.contains("win");
		IS_64BIT = dataModel.contains("64");
	}

	public static void main(String[] args) {

		Log4jTools.redirectStdOutErrLog();
		Log4jTools.logEnvironment(LOGGER);
		LOGGER.info("yaJDA " + Version.retrieveVersionFromPom(GROUPID, ARTIFACTID) + " started");
		try {
			UIManager.setLookAndFeel(new FlatDarkLaf());
		} catch (UnsupportedLookAndFeelException e) {
			System.err.println("Failed to set FlatDarkLaf: " + e.getMessage());
		}

		SwingUtilities.invokeLater(() -> {
			// Basic platform checks
			if (!IS_64BIT) {
				JOptionPane.showMessageDialog(null, "This application requires a 64-bit JVM (Java 21 x64). Detected non-64-bit JVM.", "Unsupported JVM",
						JOptionPane.ERROR_MESSAGE);
				System.exit(1);
			}

			if (!IS_WINDOWS) {
				// Allow running on Linux/macOS for UI-only usage; just warn that DLL proxying will be disabled.
				JOptionPane.showMessageDialog(null,
						"Non-Windows OS detected. DLL proxy generation via JNA will be disabled. You can still edit scripts and view project files.",
						"Non-Windows mode", JOptionPane.WARNING_MESSAGE);
			}

			try {
				MainWindow window = new MainWindow();
				window.setVisible(true);
			} catch (Exception e) {
				e.printStackTrace();
				JOptionPane.showMessageDialog(null, "Failed to start application: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
				System.exit(2);
			}
		});
	}
}