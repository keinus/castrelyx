package org.castrelyx.castrelsign.security;

import org.springframework.stereotype.Service;

@Service
public class AdminTokenService {
  private final org.castrelyx.castrelsign.kms.KmsAuthentication authentication;

  public AdminTokenService(org.castrelyx.castrelsign.kms.KmsAuthentication authentication) {
    this.authentication = authentication;
  }

  public void requireValid(String authorizationHeader) {
    var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
      var request = servlet.getRequest();
      var identity = authentication.authenticate(request);
      if (java.util.Set.of("GET", "HEAD").contains(request.getMethod())) {
        if (!identity.roles().contains("AUDITOR") && !identity.roles().contains("OPERATOR"))
          throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "administrator permission denied");
      } else identity.require("OPERATOR", null);
      return;
    }
    authentication.authenticate(authorizationHeader, null, false);
    /* All HTTP callers are authenticated by AdminSecurityFilter before reaching controllers. */
  }

}
