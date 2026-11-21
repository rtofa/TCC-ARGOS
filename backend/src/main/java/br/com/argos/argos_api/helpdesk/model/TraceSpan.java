package br.com.argos.argos_api.helpdesk.model;

import java.util.Map;

/** One stage of an execution (guardrail, analysis, retrieval…), relative to the start of the request. */
public record TraceSpan(String name, long startMs, long durationMs, String status, Map<String, Object> attributes) {
}
