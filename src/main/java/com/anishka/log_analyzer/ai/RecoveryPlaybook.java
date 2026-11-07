package com.anishka.log_analyzer.ai;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Offline knowledge base of common BFSI failure modes. Matches signals found
 * by the NLP layer to a probable root cause and a safe recovery runbook. Used
 * when Gemini is not configured or unreachable, so analysis never goes dark.
 */
@Component
public class RecoveryPlaybook {

	public record Play(String id, String title, String rootCause, String impact, List<String> recovery,
			List<String> prevention, String severity) {
	}

	private record Rule(List<String> anyOf, Play play) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule(List.of("hikari", "connection is not available", "connection pool"), new Play("db-pool",
					"Database connection pool exhaustion",
					"The application's JDBC pool (HikariCP) has no free connections: requests wait 30s and fail. Usually caused by slow or blocked queries holding connections, a connection leak, or a traffic spike beyond the pool size.",
					"Postings and balance enquiries fail or time out; customers may see failed transfers that need reconciliation.",
					List.of("Check DB health and active sessions; identify long-running or blocked queries (e.g. v$session / pg_stat_activity).",
							"Kill or wait out blocking sessions only after confirming they are not mid-commit on financial postings.",
							"Restart affected application pods one at a time to release leaked connections, keeping capacity up.",
							"Temporarily raise the pool size only if the database has headroom.",
							"Reconcile transactions that failed during the window (status check, then reverse or re-post)."),
					List.of("Add leak detection (leakDetectionThreshold) and alerts on pool usage above 80%.", "Set query timeouts and index the slow queries.", "Load-test pool sizing against peak banking hours."),
					"HIGH")),
			new Rule(List.of("deadlock", "lock wait", "optimisticlock", "optimistic lock"), new Play("db-lock",
					"Row lock contention / deadlocks on balance tables",
					"Concurrent transactions update the same account rows in different orders, causing deadlocks or optimistic-lock failures on ACCT_BALANCE.",
					"Individual postings roll back; retries add load. Risk of partial postings if retries are not idempotent.",
					List.of("Confirm the affected accounts and transactions from the logs.", "Verify every rolled-back transaction was fully reversed (no half postings).", "Retry failed postings idempotently using the original transaction reference.", "If one hot account dominates (e.g. a pool/nostro account), serialise its postings temporarily."),
					List.of("Acquire row locks in a consistent order.", "Use idempotency keys on all posting APIs.", "Shard or buffer postings to hot internal accounts."),
					"MEDIUM")),
			new Rule(List.of("npci", "upi"), new Play("upi-npci",
					"UPI switch / NPCI connectivity or latency",
					"Requests to NPCI are timing out or returning failure codes (e.g. RespCode BT/U30), pointing to latency or an outage on the NPCI side or on the bank's UPI switch link.",
					"UPI payments fail or end in 'deemed' state; customers may be debited without credit until reconciled.",
					List.of("Check NPCI status bulletins and the UPI switch link/HSM health.", "Do not blindly retry debits; use the transaction status check API for pending requests.", "Move timed-out transactions to deemed/pending and run reconciliation against NPCI settlement files.", "Raise an incident with NPCI if the failure rate stays above SLA.", "Publish a customer advisory if the outage lasts more than a few minutes."),
					List.of("Alert on UPI success rate and NPCI latency per bank/PSP.", "Automate deemed-transaction reconciliation and auto-reversal within RBI TAT."),
					"HIGH")),
			new Rule(List.of("hsm", "iso8583", "card network", "visa", "mastercard", "rupay", "acquirer", "issuer"), new Play("card-network",
					"Card network / HSM path failure",
					"Card authorisations fail between the switch, the HSM and the card network or acquirer, e.g. HSM connection refused or ISO 8583 parse errors.",
					"Card and ATM transactions decline; possible duplicate or stuck authorisations.",
					List.of("Check HSM cluster health and connection limits; fail over to the standby HSM.", "Verify network links/certificates to the card network.", "Identify reversals that were not sent and replay them (0420 messages).", "Coordinate with the network/acquirer if errors come from their side."),
					List.of("Monitor HSM latency and connection pool usage.", "Keep an active-active HSM pair with health checks."),
					"HIGH")),
			new Rule(List.of("swift", "mt300", "nak"), new Play("swift",
					"SWIFT messaging failure",
					"SWIFT confirmations are rejected (NAK) or the SWIFT gateway connection dropped, so treasury confirmations queue up.",
					"Unconfirmed FX deals and delayed settlements; operational risk if cut-off times are missed.",
					List.of("Check the SWIFT Alliance gateway session and queue depth.", "Review NAK error codes and correct the message fields.", "Resend queued confirmations once the session is back, avoiding duplicates.", "Inform treasury operations about deals at risk of missing cut-off."),
					List.of("Validate MT messages before sending.", "Alert on SWIFT session drops and queue growth."),
					"MEDIUM")),
			new Rule(List.of("kafka", "broker", "partition", "message queue", "jms", "dlq"), new Play("messaging",
					"Messaging backbone issue (Kafka/MQ)",
					"Producers cannot publish (e.g. NotLeaderForPartition), indicating broker leadership changes, broker failure or network partitions.",
					"Downstream consumers (notifications, ledger events, fraud scoring) lag or miss events.",
					List.of("Check broker health, ISR and controller logs.", "Confirm producers retry with idempotence enabled.", "Replay missed events from the outbox or source of truth once healthy.", "Watch consumer lag until it returns to normal."),
					List.of("Enable idempotent producers and the outbox pattern.", "Alert on under-replicated partitions."),
					"MEDIUM")),
			new Rule(List.of("jwt", "token expired", "authentication", "otp", "login", "unauthori", "forbidden"), new Play("auth",
					"Authentication / session failures",
					"Logins or API calls fail authentication: expired or invalid tokens, OTP delivery problems or an unavailable auth service.",
					"Customers cannot log in or complete transactions; elevated retries may look like attacks.",
					List.of("Check the identity/auth service and OTP gateway health.", "Verify clock sync (NTP) across services; skew breaks JWT validation.", "Check recent key or certificate rotation for signing keys.", "Separate genuine failures from credential-stuffing patterns before unblocking users."),
					List.of("Alert on login success rate.", "Automate key rotation with overlap windows."),
					"MEDIUM")),
			new Rule(List.of("fraud", "suspicious", "mule", "velocity"), new Play("fraud",
					"Fraud engine alerts or scoring outage",
					"Fraud rules are firing (suspicious or mule-account patterns) or the fraud model is unreachable and scoring is failing open.",
					"Either fraud losses (if failing open) or blocked genuine customers (if failing closed).",
					List.of("If the model server is down, restore it and decide fail-open vs fail-closed with the risk team.", "Review blocked transactions and confirm true positives with the fraud operations team.", "File regulatory reports for confirmed fraud as required."),
					List.of("Monitor model latency and availability.", "Keep a rules-only fallback mode."),
					"HIGH")),
			new Rule(List.of("cibil", "credit bureau", "insurer api", "vendor", "kyc", "aadhaar", "ckyc"), new Play("third-party",
					"Third-party / regulatory API dependency failure",
					"Calls to an external provider (credit bureau, KYC/Aadhaar, insurer or vendor API) are failing or timing out.",
					"Loan, onboarding or claims journeys stall; SLA breaches with customers.",
					List.of("Check the provider's status page and your egress/proxy connectivity.", "Queue requests for retry instead of failing journeys outright.", "Escalate to the vendor with request IDs and timestamps."),
					List.of("Circuit breakers with cached or degraded modes.", "Contractual SLAs and synthetic monitoring for vendors."),
					"MEDIUM")),
			new Rule(List.of("rollback", "reversal", "reconciliation", "mismatch", "ledger", "posting", "settlement"), new Play("txn-integrity",
					"Transaction integrity problem",
					"Postings are rolling back or ledgers do not reconcile, suggesting partial failures across the posting flow.",
					"Financial risk: customer balances or the general ledger may be wrong until reconciled.",
					List.of("Freeze automated retries for the affected flow.", "Run reconciliation for the window and list unmatched entries.", "Correct via maker-checker approved adjustment entries; never edit balances directly.", "Notify finance/operations of the reconciliation outcome."),
					List.of("End-of-day reconciliation alerts.", "Idempotent, compensating transaction design."),
					"CRITICAL")),
			new Rule(List.of("timeout", "timed out", "connection refused", "unreachable", "connection reset", "http 502", "http 503", "http 504"), new Play("dependency",
					"Downstream dependency timeout or outage",
					"A downstream service is slow or unreachable (timeouts, refused or reset connections, 5xx from upstream).",
					"Requests through that dependency fail; retries can amplify the load.",
					List.of("Identify the failing dependency from the error pattern and check its health.", "Enable or tighten circuit breakers to stop retry storms.", "Scale or fail over the dependency.", "Re-drive failed requests after recovery if they are idempotent."),
					List.of("Timeouts and circuit breakers on every remote call.", "Dependency dashboards with latency SLOs."),
					"MEDIUM")),
			new Rule(List.of("nullpointer", "illegalstate", "outofmemory", "exception", "traceback", "keyerror"), new Play("app-bug",
					"Application defect",
					"An unhandled exception in application code (null data, invalid state or memory exhaustion), likely triggered by an unexpected input or a recent release.",
					"The affected request types fail consistently until fixed.",
					List.of("Find the first occurrence and correlate it with recent deployments.", "Roll back or feature-flag the change if it started after a release.", "Capture a failing payload and stack trace for the development team.", "Add input validation as a hotfix if a rollback is not possible."),
					List.of("Add regression tests for the failing input.", "Use canary releases with error-rate gates."),
					"MEDIUM")));

	private static final Play GENERIC = new Play("generic", "Unclassified error pattern",
			"The error does not match a known failure mode. Inspect the full log line, its trace and nearby events from the same service.",
			"Unknown; assess using the error volume and the affected services.",
			List.of("Open the related logs for the same trace ID.", "Check deployments and infrastructure changes around the first occurrence.", "Escalate to the owning team with the log samples."),
			List.of("Add structured error codes to make this pattern classifiable."), "LOW");

	/** Best playbook for the given text (message, template or joined templates). */
	public Play match(String text) {
		String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
		for (Rule r : RULES) {
			for (String k : r.anyOf()) {
				if (t.contains(k)) {
					return r.play();
				}
			}
		}
		return GENERIC;
	}
}
