package org.castrelyx.manager.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RouterControlControllerTest {
  @Autowired MockMvc mvc;
  @Autowired LocalAuthProvider auth;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @MockitoBean CastrelSignClient client;
  private final String base="/api/assets/control-router/router-control";
  @BeforeEach void prepare() {
    jdbc.update("delete from router_control_commands");jdbc.update("delete from router_control_bindings");
    jdbc.update("delete from asset_source_bindings where source_id='router-control-test'");
    jdbc.update("delete from assets where asset_uid in ('control-router','control-other')");
    jdbc.update("delete from user_sessions");jdbc.update("delete from users");
    for(Role role:Role.values()) auth.createLocalUser(role.name(),"password",role.name(),role);
    jdbc.update("insert into assets(asset_uid,name,asset_type) values('control-router','Router','ROUTER'),('control-other','Other','ROUTER')");
    long id=jdbc.queryForObject("select id from assets where asset_uid='control-router'",Long.class);
    jdbc.update("insert into asset_source_bindings(asset_id,source_type,source_id,source_key) values(?,'SECURITY_ROUTER','router-control-test','syslog-tls')",id);
    jdbc.update("insert into router_control_bindings(asset_id,agent_id,router_id,updated_by,updated_at) values(?,'agent-25','router-control-test','ADMIN',CURRENT_TIMESTAMP)",id);
    doReturn(List.of(Map.of("agentId","agent-25","status","ACTIVE"))).when(client).listAgents();
    when(client.createRouterTask(anyString(),anyString(),anyMap(),anyLong())).thenAnswer(call -> Map.of("taskId",call.getArgument(1),"agentId",call.getArgument(0),"status","COMPLETED","resultJson","{\"ready\":true}"));
  }
  private Cookie cookie(Role role) throws Exception {
    var response=mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",role.name(),"password","password")))).andExpect(status().isOk()).andReturn().getResponse();
    return response.getCookie(AuthController.SESSION_COOKIE);
  }
  private String operation(String id,Map<String,Object> request)throws Exception{return json.writeValueAsString(Map.of("requestId",id,"request",request));}
  @Test void viewerCanReadThroughAgentButCannotSendMutationThroughQuery() throws Exception {
    Cookie viewer=cookie(Role.VIEWER);String id=UUID.randomUUID().toString();
    mvc.perform(post(base+"/queries").cookie(viewer).contentType("application/json").content(operation(id,Map.of("action","state")))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("COMPLETED"));
    mvc.perform(post(base+"/queries").cookie(viewer).contentType("application/json").content(operation(UUID.randomUUID().toString(),Map.of("action","apply","expectedRevision",1)))).andExpect(status().isBadRequest());
    mvc.perform(post(base+"/commands").cookie(viewer).contentType("application/json").content(operation(UUID.randomUUID().toString(),Map.of("action","mode","mode","automatic")))).andExpect(status().isForbidden());
    verify(client,times(1)).createRouterTask(eq("agent-25"),eq(id),eq(Map.of("action","state","routerId","router-control-test")),anyLong());
  }
  @Test void operatorCannotChangeRouterAndCrossAssetResultsAreDenied()throws Exception {
    Cookie operator=cookie(Role.OPERATOR);
    mvc.perform(post(base+"/commands").cookie(operator).contentType("application/json").content(operation(UUID.randomUUID().toString(),Map.of("action","restart","service","switchd")))).andExpect(status().isForbidden());
    String id=UUID.randomUUID().toString();Cookie admin=cookie(Role.ADMIN);
    mvc.perform(post(base+"/queries").cookie(admin).contentType("application/json").content(operation(id,Map.of("action","state")))).andExpect(status().isAccepted());
    mvc.perform(get("/api/assets/control-other/router-control/commands/"+id).cookie(admin)).andExpect(status().isBadRequest());
  }
  @Test void ambiguousDispatchRetriesSameIdAndPreservesAudit()throws Exception {
    Cookie admin=cookie(Role.ADMIN);String id=UUID.randomUUID().toString();
    doThrow(new IllegalStateException("response lost")).doReturn(Map.of("taskId",id,"agentId","agent-25","status","COMPLETED","resultJson","{}"))
      .when(client).createRouterTask(eq("agent-25"),eq(id),anyMap(),anyLong());
    String body=operation(id,Map.of("action","apply","expectedRevision",4,"kind","port.admin","parameters",Map.of("port","eth2","state","up")));
    mvc.perform(post(base+"/commands").cookie(admin).contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
    mvc.perform(get(base+"/commands/"+id).cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.createdBy").value("ADMIN"));
    mvc.perform(post(base+"/commands").cookie(admin).contentType("application/json").content(body)).andExpect(status().isAccepted());
    assertThat(jdbc.queryForObject("select count(*) from router_control_commands where id=?",Integer.class,id)).isEqualTo(1);
    verify(client,times(2)).createRouterTask(eq("agent-25"),eq(id),anyMap(),anyLong());
  }
  @Test void rejectsTargetInjectionMissingRevisionAndBlockedAgent()throws Exception {
    Cookie admin=cookie(Role.ADMIN);
    for(Map<String,Object> request:List.<Map<String,Object>>of(Map.of("action","state","routerId","another"),Map.of("action","state","url","http://internal"))) {
      mvc.perform(post(base+"/queries").cookie(admin).contentType("application/json").content(operation(UUID.randomUUID().toString(),request))).andExpect(status().isBadRequest());
    }
    mvc.perform(post(base+"/commands").cookie(admin).contentType("application/json").content(operation(UUID.randomUUID().toString(),Map.of("action","apply","kind","config")))).andExpect(status().isBadRequest());
    doReturn(List.of(Map.of("agentId","agent-25","status","BLOCKED"))).when(client).listAgents();
    mvc.perform(put(base+"/binding").cookie(admin).contentType("application/json").content("{\"agentId\":\"agent-25\",\"routerId\":\"router-control-test\"}")).andExpect(status().isBadRequest());
    verify(client,never()).createRouterTask(anyString(),anyString(),anyMap(),anyLong());
  }
  @Test void bindsRealSnakeCaseAgentApiResponse()throws Exception {
    doReturn(List.of(Map.of("agent_id","agent-25","status","ACTIVE"))).when(client).listAgents();
    mvc.perform(put(base+"/binding").cookie(cookie(Role.ADMIN)).contentType("application/json").content("{\"agentId\":\"agent-25\",\"routerId\":\"router-control-test\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
  }
}
