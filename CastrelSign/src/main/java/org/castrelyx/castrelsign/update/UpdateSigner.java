package org.castrelyx.castrelsign.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.springframework.stereotype.Component;

@Component
public class UpdateSigner {
  private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
  private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
  private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";
  private static final String PUBLIC_END = "-----END PUBLIC KEY-----";

  private final CastrelSignProperties properties;

  public UpdateSigner(CastrelSignProperties properties) {
    this.properties = properties;
  }

  public synchronized String publicKeyPem() {
    try {
      ensureKeyPair();
      return Files.readString(publicKeyPath(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("failed to load update public key", e);
    }
  }

  public synchronized String sign(byte[] payload) {
    try {
      ensureKeyPair();
      PrivateKey privateKey = readPrivateKey();
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(payload);
      return Base64.getEncoder().encodeToString(signer.sign());
    } catch (Exception e) {
      throw new IllegalStateException("failed to sign update manifest", e);
    }
  }

  private void ensureKeyPair() throws Exception {
    Path privatePath = privateKeyPath();
    Path publicPath = publicKeyPath();
    if (Files.exists(privatePath) && Files.exists(publicPath)) {
      return;
    }
    Files.createDirectories(privatePath.getParent());
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair keyPair = generator.generateKeyPair();
    Files.writeString(privatePath, pem(PRIVATE_BEGIN, PRIVATE_END, keyPair.getPrivate().getEncoded()), StandardCharsets.UTF_8);
    Files.writeString(publicPath, pem(PUBLIC_BEGIN, PUBLIC_END, keyPair.getPublic().getEncoded()), StandardCharsets.UTF_8);
  }

  private PrivateKey readPrivateKey() throws Exception {
    String pem = Files.readString(privateKeyPath(), StandardCharsets.UTF_8)
        .replace(PRIVATE_BEGIN, "")
        .replace(PRIVATE_END, "")
        .replaceAll("\\s+", "");
    byte[] der = Base64.getDecoder().decode(pem);
    return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private Path privateKeyPath() {
    return properties.getDataDir().resolve("update-signing").resolve("private.pem");
  }

  private Path publicKeyPath() {
    return properties.getDataDir().resolve("update-signing").resolve("public.pem");
  }

  private static String pem(String begin, String end, byte[] der) {
    String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    return begin + "\n" + body + "\n" + end + "\n";
  }
}
