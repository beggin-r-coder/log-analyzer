package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.anishka.log_analyzer.parser.ParsedLog;
import com.anishka.log_analyzer.parser.UniversalLogParser;
import com.anishka.log_analyzer.service.LogIngestionService;

/**
 * Follows log files in a folder (local or a network share such as \\siebel01\siebsrvr\...\log), like
 * {@code tail -f} for many files at once. Only new bytes are read on each pass; offsets are stored so a restart
 * continues where it stopped. Rotated or truncated files start again from the beginning.
 */
@Service
public class FolderTailer {

	private static final Logger log = LoggerFactory.getLogger(FolderTailer.class);

	/** Most bytes read from one file per chunk; big backlogs are read over several chunks and passes. */
	static final int CHUNK = 4 * 1024 * 1024;
	private static final int MAX_CHUNKS_PER_PASS = 8;
	/** A file unchanged for this long is assumed complete, so its last (held back) event is read too. */
	private static final long IDLE_MS = 4000;
	private static final int MAX_DEPTH = 4;

	private final LogSourceRepository sources;
	private final SourceFileStateRepository files;
	private final UniversalLogParser parser;
	private final LogIngestionService ingestion;

	public FolderTailer(LogSourceRepository sources, SourceFileStateRepository files, UniversalLogParser parser,
			LogIngestionService ingestion) {
		this.sources = sources;
		this.files = files;
		this.parser = parser;
		this.ingestion = ingestion;
	}

	@Scheduled(fixedDelayString = "${app.sources.folder-poll-ms:3000}", initialDelayString = "${app.sources.initial-delay-ms:2000}")
	public void pollAll() {
		for (LogSource s : sources.findByTypeAndEnabledTrue(LogSource.FOLDER)) {
			poll(s);
		}
	}

	/** One pass over a folder source. Returns the number of events stored. */
	public synchronized int poll(LogSource src) {
		LocalDateTime runAt = LocalDateTime.now();
		int stored = 0;
		Integer tracked = null;
		String error = null;
		try {
			Path dir = Path.of(src.getPath() == null ? "" : src.getPath().trim());
			if (!Files.isDirectory(dir)) {
				throw new IOException("Folder not found or not reachable: " + dir);
			}
			List<Path> found = list(dir, src);
			Map<String, SourceFileState> states = new HashMap<>();
			files.findBySourceId(src.getId()).forEach(f -> states.put(f.getPath(), f));
			for (Path file : found) {
				stored += pollFile(src, file, states.computeIfAbsent(file.toString(), k -> new SourceFileState(src.getId(), k)));
			}
			tracked = found.size();
		} catch (IOException | RuntimeException e) {
			error = e.getMessage() == null ? e.toString() : e.getMessage();
			log.debug("Folder source {} failed: {}", src.getName(), e.toString());
		}
		sources.recordRun(src.getId(), runAt, error == null ? LocalDateTime.now() : null,
				error == null ? null : error.length() > 1000 ? error.substring(0, 999) : error, stored, tracked);
		return stored;
	}

	/** Files in the folder (and sub-folders if enabled) whose name matches the pattern, oldest first. */
	public static List<Path> list(Path dir, LogSource src) throws IOException {
		String pattern = src.getFilePattern() == null || src.getFilePattern().isBlank() ? "*.log" : src.getFilePattern().trim();
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		try (Stream<Path> s = Files.walk(dir, src.isRecursive() ? MAX_DEPTH : 1)) {
			return s.filter(Files::isRegularFile).filter(p -> matcher.matches(p.getFileName()))
					.sorted(Comparator.comparing(FolderTailer::modified)).toList();
		}
	}

	private int pollFile(LogSource src, Path file, SourceFileState st) throws IOException {
		BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
		long size = a.size();
		LocalDateTime mtime = LocalDateTime.ofInstant(a.lastModifiedTime().toInstant(), ZoneId.systemDefault());
		boolean isNew = st.getId() == null;
		if (isNew) {
			st.setHeaderLine(readHeader(file));
			// history: files not touched within the backfill window are only followed from now on
			boolean old = mtime.isBefore(LocalDateTime.now().minusDays(Math.max(0, src.getBackfillDays())));
			st.setOffset(old ? size : 0);
		} else if (size < st.getOffset()) {
			st.setOffset(0); // rotated or truncated: read the new content from the start
			st.setHeaderLine(readHeader(file));
		}
		int stored = 0;
		boolean idle = size == st.getSize() && Instant.now().toEpochMilli() - a.lastModifiedTime().toMillis() > IDLE_MS;
		for (int i = 0; i < MAX_CHUNKS_PER_PASS && st.getOffset() < size; i++) {
			long before = st.getOffset();
			stored += readChunk(src, file, st, size, idle);
			if (st.getOffset() == before) {
				break; // waiting for the rest of an event
			}
		}
		st.setSize(size);
		st.setLastModified(mtime);
		st.setEventsRead(st.getEventsRead() + stored);
		files.save(st);
		return stored;
	}

