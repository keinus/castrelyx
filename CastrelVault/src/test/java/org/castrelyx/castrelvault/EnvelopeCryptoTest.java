package org.castrelyx.castrelvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.castrelyx.castrelvault.config.CastrelVaultProperties;
import org.castrelyx.castrelvault.crypto.EnvelopeCrypto;
import org.castrelyx.castrelvault.crypto.MasterKeyRegistry;
import org.junit.jupiter.api.Test;

class EnvelopeCryptoTest {
  @Test
  void encryptsDecryptsAndRandomizesCiphertext() {
    EnvelopeCrypto crypto = crypto("k1", key("a"));
    byte[] plaintext = "{\"value\":\"same-secret\"}".getBytes(StandardCharsets.UTF_8);

    var first = crypto.encrypt(plaintext);
    var second = crypto.encrypt(plaintext);

    assertThat(first.keyId()).isEqualTo("k1");
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    assertThat(first.encryptionNonce()).isNotEqualTo(second.encryptionNonce());
    assertThat(first.containsPlaintext("same-secret")).isFalse();
    assertThat(crypto.decrypt(first.keyId(), first.wrappedKeyNonce(), first.encryptedDataKey(), first.encryptionNonce(), first.ciphertext()))
        .isEqualTo(plaintext);
  }

  @Test
  void wrongMasterKeyCannotDecryptWrappedDataKey() {
    EnvelopeCrypto crypto = crypto("k1", key("a"));
    EnvelopeCrypto wrong = crypto("k1", key("b"));
    byte[] plaintext = "{\"value\":\"do-not-leak\"}".getBytes(StandardCharsets.UTF_8);
    var encrypted = crypto.encrypt(plaintext);

    assertThatThrownBy(() -> wrong.decrypt(encrypted.keyId(), encrypted.wrappedKeyNonce(), encrypted.encryptedDataKey(),
        encrypted.encryptionNonce(), encrypted.ciphertext()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not decryptable");
  }

  @Test
  void existingVersionsRemainDecryptableWhenActiveKeyChanges() {
    CastrelVaultProperties oldProperties = properties("old:%s,new:%s".formatted(key("a"), key("b")), "old");
    EnvelopeCrypto oldCrypto = new EnvelopeCrypto(new MasterKeyRegistry(oldProperties));
    var oldVersion = oldCrypto.encrypt("{\"value\":\"old\"}".getBytes(StandardCharsets.UTF_8));

    CastrelVaultProperties rotatedProperties = properties("old:%s,new:%s".formatted(key("a"), key("b")), "new");
    EnvelopeCrypto rotatedCrypto = new EnvelopeCrypto(new MasterKeyRegistry(rotatedProperties));
    var newVersion = rotatedCrypto.encrypt("{\"value\":\"new\"}".getBytes(StandardCharsets.UTF_8));

    assertThat(oldVersion.keyId()).isEqualTo("old");
    assertThat(newVersion.keyId()).isEqualTo("new");
    assertThat(rotatedCrypto.decrypt(oldVersion.keyId(), oldVersion.wrappedKeyNonce(), oldVersion.encryptedDataKey(),
        oldVersion.encryptionNonce(), oldVersion.ciphertext()))
        .isEqualTo("{\"value\":\"old\"}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void missingOrInvalidMasterKeyConfigurationFailsFast() {
    assertThatThrownBy(() -> new MasterKeyRegistry(properties("", "active")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CASTRELVAULT_MASTER_KEYS");

    assertThatThrownBy(() -> new MasterKeyRegistry(properties("old:%s".formatted(key("a")), "new")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACTIVE_MASTER_KEY_ID");
  }

  private static EnvelopeCrypto crypto(String keyId, String key) {
    return new EnvelopeCrypto(new MasterKeyRegistry(properties(keyId + ":" + key, keyId)));
  }

  private static CastrelVaultProperties properties(String keys, String active) {
    CastrelVaultProperties properties = new CastrelVaultProperties();
    properties.setMasterKeys(keys);
    properties.setActiveMasterKeyId(active);
    return properties;
  }

  private static String key(String seed) {
    String value = (seed.repeat(32) + "00000000000000000000000000000000").substring(0, 32);
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
