package com.God_Symbol;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 普通账号登录相关配置。管理员令牌的有效期仍由 {@link AdminProperties} 控制。
 */
@ConfigurationProperties(prefix = "transfer.auth")
public class AuthProperties {
  private static final Duration MIN_TTL = Duration.ofMinutes(5);
  private static final Duration MAX_TTL = Duration.ofDays(7);

  private Duration sessionTtl = Duration.ofHours(8);

  public Duration getSessionTtl() { return sessionTtl; }
  public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }

  public Duration effectiveSessionTtl() {
    if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) return Duration.ofHours(8);
    if (sessionTtl.compareTo(MIN_TTL) < 0) return MIN_TTL;
    if (sessionTtl.compareTo(MAX_TTL) > 0) return MAX_TTL;
    return sessionTtl;
  }
}
