package org.castrelyx.manager.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.HttpClientErrorException;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AssetFileControllerTest {
  @Autowired
  MockMvc mockMvc;

  @Autowired
  LocalAuthProvider authProvider;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @MockitoBean
  CastrelSignClient client;

  @BeforeEach
  void cleanTables() {
    jdbcTemplate.update("delete from user_sessions");
    jdbcTemplate.update("delete from users");
    authProvider.createLocalUser("admin", "password", "Administrator", Role.ADMIN);
    authProvider.createLocalUser("operator", "password", "Operator", Role.OPERATOR);
    authProvider.createLocalUser("viewer", "password", "Viewer", Role.VIEWER);
  }

  @Test
  void operatorCreatesAssetFileCommandThroughCastrelSignProxy() throws Exception {
    Cookie operator = loginCookie("operator");
    doReturn(Map.of(
        "command_id", "cmd-1",
        "agent_id", "agent-01",
        "operation", "LIST",
        "status", "QUEUED",
        "request_json", "{\"path\":\"/tmp\"}")).when(client).createAgentFileCommand(
            eq("agent-01"),
            eq("LIST"),
            anyMap(),
            eq(120L));

    mockMvc.perform(post("/api/assets/agent-01/files/commands")
            .cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"operation":"LIST","request":{"path":"/tmp"}}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.commandId", is("cmd-1")))
        .andExpect(jsonPath("$.agentId", is("agent-01")))
        .andExpect(jsonPath("$.requestJson", is("{\"path\":\"/tmp\"}")))
        .andExpect(jsonPath("$.request_json").doesNotExist());

    verify(client).createAgentFileCommand(eq("agent-01"), eq("LIST"), anyMap(), eq(120L));
  }

  @Test
  void operatorUploadsAndDownloadsAssetFileThroughProxy() throws Exception {
    Cookie operator = loginCookie("operator");
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "upload.txt",
        MediaType.TEXT_PLAIN_VALUE,
        "hello".getBytes(StandardCharsets.UTF_8));
    doReturn(Map.of(
        "command_id", "upload-1",
        "agent_id", "agent-01",
        "operation", "UPLOAD",
        "status", "QUEUED")).when(client).createAgentFileUpload(
            eq("agent-01"),
            eq("/tmp/upload.txt"),
            eq(true),
            any(byte[].class),
            eq("upload.txt"),
            eq(MediaType.TEXT_PLAIN_VALUE),
            eq(120L));

    mockMvc.perform(multipart("/api/assets/agent-01/files/upload")
            .file(file)
            .param("path", "/tmp/upload.txt")
            .param("overwrite", "true")
            .cookie(operator))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.commandId", is("upload-1")));

    ResponseEntity<byte[]> upstream = ResponseEntity.status(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"server.txt\"")
        .body("server".getBytes(StandardCharsets.UTF_8));
    doReturn(upstream).when(client).downloadAgentFileCommand("download-1");

    mockMvc.perform(get("/api/assets/agent-01/files/commands/download-1/download").cookie(operator))
        .andExpect(status().isOk())
        .andExpect(content().string("server"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"server.txt\""));
  }

  @Test
  void viewerCannotAccessAssetFileManagerEvenForGetEndpoints() throws Exception {
    Cookie viewer = loginCookie("viewer");

    mockMvc.perform(get("/api/assets/agent-01/files/commands/cmd-1").cookie(viewer))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/assets/agent-01/files/commands")
            .cookie(viewer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"operation":"ROOTS","request":{}}
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void upstreamFileManagerErrorsReturnBadGatewayInsteadOfInternalServerError() throws Exception {
    Cookie operator = loginCookie("operator");
    doThrow(HttpClientErrorException.create(
        HttpStatus.NOT_FOUND,
        "Not Found",
        HttpHeaders.EMPTY,
        new byte[0],
        StandardCharsets.UTF_8)).when(client).createAgentFileCommand(
            eq("agent-01"),
            eq("ROOTS"),
            anyMap(),
            eq(120L));

    mockMvc.perform(post("/api/assets/agent-01/files/commands")
            .cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"operation":"ROOTS","request":{}}
                """))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error", containsString("upstream service returned 404")));
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
