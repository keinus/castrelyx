package org.castrelyx.manager.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClickHouseClientTest {
  @Test
  void formatsDateTime64ValuesWithoutIsoZuluSuffixForJsonEachRow() {
    assertThat(ClickHouseClient.formatDateTime64(Instant.parse("2026-06-09T10:01:02.345Z")))
        .isEqualTo("2026-06-09 10:01:02.345");
  }
}
