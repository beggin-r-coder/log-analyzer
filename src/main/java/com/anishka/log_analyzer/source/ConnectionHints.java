package com.anishka.log_analyzer.source;

import java.util.Locale;

/**
 * Turns database driver errors into plain advice: what most likely went wrong and what to do about it. Covers the
 * usual problems with hosted and cloud databases (firewall / allowed IP addresses, VPN, required encryption,
 * certificates) as well as wrong names and passwords, for SQL Server, Oracle, PostgreSQL and MySQL.
 */
public final class ConnectionHints {

	private ConnectionHints() {
	}

	public static String explain(Throwable e, LogSource s) {
		StringBuilder all = new StringBuilder();
		for (Throwable t = e; t != null && all.length() < 4000; t = t.getCause() == t ? null : t.getCause()) {
			all.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
		}
		return explain(all.toString(), s);
	}

	/** Null when there is nothing more useful to say than the error itself. */
	public static String explain(String error, LogSource s) {
		if (error == null) return null;
		String m = error.toLowerCase(Locale.ROOT);
		String host = s == null || s.getHost() == null || s.getHost().isBlank() ? "the server" : s.getHost().trim();
		int p = s == null ? 0 : port(s);
		String port = p == 0 ? "" : String.valueOf(p);

		if (has(m, "unknownhost", "unknown host", "could not resolve", "name or service not known", "nodename nor servname", "no such host")) {
			return "The name \"" + host + "\" can't be found from this computer. Check the spelling (copy it from the provider's "
					+ "connection details). A company database usually needs the VPN to be connected first.";
		}
		if (has(m, "pkix", "certificate", "certpath", "unable to find valid certification path", "ssl_server_dn_match", "hostname in certificate")) {
			return "The connection is encrypted, but this computer doesn't trust the server's certificate. Set Encryption to "
					+ "\"Required\" (encrypts without checking the certificate), or ask IT for the company certificate.";
		}
		if (has(m, "no pg_hba.conf entry") && has(m, "ssl off", "no encryption")) {
			return "The server only accepts encrypted connections. Set Encryption to \"Required\".";
		}
		if (has(m, "no pg_hba.conf entry")) {
			return "The PostgreSQL server doesn't allow connections from this computer's address. On a hosted database, add this "
					+ "computer's public IP address to the allowed list (firewall / network rules); on a company server, ask the DBA.";
		}
		if (has(m, "requires ssl", "ssl is required", "ssl required", "require_secure_transport", "insecure transport", "sslmode",
				"encryption is required", "force encryption")) {
			return "The server only accepts encrypted connections. Set Encryption to \"Required\".";
		}
		if (has(m, "is not allowed to access the server", "client with ip address", "not allowed to connect to this mysql server")) {
			return "The database's firewall is blocking this computer. Add this computer's public IP address to the allowed list: "
					+ "Azure SQL → Networking → Firewall rules; AWS RDS → the security group's inbound rules; Google Cloud SQL → "
					+ "Authorized networks. On a company network the public IP is the office's outgoing address (IT knows it).";
		}
		if (has(m, "login failed", "password authentication failed", "ora-01017", "access denied for user", "authentication failed",
				"invalid username/password", "28000")) {
			return "The database rejected the username or password, or the account has no access to this database. Check both "
					+ "(cloud consoles often show the admin user as user@server). Windows / Active Directory login isn't "
					+ "supported yet: use a SQL login.";
		}
		if (has(m, "ora-12514", "ora-12505", "listener does not currently know of service", "listener does not currently know of sid")) {
			return "The Oracle server is reachable, but it doesn't know that service name. Use the service name (e.g. SBLPROD or "
					+ "mydb_high.adb.oraclecloud.com), not the SID; your DBA or the cloud console has it.";
		}
		if (has(m, "table or view does not exist", "invalid object name", "relation \"", "doesn't exist", "ora-00942")) {
			return "Connected, but the table wasn't found or the account can't read it. Leave the table empty and use "
					+ "\"Find log tables\", or include the schema (e.g. SIEBEL.S_SRM_REQUEST).";
		}
		if (has(m, "cannot open database", "does not exist", "unknown database", "ora-01435")) {
			return "Connected to the server, but the database name wasn't found. Check the database / service name.";
		}
		if (has(m, "permission denied", "select permission was denied", "insufficient privileges", "ora-01031")) {
			return "Connected, but the account isn't allowed to read this table. Ask the DBA for read (SELECT) access.";
		}
		if (has(m, "timed out", "timeout", "connection refused", "connectexception", "communications link failure",
				"network adapter could not establish", "tcp/ip connection to the host", "connection attempt failed",
				"no route to host", "network is unreachable", "io exception: the network", "ora-12170", "ora-12541")) {
			return "This computer can't reach " + host + (port.isEmpty() ? "" : " on port " + port) + ". For a hosted or cloud "
					+ "database: make sure it allows public access and that its firewall allows this computer's public IP address "
					+ "(Azure SQL → Networking; AWS RDS → security group inbound rule; Google Cloud SQL → Authorized networks). "
					+ "For a company database: connect the VPN, and check the port is right. Some office networks block "
					+ "database ports to the internet; IT can open it or provide a gateway.";
		}
		if (has(m, "another instance of derby may have already booted", "xsdb6")) {
			return "This Derby database is open in another program (embedded Derby lets only one program use it at a time). "
					+ "Stop that program, or start a Derby network server for it and connect with a host and port (jdbc:derby://host:1527/name).";
		}
		if (has(m, "xj004", "database '") && has(m, "not found")) {
			return "No Derby database at that location. For a database on this computer, enter the folder that contains it "
					+ "(the folder with service.properties and seg0 inside), e.g. C:/apps/crm/logdb.";
		}
		if (has(m, "no suitable driver")) {
			return "The JDBC URL isn't recognised. It should start with jdbc:oracle:thin:, jdbc:sqlserver:, jdbc:postgresql:, jdbc:mysql:, jdbc:derby: or jdbc:h2:.";
		}
		return null;
	}

	private static int port(LogSource s) {
		if (s.getPort() != null && s.getPort() > 0) return s.getPort();
		String t = s.getDbType() == null ? "" : s.getDbType().toUpperCase(Locale.ROOT);
		boolean tls = "REQUIRE".equalsIgnoreCase(s.getSslMode()) || "VERIFY".equalsIgnoreCase(s.getSslMode());
		return switch (t) {
		case "SQLSERVER" -> 1433;
		case "ORACLE" -> tls ? 1522 : 1521;
		case "POSTGRES" -> 5432;
		case "MYSQL" -> 3306;
		case "DERBY" -> s.getHost() == null || s.getHost().isBlank() ? 0 : 1527;
		default -> 0;
		};
	}

	private static boolean has(String m, String... parts) {
		for (String p : parts) if (m.contains(p)) return true;
		return false;
	}
}
