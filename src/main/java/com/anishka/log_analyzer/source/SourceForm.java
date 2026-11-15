package com.anishka.log_analyzer.source;

/** What the Log sources screen sends when a source is tested, created or edited. */
public record SourceForm(String name, String type, Boolean enabled, String path, String filePattern, Boolean recursive,
		Integer backfillDays, String defaultService, String dbType, String host, Integer port, String databaseName,
		String username, String password, String jdbcUrl, String queryText, String tsColumn, String levelColumn,
		String serviceColumn, String messageColumn, String traceColumn, String hostColumn, String rawColumn,
		String idColumn, Integer pollSeconds, String sslMode, Boolean autoColumns) {

	public SourceForm(String name, String type, Boolean enabled, String path, String filePattern, Boolean recursive,
			Integer backfillDays, String defaultService, String dbType, String host, Integer port, String databaseName,
			String username, String password, String jdbcUrl, String queryText, String tsColumn, String levelColumn,
			String serviceColumn, String messageColumn, String traceColumn, String hostColumn, String rawColumn,
			String idColumn, Integer pollSeconds) {
		this(name, type, enabled, path, filePattern, recursive, backfillDays, defaultService, dbType, host, port, databaseName,
				username, password, jdbcUrl, queryText, tsColumn, levelColumn, serviceColumn, messageColumn, traceColumn,
				hostColumn, rawColumn, idColumn, pollSeconds, null, null);
	}
}
