package br.com.argos.argos_api.identity;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Probes used by the AWS load balancer and ECS: open without login; nothing else from Actuator is exposed. */
class HealthEndpointIT extends AbstractIntegrationTest {

    @Test
    void probesAnswerWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env").with(asAdminA())).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans").with(asAdminA())).andExpect(status().isNotFound());
    }
}
