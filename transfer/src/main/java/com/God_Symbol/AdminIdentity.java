package com.God_Symbol;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 环境变量管理员身份与 JWT 密钥的唯一来源。
 *
 * <p>JWT 密钥优先使用 {@code transfer.admin.jwt-secret}；未配置时由管理员用户名和密码派生，
 * 因此修改密码会让已签发的 JWT 立即失效。无论使用哪种密钥，令牌中都会带上
 * {@link #credentialFingerprint()}，由 {@code JwtDecoder} 在每次请求时校验。
 */
@Component
public class AdminIdentity {
  private static final String JWT_DOMAIN = "dropkey/admin-jwt/v1|";
  private static final String CREDENTIAL_DOMAIN = "dropkey/admin-cred/v1|";

  private final AdminProperties properties;
  private final String username;
  private final String password;
  private final byte[] jwtKey;
  private final String credentialFingerprint;

  public AdminIdentity(AdminProperties properties) {
    this.properties = properties;
    if (properties.configured()) {
      this.username = properties.getUsername().trim();
      this.password = properties.getPassword();
      this.jwtKey = resolveJwtKey(properties.getJwtSecret(), username, password);
      this.credentialFingerprint = sha256(CREDENTIAL_DOMAIN + username + "|" + password);
    } else {
      this.username = "";
      this.password = "";
      // 管理员未启用时仍需要一对密钥来构造 encoder/decoder；随机值让任何令牌都无法通过校验。
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      this.jwtKey = random;
      this.credentialFingerprint = "";
    }
  }

  public boolean configured() { return properties.configured(); }

  public String username() { return username; }

  /** 仅供认证组件构造 UserDetails 使用，不得写入日志或响应。 */
  String password() { return password; }

  public byte[] jwtKey() { return jwtKey.clone(); }

  public String credentialFingerprint() { return credentialFingerprint; }

  private static byte[] resolveJwtKey(String configuredSecret, String username, String password) {
    if (configuredSecret != null && !configuredSecret.isBlank()) {
      byte[] secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
      if (secret.length < 32) {
        throw new IllegalStateException("transfer.admin.jwt-secret must be at least 32 bytes");
      }
      return secret;
    }
    return sha256Bytes(JWT_DOMAIN + username + "|" + password);
  }

  private static String sha256(String value) {
    return HexFormat.of().formatHex(sha256Bytes(value));
  }

  private static byte[] sha256Bytes(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
