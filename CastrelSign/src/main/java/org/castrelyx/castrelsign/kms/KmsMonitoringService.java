package org.castrelyx.castrelsign.kms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KmsMonitoringService {
  private static final Logger log = LoggerFactory.getLogger(KmsMonitoringService.class);
  private final KeyManagementService kms;
  public KmsMonitoringService(KeyManagementService kms) { this.kms = kms; }
  @Scheduled(fixedDelayString = "${castrelsign.kms.health-check-interval-ms:60000}", initialDelayString = "${castrelsign.kms.health-check-initial-delay-ms:60000}")
  public void check() {
    try {
      var health = kms.health();
      if (!"UP".equals(health.get("status"))) log.error("KMS_DEGRADED inactiveCurrentKeys={}", health.get("inactiveCurrentKeys"));
      if (((Number) health.get("keysExpiringWithin30Days")).longValue() > 0) log.warn("KMS_ROTATION_DUE keysExpiringWithin30Days={}", health.get("keysExpiringWithin30Days"));
    } catch (Exception e) { log.error("KMS_INTEGRITY_OR_STORAGE_FAILURE operator investigation required"); }
  }
}
