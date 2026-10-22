package com.God_Symbol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:unified-auth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql",
    "transfer.storage=${java.io.tmpdir}/dropkey-unified-auth-test",
    "transfer.admin.username=admin",
    "transfer.admin.password=admin-password-123",
    "transfer.admin.jwt-secret=0123456789abcdef0123456789abcdef",
    "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
class UnifiedAuthIntegrationTest {
  private static final String LOGIN = "/api/auth/login";
  private static final String ADMIN_LOGIN = "/api/admin/login";
  private static final String ACCOUNTS = "/api/admin/accounts";
  private static final AtomicInteger CLIENT_IPS = new AtomicInteger(10);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  void administratorCanUseTheUnifiedLogin() throws Exception {
    String body = mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"admin-password-123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
        .andExpect(jsonPath("$.permissions").isArray())
        .andReturn().getResponse().getContentAsString();

    String token = objectMapper.readTree(body).get("token").asText();
    mvc.perform(get("/api/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
  }

  @Test
  void userCanLogInRepeatedlyAndReadsLivePermissions() throws Exception {
    String adminToken = adminLogin();
    String username = "user" + System.nanoTime();
    long id = createAccount(adminToken, username);

    String firstToken = userLogin(username);
    String secondToken = userLogin(username);
    org.junit.jupiter.api.Assertions.assertNotEquals(firstToken, secondToken);

    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(secondToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.accountId").value(id))
        .andExpect(jsonPath("$.roles[0]").value("USER"))
        .andExpect(jsonPath("$.permissions").isEmpty());

    insertPermission("test.feature", "测试功能");
    grant(adminToken, id, "test.feature");

    // 权限实时查库：不重新登录，旧令牌立刻能看到新权限。
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(secondToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value("test.feature"));

    revokeAll(adminToken, id);
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(secondToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions").isEmpty());
  }

  @Test
  void oldAdminLoginEndpointStillRejectsOrdinaryUsers() throws Exception {
    String adminToken = adminLogin();
    String username = "user" + System.nanoTime();
    createAccount(adminToken, username);

    mvc.perform(post(ADMIN_LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void wrongPasswordAndDisabledAccountsAreRejected() throws Exception {
    String adminToken = adminLogin();
    String username = "user" + System.nanoTime();
    long id = createAccount(adminToken, username);

    mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(patch(ACCOUNTS + "/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isOk());

    mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authorizationBoundariesAreEnforced() throws Exception {
    String adminToken = adminLogin();
    String username = "user" + System.nanoTime();
    createAccount(adminToken, username);
    String userToken = userLogin(username);

    mvc.perform(get(ACCOUNTS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
    mvc.perform(get(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void oldTokensAreRejectedAfterPasswordResetDisableAndDeletion() throws Exception {
    String adminToken = adminLogin();

    String resetName = "reset" + System.nanoTime();
    long resetId = createAccount(adminToken, resetName);
    String resetToken = userLogin(resetName);
    mvc.perform(patch(ACCOUNTS + "/" + resetId)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"new-password-456\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(resetToken)))
        .andExpect(status().isUnauthorized());

    String disabledName = "disabled" + System.nanoTime();
    long disabledId = createAccount(adminToken, disabledName);
    String disabledToken = userLogin(disabledName);
    mvc.perform(patch(ACCOUNTS + "/" + disabledId)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(disabledToken)))
        .andExpect(status().isUnauthorized());

    String deletedName = "deleted" + System.nanoTime();
    long deletedId = createAccount(adminToken, deletedName);
    String deletedToken = userLogin(deletedName);
    mvc.perform(delete(ACCOUNTS + "/" + deletedId).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(deletedToken)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void administratorCanAddAndDeletePermissionsFromTheCatalog() throws Exception {
    String adminToken = adminLogin();
    String code = "ui.feature" + System.nanoTime();

    mvc.perform(post("/api/admin/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\",\"name\":\"页面功能\",\"description\":\"从管理页添加\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.name").value("页面功能"));

    String listed = mvc.perform(get("/api/admin/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    boolean found = false;
    for (JsonNode permission : objectMapper.readTree(listed).get("permissions")) {
      if (permission.get("code").asText().equals(code)) found = true;
    }
    if (!found) throw new AssertionError("created permission is missing from the catalog");

    mvc.perform(post("/api/admin/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\",\"name\":\"重复\"}"))
        .andExpect(status().isConflict());

    String username = "user" + System.nanoTime();
    createAccount(adminToken, username);
    String userToken = userLogin(username);
    mvc.perform(post("/api/admin/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(userToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"user.forbidden\",\"name\":\"不应创建\"}"))
        .andExpect(status().isForbidden());

    mvc.perform(delete("/api/admin/permissions/" + code)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/admin/permissions/" + code)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isNotFound());
  }

  @Test
  void anonymousTransferApiStaysPublic() throws Exception {
    mvc.perform(get("/api/transfers/capacity"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").exists());
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
        .andExpect(jsonPath("$.roles[0]").value("USER"))
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
    JsonNode node = objectMapper.readTree(body);
    return node.get("id").asLong();
  }

  private void grant(String adminToken, long accountId, String code) throws Exception {
    mvc.perform(put(ACCOUNTS + "/" + accountId + "/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permissions\":[\"" + code + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value(code));
  }

  private void revokeAll(String adminToken, long accountId) throws Exception {
    mvc.perform(put(ACCOUNTS + "/" + accountId + "/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permissions\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions").isEmpty());
  }

  private void insertPermission(String code, String name) {
    jdbc.update("INSERT INTO permissions (code, name, description, created_at) VALUES (?, ?, ?, ?)",
        code, name, null, Timestamp.from(Instant.now()));
  }

  private static String nextIp() {
    return "203.0.113." + CLIENT_IPS.incrementAndGet();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
