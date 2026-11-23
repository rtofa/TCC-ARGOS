package br.com.argos.argos_api.report;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
public class MicroserviceReportController {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final MicroserviceReportService service;

    public MicroserviceReportController(MicroserviceReportService service) {
        this.service = service;
    }

    @GetMapping("/microservices")
    public MicroserviceReport preview(
            @RequestParam(required = false) String serviceFilter,
            @RequestParam(required = false, name = "service") String serviceParam,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        String resolvedService = serviceParam != null ? serviceParam : serviceFilter;
        return service.generate(resolvedService, from, to);
    }

    @GetMapping("/microservices/download")
    public ResponseEntity<byte[]> download(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false, name = "service") String serviceParam,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "America/Sao_Paulo") String tz) {
        String normalized = MicroserviceReportFormatter.normalizeFormat(format);
        ZoneId zone;
        try {
            zone = ZoneId.of(tz == null || tz.isBlank() ? "America/Sao_Paulo" : tz.trim());
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Fuso horário inválido: " + tz);
        }

        MicroserviceReport report = service.generate(serviceParam, from, to);
        byte[] payload = MicroserviceReportFormatter.format(report, normalized, zone);

        String slug = report.serviceFilter() != null
                ? "-" + report.serviceFilter().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "";
        String filename = "argos-relatorio-microsservicos" + slug + "-"
                + FILE_STAMP.format(report.generatedAt().atZone(zone)) + "." + normalized;

        MediaType mediaType = switch (normalized) {
            case "csv" -> new MediaType("text", "csv", StandardCharsets.UTF_8);
            case "json" -> new MediaType("application", "json", StandardCharsets.UTF_8);
            case "html" -> new MediaType("text", "html", StandardCharsets.UTF_8);
            case "md" -> new MediaType("text", "markdown", StandardCharsets.UTF_8);
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(payload);
    }
}
