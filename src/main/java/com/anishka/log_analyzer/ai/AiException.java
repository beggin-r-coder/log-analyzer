package com.anishka.log_analyzer.ai;

/** A failure talking to the AI provider, with a message safe to show to support staff. */
public class AiException extends RuntimeException {

	private final int status;

	public AiException(String message, int status) {
		super(message);
		this.status = status;
	}

	public AiException(String message, int status, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	/** HTTP status from the provider, or 0 for network/parse errors. */
	public int getStatus() {
		return status;
	}
}
