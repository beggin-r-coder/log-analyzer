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
			// ---------- Oracle Siebel CRM (checked first: SBL codes are the most specific signal) ----------
			new Rule(List.of("sbl-dbc-", "sqlerror", "[odbc"), new Play("siebel-dbc",
					"Siebel database connector error",
					"The Siebel database connector (SBL-DBC) could not run a SQL statement: the database is unreachable or slow, the ODBC data source or credentials are wrong, a constraint was violated, or a long-running query hit a timeout. The underlying ORA- or ODBC error that follows the SBL-DBC code names the exact cause.",
					"Screens and business components that read or write the affected tables fail for users; EAI and workflow steps using them also fail.",
					List.of("Read the ORA- / ODBC error logged right after the SBL-DBC code in the same SARM ID: it names the real cause.",
							"Check database availability and that the ODBC data source used by the component (the DSN in the enterprise profile) connects with the Siebel table owner account.",
							"For timeouts, capture the slow statement: temporarily raise the component's ObjMgrSqlLog event level to 4, reproduce, then set it back.",
							"Restart the affected object manager tasks only if connections are stale after a database restart."),
					List.of("Alert on SBL-DBC errors per component.", "Review and index slow SQL found in ObjMgrSqlLog traces.", "Keep DSN and table-owner passwords in step after database maintenance."),
					"HIGH")),
			new Rule(List.of("sbl-eai-", "eaitransport", "web service '"), new Play("siebel-eai",
					"Siebel EAI / web service integration failure",
					"An outbound or inbound integration (EAI, SBL-EAI) failed: the remote endpoint is down, slow or rejecting the call, the payload no longer matches the integration object, or credentials and certificates have changed.",
					"Data stops flowing between Siebel and the connected system (core banking, payments, middleware); records may be out of sync until resubmitted.",
					List.of("Identify the web service, port and operation in the SBL-EAI message, and test the endpoint URL from the Siebel server.",
							"Check credentials, certificates and any recent WSDL or schema change on the remote side.",
							"Raise the EAIObjMgr / EAITransport event levels to 4-5 briefly to capture the request and response.",
							"Resubmit or replay failed messages once the endpoint is healthy, and reconcile records created during the outage."),
					List.of("Monitor integration endpoints and certificate expiry.", "Version integration objects together with the remote WSDL.", "Use retry queues for transient failures."),
					"HIGH")),
			new Rule(List.of("sbl-bpr-", "sbl-oms-", "workflow process", "wfprocmgr"), new Play("siebel-workflow",
					"Siebel workflow process failure",
					"A workflow process step failed (SBL-BPR / SBL-OMS): a business service it calls returned an error, required data was missing, or the Workflow Process Manager component is unavailable.",
					"Automated steps such as assignment, approvals or notifications stop; affected records wait in an incomplete state.",
					List.of("Find the process name, step and business service in the SBL-BPR message and look at the error logged just before it under the same SARM ID.",
							"Check that the Workflow Process Manager (WfProcMgr) component is online on the server.",
							"Open the process instance in the Workflow instance monitor to see the failing step and its inputs.",
							"Fix the data or service, then resume or re-run the failed instances."),
					List.of("Add error-handling branches to critical workflows.", "Alert on SBL-BPR errors per process.", "Test workflow changes against production-like data."),
					"MEDIUM")),
			new Rule(List.of("sbl-svr-", "sbl-smi-", "sbl-scm-", "sbl-osd-", "was terminated", "core dump", "crash"), new Play("siebel-server",
					"Siebel server component crash or task exit",
					"A Siebel server process or component task ended unexpectedly (crash, killed process or internal error), often after memory exhaustion, a faulty script or a native library failure.",
					"Users connected to the crashed process lose their sessions and unsaved work; the component may run with reduced capacity until tasks restart.",
					List.of("Check component and task state with Server Manager (srvrmgr: list comp, list tasks for comp <name>).",
							"Collect crash evidence from the server log directory and bin folder: crash files, FDR (*.fdr) flight-data files and the component log around the exit time.",
							"Look for memory growth or a script or business service that was running in the failing task (same SARM ID / task id).",
							"Restart the component if it did not recover, and raise a service request with Oracle with the FDR and crash files if it repeats."),
					List.of("Track crash frequency per component.", "Tune MaxTasks / MaxMTServers for the load.", "Review recent scripting and repository changes."),
					"CRITICAL")),
			new Rule(List.of("sbl-net-", "sisnapi", "scbroker", "srbroker"), new Play("siebel-network",
					"Siebel server network / SISNAPI connectivity problem",
					"Siebel server processes could not talk to each other or to the Gateway (SISNAPI, SCBroker, SRBroker), typically after a network change, a firewall rule, a port conflict or a Gateway outage.",
					"New sessions fail to start or get routed, and server-to-server requests time out.",
					List.of("Confirm the Siebel Gateway and the SCBroker / SRBroker components are running.",
							"Check connectivity and firewall rules between the web server, Siebel servers and the Gateway on the configured ports.",
							"Look for recent network or firewall changes around the first error.",
							"Restart SCBroker or the affected server only after the network path is confirmed."),
					List.of("Monitor Gateway and broker availability.", "Document required ports in firewall change requests."),
					"HIGH")),
			new Rule(List.of("sbl-sec-", "sbl-ssa-", "security adapter"), new Play("siebel-security",
					"Siebel login / security adapter failure",
					"Users could not be authenticated: the LDAP / AD security adapter is unreachable, a service account password expired, or user accounts are locked or missing.",
					"Agents cannot log in to Siebel, blocking customer service work.",
					List.of("Check the security adapter's directory server is reachable and its service account is valid.",
							"Confirm whether all users or only some are affected (directory outage vs. individual accounts).",
							"Review recent password, certificate or directory changes."),
					List.of("Alert on login failure rates.", "Track service-account password and certificate expiry."),
					"HIGH")),
			new Rule(List.of("sbl-dat-", "sbl-exl-"), new Play("siebel-data",
					"Siebel data validation / business component error",
					"A business component rejected an operation (SBL-DAT): a required field was empty, a user key or uniqueness rule was violated, a validation rule or script raised an error, or a record was changed by another user.",
					"Individual users or integrations fail to save records; usually limited to specific data rather than an outage.",
					List.of("Read the SBL-DAT message text: it names the business component and field involved.",
							"Check recent configuration or script changes on that business component.",
							"If integrations cause it, correct the payload mapping or source data and resubmit."),
					List.of("Validate data at the source.", "Add clear validation messages to custom rules."),
					"LOW")),
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
