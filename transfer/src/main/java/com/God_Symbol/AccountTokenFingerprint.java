package com.God_Symbol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 普通账号令牌的凭据指纹。
 *
 * <p>必须使用从数据库重新读出的 {@code password_changed_at}（DATETIME(6) 精度），
 * 不能用写入前的 {@code Instant.now()}，否则 MySQL 截断纳秒后会导致登录立即失效。
 * 重置密码会更新该时间，使旧令牌立刻不可用。
 */
final class AccountTokenFingerprint {
  private static final String DOMAIN = "dropkey/account-cred/v1|";

  private AccountTokenFingerprint() {}

  static String of(long accountId, String username, Instant passwordChangedAt) {
    String raw = DOMAIN + accountId + "|" + username + "|" + passwordChangedAt;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
