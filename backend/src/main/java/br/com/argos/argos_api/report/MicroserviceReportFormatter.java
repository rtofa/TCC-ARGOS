package br.com.argos.argos_api.report;

import br.com.argos.argos_api.report.MicroserviceReport.DependencyRow;
import br.com.argos.argos_api.report.MicroserviceReport.DeployRow;
import br.com.argos.argos_api.report.MicroserviceReport.ErrorTraceRow;
import br.com.argos.argos_api.report.MicroserviceReport.IncidentRow;
import br.com.argos.argos_api.report.MicroserviceReport.LogRow;
import br.com.argos.argos_api.report.MicroserviceReport.MetricRow;
import br.com.argos.argos_api.report.MicroserviceReport.RedBucketRow;
import br.com.argos.argos_api.report.MicroserviceReport.ServiceRow;
import br.com.argos.argos_api.report.MicroserviceReport.Summary;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Serializes a {@link MicroserviceReport} into CSV, JSON, standalone HTML or Markdown downloads. */
final class MicroserviceReportFormatter {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();
    private static final DateTimeFormatter PT_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private MicroserviceReportFormatter() {
    }

    static byte[] format(MicroserviceReport report, String format, ZoneId zone) {
        return switch (format) {
            case "csv" -> toCsv(report, zone);
            case "json" -> toJson(report);
            case "html" -> toHtml(report, zone).getBytes(StandardCharsets.UTF_8);
            case "md" -> toMarkdown(report, zone).getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("Formato inválido '" + format + "'. Use: csv, json, html ou md.");
        };
    }

    private static byte[] toJson(MicroserviceReport report) {
        return JSON.writeValueAsBytes(report);
    }

    private static byte[] toCsv(MicroserviceReport report, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        Summary s = report.summary();
        String scope = report.serviceFilter() != null ? report.serviceFilter() : "Todos os microsserviços";

        sb.append("# RELATORIO DE MICROSSERVICOS - ARGOS\n");
        row(sb, "Gerado em", fmt(report.generatedAt(), zone), "Inicio", fmt(report.from(), zone),
                "Fim", fmt(report.to(), zone), "Escopo", scope);
        sb.append('\n');

        sb.append("# RESUMO EXECUTIVO\n");
        row(sb, "Servicos", s.totalServices(), "Requisicoes (Spans)", s.totalRequests(),
                "Erros (Spans)", s.totalErrors(), "Taxa de Erro (%)", s.errorRatePercent(),
                "Vazao (req/min)", s.requestsPerMinute(), "p50 (ms)", s.p50DurationMs(),
                "p95 (ms)", s.p95DurationMs(), "p99 (ms)", s.p99DurationMs(),
                "Logs Totais", s.totalLogs(), "Logs de Erro", s.totalErrorLogs(),
                "Series de Metricas", s.metricSeriesCount(), "Incidentes", s.incidentsCount(),
                "Deploys", s.deploysCount());
        sb.append('\n');

        sb.append("# SAUDE POR MICROSSERVICO\n");
        row(sb, "Servico", "Spans", "Spans com Erro", "Taxa de Erro (%)", "Latencia Media (ms)",
                "Latencia p95 (ms)", "Causa-Raiz", "Logs", "Logs de Erro", "Ultimo Sinal");
        for (ServiceRow r : report.services()) {
            row(sb, r.service(), r.spans(), r.errorSpans(), r.errorRatePercent(), r.avgDurationMs(),
                    r.p95DurationMs(), r.rootCause() ? "SIM" : "NAO", r.logs(), r.errorLogs(),
                    fmt(r.lastSeen(), zone));
        }
        sb.append('\n');

        sb.append("# DEPENDENCIAS ENTRE MICROSSERVICOS\n");
        row(sb, "Origem", "Destino", "Chamadas", "Erros", "Taxa de Erro (%)", "Latencia Media (ms)", "p95 (ms)");
        for (DependencyRow d : report.dependencies()) {
            row(sb, d.source(), d.target(), d.calls(), d.errors(), d.errorRatePercent(),
                    d.avgDurationMs(), d.p95DurationMs());
        }
        sb.append('\n');

        sb.append("# SERIE TEMPORAL RED\n");
        row(sb, "Janela", "Requisicoes", "Erros", "Req/min", "Taxa de Erro (%)", "p50 (ms)", "p95 (ms)", "p99 (ms)");
        for (RedBucketRow b : report.redSeries()) {
            row(sb, fmt(b.bucket(), zone), b.requests(), b.errors(), b.requestsPerMinute(),
                    b.errorRatePercent(), b.p50DurationMs(), b.p95DurationMs(), b.p99DurationMs());
        }
        sb.append('\n');

        sb.append("# METRICAS OTLP DOS MICROSSERVICOS\n");
        row(sb, "Metrica", "Servico", "Unidade", "Pontos Coletados", "Ultimo Registro");
        for (MetricRow m : report.metrics()) {
            row(sb, m.name(), m.service(), blankDash(m.unit()), m.points(), fmt(m.lastSeen(), zone));
        }
        sb.append('\n');

        sb.append("# TRACES COM ERRO\n");
        row(sb, "Trace ID", "Inicio", "Duracao (ms)", "Servico Raiz", "Operacao", "Spans", "Erros", "Servicos Envolvidos");
        for (ErrorTraceRow t : report.errorTraces()) {
            row(sb, t.traceId(), fmt(t.startTime(), zone), t.durationMs(), t.rootService(), t.rootName(),
                    t.spanCount(), t.errorCount(), String.join(" | ", t.services()));
        }
        sb.append('\n');

        sb.append("# LOGS DE ALERTA E ERRO (MASCARADOS LGPD)\n");
        row(sb, "Ocorrencia", "Servico", "Severidade", "Trace ID", "Mensagem");
        for (LogRow l : report.errorLogs()) {
            row(sb, fmt(l.occurredAt(), zone), l.service(), l.severity(), blankDash(l.traceId()), l.body());
        }
        sb.append('\n');

        sb.append("# INCIDENTES CORRELACIONADOS\n");
        row(sb, "ID", "Titulo", "Severidade", "Status", "Origem", "Servicos Afetados", "Aberto em", "Resolvido em");
        for (IncidentRow i : report.incidents()) {
            row(sb, i.id(), i.title(), i.severity(), i.status(), i.source(),
                    String.join(" | ", i.affectedServices()), fmt(i.openedAt(), zone), fmt(i.resolvedAt(), zone));
        }
        sb.append('\n');

        sb.append("# DEPLOYS NO PERIODO\n");
        row(sb, "Servico", "Versao", "Ambiente", "Autor", "Publicado em");
        for (DeployRow d : report.deploys()) {
            row(sb, d.service(), d.version(), d.environment(), blankDash(d.author()), fmt(d.deployedAt(), zone));
        }

        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + content.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(content, 0, result, UTF8_BOM.length, content.length);
        return result;
    }

