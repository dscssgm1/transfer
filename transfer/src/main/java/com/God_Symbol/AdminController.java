package com.God_Symbol;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 管理员接口。登录使用环境变量中的管理员账号，成功后签发 JWT；
 * 除 config/login 外的接口都由 Spring Security 要求 ROLE_ADMIN。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminIdentity adminIdentity;
  private final LoginService loginService;
  private final AccountService accountService;

  public AdminController(AdminIdentity adminIdentity, LoginService loginService, AccountService accountService) {
    this.adminIdentity = adminIdentity;
    this.loginService = loginService;
    this.accountService = accountService;
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    return Map.of("configured", adminIdentity.configured());
  }

  /**
   * 旧的管理员登录接口，仅用于兼容。新前端统一使用 {@code POST /api/auth/login}。
   * 这里保持“未配置管理员返回 503、普通账号返回 401”的原有语义。
   */
  @PostMapping("/login")
  public LoginService.LoginResult login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    if (!adminIdentity.configured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "administrator login is not configured");
    }
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
    }
    return loginService.login(request.username(), request.password(), ClientAddress.key(httpRequest), true);
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
    return Map.of(
        "username", jwt.getSubject(),
        "roles", jwt.getClaimAsStringList("roles"),
        "expiresAt", String.valueOf(jwt.getExpiresAt()));
  }

  @GetMapping("/accounts")
  public Map<String, Object> list() {
    List<AccountService.AccountView> accounts = accountService.list();
    return Map.of("accounts", accounts);
  }

  @PostMapping("/accounts")
  @ResponseStatus(HttpStatus.CREATED)
  public AccountService.AccountView create(@RequestBody CreateAccountRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    return accountService.create(request.username(), request.password(), request.enabled(), jwt.getSubject());
  }

  @PatchMapping("/accounts/{id}")
  public AccountService.AccountView update(@PathVariable long id,
      @RequestBody UpdateAccountRequest request) {
    if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    return accountService.update(id, request.enabled(), request.password());
  }

  @DeleteMapping("/accounts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id) {
    accountService.delete(id);
  }

  record LoginRequest(String username, String password) {}
  record CreateAccountRequest(String username, String password, Boolean enabled) {}
  record UpdateAccountRequest(Boolean enabled, String password) {}
}
