package br.com.argos.argos_api.telemetry;

import br.com.argos.argos_api.telemetry.ParsedTelemetry.Batch;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads OTLP/HTTP JSON payloads (the OpenTelemetry Collector's {@code otlphttp} exporter with
 * {@code encoding: json}). Malformed records are dropped and counted; a malformed document is an error.
 */
@Component
class OtlpParser {

    static final int MAX_ATTRIBUTES = 64;
    static final int MAX_VALUE_LENGTH = 1_000;
    static final int MAX_LOG_BODY_LENGTH = 10_000;
    static final String UNKNOWN_SERVICE = "desconhecido";

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);

    private final JsonMapper json = JsonMapper.builder().build();

    Batch<ParsedTelemetry.Span> parseTraces(String body) {
        List<ParsedTelemetry.Span> spans = new ArrayList<>();
        int rejected = 0;
        for (JsonNode resource : array(read(body), "resourceSpans")) {
            String service = serviceName(resource);
            for (JsonNode scope : array(resource, "scopeSpans")) {
                for (JsonNode span : array(scope, "spans")) {
                    ParsedTelemetry.Span parsed = span(span, service);
                    if (parsed == null) {
                        rejected++;
                    } else {
                        spans.add(parsed);
                    }
                }
            }
        }
        return new Batch<>(spans, rejected);
    }

    Batch<ParsedTelemetry.Log> parseLogs(String body) {
        List<ParsedTelemetry.Log> logs = new ArrayList<>();
        int rejected = 0;
        for (JsonNode resource : array(read(body), "resourceLogs")) {
            String service = serviceName(resource);
            for (JsonNode scope : array(resource, "scopeLogs")) {
                for (JsonNode record : array(scope, "logRecords")) {
                    ParsedTelemetry.Log parsed = log(record, service);
                    if (parsed == null) {
                        rejected++;
                    } else {
                        logs.add(parsed);
                    }
                }
            }
        }
        return new Batch<>(logs, rejected);
    }

    Batch<ParsedTelemetry.Metric> parseMetrics(String body) {
        List<ParsedTelemetry.Metric> metrics = new ArrayList<>();
        int rejected = 0;
        for (JsonNode resource : array(read(body), "resourceMetrics")) {
            String service = serviceName(resource);
            for (JsonNode scope : array(resource, "scopeMetrics")) {
                for (JsonNode metric : array(scope, "metrics")) {
                    String name = text(metric, "name");
                    if (name == null) {
                        rejected++;
                        continue;
                    }
                    String unit = truncate(text(metric, "unit"), 50);
                    rejected += collect(metric, "gauge", "gauge", name, unit, service, metrics);
                    rejected += collect(metric, "sum", "sum", name, unit, service, metrics);
                    rejected += collectHistogram(metric, name, unit, service, metrics);
                }
            }
        }
        return new Batch<>(metrics, rejected);
    }

    private int collect(JsonNode metric, String field, String kind, String name, String unit, String service,
                        List<ParsedTelemetry.Metric> out) {
        int rejected = 0;
        for (JsonNode point : array(metric.path(field), "dataPoints")) {
            Instant time = instant(point, "timeUnixNano");
            Double value = number(point);
            if (time == null || value == null) {
                rejected++;
                continue;
            }
            out.add(new ParsedTelemetry.Metric(time, service, truncate(name, 300), kind, unit, value,
                    attributes(point.path("attributes"))));
        }
        return rejected;
    }

    /** A histogram is reduced to its count and sum, which is enough for rates and averages. */
    private int collectHistogram(JsonNode metric, String name, String unit, String service,
                                 List<ParsedTelemetry.Metric> out) {
        int rejected = 0;
        for (JsonNode point : array(metric.path("histogram"), "dataPoints")) {
            Instant time = instant(point, "timeUnixNano");
            if (time == null) {
                rejected++;
                continue;
            }
            Map<String, Object> attributes = attributes(point.path("attributes"));
            out.add(new ParsedTelemetry.Metric(time, service, truncate(name + ".count", 300), "histogram", null,
                    point.path("count").asLong(0), attributes));
            if (point.has("sum")) {
                out.add(new ParsedTelemetry.Metric(time, service, truncate(name + ".sum", 300), "histogram", unit,
                        point.path("sum").asDouble(0), attributes));
            }
        }
        return rejected;
    }

    private ParsedTelemetry.Span span(JsonNode node, String service) {
        String traceId = hex(text(node, "traceId"), TRACE_ID, ZERO_TRACE_ID);
        String spanId = hex(text(node, "spanId"), SPAN_ID, ZERO_SPAN_ID);
        Instant start = instant(node, "startTimeUnixNano");
        Instant end = instant(node, "endTimeUnixNano");
        String name = text(node, "name");
        if (traceId == null || spanId == null || start == null || name == null) {
            return null;
        }
        long durationNs = end == null ? 0 : Math.max(0, nanosBetween(start, end));
        String parent = hex(text(node, "parentSpanId"), SPAN_ID, ZERO_SPAN_ID);
        JsonNode status = node.path("status");
        return new ParsedTelemetry.Span(traceId, spanId, parent, service, truncate(name, 500),
                node.path("kind").isNumber() ? node.path("kind").asInt() : 0, start, durationNs,
                statusCode(status.path("code")), truncate(text(status, "message"), MAX_VALUE_LENGTH),
                attributes(node.path("attributes")));
    }

    private ParsedTelemetry.Log log(JsonNode node, String service) {
        Instant time = instant(node, "timeUnixNano");
        if (time == null) {
            time = instant(node, "observedTimeUnixNano");
        }
        Object body = anyValue(node.path("body"));
        if (time == null || body == null) {
            return null;
        }
        return new ParsedTelemetry.Log(time, service, node.path("severityNumber").asInt(0),
                truncate(text(node, "severityText"), 20), truncate(String.valueOf(body), MAX_LOG_BODY_LENGTH),
                hex(text(node, "traceId"), TRACE_ID, ZERO_TRACE_ID), hex(text(node, "spanId"), SPAN_ID, ZERO_SPAN_ID),
                attributes(node.path("attributes")));
    }

    private JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Corpo OTLP vazio");
        }
        try {
            JsonNode root = json.readTree(body);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Corpo OTLP deve ser um objeto JSON");
            }
            return root;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Corpo OTLP nao e um JSON valido");
        }
    }

    private static Iterable<JsonNode> array(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isArray() ? node : List.of();
    }

    private static String serviceName(JsonNode resource) {
        for (JsonNode attribute : array(resource.path("resource"), "attributes")) {
            if ("service.name".equals(text(attribute, "key"))) {
                Object value = anyValue(attribute.path("value"));
                if (value != null && !String.valueOf(value).isBlank()) {
                    return truncate(String.valueOf(value), 200);
                }
            }
        }
        return UNKNOWN_SERVICE;
    }

    /** OTLP attributes are a list of {key, value:{stringValue|intValue|...}}; flattened to a typed map. */
    private static Map<String, Object> attributes(JsonNode list) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!list.isArray()) {
            return map;
        }
        for (JsonNode attribute : list) {
            if (map.size() >= MAX_ATTRIBUTES) {
                break;
            }
            String key = text(attribute, "key");
            Object value = anyValue(attribute.path("value"));
            if (key != null && value != null) {
                map.put(truncate(key, 200), value instanceof String s ? truncate(s, MAX_VALUE_LENGTH) : value);
            }
        }
        return map;
    }

    private static Object anyValue(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        if (value.has("stringValue")) {
            return value.path("stringValue").asString("");
        }
        if (value.has("intValue")) {
            return value.path("intValue").asLong(0);
        }
        if (value.has("doubleValue")) {
            return value.path("doubleValue").asDouble(0);
        }
        if (value.has("boolValue")) {
            return value.path("boolValue").asBoolean(false);
        }
        if (value.has("arrayValue") || value.has("kvlistValue")) {
            return truncate(value.toString(), MAX_VALUE_LENGTH);
        }
        return null;
    }

    private static Double number(JsonNode point) {
        if (point.has("asDouble")) {
            return point.path("asDouble").asDouble();
        }
        if (point.has("asInt")) {
            return (double) point.path("asInt").asLong();
        }
        return null;
    }

    /** OTLP/JSON sends 64-bit nanosecond timestamps as strings (or numbers); 0 means "not set". */
    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            long nanos = Long.parseLong(value.asString("0").trim());
            return nanos <= 0 ? null : Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long nanosBetween(Instant start, Instant end) {
        return (end.getEpochSecond() - start.getEpochSecond()) * 1_000_000_000L + (end.getNano() - start.getNano());
    }

    /** 0 unset, 1 ok, 2 error; the JSON mapping may send the numeric value or the enum name. */
    private static int statusCode(JsonNode code) {
        if (code.isNumber()) {
            return code.asInt();
        }
        return switch (code.asString("").toUpperCase(Locale.ROOT)) {
            case "STATUS_CODE_OK" -> 1;
            case "STATUS_CODE_ERROR" -> 2;
            default -> 0;
        };
    }

    private static String hex(String value, Pattern format, String zero) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return format.matcher(normalized).matches() && !normalized.equals(zero) ? normalized : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
