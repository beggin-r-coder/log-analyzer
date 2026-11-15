package com.anishka.log_analyzer.source;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import com.anishka.log_analyzer.parser.UniversalLogParser;

/** Turns the many ways databases and spreadsheets store a date/time into a LocalDateTime (server-local). */
public final class Timestamps {

	private static final LocalDateTime MIN = LocalDateTime.of(1990, 1, 1, 0, 0), MAX = LocalDateTime.of(2100, 1, 1, 0, 0);
	private static final List<DateTimeFormatter> FORMATS = List.of(
			f("dd-MM-yyyy HH:mm[:ss][.SSS]"), f("dd/MM/yyyy HH:mm[:ss][.SSS]"), f("dd.MM.yyyy HH:mm[:ss]"),
			f("MM/dd/yyyy hh:mm[:ss] a"), f("M/d/yyyy h:mm[:ss] a"), f("M/d/yyyy H:mm[:ss]"),
			f("dd-MMM-yyyy HH:mm[:ss]"), f("dd-MMM-yy hh.mm.ss[.SSSSSS] a"), f("dd-MMM-yy HH:mm[:ss]"),
			f("yyyyMMddHHmmss"), f("yyyy-MM-dd'T'HH:mm[:ss][.SSS]"), f("EEE MMM d HH:mm:ss yyyy"));
	private static final List<DateTimeFormatter> DATES = List.of(f("yyyy-MM-dd"), f("dd-MM-yyyy"), f("dd/MM/yyyy"));

	private Timestamps() {
	}

	private static DateTimeFormatter f(String p) {
		return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
	}

	/** Null when the value is not a plausible date/time (1990–2100). */
	public static LocalDateTime parse(Object o) {
		LocalDateTime t = raw(o);
		return t != null && t.isAfter(MIN) && t.isBefore(MAX) ? t : null;
	}

	private static LocalDateTime raw(Object o) {
		if (o == null) return null;
		if (o instanceof Timestamp t) return t.toLocalDateTime();
		if (o instanceof LocalDateTime t) return t;
		if (o instanceof OffsetDateTime t) return t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		if (o instanceof ZonedDateTime t) return t.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		if (o instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
		if (o instanceof java.util.Date d) return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
		if (o instanceof LocalDate d) return d.atStartOfDay();
		if (o instanceof Number n) {
			long v = n.longValue();
			if (v > 946_684_800_000L && v < 4_102_444_800_000L) return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneId.systemDefault());
			if (v > 946_684_800L && v < 4_102_444_800L) return LocalDateTime.ofInstant(Instant.ofEpochSecond(v), ZoneId.systemDefault());
			return null;
		}
		String s = o.toString().strip();
		if (s.length() < 8 || s.length() > 40) return null;
		LocalDateTime t = UniversalLogParser.parseTimestamp(s);
		if (t != null) return t;
		String compact = s.replaceAll("\\s+", " ");
		for (DateTimeFormatter fmt : FORMATS) {
			try {
				return LocalDateTime.parse(compact, fmt);
			} catch (DateTimeParseException e) {
				// next
			}
		}
		for (DateTimeFormatter fmt : DATES) {
			try {
				return LocalDate.parse(compact, fmt).atStartOfDay();
			} catch (DateTimeParseException e) {
				// next
			}
		}
		return null;
	}
}
