package org.castrelyx.manager.remote;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

final class SshKeyPairFactory {
  private SshKeyPairFactory() {
  }

  static GeneratedKeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(3072);
      var pair = generator.generateKeyPair();
      String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
      byte[] publicBlob = rsaPublicBlob((RSAPublicKey) pair.getPublic());
      String publicKey = "ssh-rsa " + Base64.getEncoder().encodeToString(publicBlob) + " castrelyx-manager";
      String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
          .encodeToString(MessageDigest.getInstance("SHA-256").digest(publicBlob));
      return new GeneratedKeyPair(privatePem, publicKey, fingerprint);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to generate SSH key pair", exception);
    }
  }

  private static byte[] rsaPublicBlob(RSAPublicKey key) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeString(out, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
    writeMpint(out, key.getPublicExponent());
    writeMpint(out, key.getModulus());
    return out.toByteArray();
  }

  private static void writeMpint(ByteArrayOutputStream out, BigInteger value) {
    writeString(out, value.toByteArray());
  }

  private static void writeString(ByteArrayOutputStream out, byte[] value) {
    out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
    out.writeBytes(value);
  }

  private static String pem(String type, byte[] der) {
    String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n";
  }

  record GeneratedKeyPair(String privateKeyPem, String publicKey, String fingerprint) {
  }
}
