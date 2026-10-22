package com.God_Symbol;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一登录入口：管理员与普通账号都通过 {@code /api/auth/login} 登录。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final LoginService loginService;
  private final PermissionService permissionService;

  public AuthController(LoginService loginService, PermissionService permissionService) {
    this.loginService = loginService;
    this.permissionService = permissionService;
  }

  @PostMapping("/login")
  public LoginService.LoginResult login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
    }
    return loginService.login(request.username(), request.password(), ClientAddress.key(httpRequest), false);
  }

  /** 刷新页面后用本地 token 校验会话，并返回实时权限。 */
  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList("roles");
    boolean admin = roles != null && roles.contains("ADMIN");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", jwt.getSubject());
    body.put("roles", roles == null ? List.of() : roles);
    if (admin) {
      body.put("permissions", permissionService.allCodes());
    } else {
      String uid = jwt.getClaimAsString("uid");
      if (uid == null || uid.isBlank()) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
      }
      long accountId;
      try {
        accountId = Long.parseLong(uid);
      } catch (NumberFormatException e) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
      }
      body.put("accountId", accountId);
      body.put("permissions", permissionService.codesForAccount(accountId));
    }
    Instant expiresAt = jwt.getExpiresAt();
    body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
    return body;
  }

  record LoginRequest(String username, String password) {}
}
