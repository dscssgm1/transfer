package com.God_Symbol;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 目前只有环境变量管理员可以登录。
 *
 * <p>普通账号已经保存在 {@code accounts} 表中，但本次不开放登录；后续需要时，
 * 在此按用户名回退查询 {@link AccountsRepository} 并校验 {@code enabled} 即可。
 */
public class AdminUserDetailsService implements UserDetailsService {
  private final String adminUsername;
  private final String adminPasswordHash;

  public AdminUserDetailsService(AdminIdentity identity, PasswordEncoder encoder) {
    if (identity.configured()) {
      this.adminUsername = identity.username();
      this.adminPasswordHash = encoder.encode(identity.password());
    } else {
      this.adminUsername = null;
      this.adminPasswordHash = null;
    }
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    if (adminUsername == null || !adminUsername.equals(username)) {
      throw new UsernameNotFoundException("user not found");
    }
    // 必须每次返回新实例：ProviderManager 在认证成功后会擦除 principal 的凭据，
    // 复用同一个 UserDetails 会导致第二次登录永远失败。
    return User.withUsername(adminUsername).password(adminPasswordHash).roles("ADMIN").build();
  }
}
