package org.castrelyx.manager.hunt.v2;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.castrelyx.manager.auth.ForbiddenException;
import org.castrelyx.manager.web.AuthController;
import org.springframework.stereotype.Component;

/** Session-bound CSRF token for the new API, without changing legacy clients. */
@Component
public class HuntAccess {
  private final byte[] key = new byte[32];

  public HuntAccess() {
    new SecureRandom().nextBytes(key);
  }

  public String token(HttpServletRequest request) {
    String session = "";
    if (request.getCookies() != null)
      for (var cookie : request.getCookies())
        if (cookie.getName().equals(AuthController.SESSION_COOKIE)) session = cookie.getValue();
    if (session.isEmpty()) throw new ForbiddenException("인증 세션이 필요합니다.");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(session.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public void verify(HttpServletRequest request) {
    String supplied = request.getHeader("X-Hunt-CSRF");
    if (supplied == null
        || !MessageDigest.isEqual(
            token(request).getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ForbiddenException("세션 검증 값이 만료되었거나 없습니다. 새로고침 후 다시 실행하세요.");
  }
}
