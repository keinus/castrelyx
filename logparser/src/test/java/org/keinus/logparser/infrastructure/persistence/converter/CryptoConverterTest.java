package org.keinus.logparser.infrastructure.persistence.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoConverterTest {

    private CryptoConverter converter;
    private final String secretKey = "this-is-a-very-secret-key-123456";
    private final String salt = "deadbeef";

    @BeforeEach
    void setUp() {
        converter = new CryptoConverter();
        ReflectionTestUtils.setField(converter, "secretKey", secretKey);
        ReflectionTestUtils.setField(converter, "salt", salt);
        converter.init();
    }

    @Test
    void testInitFailsIfSecretKeyNull() {
        CryptoConverter c = new CryptoConverter();
        ReflectionTestUtils.setField(c, "secretKey", null);
        ReflectionTestUtils.setField(c, "salt", salt);
        assertThatThrownBy(c::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testInitFailsIfSaltNull() {
        CryptoConverter c = new CryptoConverter();
        ReflectionTestUtils.setField(c, "secretKey", secretKey);
        ReflectionTestUtils.setField(c, "salt", null);
        assertThatThrownBy(c::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testInitFailsIfSecretKeyTooShort() {
        CryptoConverter c = new CryptoConverter();
        ReflectionTestUtils.setField(c, "secretKey", "short");
        ReflectionTestUtils.setField(c, "salt", salt);
        assertThatThrownBy(c::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testEncryptionAndDecryption() {
        String original = "sensitive-data";
        String encrypted = converter.convertToDatabaseColumn(original);
        assertThat(encrypted).isNotEqualTo(original);
        
        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void testConvertNullOrEmpty() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn("")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    void testDecryptionFailure() {
        String invalidEncryptedData = "not-encrypted-data";
        String result = converter.convertToEntityAttribute(invalidEncryptedData);
        assertThat(result).isEqualTo(invalidEncryptedData);
    }
}
