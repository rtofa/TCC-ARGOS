package br.com.argos.argos_api.alerting.mapping;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an incoming payload into alerts using an {@link AlertMapping}. The body is parsed strictly
 * with Jackson and then navigated with JSONPath (missing paths read as null).
 */
@Component
public class AlertMapper {

    public static final int MAX_ALERTS = 100;
    private static final int MAX_DEDUP_KEY_LENGTH = 200;
    private static final int MAX_SERVICES = 20;
    private static final int MAX_SERVICE_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Configuration PATHS = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    /**
     * @throws IllegalArgumentException when the body is empty, not a JSON object or has too many alerts
     */
    public List<MappedAlert> map(String body, AlertMapping mapping) {
        List<Object> items = extractItems(parse(body), mapping.itemsPath());
        if (items.size() > MAX_ALERTS) {
            throw new IllegalArgumentException("Máximo de " + MAX_ALERTS + " alertas por envio");
        }
        List<MappedAlert> alerts = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            alerts.add(mapItem(index, items.get(index), mapping));
        }
        return alerts;
    }

    private static Object parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Corpo vazio");
        }
        Object root;
        try {
            root = JSON.readValue(body, Object.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON inválido");
        }
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("O corpo deve ser um objeto JSON");
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> extractItems(Object root, String itemsPath) {
        if (itemsPath == null || itemsPath.isBlank()) {
            return List.of(root);
        }
        Object items = JsonPath.using(PATHS).parse(root).read(itemsPath);
        if (items == null) {
            return List.of(root);
        }
        return items instanceof List ? (List<Object>) items : List.of(items);
    }

    private static MappedAlert mapItem(int index, Object item, AlertMapping mapping) {
        if (!(item instanceof Map)) {
            return rejected(index, null, "alerta não é um objeto JSON");
        }
        DocumentContext alert = JsonPath.using(PATHS).parse(item);
        String title = text(alert, mapping.titlePath());
        String dedupKey = truncate(text(alert, mapping.dedupKeyPath()), MAX_DEDUP_KEY_LENGTH);
        if (title == null) {
            return rejected(index, dedupKey, "title ausente");
        }
        if (dedupKey == null) {
            return rejected(index, null, "dedupKey ausente");
        }
        String rawStatus = text(alert, mapping.statusPath());
        String status = rawStatus == null ? "firing" : mapping.statusMap().get(rawStatus.toLowerCase(Locale.ROOT));
        if (status == null) {
            return rejected(index, dedupKey, "status desconhecido: " + rawStatus);
        }
        String rawSeverity = text(alert, mapping.severityPath());
        String severity = rawSeverity == null ? null : mapping.severityMap().get(rawSeverity.toLowerCase(Locale.ROOT));
        return new MappedAlert(index, title, dedupKey,
                "resolved".equals(status) ? AlertStatus.RESOLVED : AlertStatus.FIRING,
                severity != null ? severity : mapping.defaultSeverity(),
                services(alert, mapping.servicePath()),
                truncate(text(alert, mapping.descriptionPath()), MAX_DESCRIPTION_LENGTH),
                null);
    }

    private static MappedAlert rejected(int index, String dedupKey, String reason) {
        return new MappedAlert(index, null, dedupKey, null, null, List.of(), null, reason);
    }

    private static String text(DocumentContext document, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return scalar(document.read(path));
    }

    private static String scalar(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static List<String> services(DocumentContext document, String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        Object value = document.read(path);
        List<?> raw = value instanceof List<?> list ? list : java.util.Collections.singletonList(value);
        Set<String> services = new LinkedHashSet<>();
        for (Object item : raw) {
            String service = truncate(scalar(item), MAX_SERVICE_LENGTH);
            if (service != null && services.size() < MAX_SERVICES) {
                services.add(service);
            }
        }
        return List.copyOf(services);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
