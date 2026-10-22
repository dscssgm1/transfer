package com.God_Symbol;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void issuedTokenCarriesAdminIdentity() {
    AdminProperties properties = properties("admin-password-123", SECRET);
    AdminIdentity identity = new AdminIdentity(properties);
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    JwtService service = service(identity, properties, new AuthProperties(), Clock.fixed(now, ZoneOffset.UTC));

    JwtService.IssuedToken issued = service.issueAdmin();
    Jwt jwt = adminDecoder(identity).decode(issued.token());

    assertEquals("admin", jwt.getSubject());
    assertEquals(List.of("ADMIN"), jwt.getClaimAsStringList("roles"));
    assertEquals(identity.credentialFingerprint(), jwt.getClaimAsString("cred"));
    assertEquals(now.plus(Duration.ofHours(8)), jwt.getExpiresAt());
    assertEquals(issued.expiresAt(), jwt.getExpiresAt());
  }

  @Test
  void passwordChangeInvalidatesExistingAdminToken() {
    AdminProperties original = properties("admin-password-123", SECRET);
    AdminIdentity originalIdentity = new AdminIdentity(original);
    JwtService service = service(originalIdentity, original, new AuthProperties(), Clock.systemUTC());
    String token = service.issueAdmin().token();

    AdminProperties changed = properties("another-password-456", SECRET);
    AdminIdentity changedIdentity = new AdminIdentity(changed);

    assertThrows(JwtException.class, () -> adminDecoder(changedIdentity).decode(token));
  }

  @Test
  void expiredAdminTokenIsRejected() {
    AdminProperties properties = properties("admin-password-123", SECRET);
    properties.setSessionTtl(Duration.ofMinutes(5));
    AdminIdentity identity = new AdminIdentity(properties);
    Clock tenMinutesAgo = Clock.fixed(Instant.now().minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
    JwtService service = service(identity, properties, new AuthProperties(), tenMinutesAgo);

    String token = service.issueAdmin().token();
    assertThrows(JwtException.class, () -> adminDecoder(identity).decode(token));
  }

  @Test
  void derivedKeyChangesWithPassword() {
    AdminProperties first = properties("admin-password-123", "");
    AdminProperties second = properties("another-password-456", "");
    assertFalse(Arrays.equals(new AdminIdentity(first).jwtKey(), new AdminIdentity(second).jwtKey()));
  }

  @Test
  void userTokenCarriesAccountClaimsAndUsesAuthTtl() {
    Fixture fixture = fixture();
    AuthProperties auth = new AuthProperties();
    auth.setSessionTtl(Duration.ofHours(2));
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    JwtService service = service(fixture.identity, properties("admin-password-123", SECRET), auth,
        Clock.fixed(now, ZoneOffset.UTC));

    AccountsRepository.AccountCredentials account = fixture.account();
    JwtService.IssuedToken issued = service.issueUser(account.id(), account.username(), account.passwordChangedAt());
    Jwt jwt = userDecoder(fixture).decode(issued.token());

    assertEquals("alice", jwt.getSubject());
    assertEquals(List.of("USER"), jwt.getClaimAsStringList("roles"));
    assertEquals(Long.toString(account.id()), jwt.getClaimAsString("uid"));
    assertEquals(AccountTokenFingerprint.of(account.id(), account.username(), account.passwordChangedAt()),
        jwt.getClaimAsString("pwdv"));
    assertEquals(now.plus(Duration.ofHours(2)), jwt.getExpiresAt());
    assertEquals(issued.expiresAt(), jwt.getExpiresAt());
  }

  @Test
  void passwordResetInvalidatesExistingUserToken() {
    Fixture fixture = fixture();
    JwtService service = service(fixture.identity, properties("admin-password-123", SECRET),
        new AuthProperties(), Clock.systemUTC());
    AccountsRepository.AccountCredentials account = fixture.account();
    String token = service.issueUser(account.id(), account.username(), account.passwordChangedAt()).token();

    fixture.accounts.update(account.id(), null, "another-hash", Instant.now().plusSeconds(5));

    assertThrows(JwtException.class, () -> userDecoder(fixture).decode(token));
  }

  @Test
  void disabledAccountInvalidatesExistingUserToken() {
    Fixture fixture = fixture();
    JwtService service = service(fixture.identity, properties("admin-password-123", SECRET),
        new AuthProperties(), Clock.systemUTC());
    AccountsRepository.AccountCredentials account = fixture.account();
    String token = service.issueUser(account.id(), account.username(), account.passwordChangedAt()).token();

    fixture.accounts.update(account.id(), false, null, Instant.now());

    assertThrows(JwtException.class, () -> userDecoder(fixture).decode(token));
  }

  @Test
  void numericUidClaimIsRejected() {
    Fixture fixture = fixture();
    AccountsRepository.AccountCredentials account = fixture.account();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .subject(account.username())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .claim("roles", List.of("USER"))
        .claim("uid", account.id())
        .claim("pwdv", AccountTokenFingerprint.of(account.id(), account.username(), account.passwordChangedAt()))
        .build();
    String token = encoder(fixture.identity)
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

    assertThrows(JwtException.class, () -> userDecoder(fixture).decode(token));
  }

  private static JwtService service(AdminIdentity identity, AdminProperties adminProperties,
      AuthProperties authProperties, Clock clock) {
    return new JwtService(encoder(identity), identity, adminProperties, authProperties, clock);
  }

  private static AdminProperties properties(String password, String secret) {
    AdminProperties properties = new AdminProperties();
    properties.setUsername("admin");
    properties.setPassword(password);
    properties.setJwtSecret(secret);
    return properties;
  }

  private record Fixture(AdminIdentity identity, AccountsRepository accounts, JdbcTemplate jdbc,
      AccountsRepository.AccountCredentials account) {}

  private static Fixture fixture() {
    DataSource dataSource = new EmbeddedDatabaseBuilder().generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2).addScript("classpath:schema.sql").build();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    AccountsRepository accounts = new AccountsRepository(jdbc);
    long id = accounts.insert("alice", "hash", true, Instant.now(), "admin");
    AdminProperties properties = properties("admin-password-123", SECRET);
    AdminIdentity identity = new AdminIdentity(properties);
    return new Fixture(identity, accounts, jdbc, accounts.findCredentialsById(id).orElseThrow());
  }

  private static JwtEncoder encoder(AdminIdentity identity) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key(identity)));
  }

  private static JwtDecoder adminDecoder(AdminIdentity identity) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(identity))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefault(), SecurityConfig.identityValidator(identity)));
    return decoder;
  }

  private static JwtDecoder userDecoder(Fixture fixture) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(fixture.identity))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefault(), SecurityConfig.tokenValidator(fixture.identity, fixture.accounts)));
    return decoder;
  }

  private static SecretKey key(AdminIdentity identity) {
    return new SecretKeySpec(identity.jwtKey(), "HmacSHA256");
  }
}
