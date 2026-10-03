package org.keinus.logparser.domain.input.model.snmp;

import java.util.Locale;
import com.fasterxml.jackson.databind.JsonNode;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.*;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;

/** Shared SNMPv3 credentials. Secrets are resolved only when starting an adapter. */
public record SnmpSecurity(String name, int level, OID authProtocol, String authSecret,
                           OID privProtocol, String privSecret) {
    public static void validate(JsonNode node, JsonNode defaults) { read(node, defaults, false); }

    static SnmpSecurity read(JsonNode node, JsonNode defaults, boolean resolveSecrets) {
        String name = text(node, "securityName", text(defaults, "securityName", null));
        if (name == null) throw new IllegalArgumentException("SNMPv3 securityName is required");
        int level = level(text(node, "securityLevel", text(defaults, "securityLevel", "authPriv")));
        OID auth = level >= SecurityLevel.AUTH_NOPRIV
                ? authProtocol(text(node, "authProtocol", text(defaults, "authProtocol", "SHA256"))) : null;
        OID priv = level == SecurityLevel.AUTH_PRIV
                ? privProtocol(text(node, "privProtocol", text(defaults, "privProtocol", "AES128"))) : null;
        return new SnmpSecurity(name, level, auth,
                auth == null ? null : secret(node, defaults, "authPassphrase", resolveSecrets), priv,
                priv == null ? null : secret(node, defaults, "privPassphrase", resolveSecrets));
    }

    UsmUser user() {
        return new UsmUser(new OctetString(name), authProtocol,
                authSecret == null ? null : new OctetString(authSecret), privProtocol,
                privSecret == null ? null : new OctetString(privSecret));
    }

    public static int version(String value) {
        return switch (normalize(value)) {
            case "1", "v1" -> SnmpConstants.version1;
            case "2", "2c", "v2c" -> SnmpConstants.version2c;
            case "3", "v3" -> SnmpConstants.version3;
            default -> throw new IllegalArgumentException("Unsupported SNMP version: " + value);
        };
    }

    static int level(String value) {
        return switch (normalize(value).replace("_", "")) {
            case "noauthnopriv" -> SecurityLevel.NOAUTH_NOPRIV;
            case "authnopriv" -> SecurityLevel.AUTH_NOPRIV;
            case "authpriv" -> SecurityLevel.AUTH_PRIV;
            default -> throw new IllegalArgumentException("Unsupported SNMPv3 securityLevel: " + value);
        };
    }

    static OID authProtocol(String value) {
        return switch (normalize(value)) {
            case "md5" -> AuthMD5.ID;
            case "sha", "sha1" -> AuthSHA.ID;
            case "sha224" -> AuthHMAC128SHA224.ID;
            case "sha256" -> AuthHMAC192SHA256.ID;
            case "sha384" -> AuthHMAC256SHA384.ID;
            case "sha512" -> AuthHMAC384SHA512.ID;
            default -> throw new IllegalArgumentException("Unsupported SNMPv3 authProtocol: " + value);
        };
    }

    static OID privProtocol(String value) {
        return switch (normalize(value)) {
            case "des" -> PrivDES.ID;
            case "aes", "aes128" -> PrivAES128.ID;
            case "aes192" -> PrivAES192.ID;
            case "aes256" -> PrivAES256.ID;
            default -> throw new IllegalArgumentException("Unsupported SNMPv3 privProtocol: " + value);
        };
    }

    static OctetString engineId(String value) {
        String hex = value.replace(":", "").replace(" ", "");
        if (!hex.matches("(?i)[0-9a-f]{10,64}") || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("SNMP engineId must contain 5 to 32 hexadecimal bytes");
        }
        return new OctetString(java.util.HexFormat.of().parseHex(hex));
    }

    static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private static String secret(JsonNode node, JsonNode defaults, String field, boolean resolve) {
        String value = text(node, field, null);
        String env = text(node, field + "Env", null);
        if (value == null && env == null) {
            value = text(defaults, field, null);
            env = text(defaults, field + "Env", null);
        }
        if (value == null && env != null) {
            if (!resolve) return null;
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SNMPv3 " + field + " or " + field + "Env is required");
        }
        if (new OctetString(value).length() < 8) {
            throw new IllegalArgumentException("SNMPv3 " + field + " must contain at least 8 bytes");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override public String toString() { return "SnmpSecurity[name=" + name + ", level=" + level + "]"; }
}
