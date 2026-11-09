package br.com.argos.argos_api.telemetry;

import java.time.Instant;

/** OTLP/JSON payload builders shared by the telemetry tests. */
final class OtlpPayloads {

    static final String TRACE_ID = "5b8efff798038103d269b633813fc60c";

    private OtlpPayloads() {
    }

    static String nanos(Instant instant) {
        return String.valueOf(instant.getEpochSecond() * 1_000_000_000L + instant.getNano());
    }

    /** One span; {@code parent} may be null for a root span. {@code error} sets status code 2. */
    static String span(String traceId, String spanId, String parent, String name, Instant start, long durationMs,
                       boolean error) {
        return """
                {"traceId":"%s","spanId":"%s",%s"name":"%s","kind":2,
                 "startTimeUnixNano":"%s","endTimeUnixNano":"%s",
                 "attributes":[{"key":"http.route","value":{"stringValue":"/cart"}},
                               {"key":"http.status_code","value":{"intValue":"%d"}}],
                 "status":{"code":%d%s}}
                """.formatted(traceId, spanId, parent == null ? "" : "\"parentSpanId\":\"" + parent + "\",", name,
                nanos(start), nanos(start.plusMillis(durationMs)), error ? 500 : 200, error ? 2 : 1,
                error ? ",\"message\":\"boom\"" : "");
    }

    static String traces(String service, String... spans) {
        return """
                {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"%s"}}]},
                 "scopeSpans":[{"scope":{"name":"test"},"spans":[%s]}]}]}
                """.formatted(service, String.join(",", spans));
    }

    static String logRecord(Instant time, int severityNumber, String severityText, String body, String traceId) {
        return """
                {"timeUnixNano":"%s","severityNumber":%d,"severityText":"%s","body":{"stringValue":"%s"}%s}
                """.formatted(nanos(time), severityNumber, severityText, body,
                traceId == null ? "" : ",\"traceId\":\"" + traceId + "\",\"spanId\":\"051581bf3cb55c13\"");
    }

    static String logs(String service, String... records) {
        return """
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"%s"}}]},
                 "scopeLogs":[{"logRecords":[%s]}]}]}
                """.formatted(service, String.join(",", records));
    }

    static String metrics(String service, Instant time, String name, double value) {
        return """
                {"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"%s"}}]},
                 "scopeMetrics":[{"metrics":[
                   {"name":"%s","unit":"By","gauge":{"dataPoints":[{"timeUnixNano":"%s","asDouble":%s}]}},
                   {"name":"http.server.duration","unit":"ms","histogram":{"dataPoints":[
                     {"timeUnixNano":"%s","count":"4","sum":120.5}]}}]}]}]}
                """.formatted(service, name, nanos(time), value, nanos(time));
    }
}
