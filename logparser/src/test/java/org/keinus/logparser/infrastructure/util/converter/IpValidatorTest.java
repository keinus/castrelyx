package org.keinus.logparser.infrastructure.util.converter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IpValidatorTest {

    @Test
    void testValidateIpv4() {
        assertThat(IpValidator.validate("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(IpValidator.validate("192.168.0.1")).isEqualTo("192.168.0.1");
        assertThat(IpValidator.validate("255.255.255.255")).isEqualTo("255.255.255.255");
    }

    @Test
    void testValidateIpv6() {
        // InetAddress canonicalizes IPv6
        String result = IpValidator.validate("::1");
        assertThat(result).isNotNull();
        assertThat(result).contains(":");
    }

    @Test
    void testValidateInvalid() {
        assertThat(IpValidator.validate("999.999.999.999")).isNull();
        assertThat(IpValidator.validate("abc.def.ghi.jkl")).isNull();
        assertThat(IpValidator.validate(null)).isNull();
        assertThat(IpValidator.validate("")).isNull();
        assertThat(IpValidator.validate("-")).isNull();
    }
}
