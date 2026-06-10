package org.castrelyx.manager.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/setup")
public class SetupController {
  private final LocalAuthProvider authProvider;

  public SetupController(LocalAuthProvider authProvider) {
    this.authProvider = authProvider;
  }

  @GetMapping("/status")
  public SetupStatus status() {
    return new SetupStatus(authProvider.setupRequired());
  }

  @PostMapping("/admin")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthUser createAdmin(@Valid @RequestBody CreateAdminRequest request) {
    if (!authProvider.setupRequired()) {
      throw new IllegalStateException("admin user already exists");
    }
    return authProvider.createLocalUser(request.username(), request.password(), request.displayName(), Role.ADMIN);
  }

  public record SetupStatus(boolean required) {
  }

  public record CreateAdminRequest(
      @NotBlank String username,
      @NotBlank String password,
      String displayName) {
  }
}
