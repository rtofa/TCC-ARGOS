package br.com.argos.argos_api.helpdesk;

import br.com.argos.argos_api.helpdesk.agent.SupportAgent;
import br.com.argos.argos_api.helpdesk.model.Outcome;
import br.com.argos.argos_api.helpdesk.model.SupportAnswer;
import br.com.argos.argos_api.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * While the model is still thinking (or waiting out a provider rate limit) the stream sends nothing, and proxies close
 * idle connections: CloudFront after 60 s, the ALB after its idle timeout. The heartbeat keeps the stream alive with
 * SSE comments, which clients ignore.
 */
@TestPropertySource(properties = "argos.helpdesk.execution.heartbeat-interval=100ms")
class SupportStreamHeartbeatIT extends AbstractIntegrationTest {

    @MockitoBean
    private SupportAgent agent;

    @Test
    void aSlowAnswerKeepsTheConnectionAliveWithHeartbeatComments() throws Exception {
        when(agent.answer(any(), any())).thenAnswer(call -> {
            Thread.sleep(550); // the model is "thinking": no stage, no token
            return new SupportAnswer("a1", Outcome.ANSWERED, "Resposta pronta.", List.of(), null);
        });

        MvcResult result = mockMvc.perform(post("/api/support/ask/stream").with(asEditorA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Como abro um incidente?\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);

        String events = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int heartbeats = events.split(":keep-alive", -1).length - 1;
        assertThat(heartbeats).as("SSE comments while the answer was not ready").isGreaterThanOrEqualTo(3);
        assertThat(events).contains("event:done", "Resposta pronta.");
        assertThat(events.lastIndexOf(":keep-alive")).isLessThan(events.indexOf("event:done"));
    }
}
