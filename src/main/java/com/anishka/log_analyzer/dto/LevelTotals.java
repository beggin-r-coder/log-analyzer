package com.anishka.log_analyzer.dto;

public record LevelTotals(long total, long trace, long debug, long info, long warn, long error, long fatal) {

	/** ERROR + FATAL */
	public long errorsAndFatal() {
		return error + fatal;
	}
}
