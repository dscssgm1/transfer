package com.God_Symbol;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 管理员相关配置。管理员账号只来自环境变量，不写入数据库。
 */
@ConfigurationProperties(prefix = "transfer.admin")
public class AdminProperties {
  private static final Duration MIN_TTL = Duration.ofMinutes(5);
  private static final Duration MAX_TTL = Duration.ofDays(7);

  private String username = "";
  private String password = "";
  private String jwtSecret = "";
  private Duration sessionTtl = Duration.ofHours(8);
  private int loginMaxAttempts = 5;
  private Duration loginWindow = Duration.ofMinutes(15);

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username == null ? "" : username; }

  public String getPassword() { return password; }
  public void setPassword(String password) { this.password = password == null ? "" : password; }

  public String getJwtSecret() { return jwtSecret; }
  public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret == null ? "" : jwtSecret; }

  public Duration getSessionTtl() { return sessionTtl; }
  public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }

  public int getLoginMaxAttempts() { return loginMaxAttempts; }
  public void setLoginMaxAttempts(int loginMaxAttempts) { this.loginMaxAttempts = loginMaxAttempts; }

  public Duration getLoginWindow() { return loginWindow; }
  public void setLoginWindow(Duration loginWindow) { this.loginWindow = loginWindow; }

  /** 用户名和密码都非空才启用管理员功能。 */
  public boolean configured() {
    return !username.isBlank() && !password.isBlank();
  }

  public Duration effectiveSessionTtl() {
    if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) return Duration.ofHours(8);
    if (sessionTtl.compareTo(MIN_TTL) < 0) return MIN_TTL;
    if (sessionTtl.compareTo(MAX_TTL) > 0) return MAX_TTL;
    return sessionTtl;
  }

  public Duration effectiveLoginWindow() {
    if (loginWindow == null || loginWindow.isZero() || loginWindow.isNegative()) return Duration.ofMinutes(15);
    return loginWindow;
  }

  public int effectiveLoginMaxAttempts() {
    return loginMaxAttempts > 0 ? loginMaxAttempts : 5;
  }
}
