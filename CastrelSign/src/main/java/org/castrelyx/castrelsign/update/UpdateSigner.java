package org.castrelyx.castrelsign.update;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.castrelyx.castrelsign.kms.KeyManagementService;
import org.springframework.stereotype.Component;

@Component
public class UpdateSigner {
  private final KeyManagementService kms;
  public UpdateSigner(KeyManagementService kms) throws Exception {
    this.kms = kms;
    kms.ensureUpdate();
    Files.deleteIfExists(kms.dataDir().resolve("update-signing/private.pem"));
    Files.deleteIfExists(kms.dataDir().resolve("update-signing/private.pem.bak"));
  }
  public String publicKeyPem() { return current().publicKeyPem(); }
  public KeyManagementService.KeyView current() { return kms.current(KeyManagementService.UPDATE); }
  public List<KeyManagementService.KeyView> publicKeys() { return kms.keys().stream().filter(k -> KeyManagementService.UPDATE.equals(k.alias())).toList(); }
  public String sign(KeyManagementService.Ref ref, byte[] payload, String reference) {
    return kms.sign(ref, KeyManagementService.UPDATER, payload, reference, null).signature();
  }
  public boolean distributable(Map<String, Object> manifest) {
    Object id = manifest.get("signing_key_id"), version = manifest.get("signing_key_version");
    var key = id == null ? publicKeys().stream().filter(k -> k.version() == 1).findFirst().orElse(null)
        : kms.get(new KeyManagementService.Ref(id.toString(), ((Number) version).intValue()));
    return key != null && java.util.Set.of("ACTIVE", "VERIFY_ONLY").contains(key.state());
  }
  public void revoked(String reference) { if (reference != null) kms.releaseDependency(KeyManagementService.UPDATER, reference); }
}
