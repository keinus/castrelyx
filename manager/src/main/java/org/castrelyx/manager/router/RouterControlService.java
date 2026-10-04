package org.castrelyx.manager.router;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.ForbiddenException;
import org.castrelyx.manager.auth.Role;
import org.castrelyx.manager.integration.CastrelSignClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RouterControlService {
  private static final Set<String> READS = Set.of("state", "jobs", "audit", "backups");
  private static final Set<String> WRITES = Set.of("validate", "apply", "mode", "backup", "restore", "restart");
  private static final Set<String> FIELDS = Set.of("action", "expectedRevision", "kind", "config", "parameters", "jobId", "backupId", "mode", "until", "service");
  private static final Set<String> OPEN = Set.of("QUEUED", "PENDING", "RUNNING");
  private final JdbcTemplate jdbc;
  private final CastrelSignClient client;
  private final ObjectMapper json;

  public RouterControlService(JdbcTemplate jdbc, CastrelSignClient client, ObjectMapper json) {
    this.jdbc=jdbc; this.client=client; this.json=json;
  }
  public record Binding(boolean enabled, String agentId, String routerId, String updatedBy, Instant updatedAt) {}
  public record BindRequest(String agentId, String routerId) {}
  public record Command(String id, long assetId, String agentId, String routerId, String action, String status,
      String resultJson, String errorMessage, String createdBy, Instant createdAt, Instant updatedAt, Instant expiresAt) {}

  public Binding binding(String uid) {
    long id=assetId(uid);
    var rows=jdbc.query("select * from router_control_bindings where asset_id=?", (rs,n)->new Binding(true,rs.getString("agent_id"),rs.getString("router_id"),rs.getString("updated_by"),rs.getTimestamp("updated_at").toInstant()),id);
    return rows.isEmpty()?new Binding(false,null,null,null,null):rows.getFirst();
  }

  public synchronized Binding bind(String uid, BindRequest request, AuthUser user) {
    requireAdmin(user);
    long id=assetId(uid);
    if(request==null || !identifier(request.agentId()) || !identifier(request.routerId())) throw new IllegalArgumentException("valid agentId and routerId are required");
    Integer sourceCount=jdbc.queryForObject("select count(*) from asset_source_bindings where asset_id=? and source_type='SECURITY_ROUTER' and source_id=?",Integer.class,id,request.routerId());
    if(sourceCount==null || sourceCount==0) throw new IllegalArgumentException("routerId must match the asset's SECURITY_ROUTER source binding");
    boolean registered=client.listAgents().stream().filter(Map.class::isInstance).map(Map.class::cast)
        .anyMatch(agent->request.agentId().equals(agent.containsKey("agent_id") ? agent.get("agent_id") : agent.get("agentId")) && "ACTIVE".equals(agent.get("status")));
    if(!registered) throw new IllegalArgumentException("agent is not registered or is blocked");
    Integer pending=jdbc.queryForObject("select count(*) from router_control_commands where asset_id=? and status in ('QUEUED','PENDING','RUNNING')",Integer.class,id);
    if(pending!=null && pending>0) throw new IllegalStateException("wait for outstanding router commands before changing the binding");
    int updated=jdbc.update("update router_control_bindings set agent_id=?, router_id=?, updated_by=?, updated_at=? where asset_id=?",request.agentId(),request.routerId(),user.username(),Timestamp.from(Instant.now()),id);
    if(updated==0) jdbc.update("insert into router_control_bindings(asset_id,agent_id,router_id,updated_by,updated_at) values(?,?,?,?,?)",id,request.agentId(),request.routerId(),user.username(),Timestamp.from(Instant.now()));
    return binding(uid);
  }

  public synchronized Command create(String uid, String requestId, Map<String,Object> request, boolean query, AuthUser user) {
    if(user==null) throw new ForbiddenException("authenticated user required");
    if(request==null || !FIELDS.containsAll(request.keySet())) throw new IllegalArgumentException("unsupported router request fields");
    String action=String.valueOf(request.get("action"));
    if(query ? !READS.contains(action) : !WRITES.contains(action)) throw new IllegalArgumentException("unsupported router operation");
    if(!query) requireAdmin(user);
    if(requestId==null || !UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException("requestId must be a UUID");
    long asset=assetId(uid);
    Binding binding=binding(uid); if(!binding.enabled()) throw new IllegalStateException("router control is not configured for this asset");
    Map<String,Object> payload=new LinkedHashMap<>(request); payload.put("routerId",binding.routerId());
    if(Set.of("validate","apply","restore").contains(action)) {
      if(!(request.get("expectedRevision") instanceof Number number) || number.longValue()<0 || number.doubleValue()!=number.longValue()) throw new IllegalArgumentException("expectedRevision is required");
    }
    String encoded=encode(payload); if(encoded.length()>900_000) throw new IllegalArgumentException("router request is too large");
    var existing=jdbc.query("select * from router_control_commands where id=?",RouterControlService::command,requestId);
    if(!existing.isEmpty()) {
      Command previous=existing.getFirst();
      String previousPayload=jdbc.queryForObject("select payload_json from router_control_commands where id=?",String.class,requestId);
      if(previous.assetId()!=asset || !previous.createdBy().equals(user.username()) || !decode(previousPayload).equals(payload)) throw new IllegalArgumentException("requestId already belongs to another command");
      return refresh(previous);
    }
    Instant now=Instant.now();
    jdbc.update("""
        insert into router_control_commands(id,asset_id,agent_id,router_id,action,payload_json,status,created_by,created_at,updated_at,expires_at)
        values(?,?,?,?,?,?,'QUEUED',?,?,?,?)
        """,requestId,asset,binding.agentId(),binding.routerId(),action,encoded,user.username(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plusSeconds(600)));
    return refresh(find(requestId));
  }

  public Command get(String uid, String id) {
    Command command=find(id); if(command.assetId()!=assetId(uid)) throw new IllegalArgumentException("command does not belong to this asset");
    return refresh(command);
  }
  public List<Command> history(String uid) {
    return jdbc.query("select * from router_control_commands where asset_id=? order by created_at desc limit 100",RouterControlService::command,assetId(uid));
  }
  @Scheduled(fixedDelayString="${manager.router-control.poll-ms:3000}", initialDelay=10000)
  public synchronized void reconcile() {
    jdbc.query("select * from router_control_commands where status in ('QUEUED','PENDING','RUNNING') order by updated_at limit 20",RouterControlService::command).forEach(this::refresh);
  }
  private synchronized Command refresh(Command command) {
    if(!OPEN.contains(command.status())) return command;
    try {
      Map<?,?> remote;
      if(command.status().equals("QUEUED")) {
        if(Instant.now().isAfter(command.expiresAt())) { expire(command,"UNKNOWN","Dispatch outcome unavailable; inspect Router jobs before retrying"); return find(command.id()); }
        String payload=jdbc.queryForObject("select payload_json from router_control_commands where id=?",String.class,command.id());
        long ttl=Math.max(10,java.time.Duration.between(Instant.now(),command.expiresAt()).toSeconds());
        remote=client.createRouterTask(command.agentId(),command.id(),decode(payload),ttl);
      } else remote=client.getAgentRemoteTask(command.id());
      if(remote==null || !command.id().equals(remote.get("taskId")) || !command.agentId().equals(remote.get("agentId"))) throw new IllegalStateException("remote task identity mismatch");
      String status=String.valueOf(remote.get("status"));
      if(!Set.of("PENDING","RUNNING","COMPLETED","FAILED","EXPIRED","UNKNOWN").contains(status)) throw new IllegalStateException("unknown remote status");
      if(OPEN.contains(status) && Instant.now().isAfter(command.expiresAt().plusSeconds(180))) status="UNKNOWN";
      jdbc.update("update router_control_commands set status=?,result_json=?,error_message=?,updated_at=? where id=?",status,remote.get("resultJson"),remote.get("errorMessage"),Timestamp.from(Instant.now()),command.id());
    } catch(RuntimeException failure) {
      if(Instant.now().isAfter(command.expiresAt().plusSeconds(180))) expire(command,"UNKNOWN","Result unavailable; inspect Router jobs before retrying");
      else jdbc.update("update router_control_commands set error_message=?,updated_at=? where id=?","Control channel unavailable; retrying",Timestamp.from(Instant.now()),command.id());
    }
    return find(command.id());
  }
  private void expire(Command command,String status,String message) {jdbc.update("update router_control_commands set status=?,error_message=?,updated_at=? where id=?",status,message,Timestamp.from(Instant.now()),command.id());}
  private Command find(String id) {
    var rows=jdbc.query("select * from router_control_commands where id=?",RouterControlService::command,id);
    if(rows.isEmpty()) throw new IllegalArgumentException("router command not found");return rows.getFirst();
  }
  private long assetId(String uid) {
    var rows=jdbc.query("select id from assets where asset_uid=?",(rs,n)->rs.getLong(1),uid);
    if(rows.isEmpty()) throw new IllegalArgumentException("asset not found");return rows.getFirst();
  }
  private static boolean identifier(String id) {return id!=null && id.matches("[a-zA-Z0-9_.:-]{1,255}");}
  private static void requireAdmin(AuthUser user) {if(user==null || user.role()!=Role.ADMIN) throw new ForbiddenException("router changes require administrator role");}
  private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("invalid router request");}}
  private Map<String,Object> decode(String value) {try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalArgumentException("invalid stored router request");}}
  private static Command command(ResultSet rs,int n)throws SQLException {
    return new Command(rs.getString("id"),rs.getLong("asset_id"),rs.getString("agent_id"),rs.getString("router_id"),rs.getString("action"),rs.getString("status"),rs.getString("result_json"),rs.getString("error_message"),rs.getString("created_by"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getTimestamp("expires_at").toInstant());
  }
}
