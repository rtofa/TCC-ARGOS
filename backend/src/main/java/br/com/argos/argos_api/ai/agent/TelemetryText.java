package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.telemetry.TelemetryViews.RedBucket;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/**
 * Compact text renderings of telemetry for the model. Context engineering: numbers already compared and rounded,
 * one line per fact, so the agent spends its tokens reasoning instead of parsing raw series.
 */
final class TelemetryText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm'Z'").withZone(ZoneOffset.UTC);

    private TelemetryText() {
    }

    static String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
    }

    static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        return value >= 100 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** "12 min antes" / "3 min depois" of the incident opening. */
    static String relative(Instant instant, Instant opening) {
        long minutes = Duration.between(instant, opening).toMinutes();
        if (minutes == 0) {
            return "no momento da abertura";
        }
        return minutes > 0 ? minutes + " min antes da abertura" : (-minutes) + " min depois da abertura";
    }

    static String health(String service, RedSeries before, RedSeries during) {
        StringBuilder out = new StringBuilder();
        out.append("Serviço ").append(service).append('\n');
        out.append(line("antes  ", before)).append('\n');
        out.append(line("durante", during));
        if (before.totalRequests() > 0 && during.totalRequests() > 0) {
            out.append('\n').append("variação: erros ").append(delta(before.errorRatePercent(), during.errorRatePercent(), " p.p."))
                    .append(", p95 ").append(ratio(before.p95DurationMs(), during.p95DurationMs()))
                    .append(", tráfego ").append(ratio(before.requestsPerMinute(), during.requestsPerMinute()));
        }
        during.buckets().stream()
                .max(Comparator.comparingDouble(RedBucket::errorRatePercent))
                .filter(peak -> peak.errors() > 0)
                .ifPresent(peak -> out.append('\n').append("pico de erros: ").append(number(peak.errorRatePercent()))
                        .append("% às ").append(time(peak.bucket())));
        return out.toString();
    }

    private static String line(String label, RedSeries series) {
        if (series.totalRequests() == 0) {
            return label + ": sem requisições registradas";
        }
        return label + ": " + number(series.requestsPerMinute()) + " req/min, erros " + number(series.errorRatePercent())
                + "% (" + series.totalErrors() + " de " + series.totalRequests() + "), p50 " + number(series.p50DurationMs())
                + " ms, p95 " + number(series.p95DurationMs()) + " ms, p99 " + number(series.p99DurationMs()) + " ms";
    }

    private static String delta(double before, double after, String unit) {
        double diff = after - before;
        return (diff >= 0 ? "+" : "") + number(diff) + unit;
    }

    private static String ratio(double before, double after) {
        if (before <= 0) {
            return after > 0 ? "surgiu (antes zero)" : "estável";
        }
        return "x" + number(after / before);
    }
}
