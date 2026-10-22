package com.God_Symbol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({AdminProperties.class, AuthProperties.class})
public class SecurityConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService loginUserDetailsService(AdminIdentity identity, PasswordEncoder passwordEncoder,
      AccountsRepository accounts) {
    // 只暴露一个 UserDetailsService，避免 Spring Boot 自动配置时出现歧义。
    return new LoginUserDetailsService(
        new AdminUserDetailsService(identity, passwordEncoder), identity, accounts);
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  @Bean
  JwtEncoder jwtEncoder(AdminIdentity identity) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(identity)));
  }

  @Bean
  JwtDecoder jwtDecoder(AdminIdentity identity, AccountsRepository accounts) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(identity))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefault(), tokenValidator(identity, accounts)));
    return decoder;
  }

  /**
   * 按角色分别校验令牌：
   * 管理员沿用用户名 + 密码指纹；普通账号回查数据库，确认账号仍存在、启用且密码未变更。
   */
  static OAuth2TokenValidator<Jwt> tokenValidator(AdminIdentity identity, AccountsRepository accounts) {
    return jwt -> {
      java.util.List<String> roles = jwt.getClaimAsStringList("roles");
      if (roles == null || roles.isEmpty()) return failure("token has no role");
      if (roles.contains("ADMIN")) {
        if (roles.size() != 1) return failure("invalid role combination");
        return identityValidator(identity).validate(jwt);
      }
      if (roles.contains("USER")) {
        if (roles.size() != 1) return failure("invalid role combination");
        // 必须严格是字符串，避免数字型 claim 被 toString 后误当成合法账号 id。
        Object rawUid = jwt.getClaims().get("uid");
        if (!(rawUid instanceof String uid) || uid.isBlank()) {
          return failure("token is missing account id");
        }
        long accountId;
        try {
          accountId = Long.parseLong(uid);
        } catch (NumberFormatException e) {
          return failure("token has an invalid account id");
        }
        AccountsRepository.AccountCredentials account = accounts.findCredentialsById(accountId).orElse(null);
        if (account == null) return failure("account no longer exists");
        if (account.systemAccount()) return failure("system account cannot be used for login");
        if (!account.enabled()) return failure("account is disabled");
        if (!account.username().equals(jwt.getSubject())) return failure("token subject is no longer valid");
        String credential = jwt.getClaimAsString("pwdv");
        String current = AccountTokenFingerprint.of(account.id(), account.username(), account.passwordChangedAt());
        if (credential == null || !MessageDigest.isEqual(
            credential.getBytes(StandardCharsets.UTF_8), current.getBytes(StandardCharsets.UTF_8))) {
          return failure("credentials changed, please sign in again");
        }
        return OAuth2TokenValidatorResult.success();
      }
      return failure("unknown role");
    };
  }

  /** 除签名与过期时间外，再校验当前管理员身份与密码指纹，使改密后旧令牌立即失效。 */
  static OAuth2TokenValidator<Jwt> identityValidator(AdminIdentity identity) {
    return jwt -> {
      if (!identity.configured()) return failure("administrator is not configured");
      if (!identity.username().equals(jwt.getSubject())) return failure("token subject is no longer valid");
      String credential = jwt.getClaimAsString("cred");
      if (credential == null || !MessageDigest.isEqual(
          credential.getBytes(StandardCharsets.UTF_8),
          identity.credentialFingerprint().getBytes(StandardCharsets.UTF_8))) {
        return failure("credentials changed, please sign in again");
      }
      return OAuth2TokenValidatorResult.success();
    };
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    converter.setPrincipalClaimName("sub");
    return converter;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
      ObjectMapper objectMapper) throws Exception {
    AuthenticationEntryPoint authenticationEntryPoint =
        (request, response, exception) -> writeJson(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
            "Unauthorized", "authentication required");
    AccessDeniedHandler accessDeniedHandler =
        (request, response, exception) -> writeJson(objectMapper, response, HttpServletResponse.SC_FORBIDDEN,
            "Forbidden", "insufficient permissions");

    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(handling -> handling
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/api/admin/login", "/api/admin/config", "/api/auth/login").permitAll()
            .requestMatchers("/api/auth/me").authenticated()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            // 后续需要功能权限的用户接口统一放在 /api/user/**，先要求登录，再用 @PreAuthorize 校验权限。
            .requestMatchers("/api/user/**").authenticated()
            // 现有匿名文件传输与静态页面保持公开。
            .anyRequest().permitAll())
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }

  private static SecretKey secretKey(AdminIdentity identity) {
    return new SecretKeySpec(identity.jwtKey(), "HmacSHA256");
  }

  private static OAuth2TokenValidatorResult failure(String description) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
  }

  private static void writeJson(ObjectMapper mapper, HttpServletResponse response, int status, String error,
      String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    mapper.writeValue(response.getOutputStream(), Map.of(
        "status", status,
        "error", error,
        "message", message,
        "timestamp", Instant.now().toString()));
  }
}
