package com.anishka.log_analyzer.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.anishka.log_analyzer.LogAnalyzerApplication;

/**
 * Starts Log Analyzer as a desktop application ({@code LogAnalyzer.exe} runs the jar with {@code --desktop}):
 * one instance per user, data in %LOCALAPPDATA%\LogAnalyzer, the web server on 127.0.0.1 only, a splash screen
 * while it starts, the dashboard in its own Microsoft Edge "app" window and a tray icon to reopen or quit.
 *
 * <p>Options: {@code --no-window} (start without UI, e.g. for automated checks), {@code --port=N},
 * {@code --home=PATH}.
 */
public final class DesktopLauncher {

	private static final int PREFERRED_PORT = 17171;

	private DesktopLauncher() {
	}

	public static void launch(String[] args) {
		boolean noWindow = false;
		Integer port = null;
		Path home = null;
		List<String> rest = new ArrayList<>();
		for (String a : args) {
			if (a.equals("--desktop")) continue;
			if (a.equals("--no-window")) noWindow = true;
			else if (a.startsWith("--port=")) port = Integer.valueOf(a.substring(7));
			else if (a.startsWith("--home=")) home = Path.of(a.substring(7));
			else rest.add(a);
		}
		if (home == null) home = defaultHome();
		boolean ui = !noWindow;
		JWindow splash = null;
		try {
			Files.createDirectories(home.resolve("data"));
			Files.createDirectories(home.resolve("logs"));

			// one instance per user: if it is already running, just open its window
			FileChannel lockChannel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock lock;
			try {
				lock = lockChannel.tryLock();
			} catch (OverlappingFileLockException e) {
				lock = null;
			}
			if (lock == null) {
				Properties running = readInstance(home);
				if (running != null && ui) openWindow(running.getProperty("url"));
				System.exit(0);
				return;
			}

			if (ui) {
				System.setProperty("java.awt.headless", "false");
				splash = splash();
			}
			int p = port != null ? port : freePort();
			String token = randomToken();
			System.setProperty("app.desktop.home", home.toString());
			System.setProperty("app.desktop.token", token);
			System.setProperty("DATA_DIR", home.resolve("data").toString());
			System.setProperty("server.port", String.valueOf(p));

			SpringApplication app = new SpringApplication(LogAnalyzerApplication.class);
			app.setAdditionalProfiles("desktop");
			app.setHeadless(!ui);
			ConfigurableApplicationContext ctx = app.run(rest.toArray(String[]::new));

			String url = "http://127.0.0.1:" + p + "/desktop/enter?t=" + token;
			writeInstance(home, p, url);
			final Path h = home;
			final FileLock held = lock;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					Files.deleteIfExists(h.resolve("instance.properties"));
					held.release();
				} catch (IOException e) {
					// exiting anyway
				}
			}));
			if (ui && !GraphicsEnvironment.isHeadless()) {
				tray(ctx, url, home);
				openWindow(url);
			}
			System.out.println("Log Analyzer is running at " + url);
		} catch (Throwable e) {
			fail(e, home, ui);
		} finally {
			if (splash != null) splash.dispose();
		}
	}

	// ------------------------------------------------------------------ window

	/** Edge (installed on every corporate Windows PC) in app mode: its own window, no tabs or address bar. */
	static void openWindow(String url) {
		try {
			if (isWindows()) {
				for (String exe : browserCandidates()) {
					if (new File(exe).isFile()) {
						new ProcessBuilder(exe, "--app=" + url, "--window-size=1440,900").start();
						return;
					}
				}
			}
			if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
				java.awt.Desktop.getDesktop().browse(URI.create(url));
				return;
			}
			if (!isWindows()) new ProcessBuilder("xdg-open", url).start();
		} catch (IOException | RuntimeException e) {
			System.err.println("Open this address in a browser: " + url);
		}
	}

	private static List<String> browserCandidates() {
		List<String> c = new ArrayList<>();
		for (String env : new String[] { "ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA" }) {
			String base = System.getenv(env);
			if (base == null) continue;
			c.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
		}
		for (String env : new String[] { "ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA" }) {
			String base = System.getenv(env);
			if (base != null) c.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
		}
		return c;
	}

	private static void tray(ConfigurableApplicationContext ctx, String url, Path home) {
		if (!SystemTray.isSupported()) return;
		try {
			Image img = icon();
			PopupMenu menu = new PopupMenu();
			MenuItem open = new MenuItem("Open Log Analyzer");
			open.addActionListener(e -> openWindow(url));
			MenuItem sources = new MenuItem("Log sources");
			sources.addActionListener(e -> openWindow(url + "&next=/sources"));
			MenuItem folder = new MenuItem("Open data folder");
			folder.addActionListener(e -> {
				try {
					java.awt.Desktop.getDesktop().open(home.toFile());
				} catch (IOException | RuntimeException ex) {
					// ignore
				}
			});
			MenuItem quit = new MenuItem("Quit");
			quit.addActionListener(e -> System.exit(SpringApplication.exit(ctx, () -> 0)));
			menu.add(open);
			menu.add(sources);
			menu.add(folder);
			menu.addSeparator();
			menu.add(quit);
			TrayIcon icon = new TrayIcon(img, "Log Analyzer", menu);
			icon.setImageAutoSize(true);
			icon.addActionListener(e -> openWindow(url)); // double-click
			SystemTray.getSystemTray().add(icon);
		} catch (Exception e) {
			System.err.println("No tray icon: " + e);
		}
	}

	private static JWindow splash() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			JWindow w = new JWindow();
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBackground(new Color(0x11, 0x13, 0x18));
			p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2a, 0x2e, 0x37)),
					BorderFactory.createEmptyBorder(26, 30, 24, 30)));
			JLabel title = new JLabel("Log Analyzer");
			title.setForeground(new Color(0xee, 0xf0, 0xf7));
			title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
			Image img = icon();
			if (img != null) title.setIcon(new javax.swing.ImageIcon(img.getScaledInstance(32, 32, Image.SCALE_SMOOTH)));
			title.setIconTextGap(12);
			JLabel sub = new JLabel("Starting…", SwingConstants.LEFT);
			sub.setForeground(new Color(0x9a, 0xa3, 0xb8));
			JProgressBar bar = new JProgressBar();
			bar.setIndeterminate(true);
			p.add(title, BorderLayout.NORTH);
			p.add(sub, BorderLayout.CENTER);
			p.add(bar, BorderLayout.SOUTH);
			w.setContentPane(p);
			w.setSize(380, 150);
			w.setLocationRelativeTo(null);
			w.setAlwaysOnTop(true);
			w.setVisible(true);
			return w;
		} catch (Throwable e) {
			return null; // no display: carry on without a splash
		}
	}

	private static Image icon() {
		try (InputStream in = DesktopLauncher.class.getResourceAsStream("/desktop/app-icon.png")) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException e) {
			return null;
		}
	}

	private static void fail(Throwable e, Path home, boolean ui) {
		Throwable root = e;
		while (root.getCause() != null) root = root.getCause();
		String msg = "Log Analyzer could not start.\n\n" + root.getMessage() + "\n\nDetails: " + home.resolve("logs").resolve("log-analyzer.log");
		e.printStackTrace();
		if (ui && !GraphicsEnvironment.isHeadless()) {
			try {
				JOptionPane.showMessageDialog(null, msg, "Log Analyzer", JOptionPane.ERROR_MESSAGE);
			} catch (Throwable ignored) {
				// no display
			}
		}
		System.exit(1);
	}

	// ------------------------------------------------------------------ helpers

	static Path defaultHome() {
		String local = System.getenv("LOCALAPPDATA");
		if (isWindows() && local != null) return Path.of(local, "LogAnalyzer");
		return Path.of(System.getProperty("user.home"), ".log-analyzer");
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** 17171 if free (stable address for bookmarks), else the next free one. */
	private static int freePort() throws IOException {
		for (int p = PREFERRED_PORT; p < PREFERRED_PORT + 20; p++) {
			try (ServerSocket s = new ServerSocket(p, 0, InetAddress.getLoopbackAddress())) {
				return p;
			} catch (IOException e) {
				// taken, try the next one
			}
		}
		try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return s.getLocalPort();
		}
	}

	private static String randomToken() {
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static void writeInstance(Path home, int port, String url) throws IOException {
		Properties p = new Properties();
		p.setProperty("port", String.valueOf(port));
		p.setProperty("url", url);
		p.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
		Path f = home.resolve("instance.properties");
		try (var out = Files.newOutputStream(f)) {
			p.store(out, "Log Analyzer: running instance (contains this run's access link)");
		}
		try {
			Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException e) {
			// Windows: inside the user's own profile
		}
	}

	private static Properties readInstance(Path home) {
		Path f = home.resolve("instance.properties");
		if (!Files.exists(f)) return null;
		Properties p = new Properties();
		try (var in = Files.newBufferedReader(f, StandardCharsets.ISO_8859_1)) {
			p.load(in);
			return p.getProperty("url") == null ? null : p;
		} catch (IOException e) {
			return null;
		}
	}
}
