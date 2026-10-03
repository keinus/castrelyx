package org.castrelyx.castrelsign.kms;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/** Existing token entries only. Key generation, CKA policy and backups belong to the HSM operator. */
public final class Pkcs11SigningBackend implements SigningBackend {
  private final Provider provider;
  private final KeyStore token;
  public Pkcs11SigningBackend(KmsSettings settings) throws Exception {
    Provider base = Security.getProvider("SunPKCS11");
    if (base == null) throw new GeneralSecurityException("SunPKCS11 is unavailable");
    provider = base.configure(settings.getPkcs11Config().toAbsolutePath().toString());
    char[] pin = Files.readString(settings.getPkcs11PinFile()).strip().toCharArray();
    try { token = KeyStore.getInstance("PKCS11", provider); token.load(null, pin); }
    finally { Arrays.fill(pin, '\0'); }
  }
  public X509Certificate certificate(String alias) throws Exception {
    var cert = token.getCertificate(alias);
    if (!(cert instanceof X509Certificate x509)) throw new GeneralSecurityException("provisioned PKCS11 certificate entry is missing");
    privateKey(alias); return x509;
  }
  private PrivateKey privateKey(String alias) throws Exception {
    var value = token.getKey(alias, null);
    if (!(value instanceof PrivateKey key) || key.getEncoded() != null) throw new GeneralSecurityException("PKCS11 key must be a non-encodable private key");
    return key;
  }
  @Override public byte[] sign(String algorithm, byte[] reference, String binding, byte[] message) throws Exception {
    String alias = new String(reference, StandardCharsets.UTF_8);
    Signature signature = Signature.getInstance(algorithm, provider);
    signature.initSign(privateKey(alias)); signature.update(message); return signature.sign();
  }
  @Override public byte[] protect(byte[] pkcs8, String binding) { throw new UnsupportedOperationException("PKCS11 keys must be provisioned on the token"); }
}
