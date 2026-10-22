package com.God_Symbol;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * 管理员与普通账号共用的登录流程。
 *
 * <p>{@code adminOnly} 仅供旧的 {@code /api/admin/login} 使用，保证该接口不会给普通账号签发令牌。
 */
@Service
public class LoginService {
  private final AdminIdentity adminIdentity;
  private final AuthenticationManager authenticationManager;
  private final JwtService jwtService;
  private final LoginRateLimiter loginRateLimiter;
  private final AccountsRepository accounts;
  private final PermissionService permissionService;

  public LoginService(AdminIdentity adminIdentity, AuthenticationManager authenticationManager,
      JwtService jwtService, LoginRateLimiter loginRateLimiter, AccountsRepository accounts,
      PermissionService permissionService) {
    this.adminIdentity = adminIdentity;
    this.authenticationManager = authenticationManager;
    this.jwtService = jwtService;
    this.loginRateLimiter = loginRateLimiter;
    this.accounts = accounts;
    this.permissionService = permissionService;
  }

  public LoginResult login(String username, String password, String clientKey, boolean adminOnly) {
    if (adminOnly && !adminIdentity.configured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "administrator login is not configured");
    }
    if (username == null || password == null || username.isBlank() || password.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
    }

    loginRateLimiter.check(clientKey);
    Authentication authentication;
    try {
      authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(username.trim(), password));
    } catch (InternalAuthenticationServiceException e) {
      // 数据库等后端故障不能算作用户密码错误，也不能计入失败次数。
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "authentication service unavailable");
    } catch (AuthenticationException e) {
      loginRateLimiter.recordFailure(clientKey);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
    }

    boolean admin = isAdmin(authentication);
    if (adminOnly && !admin) {
      loginRateLimiter.recordFailure(clientKey);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
    }

    if (admin) {
      loginRateLimiter.reset(clientKey);
      JwtService.IssuedToken issued = jwtService.issueAdmin();
      return new LoginResult(issued.token(), "Bearer", adminIdentity.username(), List.of("ADMIN"),
          permissionService.allCodes(), issued.expiresAt());
    }

    String normalized = authentication.getName().trim().toLowerCase(Locale.ROOT);
    AccountsRepository.AccountCredentials account = accounts.findCredentialsByUsername(normalized)
        .filter(AccountsRepository.AccountCredentials::enabled)
        .filter(candidate -> !candidate.systemAccount())
        .orElseThrow(() -> {
          loginRateLimiter.recordFailure(clientKey);
          return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
        });

    loginRateLimiter.reset(clientKey);
    JwtService.IssuedToken issued = jwtService.issueUser(account.id(), account.username(),
        account.passwordChangedAt());
    return new LoginResult(issued.token(), "Bearer", account.username(), List.of("USER"),
        permissionService.codesForAccount(account.id()), issued.expiresAt());
  }

  private boolean isAdmin(Authentication authentication) {
    boolean roleAdmin = authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    return roleAdmin && adminIdentity.configured()
        && adminIdentity.username().equals(authentication.getName());
  }

  public record LoginResult(String token, String tokenType, String username, List<String> roles,
      List<String> permissions, java.time.Instant expiresAt) {}
}
