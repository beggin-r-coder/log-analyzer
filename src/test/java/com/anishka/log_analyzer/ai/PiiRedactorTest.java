package com.anishka.log_analyzer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PiiRedactorTest {

	private final PiiRedactor r = new PiiRedactor();

	@Test
	void redactsPersonalAndSecretData() {
		PiiRedactor.Result res = r.redact("user priya.k@example.com card 4111 1111 1111 1111 aadhaar 1234 5678 9012 "
				+ "PAN ABCDE1234F phone +91 9876543210 from 49.36.10.2 Authorization: Bearer abcdefghijklmnop123 password=hunter2");
		String t = res.text();
		assertFalse(t.contains("priya"), t);
		assertFalse(t.contains("4111 1111"), t);
		assertFalse(t.contains("5678 9012"), t);
		assertFalse(t.contains("ABCDE1234F"), t);
		assertFalse(t.contains("9876543210"), t);
		assertFalse(t.contains("10.2"), t);
		assertFalse(t.contains("hunter2"), t);
		assertFalse(t.contains("abcdefghijklmnop123"), t);
		assertTrue(t.contains("49.36.x.x"), t);
		assertEquals(1, res.counts().get("card"));
		assertTrue(res.total() >= 8);
	}

	@Test
	void leavesOperationalIdentifiersAlone() {
		String in = "UPI pay request NOVA12345678901234 completed, RRN 123456789012, TXN000123456789 acct XXXXXX4821";
		PiiRedactor.Result res = r.redact(in);
		assertEquals(in, res.text());
		assertEquals(0, res.total());
	}

	@Test
	void luhnCheck() {
		assertTrue(PiiRedactor.luhn("4111111111111111"));
		assertFalse(PiiRedactor.luhn("4111111111111112"));
	}
}
