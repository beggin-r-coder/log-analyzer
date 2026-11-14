package com.anishka.log_analyzer.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Rule-based classifier that maps an error message to a BFSI incident
 * category. Rules are evaluated in order; the first match wins, so the more
 * specific categories come first.
 */
@Component
public class ErrorClassifier {

	public static final String OTHER = "Other Errors";

	private static final Map<String, List<String>> RULES = new LinkedHashMap<>();

	static {
		// Oracle Siebel CRM: SBL-<area>-<number> error codes identify the failing layer
		RULES.put("Siebel Database Connector", List.of("sbl-dbc-", "sqlerror", "[odbc"));
		RULES.put("Siebel EAI & Integration", List.of("sbl-eai-", "eaitransport", "eaiobjmgr", "web service '"));
		RULES.put("Siebel Workflow", List.of("sbl-bpr-", "sbl-oms-", "workflow process", "wfprocmgr"));
		RULES.put("Siebel Server & Components", List.of("sbl-svr-", "sbl-smi-", "sbl-scm-", "sbl-osd-", "sbl-net-", "sisnapi",
				"scbroker", "was terminated", "core dump", "srbroker"));
		RULES.put("Siebel Login & Security", List.of("sbl-sec-", "sbl-ssa-", "security adapter"));
		RULES.put("Siebel Data Validation", List.of("sbl-dat-", "sbl-exl-"));
		RULES.put("Siebel Application Errors", List.of("sbl-"));
		RULES.put("Fraud & Risk Alerts", List.of("fraud", "velocity", "risk score", "aml ", "aml-", "sanctions screening", "sanctions list",
				"suspicious", "blacklist", "mule account", "chargeback"));
		RULES.put("Database Issues", List.of("database", "jdbc", "sql", "deadlock", "connection pool", "hikari",
				"ora-", "db2", "tablespace", "replica lag", "lock wait"));
		RULES.put("Authentication & Access", List.of("authentication", "unauthori", "forbidden", "access denied",
				"otp", "login", "jwt", "token expired", "invalid token", "mfa", "password", "session expired",
				"http 401", "http 403"));
		RULES.put("Messaging Issues", List.of("kafka", "message queue", "jms", "rabbitmq", "ibm mq", "consumer lag",
				"broker", "dead letter", "dlq"));
		RULES.put("Payment Network Failures", List.of("npci", "upi", "visa", "mastercard", "rupay", "swift", "neft",
				"rtgs", "imps", "iso8583", "iso 8583", "acquirer", "issuer", "card network", "switch", "hsm"));
		RULES.put("Transaction Failures", List.of("rollback", "transaction failed", "reversal", "posting",
				"ledger", "debit failed", "credit failed", "settlement", "reconciliation", "mismatch",
				"duplicate transaction", "double debit"));
		RULES.put("Compliance & KYC", List.of("kyc", "aadhaar", "pan verification", "ckyc", "compliance",
				"regulatory", "fatca", "pep check"));
		RULES.put("Third-Party Integrations", List.of("credit bureau", "cibil", "experian", "equifax", "partner",
				"vendor", "aggregator", "insurer api"));
		RULES.put("API Gateway Issues", List.of("gateway", "rate limit", "http 429", "circuit breaker",
				"upstream"));
		RULES.put("Timeouts & Connectivity", List.of("timeout", "timed out", "connection refused", "unreachable",
				"socket", "connection reset", "http 502", "http 503", "http 504", "network"));
		RULES.put("Application Exceptions", List.of("exception", "null pointer", "nullpointer", "illegalstate",
				"outofmemory", "out of memory", "stackoverflow", "classcast", "panic", "traceback", "http 500"));
	}

	public String classify(String message) {
		if (message == null) {
			return OTHER;
		}
		String msg = message.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, List<String>> rule : RULES.entrySet()) {
			for (String keyword : rule.getValue()) {
				if (msg.contains(keyword)) {
					return rule.getKey();
				}
			}
		}
		return OTHER;
	}
}
