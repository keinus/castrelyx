package org.castrelyx.castrelsign.kms;

/** Signing callers receive signatures and public material, never a private key. */
public interface SigningBackend extends AutoCloseable {
  byte[] sign(String algorithm, byte[] protectedMaterial, String binding, byte[] message) throws Exception;
  byte[] protect(byte[] pkcs8, String binding) throws Exception;
  @Override default void close() throws Exception {}
}