	/**
	 * Reads complete lines from the current offset. Unless the file is idle, the last event is held back,
	 * because its continuation lines (SQL text, stack frames) may not have been written yet.
	 */
	int readChunk(LogSource src, Path file, SourceFileState st, long size, boolean idle) throws IOException {
		long start = st.getOffset();
		int len = (int) Math.min(CHUNK, size - start);
		byte[] buf = new byte[len];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(start);
			raf.readFully(buf);
		}
		boolean atEnd = start + len == size;
		// line boundaries, in bytes
		List<int[]> lines = new ArrayList<>();
		int lineStart = 0;
		for (int i = 0; i < len; i++) {
			if (buf[i] == '\n') {
				lines.add(new int[] { lineStart, i + 1 });
				lineStart = i + 1;
			}
		}
		if (lineStart < len && idle && atEnd) {
			lines.add(new int[] { lineStart, len }); // a last line without a newline, in a finished file
		}
		if (lines.isEmpty()) {
			return 0;
		}
		boolean siebel = st.getHeaderLine() != null;
		List<String> text = new ArrayList<>(lines.size());
		for (int[] l : lines) {
			text.add(decode(buf, l[0], l[1]));
		}
		int keep = lines.size();
		if (!(idle && atEnd)) {
			// hold back the last event: at the end of a growing file, or at the edge of this chunk
			int lastStart = -1;
			for (int i = lines.size() - 1; i >= 0; i--) {
				if (UniversalLogParser.startsNewEvent(text.get(i), siebel)) {
					lastStart = i;
					break;
				}
			}
			if (lastStart > 0) {
				keep = lastStart;
			} else if (atEnd) {
				return 0; // only one (possibly unfinished) event so far
			} // else: a single event larger than a chunk; take it as it is
		}
		long consumed = lines.get(keep - 1)[1];
		String body = String.join("\n", text.subList(0, keep));
		boolean prependHeader = siebel && start > 0;
		String service = src.getDefaultService() == null || src.getDefaultService().isBlank() ? serviceFromFile(file)
				: src.getDefaultService().trim();
		List<ParsedLog> parsed = parser.parse(prependHeader ? st.getHeaderLine() + "\n" + body : body, service);
		if (prependHeader && !parsed.isEmpty() && "SiebelLogHeader".equals(parsed.get(0).getLogger())) {
			parsed = parsed.subList(1, parsed.size()); // the header was only added for context
		}
		if (!parsed.isEmpty()) {
			ingestion.save(new ArrayList<>(parsed));
		}
		st.setOffset(start + consumed);
		return parsed.size();
	}

	/** "SCCObjMgr_enu_0020_2097155.log" -> "SCCObjMgr_enu", "app.2026-11-04.log" -> "app". */
	static String serviceFromFile(Path file) {
		String n = file.getFileName().toString();
		int dot = n.indexOf('.');
		if (dot > 0) n = n.substring(0, dot);
		n = n.replaceAll("(_\\d+)+$", "").replaceAll("[-_]?\\d{4}-?\\d{2}-?\\d{2}.*$", "");
		return n.isBlank() ? "unknown" : n.length() > 64 ? n.substring(0, 64) : n;
	}

	private static String readHeader(Path file) {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			byte[] b = new byte[(int) Math.min(2048, raf.length())];
			raf.readFully(b);
			String first = decode(b, 0, b.length).split("\\r?\\n", 2)[0];
			return UniversalLogParser.isSiebelHeader(first) ? first.strip() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static String decode(byte[] b, int from, int to) {
		CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		try {
			CharBuffer cb = d.decode(ByteBuffer.wrap(b, from, to - from));
			String s = cb.toString();
			return s.endsWith("\r\n") ? s.substring(0, s.length() - 2) : s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
		} catch (CharacterCodingException e) {
			return new String(b, from, to - from, StandardCharsets.ISO_8859_1).stripTrailing();
		}
	}

	private static Instant modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toInstant();
		} catch (IOException e) {
			return Instant.EPOCH;
		}
	}
}
