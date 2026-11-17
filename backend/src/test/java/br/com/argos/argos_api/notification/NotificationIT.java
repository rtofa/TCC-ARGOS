package br.com.argos.argos_api.notification;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationIT extends AbstractIntegrationTest {

    record CapturedRequest(String path, String body) {
    }

    private HttpServer webhookServer;
    private int serverPort;
    private final List<CapturedRequest> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startLocalWebhookServer() throws IOException {
        captured.clear();
        webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhookServer.createContext("/", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            captured.add(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    new String(bytes, StandardCharsets.UTF_8)));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        webhookServer.start();
        serverPort = webhookServer.getAddress().getPort();
    }

    @AfterEach
    void stopLocalWebhookServer() {
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
    }

    @Test
    void deliversSlackAndDiscordNotificationsWhenIncidentOpensAndRespectsSeverityFilter() throws Exception {
        String slackUrl = "http://127.0.0.1:" + serverPort + "/slack/T000/secret1234";
        String discordUrl = "http://127.0.0.1:" + serverPort + "/discord/999/token5678";

        // 1. Cria canal Slack (SEV2+) e Discord (SEV1 apenas)
        String slackJson = mockMvc.perform(post("/api/notifications/channels").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Plantão Slack","type":"SLACK","target":"%s","minSeverity":"SEV2","enabled":true}
                                """.formatted(slackUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetPreview").value("http://127.0.0.1/.../****1234"))
                .andReturn().getResponse().getContentAsString();
        String slackId = JsonPath.read(slackJson, "$.id");

        mockMvc.perform(post("/api/notifications/channels").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"War Room Discord","type":"DISCORD","target":"%s","minSeverity":"SEV1","enabled":true}
                                """.formatted(discordUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetPreview").value("http://127.0.0.1/.../****5678"));

        // 2. Abre um incidente SEV4 -> nenhum dos dois canais (SEV2+ e SEV1) deve receber
        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Aviso menor\",\"severity\":\"SEV4\",\"affectedServices\":[\"cart\"]}"))
                .andExpect(status().isCreated());
        Thread.sleep(250);
        assertThat(captured).isEmpty();

        // 3. Abre um incidente SEV1 -> ambos Slack e Discord devem receber de forma assíncrona
        mockMvc.perform(post("/api/incidents").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Queda total no checkout\",\"severity\":\"SEV1\",\"affectedServices\":[\"checkout\"]}"))
                .andExpect(status().isCreated());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(captured).hasSize(2));
        assertThat(captured)
                .anySatisfy(req -> {
                    assertThat(req.path()).isEqualTo("/slack/T000/secret1234");
                    assertThat(req.body()).contains("Queda total no checkout", "SEV1", "checkout");
                })
                .anySatisfy(req -> {
                    assertThat(req.path()).isEqualTo("/discord/999/token5678");
                    assertThat(req.body()).contains("Queda total no checkout", "SEV1", "Argos Alertas");
                });

        // 4. Testa disparo manual de teste no canal Slack
        mockMvc.perform(post("/api/notifications/channels/{id}/test", slackId).with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(true));
        assertThat(captured).hasSize(3);
    }

    @Test
    void enforcesRbacValidationAndMultiTenantIsolation() throws Exception {
        String slackUrl = "http://127.0.0.1:" + serverPort + "/slack/orgA";

        // Viewer não pode criar canal
        mockMvc.perform(post("/api/notifications/channels").with(asViewerA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"type\":\"SLACK\",\"target\":\"" + slackUrl + "\"}"))
                .andExpect(status().isForbidden());

        // Valida esquemas perigosos e e-mails inválidos
        mockMvc.perform(post("/api/notifications/channels").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"type\":\"SLACK\",\"target\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/notifications/channels").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BadEmail\",\"type\":\"EMAIL\",\"target\":\"nao-e-email\"}"))
                .andExpect(status().isBadRequest());

        // Cria canal válido de EMAIL na Org A
        String created = mockMvc.perform(post("/api/notifications/channels").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SRE Mail\",\"type\":\"EMAIL\",\"target\":\"sre@empresa.com.br\",\"minSeverity\":\"SEV2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetPreview").value("sre@empresa.com.br"))
                .andReturn().getResponse().getContentAsString();
        String channelId = JsonPath.read(created, "$.id");

        // Teste sem SMTP configurado reporta falha amigável sem quebrar a requisição
        mockMvc.perform(post("/api/notifications/channels/{id}/test", channelId).with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.message", containsString("SMTP")));

        // Org B não enxerga nem altera canal da Org A
        mockMvc.perform(get("/api/notifications/channels").with(asAdminB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(put("/api/notifications/channels/{id}", channelId).with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijack\",\"type\":\"EMAIL\",\"target\":\"hack@b.com\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/notifications/channels/{id}", channelId).with(asAdminB()))
                .andExpect(status().isNotFound());
    }
}