    private static String toMarkdown(MicroserviceReport report, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        Summary s = report.summary();
        String scope = report.serviceFilter() != null ? report.serviceFilter() : "Todos os microsserviços";

        sb.append("# Relatório de Microsserviços — ARGOS\n\n");
        sb.append("- **Escopo:** ").append(scope).append('\n');
        sb.append("- **Janela analisada:** ").append(fmt(report.from(), zone)).append(" até ").append(fmt(report.to(), zone)).append('\n');
        sb.append("- **Gerado em:** ").append(fmt(report.generatedAt(), zone)).append("\n\n");

        sb.append("## Resumo Executivo\n\n");
        sb.append("| Indicador | Valor |\n|---|---:|\n");
        sb.append("| Serviços ativos | ").append(s.totalServices()).append(" |\n");
        sb.append("| Requisições (spans) | ").append(s.totalRequests()).append(" |\n");
        sb.append("| Spans com erro | ").append(s.totalErrors()).append(" (").append(s.errorRatePercent()).append("%) |\n");
        sb.append("| Vazão média | ").append(s.requestsPerMinute()).append(" req/min |\n");
        sb.append("| Latência (p50 / p95 / p99) | ").append(s.p50DurationMs()).append(" / ")
                .append(s.p95DurationMs()).append(" / ").append(s.p99DurationMs()).append(" ms |\n");
        sb.append("| Logs (totais / erros) | ").append(s.totalLogs()).append(" / ").append(s.totalErrorLogs()).append(" |\n");
        sb.append("| Séries de métricas OTLP | ").append(s.metricSeriesCount()).append(" |\n");
        sb.append("| Incidentes / Deploys no período | ").append(s.incidentsCount()).append(" / ").append(s.deploysCount()).append(" |\n\n");

        sb.append("## Saúde por Microsserviço\n\n");
        if (report.services().isEmpty()) {
            sb.append("_Nenhum sinal recebido no período._\n\n");
        } else {
            sb.append("| Serviço | Spans | Erros | Taxa (%) | Média (ms) | p95 (ms) | Logs | Origem de falha |\n");
            sb.append("|---|---:|---:|---:|---:|---:|---:|---|\n");
            for (ServiceRow r : report.services()) {
                sb.append("| ").append(mdCell(r.service())).append(" | ").append(r.spans()).append(" | ")
                        .append(r.errorSpans()).append(" | ").append(r.errorRatePercent()).append("% | ")
                        .append(r.avgDurationMs()).append(" | ").append(r.p95DurationMs()).append(" | ")
                        .append(r.logs()).append(" | ").append(r.rootCause() ? "Sim" : "Não").append(" |\n");
            }
            sb.append('\n');
        }

        if (!report.dependencies().isEmpty()) {
            sb.append("## Dependências entre Serviços\n\n");
            sb.append("| Origem | Destino | Chamadas | Erros | Taxa (%) | p95 (ms) |\n");
            sb.append("|---|---|---:|---:|---:|---:|\n");
            for (DependencyRow d : report.dependencies()) {
                sb.append("| ").append(mdCell(d.source())).append(" | ").append(mdCell(d.target())).append(" | ")
                        .append(d.calls()).append(" | ").append(d.errors()).append(" | ")
                        .append(d.errorRatePercent()).append("% | ").append(d.p95DurationMs()).append(" |\n");
            }
            sb.append('\n');
        }

        if (!report.metrics().isEmpty()) {
            sb.append("## Métricas OTLP Coletadas\n\n");
            sb.append("| Métrica | Serviço | Unidade | Pontos | Último registro |\n");
            sb.append("|---|---|---|---:|---|\n");
            for (MetricRow m : report.metrics()) {
                sb.append("| `").append(mdCell(m.name())).append("` | ").append(mdCell(m.service())).append(" | ")
                        .append(mdCell(blankDash(m.unit()))).append(" | ").append(m.points()).append(" | ")
                        .append(fmt(m.lastSeen(), zone)).append(" |\n");
            }
            sb.append('\n');
        }

        if (!report.errorTraces().isEmpty()) {
            sb.append("## Traces com Erro\n\n");
            sb.append("| Trace ID | Serviço Raiz | Operação | Duração (ms) | Erros | Início |\n");
            sb.append("|---|---|---|---:|---:|---|\n");
            for (ErrorTraceRow t : report.errorTraces()) {
                sb.append("| `").append(mdCell(t.traceId())).append("` | ").append(mdCell(t.rootService())).append(" | ")
                        .append(mdCell(t.rootName())).append(" | ").append(t.durationMs()).append(" | ")
                        .append(t.errorCount()).append(" | ").append(fmt(t.startTime(), zone)).append(" |\n");
            }
            sb.append('\n');
        }

        if (!report.incidents().isEmpty()) {
            sb.append("## Incidentes Correlacionados\n\n");
            sb.append("| Título | Severidade | Status | Serviços | Abertura |\n");
            sb.append("|---|---|---|---|---|\n");
            for (IncidentRow i : report.incidents()) {
                sb.append("| ").append(mdCell(i.title())).append(" | ").append(i.severity()).append(" | ")
                        .append(i.status()).append(" | ").append(mdCell(String.join(", ", i.affectedServices())))
                        .append(" | ").append(fmt(i.openedAt(), zone)).append(" |\n");
            }
            sb.append('\n');
        }

        if (!report.errorLogs().isEmpty()) {
            sb.append("## Logs de Alerta/Erro (Mascarados LGPD)\n\n");
            sb.append("| Horário | Serviço | Nível | Mensagem |\n|---|---|---|---|\n");
            for (LogRow l : report.errorLogs()) {
                sb.append("| ").append(fmt(l.occurredAt(), zone)).append(" | ").append(mdCell(l.service())).append(" | ")
                        .append(l.severity()).append(" | ").append(mdCell(truncate(l.body(), 240))).append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String toHtml(MicroserviceReport report, ZoneId zone) {
        Summary s = report.summary();
        String scope = report.serviceFilter() != null ? report.serviceFilter() : "Todos os microsserviços";
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html>
                <html lang="pt-BR">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Relatório de Microsserviços — ARGOS</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 28px; color: #0f172a; background: #f8fafc; line-height: 1.45; }
                    .container { max-width: 1080px; margin: 0 auto; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
                    .hero { background: #0a1f3d; color: #fff; padding: 24px 28px; }
                    .hero h1 { margin: 0 0 6px; font-size: 22px; }
                    .hero p { margin: 0; font-size: 13px; color: #cbd5e1; }
                    .section { padding: 20px 28px; border-top: 1px solid #e2e8f0; }
                    .section h2 { margin: 0 0 12px; font-size: 15px; text-transform: uppercase; letter-spacing: 0.04em; color: #334155; }
                    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
                    .kpi { padding: 12px 14px; border: 1px solid #e2e8f0; border-radius: 8px; border-top: 3px solid #2563eb; background: #fff; }
                    .kpi small { display: block; color: #64748b; font-size: 11px; text-transform: uppercase; }
                    .kpi strong { font-size: 22px; color: #0f172a; }
                    table { width: 100%; border-collapse: collapse; font-size: 13px; }
                    th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: #64748b; padding: 8px 10px; border-bottom: 1px solid #cbd5e1; }
                    td { padding: 8px 10px; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
                    .num { text-align: right; font-variant-numeric: tabular-nums; }
                    .badge { display: inline-block; padding: 2px 7px; border-radius: 999px; font-size: 11px; font-weight: 600; background: #eff6ff; color: #1d4ed8; }
                    .badge-err { background: #fef2f2; color: #b91c1c; }
                    .mono { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
                    @media print { body { padding: 0; background: #fff; } .container { border: 0; } }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <header class="hero">
                      <h1>ARGOS — Relatório Operacional de Microsserviços</h1>
                      <p>Escopo: <strong>""").append(esc(scope)).append("</strong> · Período: ")
                .append(esc(fmt(report.from(), zone))).append(" até ").append(esc(fmt(report.to(), zone)))
                .append(" · Gerado em ").append(esc(fmt(report.generatedAt(), zone))).append("</p></header>\n");

        sb.append("<section class=\"section\"><h2>Resumo Executivo (OpenTelemetry)</h2><div class=\"kpis\">\n");
        kpi(sb, "Serviços ativos", String.valueOf(s.totalServices()));
        kpi(sb, "Requisições (Spans)", String.valueOf(s.totalRequests()));
        kpi(sb, "Taxa de erro", s.errorRatePercent() + "% (" + s.totalErrors() + ")");
        kpi(sb, "Latência p95", s.p95DurationMs() + " ms");
        kpi(sb, "Logs de erro", s.totalErrorLogs() + " / " + s.totalLogs());
        kpi(sb, "Incidentes / Deploys", s.incidentsCount() + " / " + s.deploysCount());
        sb.append("</div></section>\n");

        sb.append("<section class=\"section\"><h2>Saúde por Microsserviço</h2>");
        if (report.services().isEmpty()) {
            sb.append("<p>Nenhum serviço reportou telemetria neste período.</p>");
        } else {
            sb.append("<table><thead><tr><th>Serviço</th><th class=\"num\">Spans</th><th class=\"num\">Erros</th><th class=\"num\">Taxa</th><th class=\"num\">Média</th><th class=\"num\">p95</th><th class=\"num\">Logs</th><th>Status</th></tr></thead><tbody>");
            for (ServiceRow r : report.services()) {
                sb.append("<tr><td><strong>").append(esc(r.service())).append("</strong></td>")
                        .append("<td class=\"num\">").append(r.spans()).append("</td>")
                        .append("<td class=\"num\">").append(r.errorSpans()).append("</td>")
                        .append("<td class=\"num\">").append(r.errorRatePercent()).append("%</td>")
                        .append("<td class=\"num\">").append(r.avgDurationMs()).append(" ms</td>")
                        .append("<td class=\"num\">").append(r.p95DurationMs()).append(" ms</td>")
                        .append("<td class=\"num\">").append(r.logs()).append("</td>")
                        .append("<td>").append(r.rootCause()
                                ? "<span class=\"badge badge-err\">Origem de falha</span>"
                                : "<span class=\"badge\">Monitorado</span>")
                        .append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</section>\n");

        if (!report.dependencies().isEmpty()) {
            sb.append("<section class=\"section\"><h2>Dependências entre Microsserviços</h2><table><thead><tr><th>Origem</th><th>Destino</th><th class=\"num\">Chamadas</th><th class=\"num\">Erros</th><th class=\"num\">Taxa</th><th class=\"num\">p95</th></tr></thead><tbody>");
            for (DependencyRow d : report.dependencies()) {
                sb.append("<tr><td>").append(esc(d.source())).append("</td><td>").append(esc(d.target())).append("</td>")
                        .append("<td class=\"num\">").append(d.calls()).append("</td>")
                        .append("<td class=\"num\">").append(d.errors()).append("</td>")
                        .append("<td class=\"num\">").append(d.errorRatePercent()).append("%</td>")
                        .append("<td class=\"num\">").append(d.p95DurationMs()).append(" ms</td></tr>");
            }
            sb.append("</tbody></table></section>\n");
        }

        if (!report.metrics().isEmpty()) {
            sb.append("<section class=\"section\"><h2>Métricas OTLP Recebidas</h2><table><thead><tr><th>Métrica</th><th>Serviço</th><th>Unidade</th><th class=\"num\">Pontos</th><th>Último sinal</th></tr></thead><tbody>");
            for (MetricRow m : report.metrics()) {
                sb.append("<tr><td class=\"mono\">").append(esc(m.name())).append("</td><td>").append(esc(m.service())).append("</td>")
                        .append("<td>").append(esc(blankDash(m.unit()))).append("</td>")
                        .append("<td class=\"num\">").append(m.points()).append("</td>")
                        .append("<td>").append(esc(fmt(m.lastSeen(), zone))).append("</td></tr>");
            }
            sb.append("</tbody></table></section>\n");
        }

        if (!report.errorTraces().isEmpty()) {
            sb.append("<section class=\"section\"><h2>Traces com Erro</h2><table><thead><tr><th>Trace ID</th><th>Serviço Raiz</th><th>Operação</th><th class=\"num\">Duração</th><th class=\"num\">Erros</th><th>Início</th></tr></thead><tbody>");
            for (ErrorTraceRow t : report.errorTraces()) {
                sb.append("<tr><td class=\"mono\">").append(esc(t.traceId())).append("</td><td>").append(esc(t.rootService())).append("</td>")
                        .append("<td>").append(esc(t.rootName())).append("</td>")
                        .append("<td class=\"num\">").append(t.durationMs()).append(" ms</td>")
                        .append("<td class=\"num\">").append(t.errorCount()).append("</td>")
                        .append("<td>").append(esc(fmt(t.startTime(), zone))).append("</td></tr>");
            }
            sb.append("</tbody></table></section>\n");
        }

        if (!report.incidents().isEmpty()) {
            sb.append("<section class=\"section\"><h2>Incidentes Correlacionados</h2><table><thead><tr><th>Incidente</th><th>Severidade</th><th>Status</th><th>Serviços</th><th>Abertura</th></tr></thead><tbody>");
            for (IncidentRow i : report.incidents()) {
                sb.append("<tr><td>").append(esc(i.title())).append("</td><td>").append(esc(i.severity())).append("</td>")
                        .append("<td>").append(esc(i.status())).append("</td>")
                        .append("<td>").append(esc(String.join(", ", i.affectedServices()))).append("</td>")
                        .append("<td>").append(esc(fmt(i.openedAt(), zone))).append("</td></tr>");
            }
            sb.append("</tbody></table></section>\n");
        }

        if (!report.errorLogs().isEmpty()) {
            sb.append("<section class=\"section\"><h2>Logs de Alerta/Erro (Mascarados LGPD)</h2><table><thead><tr><th>Horário</th><th>Serviço</th><th>Nível</th><th>Mensagem</th></tr></thead><tbody>");
            for (LogRow l : report.errorLogs()) {
                sb.append("<tr><td>").append(esc(fmt(l.occurredAt(), zone))).append("</td><td>").append(esc(l.service())).append("</td>")
                        .append("<td>").append(esc(l.severity())).append("</td><td class=\"mono\">")
                        .append(esc(truncate(l.body(), 280))).append("</td></tr>");
            }
            sb.append("</tbody></table></section>\n");
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static void kpi(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"kpi\"><small>").append(esc(label)).append("</small><strong>")
                .append(esc(value)).append("</strong></div>\n");
    }

    private static void row(StringBuilder sb, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(values[i]));
        }
        sb.append('\n');
    }

    private static String csvCell(Object raw) {
        if (raw == null) return "";
        String value = String.valueOf(raw).replace('\r', ' ').replace('\n', ' ');
        // Neutralize spreadsheet formula triggers before quoting.
        if (!value.isEmpty() && ("=+-@\t".indexOf(value.charAt(0)) >= 0)) {
            value = "'" + value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String mdCell(String raw) {
        if (raw == null) return "";
        return raw.replace("|", "\\|").replace('\r', ' ').replace('\n', ' ');
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String fmt(Instant instant, ZoneId zone) {
        return instant == null ? "—" : PT_TIME.format(instant.atZone(zone));
    }

    private static String blankDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    static String normalizeFormat(String format) {
        String normalized = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!List.of("csv", "json", "html", "md").contains(normalized)) {
            throw new IllegalArgumentException("Formato inválido '" + format + "'. Use: csv, json, html ou md.");
        }
        return normalized;
    }
}
