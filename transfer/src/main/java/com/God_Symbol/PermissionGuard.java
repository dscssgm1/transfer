package com.God_Symbol;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 功能权限校验，供后续受保护接口使用：
 *
 * <pre>{@code
 * @PreAuthorize("@permissionGuard.has(authentication, 'transfer.manage')")
 * }</pre>
 *
 * <p>普通用户每次请求都会实时查询 {@code account_permissions}，因此撤销权限立即生效；
 * 管理员（环境变量账号）绕过所有权限检查。
 */
@Component("permissionGuard")
public class PermissionGuard {
  private final PermissionService permissionService;
  private final AdminIdentity adminIdentity;

  public PermissionGuard(PermissionService permissionService, AdminIdentity adminIdentity) {
    this.permissionService = permissionService;
    this.adminIdentity = adminIdentity;
  }

  public boolean has(Authentication authentication, String code) {
    if (authentication != null && isAdmin(authentication)) return true;
    if (isAnonymous(authentication)) {
      // 未登录访客统一使用“未登录用户”系统账号的授权。
      return permissionService.hasAnonymous(code);
    }
    if (!(authentication.getPrincipal() instanceof Jwt jwt)) return false;
    String uid = jwt.getClaimAsString("uid");
    if (uid == null || uid.isBlank()) return false;
    try {
      return permissionService.has(Long.parseLong(uid), code);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean isAnonymous(Authentication authentication) {
    return authentication == null || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken;
  }

  public boolean hasAny(Authentication authentication, String... codes) {
    if (codes == null) return false;
    for (String code : codes) {
      if (has(authentication, code)) return true;
    }
    return false;
  }

  private boolean isAdmin(Authentication authentication) {
    boolean roleAdmin = authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    return roleAdmin && adminIdentity.configured()
        && adminIdentity.username().equals(authentication.getName());
  }
}
