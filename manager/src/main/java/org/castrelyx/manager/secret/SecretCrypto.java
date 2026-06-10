package org.castrelyx.manager.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.castrelyx.manager.config.ManagerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SecretCrypto {
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public SecretCrypto(ManagerProperties properties) {
    this(properties.cryptoKey());
  }

  public SecretCrypto(String configuredKey) {
    this.key = new SecretKeySpec(normalizeKey(configuredKey), "AES");
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return "v1:%s:%s".formatted(
          Base64.getEncoder().encodeToString(nonce),
          Base64.getEncoder().encodeToString(ciphertext));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("failed to encrypt secret", exception);
    }
  }

  public String decrypt(String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      return null;
    }
    String[] parts = ciphertext.split(":", 3);
    if (parts.length != 3 || !"v1".equals(parts[0])) {
      throw new IllegalArgumentException("unsupported secret ciphertext format");
    }
    try {
      byte[] nonce = Base64.getDecoder().decode(parts[1]);
      byte[] encrypted = Base64.getDecoder().decode(parts[2]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException | GeneralSecurityException exception) {
      throw new IllegalArgumentException("failed to decrypt secret", exception);
    }
  }

  private static byte[] normalizeKey(String configuredKey) {
    if (configuredKey == null || configuredKey.isBlank()) {
      throw new IllegalStateException("MANAGER_CRYPTO_KEY is required");
    }
    byte[] base64 = tryBase64Key(configuredKey);
    if (base64 != null) {
      return base64;
    }
    byte[] raw = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (raw.length < KEY_BYTES) {
      throw new IllegalArgumentException("MANAGER_CRYPTO_KEY must be at least 32 bytes");
    }
    return Arrays.copyOf(raw, KEY_BYTES);
  }

  private static byte[] tryBase64Key(String configuredKey) {
    try {
      byte[] decoded = Base64.getDecoder().decode(configuredKey);
      if (decoded.length >= KEY_BYTES) {
        return Arrays.copyOf(decoded, KEY_BYTES);
      }
      return null;
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
