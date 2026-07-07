package org.castrelyx.castrelvault.crypto;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.springframework.stereotype.Component;

@Component
public class MasterKeyRegistry {
  private static final int KEY_BYTES = 32;

  private final Map<String, SecretKeySpec> keys;
  private final String activeKeyId;

  public MasterKeyRegistry(CastrelVaultProperties properties) {
    this.keys = parse(properties.getMasterKeys());
    this.activeKeyId = requireText(properties.getActiveMasterKeyId(), "CASTRELVAULT_ACTIVE_MASTER_KEY_ID is required");
    if (!keys.containsKey(activeKeyId)) {
      throw new IllegalStateException("CASTRELVAULT_ACTIVE_MASTER_KEY_ID must match CASTRELVAULT_MASTER_KEYS");
    }
  }

  public String activeKeyId() {
    return activeKeyId;
  }

  public SecretKeySpec activeKey() {
    return key(activeKeyId);
  }

  public SecretKeySpec key(String keyId) {
    SecretKeySpec key = keys.get(keyId);
    if (key == null) {
      throw new IllegalArgumentException("configured master key is unavailable");
    }
    return key;
  }

  public Map<String, SecretKeySpec> keys() {
    return Map.copyOf(keys);
  }

  private static Map<String, SecretKeySpec> parse(String configured) {
    String value = requireText(configured, "CASTRELVAULT_MASTER_KEYS is required");
    Map<String, SecretKeySpec> parsed = new LinkedHashMap<>();
    for (String entry : value.split(",")) {
      if (entry.isBlank()) {
        continue;
      }
      String[] parts = entry.split(":", 2);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        throw new IllegalStateException("CASTRELVAULT_MASTER_KEYS entries must be key-id:base64-32-byte-key");
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(parts[1]);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("CASTRELVAULT_MASTER_KEYS contains invalid base64", e);
      }
      if (decoded.length != KEY_BYTES) {
        throw new IllegalStateException("CASTRELVAULT_MASTER_KEYS values must decode to exactly 32 bytes");
      }
      parsed.put(parts[0].trim(), new SecretKeySpec(Arrays.copyOf(decoded, KEY_BYTES), "AES"));
    }
    if (parsed.isEmpty()) {
      throw new IllegalStateException("CASTRELVAULT_MASTER_KEYS is required");
    }
    return parsed;
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(message);
    }
    return value.trim();
  }
}
