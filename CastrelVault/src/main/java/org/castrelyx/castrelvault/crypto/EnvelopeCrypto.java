package org.castrelyx.castrelvault.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class EnvelopeCrypto {
  private static final int DEK_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final MasterKeyRegistry masterKeys;
  private final SecureRandom random = new SecureRandom();

  public EnvelopeCrypto(MasterKeyRegistry masterKeys) {
    this.masterKeys = masterKeys;
  }

  public EncryptedPayload encrypt(byte[] plaintext) {
    if (plaintext == null || plaintext.length == 0) {
      throw new IllegalArgumentException("secret payload is required");
    }
    byte[] dataKey = randomBytes(DEK_BYTES);
    byte[] payloadNonce = randomBytes(NONCE_BYTES);
    byte[] wrappedKeyNonce = randomBytes(NONCE_BYTES);
    byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), payloadNonce, plaintext);
    byte[] wrappedKey = gcm(Cipher.ENCRYPT_MODE, masterKeys.activeKey(), wrappedKeyNonce, dataKey);
    return new EncryptedPayload(
        masterKeys.activeKeyId(),
        wrappedKeyNonce,
        wrappedKey,
        payloadNonce,
        ciphertext,
        sha256Hex(plaintext));
  }

  public byte[] decrypt(String keyId, byte[] wrappedKeyNonce, byte[] wrappedKey, byte[] payloadNonce, byte[] ciphertext) {
    try {
      byte[] dataKey = gcm(Cipher.DECRYPT_MODE, masterKeys.key(keyId), wrappedKeyNonce, wrappedKey);
      return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), payloadNonce, ciphertext);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("secret version is not decryptable", e);
    }
  }

  private byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }

  private static byte[] gcm(int mode, SecretKeySpec key, byte[] nonce, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM operation failed", e);
    }
  }

  private static String sha256Hex(byte[] plaintext) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("failed to hash secret payload", e);
    }
  }

  public record EncryptedPayload(
      String keyId,
      byte[] wrappedKeyNonce,
      byte[] encryptedDataKey,
      byte[] encryptionNonce,
      byte[] ciphertext,
      String payloadContentHash) {
    public boolean containsPlaintext(String plaintext) {
      if (plaintext == null) {
        return false;
      }
      String needle = plaintext;
      return new String(encryptedDataKey, StandardCharsets.ISO_8859_1).contains(needle)
          || new String(ciphertext, StandardCharsets.ISO_8859_1).contains(needle);
    }
  }
}
