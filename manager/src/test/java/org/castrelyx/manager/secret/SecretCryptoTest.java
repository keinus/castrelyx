package org.castrelyx.manager.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCryptoTest {
  @Test
  void encryptsAndDecryptsWithVersionedAesGcmEnvelope() {
    SecretCrypto crypto = new SecretCrypto("12345678901234567890123456789012");

    String ciphertext = crypto.encrypt("plain-admin-token");

    assertThat(ciphertext).startsWith("v1:");
    assertThat(ciphertext).doesNotContain("plain-admin-token");
    assertThat(crypto.decrypt(ciphertext)).isEqualTo("plain-admin-token");
  }

  @Test
  void rejectsMissingOrShortCryptoKeys() {
    assertThatThrownBy(() -> new SecretCrypto(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MANAGER_CRYPTO_KEY");
    assertThatThrownBy(() -> new SecretCrypto("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void failsToDecryptWithWrongKeyAndMasksConfiguredSecrets() {
    SecretCrypto crypto = new SecretCrypto("12345678901234567890123456789012");
    String ciphertext = crypto.encrypt("sensitive-value");

    assertThatThrownBy(() -> new SecretCrypto("abcdefghijklmnopqrstuvwxyz123456").decrypt(ciphertext))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(SecretMasker.mask(null)).isEqualTo(new SecretValue(false, null));
    assertThat(SecretMasker.mask(ciphertext)).isEqualTo(new SecretValue(true, "********"));
  }
}
