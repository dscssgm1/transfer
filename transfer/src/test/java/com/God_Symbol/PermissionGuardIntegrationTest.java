package com.God_Symbol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用一个仅存在于测试源码的受保护接口，证明 {@code PermissionGuard} 的授权/撤销是实时生效的。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:permission-guard;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql",
    "transfer.storage=${java.io.tmpdir}/dropkey-permission-guard-test",
    "transfer.admin.username=admin",
    "transfer.admin.password=admin-password-123",
    "transfer.admin.jwt-secret=0123456789abcdef0123456789abcdef",
    "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
@Import(PermissionGuardIntegrationTest.GuardedController.class)
class PermissionGuardIntegrationTest {
  private static final String LOGIN = "/api/auth/login";
  private static final String ACCOUNTS = "/api/admin/accounts";
  private static final String FEATURE = "/api/user/test-feature";
  private static final String PERMISSION = "test.feature";
  private static final AtomicInteger CLIENT_IPS = new AtomicInteger(10);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  void grantAndRevokeTakeEffectOnTheSameToken() throws Exception {
    String adminToken = adminLogin();
    String username = "guard" + System.nanoTime();
    long accountId = createAccount(adminToken, username);
    String userToken = userLogin(username);

    insertPermission(PERMISSION, "测试功能");

    mvc.perform(get(FEATURE).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));

    replacePermissions(adminToken, accountId, "[\"" + PERMISSION + "\"]");
    mvc.perform(get(FEATURE).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    replacePermissions(adminToken, accountId, "[]");
    mvc.perform(get(FEATURE).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isForbidden());
  }

  @Test
  void administratorBypassesPermissionChecks() throws Exception {
    String adminToken = adminLogin();
    mvc.perform(get(FEATURE).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk());
  }

  @Test
  void anonymousCallersGetUnauthorized() throws Exception {
    mvc.perform(get(FEATURE))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  private String adminLogin() throws Exception {
    String body = mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"admin-password-123\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).get("token").asText();
  }

  private String userLogin(String username) throws Exception {
    String body = mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).get("token").asText();
  }

  private long createAccount(String adminToken, String username) throws Exception {
    String body = mvc.perform(post(ACCOUNTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).get("id").asLong();
  }

  private void replacePermissions(String adminToken, long accountId, String permissionsJson) throws Exception {
    mvc.perform(put(ACCOUNTS + "/" + accountId + "/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permissions\":" + permissionsJson + "}"))
        .andExpect(status().isOk());
  }

  private void insertPermission(String code, String name) {
    jdbc.update("INSERT INTO permissions (code, name, description, created_at) VALUES (?, ?, ?, ?)",
        code, name, null, Timestamp.from(Instant.now()));
  }

  private static String nextIp() {
    return "192.0.2." + CLIENT_IPS.incrementAndGet();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  /** 后续真实功能照这个写法加权限即可。 */
  @RestController
  static class GuardedController {
    @GetMapping(FEATURE)
    @RequiresPermission(PERMISSION)
    Map<String, Object> feature() {
      return Map.of("ok", true);
    }
  }
}
