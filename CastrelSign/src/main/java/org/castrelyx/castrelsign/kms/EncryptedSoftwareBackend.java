package org.castrelyx.castrelsign.kms;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** At-rest protection, not an HSM: private material temporarily exists in the JVM. */
public final class EncryptedSoftwareBackend implements SigningBackend {
  private final byte[] key;
  public EncryptedSoftwareBackend(byte[] key) { this.key = key.clone(); }
  @Override public void close() { Arrays.fill(key, (byte) 0); }
  @Override public byte[] protect(byte[] pkcs8, String binding) throws Exception { return encrypt(key, pkcs8, binding); }
  @Override public byte[] sign(String algorithm, byte[] material, String binding, byte[] message) throws Exception {
    byte[] plain = decrypt(key, material, binding);
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(plain);
    try {
      PrivateKey privateKey = KeyFactory.getInstance("Ed25519".equals(algorithm) ? "Ed25519" : "EC").generatePrivate(spec);
      Signature signature = Signature.getInstance(algorithm);
      signature.initSign(privateKey);
      signature.update(message);
      return signature.sign();
    } finally { Arrays.fill(plain, (byte) 0); }
  }
  public static byte[] encrypt(byte[] key, byte[] plain, String aad) throws Exception {
    byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(plain);
    return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
  }
  public static byte[] decrypt(byte[] key, byte[] encrypted, String aad) throws Exception {
    if (encrypted.length < 28) throw new GeneralSecurityException("invalid encrypted material");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encrypted, 0, 12));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(encrypted, 12, encrypted.length - 12);
  }
}
