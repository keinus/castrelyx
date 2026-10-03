package org.castrelyx.castrelsign;

import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.BouncyCastleSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(CastrelSignProperties.class)
@EnableScheduling
public class CastrelSignApplication {
  public static void main(String[] args) {
    if (args.length > 0 && "kms-backup".equals(args[0])) {
      try { org.castrelyx.castrelsign.kms.KmsBackupTool.run(args); }
      catch (Exception e) { throw new IllegalStateException("KMS backup operation failed", e); }
      return;
    }
    BouncyCastleSupport.install();
    SpringApplication.run(CastrelSignApplication.class, args);
  }
}
