package org.castrelyx.castrelsign.crypto;

import java.io.StringReader;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.util.Objects;

import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Service;

@Service
public class CsrService {
  public ParsedCsr parseAndValidate(String csrPem, String agentId) {
    if (agentId == null || agentId.isBlank()) {
      throw new IllegalArgumentException("agent_id is required");
    }
    PKCS10CertificationRequest csr = parse(csrPem);
    verifySignature(csr);
    String commonName = commonName(csr);
    if (commonName == null || commonName.isBlank()) {
      throw new IllegalArgumentException("CSR common name is required");
    }
    if (!Objects.equals(commonName, agentId)) {
      throw new IllegalArgumentException("CSR common name must match agent_id");
    }
    PublicKey publicKey = publicKey(csr);
    if (!(publicKey instanceof ECPublicKey)) {
      throw new IllegalArgumentException("agent CSR public key must be EC");
    }
    return new ParsedCsr(csr, commonName, publicKey, subjectAlternativeNames(csr));
  }

  private PKCS10CertificationRequest parse(String csrPem) {
    if (csrPem == null || csrPem.isBlank()) {
      throw new IllegalArgumentException("csr_pem is required");
    }
    try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
      Object object = parser.readObject();
      if (object instanceof PKCS10CertificationRequest request) {
        return request;
      }
      throw new IllegalArgumentException("csr_pem is not a certificate request");
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to parse csr_pem", e);
    }
  }

  private void verifySignature(PKCS10CertificationRequest csr) {
    try {
      boolean valid = csr.isSignatureValid(new JcaContentVerifierProviderBuilder()
          .setProvider(BouncyCastleSupport.PROVIDER)
          .build(csr.getSubjectPublicKeyInfo()));
      if (!valid) {
        throw new IllegalArgumentException("CSR signature is invalid");
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to verify CSR signature", e);
    }
  }

  private String commonName(PKCS10CertificationRequest csr) {
    RDN[] rdns = csr.getSubject().getRDNs(BCStyle.CN);
    if (rdns.length == 0) {
      return null;
    }
    return rdns[0].getFirst().getValue().toString();
  }

  private PublicKey publicKey(PKCS10CertificationRequest csr) {
    try {
      return new JcaPEMKeyConverter()
          .setProvider(BouncyCastleSupport.PROVIDER)
          .getPublicKey(csr.getSubjectPublicKeyInfo());
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to parse CSR public key", e);
    }
  }

  private GeneralNames subjectAlternativeNames(PKCS10CertificationRequest csr) {
    Attribute[] attributes = csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest);
    if (attributes == null || attributes.length == 0) {
      return null;
    }
    ASN1Set values = attributes[0].getAttrValues();
    if (values == null || values.size() == 0) {
      return null;
    }
    Extensions extensions = Extensions.getInstance(values.getObjectAt(0));
    return GeneralNames.fromExtensions(extensions, Extension.subjectAlternativeName);
  }

  public record ParsedCsr(PKCS10CertificationRequest request, String commonName, PublicKey publicKey, GeneralNames subjectAlternativeNames) {
  }
}
