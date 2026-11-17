package br.com.argos.argos_api.identity;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserManagementIT extends AbstractIntegrationTest {

    @Test
    void adminCreatesUpdatesRoleResetsPasswordAndDeactivatesUsers() throws Exception {
        // Admin A lista os 4 usuários iniciais da Org A
        mockMvc.perform(get("/api/users").with(asAdminA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));

        // Non-admins não podem acessar
        mockMvc.perform(get("/api/users").with(asEditorA())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(asViewerA())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(asExecutiveA())).andExpect(status().isForbidden());

        // Admin A cria novo membro EDITOR
        String createdJson = mockMvc.perform(post("/api/users").with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Carla SRE","email":"carla@lojaa.com","password":"Senha@123","role":"EDITOR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Carla SRE"))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        String carlaId = JsonPath.read(createdJson, "$.id");

        // Novo usuário faz login com sucesso e registra lastLoginAt
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carla@lojaa.com\",\"password\":\"Senha@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()));

        // Admin A altera papel para EXECUTIVE e redefine a senha
        mockMvc.perform(patch("/api/users/{id}/role", carlaId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EXECUTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EXECUTIVE"));

        mockMvc.perform(post("/api/users/{id}/reset-password", carlaId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"NovaSenha@456\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carla@lojaa.com\",\"password\":\"NovaSenha@456\"}"))
                .andExpect(status().isOk());

        // Admin A desativa o usuário e o login passa a ser bloqueado (403 Forbidden)
        mockMvc.perform(patch("/api/users/{id}/status", carlaId).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carla@lojaa.com\",\"password\":\"NovaSenha@456\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesSelfProtectionLastAdminAndTenantIsolation() throws Exception {
        // Admin A não pode desativar a si mesmo nem rebaixar o único ADMIN ativo
        mockMvc.perform(patch("/api/users/{id}/status", adminA).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/users/{id}/role", adminA).with(asAdminA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());

        // Admin B não enxerga nem altera usuários da Org A
        mockMvc.perform(get("/api/users").with(asAdminB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(patch("/api/users/{id}/role", editorA).with(asAdminB())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound());
    }
}
