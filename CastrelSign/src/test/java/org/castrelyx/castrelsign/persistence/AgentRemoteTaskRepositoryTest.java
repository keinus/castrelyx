package org.castrelyx.castrelsign.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AgentRemoteTaskRepositoryTest {
  @TempDir Path dir;
  JdbcTemplate jdbc; AgentRemoteTaskRepository tasks;
  @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("tasks.db")));new SchemaInitializer(jdbc).afterPropertiesSet();tasks=new AgentRemoteTaskRepository(jdbc);}
  @Test void duplicateDispatchAndResultAreIdempotentAndScoped(){
    String id=UUID.randomUUID().toString();Instant expiry=Instant.now().plusSeconds(600);
    tasks.create("agent-25","security_router","{}",expiry,id);
    tasks.create("agent-25","security_router","{}",expiry,id);
    assertThat(tasks.poll("agent-21",5)).isEmpty();assertThat(tasks.poll("agent-25",5)).hasSize(1);
    tasks.complete("agent-25",id,"COMPLETED","{}",null);tasks.complete("agent-25",id,"COMPLETED","{}",null);
    assertThatThrownBy(()->tasks.complete("agent-21",id,"COMPLETED","{}",null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->tasks.create("agent-25","security_router","{\"different\":true}",expiry,id)).isInstanceOf(IllegalArgumentException.class);
    assertThat(tasks.poll("agent-25",5)).isEmpty();
  }
  @Test void reclaimsRouterLeaseButNotLegacyTasksAndExpiresPending(){
    var router=tasks.create("agent-25","security_router","{}",Instant.now().plusSeconds(600));
    var ssh=tasks.create("agent-25","ssh_preflight","{}",Instant.now().plusSeconds(600));
    tasks.poll("agent-25",5);
    jdbc.update("update agent_remote_tasks set claimed_at=?",Instant.now().minusSeconds(181).toString());
    assertThat(tasks.poll("agent-25",5)).extracting(AgentRemoteTaskRepository.RemoteTask::taskId).containsExactly(router.taskId());
    assertThat(tasks.get(ssh.taskId()).status()).isEqualTo("RUNNING");
    var expired=tasks.create("agent-25","security_router","{}",Instant.now().minusSeconds(1));
    assertThat(tasks.poll("agent-25",5)).isEmpty();assertThat(tasks.get(expired.taskId()).status()).isEqualTo("EXPIRED");
  }
}
