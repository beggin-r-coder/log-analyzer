package com.anishka.log_analyzer.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/** Plain advice for connection problems, and the JDBC URLs built for Oracle and the encryption setting. */
class ConnectionHintsTest {

	private static LogSource oracle(String ssl) {
		LogSource s = new LogSource();
		s.setType(LogSource.DATABASE);
		s.setDbType("ORACLE");
		s.setHost("sbldb01.bank.local");
		s.setDatabaseName("SBLPROD");
		s.setSslMode(ssl);
		return s;
	}

	@Test
	void explainsTheUsualOracleProblems() {
		LogSource s = oracle(null);
		assertTrue(ConnectionHints.explain(new SQLException("IO Error: The Network Adapter could not establish the connection"), s)
				.contains("can't reach sbldb01.bank.local on port 1521"));
		assertTrue(ConnectionHints.explain(new SQLException("ORA-01017: invalid username/password; logon denied"), s).contains("username or password"));
		assertTrue(ConnectionHints.explain(new SQLException("ORA-12514: TNS:listener does not currently know of service requested"), s).contains("service name"));
		assertTrue(ConnectionHints.explain(new SQLException("ORA-00942: table or view does not exist"), s).contains("Find log tables"));
		assertTrue(ConnectionHints.explain(new SQLException("IO Error", new UnknownHostException("sbldb01.bank.local")), s).contains("VPN"));
		assertNull(ConnectionHints.explain(new SQLException("ORA-00600: internal error"), s));
	}

	@Test
	void explainsFirewallAndEncryptionProblemsOfOtherDatabases() {
		LogSource s = oracle(null);
		s.setDbType("POSTGRES");
		assertTrue(ConnectionHints.explain("FATAL: no pg_hba.conf entry for host \"1.2.3.4\", user \"ro\", database \"logs\", no encryption", s).contains("Required"));
		assertTrue(ConnectionHints.explain("Cannot open server 'crm' requested by the login. Client with IP address '1.2.3.4' is not allowed to access the server.", s)
				.contains("allowed list"));
		assertTrue(ConnectionHints.explain("PKIX path building failed", s).contains("certificate"));
	}

	@Test
	void oracleUrlsForPlainAndEncryptedConnections() {
		assertEquals("jdbc:oracle:thin:@//sbldb01.bank.local:1521/SBLPROD", DatabasePoller.url(oracle(null)));
		assertEquals("jdbc:oracle:thin:@//sbldb01.bank.local:1521/SBLPROD", DatabasePoller.url(oracle("AUTO")));
		assertEquals("jdbc:oracle:thin:@tcps://sbldb01.bank.local:1522/SBLPROD", DatabasePoller.url(oracle("REQUIRE")));
		LogSource pg = oracle("REQUIRE");
		pg.setDbType("POSTGRES");
		assertEquals("jdbc:postgresql://sbldb01.bank.local:5432/SBLPROD?sslmode=require", DatabasePoller.url(pg));
		LogSource ms = oracle("DISABLE");
		ms.setDbType("SQLSERVER");
		assertTrue(DatabasePoller.url(ms).contains(";encrypt=false"));
	}
}
