package com.God_Symbol;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Locale;

/**
 * 统一登录入口：环境变量管理员优先，其余用户名回退到 {@code accounts} 表。
 *
 * <p>每次调用都返回新的 {@link UserDetails} 实例：ProviderManager 在认证成功后会擦除
 * principal 的凭据，复用同一个实例会导致第二次登录永远失败。
 */
public class LoginUserDetailsService implements UserDetailsService {
  private final AdminUserDetailsService adminUserDetailsService;
  private final AdminIdentity adminIdentity;
  private final AccountsRepository accounts;

  public LoginUserDetailsService(AdminUserDetailsService adminUserDetailsService, AdminIdentity adminIdentity,
      AccountsRepository accounts) {
    this.adminUserDetailsService = adminUserDetailsService;
    this.adminIdentity = adminIdentity;
    this.accounts = accounts;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    if (adminIdentity.configured() && adminIdentity.username().equals(username)) {
      return adminUserDetailsService.loadUserByUsername(username);
    }
    String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    AccountsRepository.AccountCredentials account = accounts.findCredentialsByUsername(normalized)
        .filter(candidate -> !candidate.systemAccount())
        .orElseThrow(() -> new UsernameNotFoundException("user not found"));
    return User.withUsername(account.username())
        .password(account.passwordHash())
        .roles("USER")
        .disabled(!account.enabled())
        .build();
  }
}
