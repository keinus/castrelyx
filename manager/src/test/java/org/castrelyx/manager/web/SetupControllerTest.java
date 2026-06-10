package org.castrelyx.manager.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
class SetupControllerTest {
  @Autowired
  MockMvc mockMvc;

  @Autowired
  LocalAuthProvider authProvider;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from user_sessions");
    jdbcTemplate.update("delete from alert_instances");
    jdbcTemplate.update("delete from alert_rules");
    jdbcTemplate.update("delete from asset_merge_candidates");
    jdbcTemplate.update("delete from asset_source_bindings");
    jdbcTemplate.update("delete from assets");
    jdbcTemplate.update("delete from users");
  }

  @Test
  void setupCreatesFirstAdminOnceThenLoginSessionCookieWorks() throws Exception {
    mockMvc.perform(get("/api/setup/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.required", is(true)));

    mockMvc.perform(post("/api/setup/admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"admin","password":"correct-password","displayName":"Administrator"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username", is("admin")))
        .andExpect(jsonPath("$.role", is("ADMIN")));

    mockMvc.perform(get("/api/setup/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.required", is(false)));

    mockMvc.perform(post("/api/setup/admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"second","password":"password","displayName":"Second"}
                """))
        .andExpect(status().isConflict());

    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"admin","password":"correct-password"}
                """))
        .andExpect(status().isOk())
        .andExpect(cookie().exists(AuthController.SESSION_COOKIE))
        .andReturn();

    Cookie cookie = login.getResponse().getCookie(AuthController.SESSION_COOKIE);
    mockMvc.perform(get("/api/auth/session").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated", is(true)))
        .andExpect(jsonPath("$.user.role", is("ADMIN")));
  }

  @Test
  void viewerCanReadButCannotMutateAssetsWhileOperatorCan() throws Exception {
    authProvider.createLocalUser("viewer", "password", "Viewer", Role.VIEWER);
    authProvider.createLocalUser("operator", "password", "Operator", Role.OPERATOR);

    Cookie viewerCookie = loginCookie("viewer");
    mockMvc.perform(get("/api/assets").cookie(viewerCookie))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/assets")
            .cookie(viewerCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"core-fw","assetType":"FIREWALL","managementIp":"10.0.0.1"}
                """))
        .andExpect(status().isForbidden());

    Cookie operatorCookie = loginCookie("operator");
    mockMvc.perform(post("/api/assets")
            .cookie(operatorCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"core-fw","assetType":"FIREWALL","managementIp":"10.0.0.1"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name", is("core-fw")));
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
