package com.anishka.log_analyzer.desktop;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The app's window: Microsoft Edge (or Chrome) in app mode with a profile of its own, so the window belongs to
 * Log Analyzer: it can be closed when the app quits, its closing is noticed, and it never restores tabs or mixes
 * with the user's own browser windows.
 */
public final class DesktopWindow {

	private static Path home;
	private static String url;
	private static Process browser;
	private static Consumer<Boolean> onClosed = b -> { };
	private static volatile boolean quitting;

	private DesktopWindow() {
	}

	static synchronized void init(Path appHome, String appUrl, Consumer<Boolean> closedListener) {
		home = appHome;
		url = appUrl;
		onClosed = closedListener;
	}

	/** Opens the window (or another one, which Windows brings to the front, if it is already open). */
	public static synchronized void open() {
		open(null);
	}

	public static synchronized void open(String next) {
		if (url == null) return;
		String target = next == null ? url : url + "&next=" + next;
		try {
			String exe = browserExe();
			if (exe != null) {
				Path profile = home.resolve("window");
				markCleanExit(profile);
				List<String> cmd = new ArrayList<>(List.of(exe, "--app=" + target, "--user-data-dir=" + profile,
						"--no-first-run", "--no-default-browser-check", "--disable-session-crashed-bubble",
						"--window-size=1440,900"));
				Process p = new ProcessBuilder(cmd).start();
				if (browser == null || !browser.isAlive()) {
					browser = p; // the first process owns this profile and lives as long as its windows
					p.onExit().thenRun(() -> {
						if (!quitting) onClosed.accept(true);
					});
				}
				return;
			}
			if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
				java.awt.Desktop.getDesktop().browse(URI.create(target));
			} else if (!isWindows()) {
				new ProcessBuilder("xdg-open", target).start();
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("Open this address in a browser: " + target);
		}
	}

	/** Closes the app's window (used when quitting). */
	public static synchronized void close() {
		quitting = true;
		if (browser != null && browser.isAlive()) {
			browser.descendants().forEach(ProcessHandle::destroy);
			browser.destroy();
			try {
				if (!browser.waitFor(3, TimeUnit.SECONDS)) browser.destroyForcibly();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	static boolean isWindowOpen() {
		return browser != null && browser.isAlive();
	}

	private static String browserExe() {
		if (!isWindows()) return null;
		List<String> c = new ArrayList<>();
		for (String env : new String[] { "ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA" }) {
			String base = System.getenv(env);
			if (base != null) c.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
		}
		for (String env : new String[] { "ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA" }) {
			String base = System.getenv(env);
			if (base != null) c.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
		}
		return c.stream().filter(p -> new File(p).isFile()).findFirst().orElse(null);
	}

	/** Tells the browser its last session ended normally, so it never offers to "restore pages". */
	private static void markCleanExit(Path profile) {
		Path prefs = profile.resolve("Default").resolve("Preferences");
		try {
			if (!Files.exists(prefs)) return;
			String json = Files.readString(prefs, StandardCharsets.UTF_8);
			String fixed = json.replace("\"exit_type\":\"Crashed\"", "\"exit_type\":\"Normal\"")
					.replace("\"exited_cleanly\":false", "\"exited_cleanly\":true");
			if (!fixed.equals(json)) Files.writeString(prefs, fixed, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// not critical
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
