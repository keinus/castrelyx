package org.castrelyx.castrelsign.kms;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class KmsAuthenticationTest {
  @TempDir Path dir;
  private CastrelSignProperties properties;
  private byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
  @BeforeEach void configure() throws Exception {
    properties = new CastrelSignProperties(); properties.setDataDir(dir.resolve("data")); properties.setAdminToken("legacy-token");
    properties.getKms().setAdministratorsFile(dir.resolve("administrators.json"));
    writeAdministrators(List.of(new KmsAuthentication.Administrator("alice", hash("alice-token"), Set.of("KEY_ADMIN", "AUDITOR"), Set.of(KeyManagementService.UPDATE), Base64.getEncoder().encodeToString(secret)),
        new KmsAuthentication.Administrator("bob", hash("bob-token"), Set.of("SECURITY_OFFICER"), Set.of(KeyManagementService.UPDATE), Base64.getEncoder().encodeToString(secret))));
  }
  @AfterEach void close() throws Exception { KeyManagementService.closeAt(properties.getDataDir()); }
  @Test void verifiesRfc6238Sha1VectorAndRejectsInvalidCode() throws Exception {
    assertThat(KmsAuthentication.totp(secret, 1)).isEqualTo("287082");
    assertThat(KmsAuthentication.verifyTotp(secret, "287082", 59)).isEqualTo(1);
    assertThatThrownBy(() -> KmsAuthentication.verifyTotp(secret, "000000", 59)).isInstanceOf(ResponseStatusException.class);
  }
  @Test void requiresMfaForMutationAndRejectsReplayAfterRestart() throws Exception {
    var auth = new KmsAuthentication(properties, KeyManagementService.open(properties));
    assertThat(auth.authenticate("Bearer alice-token", null, false).id()).isEqualTo("alice");
    assertThatThrownBy(() -> auth.authenticate("Bearer alice-token", null, true)).isInstanceOf(ResponseStatusException.class);
    String code = KmsAuthentication.totp(secret, Instant.now().getEpochSecond() / 30);
    var identity = auth.authenticate("Bearer alice-token", code, true);
    assertThatThrownBy(() -> identity.require("OPERATOR", null)).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> identity.require("KEY_ADMIN", KeyManagementService.CA)).isInstanceOf(ResponseStatusException.class);
    identity.require("KEY_ADMIN", KeyManagementService.UPDATE);
    KeyManagementService.closeAt(properties.getDataDir());
    var restarted = new KmsAuthentication(properties, KeyManagementService.open(properties));
    assertThatThrownBy(() -> restarted.authenticate("Bearer alice-token", code, true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("already used");
    assertThatThrownBy(() -> restarted.authenticate("Bearer legacy-token", null, false)).isInstanceOf(ResponseStatusException.class);
  }
  @Test void hotReloadedPolicyRevokesRequesterAndBlocksMfaGuessing() throws Exception {
    var auth = new KmsAuthentication(properties, KeyManagementService.open(properties));
    auth.requireCurrentRequester("alice", KeyManagementService.UPDATE);
    for (int i = 0; i < 10; i++) assertThatThrownBy(() -> auth.authenticate("Bearer alice-token", "invalid", true)).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> auth.authenticate("Bearer alice-token", "invalid", true)).hasMessageContaining("429");
    writeAdministrators(List.of());
    assertThatThrownBy(() -> auth.authenticate("Bearer alice-token", null, false)).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> auth.requireCurrentRequester("alice", KeyManagementService.UPDATE)).isInstanceOf(ResponseStatusException.class);
  }
  private static String hash(String token) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
  private void writeAdministrators(List<KmsAuthentication.Administrator> admins) throws Exception { Files.write(properties.getKms().getAdministratorsFile(), new ObjectMapper().writeValueAsBytes(admins)); }
}
