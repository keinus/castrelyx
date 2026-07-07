package org.castrelyx.manager.vault;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.castrelyx.manager.config.ManagerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "manager.vault", name = "enabled", havingValue = "true")
public class HttpVaultClient implements VaultClient {
  private final RestClient restClient;
  private final ManagerProperties properties;

  public HttpVaultClient(RestClient.Builder builder, ManagerProperties properties) {
    this.properties = properties;
    ClientHttpRequestFactory requestFactory = mtlsRequestFactory(properties);
    if (requestFactory != null) {
      builder.requestFactory(requestFactory);
    }
    this.restClient = builder.build();
  }

  @Override
  public boolean isEnabled() {
    return properties.vault() != null && properties.vault().enabled();
  }

  @Override
  @SuppressWarnings("unchecked")
  public String createSecret(VaultSecretWriteRequest request) {
    return createSecret(request, properties.vault() == null ? null : properties.vault().adminSessionToken());
  }

  @Override
  @SuppressWarnings("unchecked")
  public String createSecret(VaultSecretWriteRequest request, String adminSessionToken) {
    requireAdminSession(adminSessionToken);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("path", request.path());
    body.put("displayName", request.displayName());
    body.put("type", request.type());
    body.put("tags", List.of("manager-migrated"));
    body.put("payload", request.payload());
    Map<String, Object> response = restClient.post()
        .uri(URI.create(baseUrl() + "/api/secrets"))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminSessionToken)
        .body(body)
        .retrieve()
        .body(Map.class);
    Object path = response == null ? null : response.get("path");
    if (path == null || String.valueOf(path).isBlank()) {
      throw new IllegalStateException("CastrelVault did not return a secret path");
    }
    return "vault://" + path;
  }

  @Override
  @SuppressWarnings("unchecked")
  public String resolveString(String reference) {
    Map<String, Object> response = restClient.post()
        .uri(URI.create(baseUrl() + "/api/v1/secrets/resolve"))
        .body(Map.of("reference", reference))
        .retrieve()
        .body(Map.class);
    if (response == null || !(response.get("secrets") instanceof Map<?, ?> secrets)) {
      return null;
    }
    String path = normalizeReference(reference);
    Object payload = secrets.get(path);
    if (payload instanceof Map<?, ?> map && map.get("value") != null) {
      return String.valueOf(map.get("value"));
    }
    return payload == null ? null : String.valueOf(payload);
  }

  private String baseUrl() {
    if (properties.vault() == null || properties.vault().baseUrl() == null || properties.vault().baseUrl().isBlank()) {
      throw new IllegalStateException("MANAGER_VAULT_BASE_URL is required when Manager Vault client is enabled");
    }
    return properties.vault().baseUrl();
  }

  private void requireAdminSession(String adminSessionToken) {
    if (adminSessionToken == null || adminSessionToken.isBlank()) {
      throw new IllegalStateException("a CastrelVault admin session token is required for Manager Vault migration writes");
    }
  }

  private static String normalizeReference(String reference) {
    String normalized = reference == null ? "" : reference.trim();
    if (normalized.startsWith("vault://")) {
      normalized = normalized.substring("vault://".length());
      if (!normalized.startsWith("/")) {
        normalized = "/" + normalized;
      }
    } else if (normalized.startsWith("vault:")) {
      normalized = normalized.substring("vault:".length());
    }
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  private static ClientHttpRequestFactory mtlsRequestFactory(ManagerProperties properties) {
    ManagerProperties.Vault vault = properties.vault();
    if (vault == null || isBlank(vault.keyStorePath()) || isBlank(vault.trustStorePath())) {
      return null;
    }
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keyManagers(vault), trustManagers(vault), null);
      return new JdkClientHttpRequestFactory(HttpClient.newBuilder()
          .sslContext(context)
          .build());
    } catch (Exception e) {
      throw new IllegalStateException("failed to initialize Manager Vault mTLS client", e);
    }
  }

  private static KeyManager[] keyManagers(ManagerProperties.Vault vault) throws Exception {
    KeyStore keyStore = loadPkcs12(vault.keyStorePath(), vault.keyStorePassword());
    KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(keyStore, password(vault.keyStorePassword()));
    return factory.getKeyManagers();
  }

  private static TrustManager[] trustManagers(ManagerProperties.Vault vault) throws Exception {
    KeyStore trustStore = loadPkcs12(vault.trustStorePath(), vault.trustStorePassword());
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trustStore);
    return factory.getTrustManagers();
  }

  private static KeyStore loadPkcs12(String path, String password) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(Path.of(path))) {
      store.load(input, password(password));
    }
    return store;
  }

  private static char[] password(String value) {
    return value == null ? new char[0] : value.toCharArray();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
