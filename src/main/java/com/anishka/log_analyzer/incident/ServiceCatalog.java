package com.anishka.log_analyzer.incident;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Business metadata per service: owning on-call team, SLA availability target and
 * a fallback average ticket size. Values are sensible retail-bank defaults; SLA
 * targets can be overridden with {@code app.impact.sla-targets}.
 */
public final class ServiceCatalog {

	/** Services whose failures are customer money movements. */
	public static final Set<String> PAYMENT_SERVICES = Set.of("upi-switch", "payment-gateway", "card-auth",
			"core-banking", "atm-switch", "netbanking-portal", "mobile-banking-api");

	private static final Map<String, String> OWNERS = Map.ofEntries(Map.entry("upi-switch", "payments-upi"),
			Map.entry("payment-gateway", "payments-pg"), Map.entry("card-auth", "cards-platform"),
			Map.entry("core-banking", "cbs-sre"), Map.entry("atm-switch", "atm-ops"),
			Map.entry("netbanking-portal", "digital-channels"), Map.entry("mobile-banking-api", "digital-channels"),
			Map.entry("fraud-engine", "fraud-risk"), Map.entry("kyc-service", "onboarding"),
			Map.entry("loan-origination", "lending-tech"), Map.entry("treasury-fx", "treasury-it"),
			Map.entry("insurance-claims", "bancassurance"), Map.entry("SCCObjMgr_enu", "crm-siebel"),
			Map.entry("EAIObjMgr_enu", "crm-siebel"), Map.entry("WfProcMgr", "crm-siebel"));

	private static final Map<String, Double> DEFAULT_TICKET = Map.of("upi-switch", 1_800.0, "payment-gateway",
			2_500.0, "card-auth", 3_200.0, "core-banking", 25_000.0, "atm-switch", 4_000.0, "netbanking-portal", 6_000.0,
			"mobile-banking-api", 2_000.0);

	private final Map<String, Double> slaTargets = new LinkedHashMap<>();
	private final double defaultSla;

	public ServiceCatalog(String slaTargetsSpec, double defaultSla) {
		this.defaultSla = defaultSla;
		if (slaTargetsSpec != null) {
			for (String part : slaTargetsSpec.split(",")) {
				String[] kv = part.trim().split("[:=]");
				if (kv.length == 2) {
					try {
						slaTargets.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
					} catch (NumberFormatException ignored) {
						// skip malformed entries
					}
				}
			}
		}
	}

	public static String owner(String service) {
		return OWNERS.getOrDefault(service, "platform-sre");
	}

	public static double defaultTicket(String service) {
		return DEFAULT_TICKET.getOrDefault(service, 2_000.0);
	}

	public double slaTarget(String service) {
		return slaTargets.getOrDefault(service, defaultSla);
	}
}
