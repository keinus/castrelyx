package org.castrelyx.manager.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CastrelSignIntegrationControllerTest {
  @Autowired
  MockMvc mockMvc;

  @Autowired
  LocalAuthProvider authProvider;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from user_sessions");
    jdbcTemplate.update("delete from integration_configs");
    jdbcTemplate.update("delete from users");
    authProvider.createLocalUser("admin", "password", "Administrator", Role.ADMIN);
    authProvider.createLocalUser("operator", "password", "Operator", Role.OPERATOR);
  }

  @Test
  void adminUpdatesCastrelSignConfigAndResponseMasksSecret() throws Exception {
    Cookie admin = loginCookie("admin");

    mockMvc.perform(put("/api/integrations/castrelsign")
            .cookie(admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"baseUrl":"https://castrelsign:8443","adminToken":"super-secret-token","enabled":true}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serviceName", is("castrelsign")))
        .andExpect(jsonPath("$.baseUrl", is("https://castrelsign:8443")))
        .andExpect(jsonPath("$.secret.configured", is(true)))
        .andExpect(jsonPath("$.secret.masked", is("********")))
        .andExpect(jsonPath("$.enabled", is(true)));

    String encrypted = jdbcTemplate.queryForObject(
        "select encrypted_secret from integration_configs where service_name = 'castrelsign'",
        String.class);
    org.assertj.core.api.Assertions.assertThat(encrypted).startsWith("v1:").doesNotContain("super-secret-token");

    mockMvc.perform(get("/api/integrations/castrelsign").cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secret.configured", is(true)))
        .andExpect(jsonPath("$.secret.masked", is("********")));
  }

  @Test
  void operatorCannotChangeCastrelSignSecret() throws Exception {
    Cookie operator = loginCookie("operator");

    mockMvc.perform(put("/api/integrations/castrelsign")
            .cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"baseUrl":"https://castrelsign:8443","adminToken":"super-secret-token","enabled":true}
                """))
        .andExpect(status().isForbidden());
  }

  private Cookie loginCookie(String username) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"password"}
                """.formatted(username)))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie(AuthController.SESSION_COOKIE);
  }
}
