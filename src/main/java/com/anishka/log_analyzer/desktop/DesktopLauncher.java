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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.anishka.log_analyzer.LogAnalyzerApplication;

/**
 * Starts Log Analyzer as a desktop application (LogAnalyzer.exe sets {@code -Dapp.launch=desktop}):
 * one instance per user, data in %LOCALAPPDATA%\LogAnalyzer, the server on 127.0.0.1 only, a splash screen
 * while it starts, the dashboard in its own window and a tray icon. Closing the window keeps the app reading
 * logs in the background; Quit (in the app or the tray) stops it.
 *
 * <p>Options: {@code --no-window} (no UI, for automated checks), {@code --port=N}, {@code --home=PATH}.
 */
public final class DesktopLauncher {

	private static final int PREFERRED_PORT = 17171;
	private static TrayIcon trayIcon;
	private static boolean toldAboutTray;

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
		if (ui) System.setProperty("java.awt.headless", "false");
		JWindow splash = null;
		try {
			Files.createDirectories(home.resolve("data"));
			Files.createDirectories(home.resolve("logs"));

			FileLock lock = acquireLock(home, ui);
			if (lock == null) {
				System.exit(0); // another instance is running and has been asked to show its window
				return;
			}

			if (ui) splash = splash();
			int p = port != null ? port : freePort();
			String token = randomToken();
			System.setProperty("app.desktop.home", home.toString());
			System.setProperty("app.desktop.token", token);
			System.setProperty("DATA_DIR", home.resolve("data").toString());
			System.setProperty("server.port", String.valueOf(p));

			// Spring DevTools (on the classpath when run from an IDE) would restart the app on a new thread, without the
			// window, tray and single-instance lock set up here: the desktop app is never restarted that way
			System.setProperty("spring.devtools.restart.enabled", "false");
			SpringApplication app = new SpringApplication(LogAnalyzerApplication.class);
			app.setAdditionalProfiles("desktop");
			app.setHeadless(!ui);
			ConfigurableApplicationContext ctx = app.run(rest.toArray(String[]::new));

			String url = "http://127.0.0.1:" + p + "/desktop/enter?t=" + token;
			writeInstance(home, p, token, url);
			final Path h = home;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				DesktopWindow.close();
				try {
					Files.deleteIfExists(h.resolve("instance.properties"));
					lock.release();
				} catch (IOException e) {
					// exiting anyway
				}
			}));
			DesktopWindow.init(home, url, closed -> tellAboutTray());
			if (ui && !GraphicsEnvironment.isHeadless()) {
				tray(ctx, home);
				DesktopWindow.open();
			}
			System.out.println("Log Analyzer is running at " + url);
		} catch (Throwable e) {
			if (e.getClass().getName().endsWith("SilentExitException")) {
				throw (RuntimeException) e; // DevTools' own restart signal, not an error
			}
			fail(e, home, ui);
		} finally {
			if (splash != null) splash.dispose();
		}
	}

	/**
	 * One instance per user. If another one holds the lock and answers, it is asked to show its window and
	 * null is returned. If it holds the lock but does not answer (it is still shutting down), wait for it.
	 */
	private static FileLock acquireLock(Path home, boolean ui) throws IOException, InterruptedException {
		FileChannel ch = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		long deadline = System.currentTimeMillis() + 30_000;
		while (true) {
			FileLock lock;
			try {
				lock = ch.tryLock();
			} catch (OverlappingFileLockException e) {
				lock = null;
			}
			if (lock != null) return lock;
			Properties running = readInstance(home);
			if (running != null && ping(running)) {
				if (ui) show(running);
				return null;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new IOException("Another copy of Log Analyzer is still closing. Wait a moment and start it again.");
			}
			Thread.sleep(500);
		}
	}

	private static boolean ping(Properties running) {
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			HttpResponse<Void> r = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.getProperty("port") + "/actuator/health"))
					.timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
			return r.statusCode() == 200;
		} catch (IOException | InterruptedException | RuntimeException e) {
			return false;
		}
	}

	/** Asks the running instance to open (or bring up) its window. */
	private static void show(Properties running) {
		try {
			HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.getProperty("port")
					+ "/desktop/show?t=" + running.getProperty("token"))).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
		} catch (IOException | InterruptedException | RuntimeException e) {
			// nothing more we can do; the running instance is still reachable from its tray icon
		}
	}

	// ------------------------------------------------------------------ tray & splash

	private static void tray(ConfigurableApplicationContext ctx, Path home) {
		if (!SystemTray.isSupported()) return;
		try {
			PopupMenu menu = new PopupMenu();
			MenuItem open = new MenuItem("Open Log Analyzer");
			open.addActionListener(e -> DesktopWindow.open());
			MenuItem sources = new MenuItem("Log sources");
			sources.addActionListener(e -> DesktopWindow.open("%2F%3Fview%3Dsources"));
			MenuItem folder = new MenuItem("Open data folder");
			folder.addActionListener(e -> {
				try {
					java.awt.Desktop.getDesktop().open(home.toFile());
				} catch (IOException | RuntimeException ex) {
					// ignore
				}
			});
			MenuItem quit = new MenuItem("Quit Log Analyzer");
			quit.addActionListener(e -> {
				DesktopWindow.close();
				System.exit(SpringApplication.exit(ctx, () -> 0));
			});
			menu.add(open);
			menu.add(sources);
			menu.add(folder);
			menu.addSeparator();
			menu.add(quit);
			trayIcon = new TrayIcon(icon(), "Log Analyzer · reading your logs", menu);
			trayIcon.setImageAutoSize(true);
			trayIcon.addActionListener(e -> DesktopWindow.open()); // double-click
			SystemTray.getSystemTray().add(trayIcon);
		} catch (Exception e) {
			System.err.println("No tray icon: " + e);
		}
	}

	/** First time the window is closed in a run: explain that the app keeps working in the tray. */
	private static void tellAboutTray() {
		if (trayIcon == null || toldAboutTray) return;
		toldAboutTray = true;
		trayIcon.displayMessage("Log Analyzer is still running",
				"It keeps reading your logs in the background. Double-click the tray icon to open it, or right-click to quit.",
				TrayIcon.MessageType.INFO);
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
		e.printStackTrace();
		String why = root.getMessage() == null ? root.toString() : root.getMessage();
		if (ui && !GraphicsEnvironment.isHeadless()) {
			try {
				DesktopDialog.error("Log Analyzer couldn't start", why + "\n\nThe log file in the log folder has the details.", home.resolve("logs"));
			} catch (Throwable ignored) {
				// no display
			}
		}
		System.exit(1);
	}

	// ------------------------------------------------------------------ helpers

	static Path defaultHome() {
		String local = System.getenv("LOCALAPPDATA");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && local != null) return Path.of(local, "LogAnalyzer");
		return Path.of(System.getProperty("user.home"), ".log-analyzer");
	}

	/** 17171 if free (stable address), else the next free one. */
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

	private static void writeInstance(Path home, int port, String token, String url) throws IOException {
		Properties p = new Properties();
		p.setProperty("port", String.valueOf(port));
		p.setProperty("token", token);
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
			return p.getProperty("port") == null ? null : p;
		} catch (IOException e) {
			return null;
		}
	}
}
