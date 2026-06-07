package org.castrelyx.castrelsign;

import java.util.Base64;

final class PemFiles {
  private PemFiles() {
  }

  static String toPem(String type, byte[] der) {
    String encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n";
  }
}
