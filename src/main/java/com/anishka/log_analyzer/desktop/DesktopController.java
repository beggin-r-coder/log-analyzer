package com.anishka.log_analyzer.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Things only a desktop app can do: pick a folder with the Windows dialog, open folders, quit. */
@RestController
@RequestMapping("/api/desktop")
@ConditionalOnProperty(name = "app.desktop.enabled", havingValue = "true")
public class DesktopController {

	private final ApplicationContext context;
	private final Path home;
	private final String version;

	public DesktopController(ApplicationContext context, @Value("${app.desktop.home:.}") String home,
			@Value("${app.version:1.0.0}") String version) {
		this.context = context;
		this.home = Path.of(home).toAbsolutePath();
		this.version = version;
	}

	@GetMapping("/info")
	public Map<String, Object> info() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("version", version);
		m.put("home", home.toString());
		m.put("dataDir", home.resolve("data").toString());
		m.put("logFile", home.resolve("logs").resolve("log-analyzer.log").toString());
		m.put("java", System.getProperty("java.version"));
		m.put("os", System.getProperty("os.name"));
		m.put("folderPicker", !GraphicsEnvironment.isHeadless());
		return m;
	}

	/** Opens the native folder chooser on this computer and returns the chosen path. */
	@PostMapping("/browse")
	public ResponseEntity<Map<String, Object>> browse(@RequestParam(required = false) String start) throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			return ResponseEntity.badRequest().body(Map.of("error", "No folder picker available; type the path instead"));
		}
		AtomicReference<String> chosen = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			JFrame owner = new JFrame();
			owner.setAlwaysOnTop(true);
			owner.setUndecorated(true);
			owner.setLocationRelativeTo(null);
			owner.setVisible(true);
			JFileChooser fc = new JFileChooser(start != null && new File(start).isDirectory() ? new File(start) : null);
			fc.setDialogTitle("Choose the folder that contains the log files");
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			fc.setAcceptAllFileFilterUsed(false);
			if (fc.showDialog(owner, "Use this folder") == JFileChooser.APPROVE_OPTION) {
				chosen.set(fc.getSelectedFile().getAbsolutePath());
			}
			owner.dispose();
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("path", chosen.get());
		out.put("cancelled", chosen.get() == null);
		return ResponseEntity.ok(out);
	}

	/** Opens the app's data or log folder in Explorer. */
	@PostMapping("/open-folder")
	public ResponseEntity<Map<String, Object>> openFolder(@RequestParam(defaultValue = "home") String which) throws Exception {
		Path p = switch (which) {
		case "logs" -> home.resolve("logs");
		case "data" -> home.resolve("data");
		default -> home;
		};
		Files.createDirectories(p);
		if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Not available", "path", p.toString()));
		}
		Desktop.getDesktop().open(p.toFile());
		return ResponseEntity.ok(Map.of("path", p.toString()));
	}

	@PostMapping("/quit")
	public Map<String, Object> quit() {
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(400); // let the response reach the window first
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			System.exit(SpringApplication.exit(context, () -> 0));
		}, "desktop-quit");
		t.setDaemon(false);
		t.start();
		return Map.of("quitting", true);
	}
}
