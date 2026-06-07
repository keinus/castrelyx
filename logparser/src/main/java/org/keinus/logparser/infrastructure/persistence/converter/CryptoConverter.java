package org.keinus.logparser.infrastructure.persistence.converter;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

@Converter
@Component
@Slf4j
public class CryptoConverter implements AttributeConverter<String, String> {

    @Value("${logparser.crypto.secret-key:your-secret-key-change-this-in-production}")
    private String secretKey;

    @Value("${logparser.crypto.salt:deadbeef}")
    private String salt;

    private TextEncryptor encryptor;

    @PostConstruct
    public void init() {
        // 빈 문자열 또는 null 체크
        if (secretKey == null || secretKey.trim().isEmpty()) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("SECURITY ERROR: Crypto secret key is not configured!");
            log.error("REQUIRED: Set LOGPARSER_CRYPTO_KEY environment variable");
            log.error("Example: export LOGPARSER_CRYPTO_KEY=\"$(openssl rand -base64 32)\"");
            log.error("═══════════════════════════════════════════════════════════");
            throw new IllegalStateException(
                "Crypto secret key must be configured. " +
                "Set LOGPARSER_CRYPTO_KEY environment variable."
            );
        }

        if (salt == null || salt.trim().isEmpty()) {
            log.error("═══════════════════════════════════════════════════════════");
            log.error("SECURITY ERROR: Crypto salt is not configured!");
            log.error("REQUIRED: Set LOGPARSER_CRYPTO_SALT environment variable");
            log.error("Example: export LOGPARSER_CRYPTO_SALT=\"$(openssl rand -hex 16)\"");
            log.error("═══════════════════════════════════════════════════════════");
            throw new IllegalStateException(
                "Crypto salt must be configured. " +
                "Set LOGPARSER_CRYPTO_SALT environment variable."
            );
        }

        // 키 길이 검증
        if (secretKey.length() < 16) {
            throw new IllegalStateException(
                "Crypto secret key must be at least 16 characters long. Current length: " + secretKey.length()
            );
        }

        // TextEncryptor 초기화
        this.encryptor = Encryptors.text(secretKey, salt);
        log.info("CryptoConverter initialized with configured secret key");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.trim().isEmpty()) {
            return attribute;
        }
        try {
            return encryptor.encrypt(attribute);
        } catch (Exception e) {
            log.error("Error encrypting sensitive data - cannot store in plain text", e);
            throw new RuntimeException("Encryption failed for sensitive data", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return dbData;
        }
        try {
            return encryptor.decrypt(dbData);
        } catch (Exception e) {
            log.warn("Error decrypting data, returning original value", e);
            return dbData;
        }
    }
}
