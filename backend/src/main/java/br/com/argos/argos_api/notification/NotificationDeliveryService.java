package br.com.argos.argos_api.notification;

import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.notification.domain.NotificationChannel;
import br.com.argos.argos_api.notification.domain.NotificationChannelType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formata e entrega notificações de abertura de incidente via Slack Webhook, Discord Webhook ou SMTP (E-mail).
 */
@Service
class NotificationDeliveryService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    private final JsonMapper json;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String consoleBaseUrl;
    private final String mailFrom;
    private final String mailHost;

    NotificationDeliveryService(
            JsonMapper json,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${argos.notifications.console-base-url:http://localhost:3030}") String consoleBaseUrl,
            @Value("${argos.notifications.mail-from:noreply@argos.local}") String mailFrom,
            @Value("${spring.mail.host:}") String mailHost) {
        this.json = json;
        this.mailSenderProvider = mailSenderProvider;
        this.consoleBaseUrl = consoleBaseUrl.endsWith("/")
                ? consoleBaseUrl.substring(0, consoleBaseUrl.length() - 1)
                : consoleBaseUrl;
        this.mailFrom = mailFrom;
        this.mailHost = mailHost == null ? "" : mailHost.trim();
    }

    void deliver(NotificationChannel channel, IncidentSnapshot incident, boolean testMode) throws Exception {
        if (channel.getType() == NotificationChannelType.SLACK) {
            postWebhook(channel.getTarget(), slackPayload(incident, testMode));
        } else if (channel.getType() == NotificationChannelType.DISCORD) {
            postWebhook(channel.getTarget(), discordPayload(incident, testMode));
        } else if (channel.getType() == NotificationChannelType.EMAIL) {
            sendEmail(channel.getTarget(), incident, testMode);
        }
    }

    private void postWebhook(String webhookUrl, Map<String, Object> payload) throws Exception {
        String body = json.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Argos-Incident-Notifier/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Webhook retornou HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body(), 200));
        }
    }

    private void sendEmail(String targets, IncidentSnapshot incident, boolean testMode) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || mailHost.isBlank()) {
            throw new IllegalStateException(
                    "Servidor SMTP não configurado (defina SMTP_HOST para envio real de e-mails).");
        }
        String[] recipients = targets.split(",");
        for (int i = 0; i < recipients.length; i++) {
            recipients[i] = recipients[i].trim();
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipients);
        message.setSubject((testMode ? "[TESTE] " : "") + "[Argos][" + incident.severity() + "] " + incident.title());
        message.setText(plainTextBody(incident, testMode));
        sender.send(message);
    }

    Map<String, Object> slackPayload(IncidentSnapshot incident, boolean testMode) {
        String url = incidentUrl(incident);
        String services = formatServices(incident);
        String prefix = testMode ? "[TESTE DE INTEGRAÇÃO] " : "";
        String summary = prefix + "*[" + incident.severity() + "] " + incident.title() + "*";

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", hexColor(incident.severity()));
        attachment.put("title", prefix + "[" + incident.severity() + "] " + incident.title());
        attachment.put("title_link", url);
        attachment.put("text", incident.description() != null && !incident.description().isBlank()
                ? abbreviate(incident.description(), 300)
                : "Novo incidente registrado na plataforma Argos.");
        attachment.put("fields", List.of(
                Map.of("title", "Severidade", "value", incident.severity(), "short", true),
                Map.of("title", "Origem", "value", incident.source(), "short", true),
                Map.of("title", "Serviços afetados", "value", services, "short", false)
        ));
        attachment.put("footer", "Argos · Observabilidade & Resposta a Incidentes");

        return Map.of(
                "text", summary + " — " + url,
                "attachments", List.of(attachment)
        );
    }

    Map<String, Object> discordPayload(IncidentSnapshot incident, boolean testMode) {
        String url = incidentUrl(incident);
        String services = formatServices(incident);
        String prefix = testMode ? "[TESTE] " : "";

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", prefix + "[" + incident.severity() + "] " + incident.title());
        embed.put("url", url);
        embed.put("description", incident.description() != null && !incident.description().isBlank()
                ? abbreviate(incident.description(), 300)
                : "Novo incidente aberto e disponível para investigação no console do Argos.");
        embed.put("color", decimalColor(incident.severity()));
        embed.put("fields", List.of(
                Map.of("name", "Severidade", "value", incident.severity(), "inline", true),
                Map.of("name", "Origem", "value", incident.source(), "inline", true),
                Map.of("name", "Serviços afetados", "value", services, "inline", false)
        ));
        embed.put("timestamp", incident.openedAt().toString());
        embed.put("footer", Map.of("text", "Argos · Gestão de Incidentes"));

        return Map.of(
                "username", "Argos Alertas",
                "content", prefix + "Incidente aberto: **" + incident.title() + "** (" + incident.severity() + ")",
                "embeds", List.of(embed)
        );
    }

    private String plainTextBody(IncidentSnapshot incident, boolean testMode) {
        return (testMode ? "=== MENSAGEM DE TESTE DO ARGOS ===\n\n" : "")
                + "Incidente aberto: " + incident.title() + "\n"
                + "Severidade: " + incident.severity() + "\n"
                + "Origem: " + incident.source() + "\n"
                + "Serviços afetados: " + formatServices(incident) + "\n"
                + "Aberto em: " + incident.openedAt() + "\n"
                + (incident.description() != null && !incident.description().isBlank()
                        ? "\nDescrição:\n" + incident.description() + "\n" : "")
                + "\nAcesse no console: " + incidentUrl(incident) + "\n";
    }

    private String incidentUrl(IncidentSnapshot incident) {
        return consoleBaseUrl + "/incidents/" + incident.id();
    }

    private static String formatServices(IncidentSnapshot incident) {
        return incident.affectedServices() == null || incident.affectedServices().isEmpty()
                ? "Não especificado"
                : String.join(", ", incident.affectedServices());
    }

    private static String hexColor(String severity) {
        return switch (severity) {
            case "SEV1" -> "#dc2626";
            case "SEV2" -> "#ea580c";
            case "SEV3" -> "#ca8a04";
            default -> "#64748b";
        };
    }

    private static int decimalColor(String severity) {
        return switch (severity) {
            case "SEV1" -> 0xDC2626;
            case "SEV2" -> 0xEA580C;
            case "SEV3" -> 0xCA8A04;
            default -> 0x64748B;
        };
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
