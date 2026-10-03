package org.castrelyx.castrelsign.kms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.castrelyx.castrelsign.update.AgentUpdateService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class KmsControllerTest {
  @TempDir static Path dir;
  @Autowired MockMvc mvc;
  @Autowired KeyManagementService kms;
  @Autowired AgentUpdateService updates;
  @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
    registry.add("castrelsign.data-dir", () -> dir.toString()); registry.add("castrelsign.public-base-url", () -> "https://localhost");
    registry.add("castrelsign.admin-token", () -> "kms-test-admin");
  }
  @AfterAll static void close() throws Exception { KeyManagementService.closeAt(dir); }
  @Test void protectsManagementApiAndImmediatelyStopsCompromisedUpdateDistribution() throws Exception {
    mvc.perform(get("/api/admin/kms/keys")).andExpect(status().isUnauthorized());
    String inventory = mvc.perform(get("/api/admin/kms/keys").header("Authorization", "Bearer kms-test-admin"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(inventory).contains("ca-root", "update-signing", "ENCRYPTED_SOFTWARE").doesNotContain("PRIVATE KEY", "material");
    mvc.perform(get("/api/admin/kms/certificates/status").param("serial", "0123").header("Authorization", "Bearer kms-test-admin"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN")).andExpect(jsonPath("$.maxCacheSeconds").value(60));
    var key = kms.current(KeyManagementService.UPDATE);
    var release = updates.createRelease("kms-1.0", "linux", "amd64", "stable", new byte[]{1, 2, 3});
    assertThat(release.manifestJson()).contains("signing_key_id", "signing_key_version", key.keyId());
    byte[] der = Base64.getDecoder().decode(key.publicKeyPem().replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
    verifier.update(release.manifestJson().getBytes(StandardCharsets.UTF_8)); assertThat(verifier.verify(Base64.getDecoder().decode(release.signature()))).isTrue();
    updates.activateRelease(release.id()); updates.upsertPolicy(null, true, "stable", release.version());
    var check = new AgentUpdateService.UpdateCheckRequest("old", "linux", "amd64", "stable", "daemon");
    assertThat(updates.check("kms-agent", check).updateAvailable()).isTrue();
    mvc.perform(post("/api/admin/kms/keys/" + key.keyId() + "/versions/1/suspend").header("Authorization", "Bearer kms-test-admin")
        .contentType("application/json").content("{\"reason\":\"incident-1\",\"compromised\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPROMISED"));
    assertThat(updates.check("kms-agent", check).updateAvailable()).isFalse();
    assertThatThrownBy(() -> updates.artifact("kms-agent", release.id())).hasMessageContaining("not trusted");
    assertThatThrownBy(() -> updates.createRelease("kms-2.0", "linux", "amd64", "stable", new byte[]{4})).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    mvc.perform(get("/api/admin/kms/health").header("Authorization", "Bearer kms-test-admin"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DEGRADED"));
  }
}
