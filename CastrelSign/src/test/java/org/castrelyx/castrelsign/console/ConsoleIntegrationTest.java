package org.castrelyx.castrelsign.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.kms.*;
import org.castrelyx.castrelsign.persistence.ApplicationPrincipalRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ConsoleIntegrationTest {
  @TempDir static Path dir;
  static final ObjectMapper JSON = new ObjectMapper();
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationPrincipalRepository apps;
  @Autowired CastrelSignProperties properties;
  @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
    r.add("castrelsign.data-dir", () -> dir.resolve("data").toString());
    r.add("castrelsign.public-base-url", () -> "https://localhost");
    r.add("castrelsign.admin-token", () -> "test-console-admin");
    r.add("castrelsign.portal-users-file", () -> dir.resolve("users.json").toString());
  }
  @AfterAll static void close() throws Exception { KeyManagementService.closeAt(dir.resolve("data")); }
  @BeforeEach void setup() throws Exception {
    properties.getKms().setAdministratorsFile(null);
    Files.writeString(dir.resolve("users.json"), JSON.writeValueAsString(List.of(
        Map.of("id","alice","tokenSha256",KmsAuthentication.tokenDigest("alice-test-token"),"services",List.of("app-a")),
        Map.of("id","bob","tokenSha256",KmsAuthentication.tokenDigest("bob-test-token"),"services",List.of("app-b")))));
    apps.upsertPrincipal("app-a", "A"); apps.upsertPrincipal("app-b", "B");
    apps.setStatus("app-a", "ACTIVE"); apps.setStatus("app-b", "ACTIVE");
    jdbc.update("delete from portal_certificate_requests"); jdbc.update("delete from portal_activity");
    jdbc.update("delete from application_certificates");
  }
  record Session(MockHttpSession http, String csrf) {}
  Session login(String realm, String token) throws Exception {
    var result = mvc.perform(post("/api/console/login").contentType("application/json").content(JSON.writeValueAsString(Map.of("realm",realm,"token",token))))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain(token, "tokenSha256");
    return new Session((MockHttpSession) result.getRequest().getSession(false), JSON.readTree(result.getResponse().getContentAsString()).get("csrf").asText());
  }
  @Test void adminSessionRequiresCsrfAndLogoutInvalidatesIt() throws Exception {
    var s=login("admin","test-console-admin");
    mvc.perform(get("/api/admin/kms/keys").session(s.http())).andExpect(status().isOk());
    mvc.perform(post("/api/admin/applications").session(s.http()).contentType("application/json").content("{\"principal_id\":\"new-app\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/applications").session(s.http()).header("X-CSRF-Token",s.csrf()).contentType("application/json").content("{\"principal_id\":\"new-app\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/console/logout").session(s.http()).header("X-CSRF-Token",s.csrf())).andExpect(status().isNoContent());
    assertThat(s.http().isInvalid()).isTrue();
    mvc.perform(get("/api/console/session")).andExpect(status().isUnauthorized());
  }
  @Test void crossOriginAndInvalidLoginAreDenied() throws Exception {
    mvc.perform(post("/api/console/login").header("Origin","https://evil.example").contentType("application/json").content("{\"realm\":\"admin\",\"token\":\"test-console-admin\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/console/login").contentType("application/json").content("{\"realm\":\"admin\",\"token\":\"bad\"}")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/console/login").contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
  }
  @Test void usersAreIsolatedAndPolicyRevocationIsImmediate() throws Exception {
    var a=login("user","alice-test-token");
    mvc.perform(get("/api/portal/services").session(a.http())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].principalId").value("app-a"));
    mvc.perform(get("/api/admin/kms/keys").session(a.http())).andExpect(status().isForbidden());
    mvc.perform(get("/api/console/configuration").session(a.http())).andExpect(status().isForbidden());
    mvc.perform(post("/api/portal/requests").session(a.http()).header("X-CSRF-Token",a.csrf()).contentType("application/json").content(JSON.writeValueAsString(Map.of("principalId","app-b","csrPem",csr("app-b"))))).andExpect(status().isForbidden());
    mvc.perform(get("/api/portal/certificate.pem").param("principalId","app-b").param("serial","01").session(a.http())).andExpect(status().isNotFound());
    Files.writeString(dir.resolve("users.json"),"[]");
    mvc.perform(get("/api/portal/services").session(a.http())).andExpect(status().isUnauthorized());
  }
  @Test void validCsrIsPersistedApprovedAndDownloadedOnlyByOwner() throws Exception {
    var a=login("user","alice-test-token");String pem=csr("app-a");
    String id=submit(a,"app-a",pem);
    mvc.perform(post("/api/portal/requests").session(a.http()).header("X-CSRF-Token",a.csrf()).contentType("application/json").content(JSON.writeValueAsString(Map.of("principalId","app-a","csrPem",pem)))).andExpect(status().isConflict());
    var b=login("user","bob-test-token");
    mvc.perform(get("/api/portal/requests").session(b.http())).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(post("/api/admin/portal/requests/"+id+"/review").header("Authorization","Bearer test-console-admin").contentType("application/json").content("{\"approve\":true,\"reason\":\"verified service owner\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/admin/portal/requests/"+id+"/review").header("Authorization","Bearer test-console-admin").contentType("application/json").content("{\"approve\":true,\"reason\":\"retry\"}")).andExpect(status().isConflict());
    var record=jdbc.queryForMap("select * from portal_certificate_requests where id=?",id);
    assertThat(record.get("state")).isEqualTo("APPROVED"); assertThat(record.get("csr_pem")).isEqualTo("");
    mvc.perform(get("/api/portal/certificate.pem").session(a.http()).param("principalId","app-a").param("serial",record.get("certificate_serial").toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("BEGIN CERTIFICATE")));
    mvc.perform(get("/api/portal/certificate.pem").session(b.http()).param("principalId","app-a").param("serial",record.get("certificate_serial").toString())).andExpect(status().isNotFound());
    mvc.perform(get("/api/portal/certificates").session(a.http())).andExpect(jsonPath("$[0].status").value("ACTIVE"));
    mvc.perform(get("/api/portal/activity").session(a.http())).andExpect(jsonPath("$.length()").value(3));
  }
  @Test void privateKeyWrongSubjectAndOversizedCsrAreRejected() throws Exception {
    var a=login("user","alice-test-token");
    for(String value:List.of("-----BEGIN PRIVATE KEY-----",csr("app-b"),"a".repeat(65537)))
      mvc.perform(post("/api/portal/requests").session(a.http()).header("X-CSRF-Token",a.csrf()).contentType("application/json").content(JSON.writeValueAsString(Map.of("principalId","app-a","csrPem",value)))).andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select count(*) from portal_certificate_requests",Integer.class)).isZero();
  }
  @Test void approvalRechecksBlockedServiceAndRevokedOwnership() throws Exception {
    var a=login("user","alice-test-token");String id=submit(a,"app-a",csr("app-a"));
    apps.setStatus("app-a","BLOCKED");
    mvc.perform(post("/api/admin/portal/requests/"+id+"/review").header("Authorization","Bearer test-console-admin").contentType("application/json").content("{\"approve\":true,\"reason\":\"review\"}")).andExpect(status().isConflict());
    apps.setStatus("app-a","ACTIVE");Files.writeString(dir.resolve("users.json"),"[]");
    mvc.perform(post("/api/admin/portal/requests/"+id+"/review").header("Authorization","Bearer test-console-admin").contentType("application/json").content("{\"approve\":true,\"reason\":\"review\"}")).andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("select state from portal_certificate_requests where id=?",String.class,id)).isEqualTo("PENDING");
  }
  @Test void configurationExcludesSecretsAndTokenRotationInvalidatesAdminSession() throws Exception {
    var a=login("admin","test-console-admin");
    var result=mvc.perform(get("/api/console/configuration").session(a.http())).andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("test-console-admin","tokenSha256","totpSecret","changeit");
    properties.setAdminToken("rotated-test-token");
    try { mvc.perform(get("/api/admin/kms/keys").session(a.http())).andExpect(status().isUnauthorized()); }
    finally { properties.setAdminToken("test-console-admin"); }
  }
  private String submit(Session session,String service,String pem) throws Exception {
    var result=mvc.perform(post("/api/portal/requests").session(session.http()).header("X-CSRF-Token",session.csrf()).contentType("application/json").content(JSON.writeValueAsString(Map.of("principalId",service,"csrPem",pem))))
        .andExpect(status().isCreated()).andReturn();
    return JSON.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }
  @Test void sessionMutationsRequireFreshMfaAndRecheckRoles() throws Exception {
    byte[] secret = new byte[20]; new SecureRandom().nextBytes(secret);
    var policy = dir.resolve("admins.json");
    var administrator = new HashMap<String,Object>(Map.of("id","console-mfa-test", "tokenSha256",KmsAuthentication.tokenDigest("mfa-test-token"), "roles",List.of("AUDITOR","OPERATOR"), "keys",List.of(KeyManagementService.CA,KeyManagementService.UPDATE), "totpSecretBase64",Base64.getEncoder().encodeToString(secret)));
    Files.writeString(policy,JSON.writeValueAsString(List.of(administrator)));
    properties.getKms().setAdministratorsFile(policy);
    var s=login("admin","mfa-test-token");
    mvc.perform(post("/api/admin/applications").session(s.http()).header("X-CSRF-Token",s.csrf()).contentType("application/json").content("{\"principal_id\":\"mfa-app\"}")).andExpect(status().isUnauthorized());
    var mac=javax.crypto.Mac.getInstance("HmacSHA1"); mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA1"));
    byte[] hash=mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(java.time.Instant.now().getEpochSecond()/30).array());
    String code=String.format(Locale.ROOT,"%06d",(java.nio.ByteBuffer.wrap(hash,hash[hash.length-1]&15,4).getInt()&0x7fffffff)%1_000_000);
    mvc.perform(post("/api/admin/applications").session(s.http()).header("X-CSRF-Token",s.csrf()).header("X-KMS-TOTP",code).contentType("application/json").content("{\"principal_id\":\"mfa-app\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/admin/applications").session(s.http()).header("X-CSRF-Token",s.csrf()).header("X-KMS-TOTP",code).contentType("application/json").content("{\"principal_id\":\"mfa-app-replay\"}")).andExpect(status().isForbidden());
    administrator.put("roles",List.of("VERIFIER")); Files.writeString(policy,JSON.writeValueAsString(List.of(administrator)));
    mvc.perform(get("/api/admin/applications").session(s.http())).andExpect(status().isForbidden());
    mvc.perform(post("/api/console/logout").session(s.http()).header("X-CSRF-Token",s.csrf())).andExpect(status().isNoContent());
  }
  static String csr(String cn) throws Exception {
    var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));var pair=generator.generateKeyPair();
    byte[] der=new JcaPKCS10CertificationRequestBuilder(new X500Name("CN="+cn),pair.getPublic()).build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())).getEncoded();
    return "-----BEGIN CERTIFICATE REQUEST-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(der)+"\n-----END CERTIFICATE REQUEST-----\n";
  }
}

