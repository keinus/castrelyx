package org.castrelyx.castrelsign.crypto;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

public final class PemUtil {
  private PemUtil() {
  }

  public static String certificateToPem(X509Certificate certificate) {
    try {
      return toPem("CERTIFICATE", certificate.getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException("failed to encode certificate", e);
    }
  }

  public static String privateKeyToPem(PrivateKey key) {
    byte[] encoded = key.getEncoded();
    if (encoded == null || encoded.length == 0) {
      throw new IllegalStateException("private key does not expose PKCS#8 encoding");
    }
    return toPem("PRIVATE KEY", encoded);
  }

  public static String toPem(String type, byte[] der) {
    String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n";
  }
}
