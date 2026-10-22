package com.God_Symbol;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {
  private final JwtEncoder encoder;
  private final AdminIdentity identity;
  private final AdminProperties adminProperties;
  private final AuthProperties authProperties;
  private final Clock clock;

  public JwtService(JwtEncoder encoder, AdminIdentity identity, AdminProperties adminProperties,
      AuthProperties authProperties, Clock clock) {
    this.encoder = encoder;
    this.identity = identity;
    this.adminProperties = adminProperties;
    this.authProperties = authProperties;
    this.clock = clock;
  }

  /** 管理员令牌：沿用原有 claims，有效期由 transfer.admin.session-ttl 控制。 */
  public IssuedToken issueAdmin() {
    JwtClaimsSet claims = claims(identity.username(), adminProperties.effectiveSessionTtl())
        .claim("roles", List.of("ADMIN"))
        .claim("cred", identity.credentialFingerprint())
        .build();
    return issue(claims);
  }

  /** 普通账号令牌：uid 与 pwdv 用于每次请求时回查账号与密码变更状态。 */
  public IssuedToken issueUser(long accountId, String username, Instant passwordChangedAt) {
    JwtClaimsSet claims = claims(username, authProperties.effectiveSessionTtl())
        .claim("roles", List.of("USER"))
        .claim("uid", Long.toString(accountId))
        .claim("pwdv", AccountTokenFingerprint.of(accountId, username, passwordChangedAt))
        .build();
    return issue(claims);
  }

  private JwtClaimsSet.Builder claims(String username, Duration ttl) {
    Instant now = clock.instant();
    return JwtClaimsSet.builder()
        .subject(username)
        .issuedAt(now)
        .expiresAt(now.plus(ttl))
        .id(UUID.randomUUID().toString());
  }

  private IssuedToken issue(JwtClaimsSet claims) {
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedToken(token, claims.getExpiresAt());
  }

  public record IssuedToken(String token, Instant expiresAt) {}
}
