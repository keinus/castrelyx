package org.castrelyx.castrelsign;

import org.castrelyx.castrelsign.bootstrap.BootstrapEnvironment;
import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.BouncyCastleSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CastrelSignProperties.class)
public class CastrelSignApplication {
  public static void main(String[] args) {
    BouncyCastleSupport.install();
    BootstrapEnvironment.apply();
    SpringApplication.run(CastrelSignApplication.class, args);
  }
}
