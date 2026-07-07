package org.castrelyx.castrelvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CastrelVaultApplication {
  public static void main(String[] args) {
    SpringApplication.run(CastrelVaultApplication.class, args);
  }
}
