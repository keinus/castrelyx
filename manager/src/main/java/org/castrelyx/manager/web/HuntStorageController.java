package org.castrelyx.manager.web;

import jakarta.servlet.http.HttpServletRequest;
import org.castrelyx.manager.auth.*;
import org.castrelyx.manager.hunt.v2.HuntReadModelOperations;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/security/hunt/v2/storage")
public class HuntStorageController {
  private final HuntReadModelOperations operations;

  public HuntStorageController(HuntReadModelOperations operations) {
    this.operations = operations;
  }

  public record Backfill(String partition) {}

  @GetMapping
  public Object status(HttpServletRequest request) {
    return operations.status(user(request));
  }

  @PostMapping("/install")
  public Object install(HttpServletRequest request) {
    return operations.install(user(request));
  }

  @PostMapping("/backfill")
  public Object backfill(@RequestBody Backfill input, HttpServletRequest request) {
    return operations.backfill(input.partition(), user(request));
  }

  private static AuthUser user(HttpServletRequest request) {
    return (AuthUser) request.getAttribute(CurrentUser.REQUEST_ATTRIBUTE);
  }
}
