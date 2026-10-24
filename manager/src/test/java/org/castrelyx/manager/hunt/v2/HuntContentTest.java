package org.castrelyx.manager.hunt.v2;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class HuntContentTest {
  @Test
  void validatesLiteralAddressesAndLookupLimitsBeforeExecution() {
    assertThatCode(() -> lookup("internal_networks", List.of("10.0.0.0/8", "2001:db8::/32")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> lookup("approved_dns", List.of("attacker.example")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> lookup("approved_services", List.of("65536")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> lookup("business_hours", List.of("18-9")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresIndicatorProvenanceConfidenceAndFutureExpiration() {
    var data =
        new LinkedHashMap<String, Object>(
            Map.of(
                "type",
                "domain",
                "value",
                "malicious.example",
                "source",
                "analyst review",
                "confidence",
                75,
                "expiresAt",
                Instant.now().plusSeconds(86400).toString()));
    assertThatCode(() -> indicator(data)).doesNotThrowAnyException();
    data.put("expiresAt", Instant.now().minusSeconds(1).toString());
    assertThatThrownBy(() -> indicator(data)).isInstanceOf(IllegalArgumentException.class);
    data.put("expiresAt", Instant.now().plusSeconds(86400).toString());
    data.put("confidence", 101);
    assertThatThrownBy(() -> indicator(data)).isInstanceOf(IllegalArgumentException.class);
    data.put("confidence", 75);
    data.put("source", "");
    assertThatThrownBy(() -> indicator(data)).isInstanceOf(IllegalArgumentException.class);
  }

  private void lookup(String name, List<String> values) {
    HuntContent.validate("lookup", new HuntWorkspace.Save(name, true, Map.of("values", values), 0));
  }

  private void indicator(Map<String, Object> body) {
    HuntContent.validate("indicator", new HuntWorkspace.Save("test", true, body, 0));
  }
}
