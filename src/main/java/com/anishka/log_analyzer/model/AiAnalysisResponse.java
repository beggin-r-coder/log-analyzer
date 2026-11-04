package com.anishka.log_analyzer.model;

public class AiAnalysisResponse {

	private String summary;
	private String rootCause;
	private String impact;
	private String recommendation;

	public AiAnalysisResponse() {
	}

	public AiAnalysisResponse(String summary, String rootCause, String impact, String recommendation) {

		this.summary = summary;
		this.rootCause = rootCause;
		this.impact = impact;
		this.recommendation = recommendation;
	}

	public String getSummary() {
		return summary;
	}

	public String getRootCause() {
		return rootCause;
	}

	public String getImpact() {
		return impact;
	}

	public String getRecommendation() {
		return recommendation;
	}
}