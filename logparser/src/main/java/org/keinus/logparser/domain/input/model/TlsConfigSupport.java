package org.keinus.logparser.domain.input.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;

final class TlsConfigSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String[] DEFAULT_ENABLED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private TlsConfigSupport() {
    }

    static ServerSocket createServerSocket(InputAdapterConfig config, int port, String adapterName) throws IOException {
        JsonNode root = readConfigParams(config, adapterName);
        try {
            SSLContext sslContext = createServerSslContext(root);
            SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
            SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port);
            socket.setEnabledProtocols(readEnabledProtocols(root));

            ClientAuth clientAuth = readClientAuth(root);
            if (clientAuth == ClientAuth.NEED) {
                socket.setNeedClientAuth(true);
            } else if (clientAuth == ClientAuth.WANT) {
                socket.setWantClientAuth(true);
            }
            return socket;
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to initialize TLS server socket for " + adapterName, e);
        }
    }

    static SSLContext createClientSslContext(JsonNode root, String adapterName) throws IOException {
        try {
            KeyManager[] keyManagers = null;
            if (hasText(root, "keyStorePath")) {
                keyManagers = createKeyManagers(root);
            }

            TrustManager[] trustManagers = null;
            if (hasText(root, "trustStorePath")) {
                trustManagers = createTrustManagers(root);
            }

            SSLContext sslContext = SSLContext.getInstance(text(root, "tlsAlgorithm", "TLS"));
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (Exception e) {
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to initialize TLS client context for " + adapterName, e);
        }
    }

    static JsonNode readConfigParams(InputAdapterConfig config, String adapterName) throws IOException {
        String configParams = config.getConfigParams();
        if (configParams == null || configParams.trim().isEmpty()) {
            throw new IOException(adapterName + " requires configParams");
        }
        return OBJECT_MAPPER.readTree(configParams);
    }

    static boolean bool(JsonNode root, String fieldName, boolean defaultValue) {
        JsonNode value = root == null ? null : root.get(fieldName);
        if (value == null || !value.isBoolean()) {
            return defaultValue;
        }
        return value.asBoolean();
    }

    static String text(JsonNode root, String fieldName, String defaultValue) {
        JsonNode value = root == null ? null : root.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private static SSLContext createServerSslContext(JsonNode root) throws Exception {
        KeyManager[] keyManagers = createKeyManagers(root);
        TrustManager[] trustManagers = hasText(root, "trustStorePath") ? createTrustManagers(root) : null;

        SSLContext sslContext = SSLContext.getInstance(text(root, "tlsAlgorithm", "TLS"));
        sslContext.init(keyManagers, trustManagers, null);
        return sslContext;
    }

    private static KeyManager[] createKeyManagers(JsonNode root) throws Exception {
        String keyStorePath = requiredText(root, "keyStorePath");
        char[] keyStorePassword = requiredSecret(root, "keyStorePassword", "keyStorePasswordEnv");
        char[] keyPassword = optionalSecret(root, "keyPassword", "keyPasswordEnv");
        if (keyPassword == null) {
            keyPassword = keyStorePassword;
        }

        KeyStore keyStore = loadStore(keyStorePath, text(root, "keyStoreType", "PKCS12"), keyStorePassword);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyPassword);
        return keyManagerFactory.getKeyManagers();
    }

    private static TrustManager[] createTrustManagers(JsonNode root) throws Exception {
        String trustStorePath = requiredText(root, "trustStorePath");
        char[] trustStorePassword = requiredSecret(root, "trustStorePassword", "trustStorePasswordEnv");
        KeyStore trustStore = loadStore(trustStorePath, text(root, "trustStoreType", "PKCS12"), trustStorePassword);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        return trustManagerFactory.getTrustManagers();
    }

    private static KeyStore loadStore(String path, String type, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        try (var input = Files.newInputStream(Path.of(path))) {
            store.load(input, password);
        }
        return store;
    }

    private static String requiredText(JsonNode root, String fieldName) {
        String value = text(root, fieldName, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("configParams." + fieldName + " is required");
        }
        return value;
    }

    private static char[] requiredSecret(JsonNode root, String directField, String envField) {
        char[] secret = optionalSecret(root, directField, envField);
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("configParams." + directField + " or " + envField + " is required");
        }
        return secret;
    }

    private static char[] optionalSecret(JsonNode root, String directField, String envField) {
        String direct = text(root, directField, null);
        if (direct != null && !direct.isBlank()) {
            return direct.toCharArray();
        }

        String envName = text(root, envField, null);
        if (envName == null || envName.isBlank()) {
            return null;
        }
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + envName + " is required");
        }
        return value.toCharArray();
    }

    private static boolean hasText(JsonNode root, String fieldName) {
        String value = text(root, fieldName, null);
        return value != null && !value.isBlank();
    }

    private static String[] readEnabledProtocols(JsonNode root) {
        JsonNode value = root == null ? null : root.get("enabledProtocols");
        if (value == null || value.isNull()) {
            return DEFAULT_ENABLED_PROTOCOLS;
        }
        if (value.isArray()) {
            List<String> protocols = new ArrayList<>();
            for (JsonNode item : value) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    protocols.add(item.asText());
                }
            }
            return protocols.isEmpty() ? DEFAULT_ENABLED_PROTOCOLS : protocols.toArray(String[]::new);
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            return List.of(value.asText().split(",")).stream()
                    .map(String::trim)
                    .filter(protocol -> !protocol.isBlank())
                    .toArray(String[]::new);
        }
        return DEFAULT_ENABLED_PROTOCOLS;
    }

    private static ClientAuth readClientAuth(JsonNode root) {
        if (bool(root, "needClientAuth", false)) {
            return ClientAuth.NEED;
        }
        if (bool(root, "wantClientAuth", false)) {
            return ClientAuth.WANT;
        }

        String value = text(root, "clientAuth", "none").trim().toLowerCase();
        return switch (value) {
            case "need", "required", "true" -> ClientAuth.NEED;
            case "want", "optional" -> ClientAuth.WANT;
            case "none", "false" -> ClientAuth.NONE;
            default -> throw new IllegalArgumentException("configParams.clientAuth must be one of none, want, need");
        };
    }

    private enum ClientAuth {
        NONE,
        WANT,
        NEED
    }
}
