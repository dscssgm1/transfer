package com.God_Symbol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:transfer-permissions;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql",
    "transfer.storage=${java.io.tmpdir}/dropkey-transfer-permissions-test",
    "transfer.admin.username=admin",
    "transfer.admin.password=admin-password-123",
    "transfer.admin.jwt-secret=0123456789abcdef0123456789abcdef",
    "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
class TransferPermissionIntegrationTest {
  private static final String LOGIN = "/api/auth/login";
  private static final String ACCOUNTS = "/api/admin/accounts";
  private static final String TRANSFERS = "/api/transfers";
  private static final AtomicInteger CLIENT_IPS = new AtomicInteger(10);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired SystemAccountInitializer initializer;

  @BeforeEach
  void restoreSystemPermissions() {
    long systemId = systemAccountId();
    jdbc.update("DELETE FROM account_permissions WHERE account_id = ?", systemId);
    jdbc.update("INSERT INTO account_permissions (account_id, permission_id, granted_at, granted_by) "
        + "SELECT ?, id, CURRENT_TIMESTAMP, 'test' FROM permissions", systemId);
  }

  @Test
  void anonymousVisitorsUseTheSystemAccountPermissions() throws Exception {
    String adminToken = adminLogin();

    mvc.perform(get("/api/admin/permissions").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[?(@.code == 'transfer.upload')]").exists())
        .andExpect(jsonPath("$.permissions[?(@.code == 'transfer.download')]").exists())
        .andExpect(jsonPath("$.permissions[?(@.code == 'transfer.delete')]").exists())
        .andExpect(jsonPath("$.permissions[?(@.code == 'transfer.list')]").exists())
        .andExpect(jsonPath("$.permissions[?(@.code == 'transfer.query')]").exists());

    JsonNode system = systemAccount(adminToken);
    org.junit.jupiter.api.Assertions.assertTrue(system.get("systemAccount").asBoolean());
    org.junit.jupiter.api.Assertions.assertEquals(5, system.get("permissions").size());

    MvcResult uploaded = uploadAnonymously("anonymous.txt", "hello".getBytes(StandardCharsets.UTF_8));
    String key = objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("key").asText();
    Cookie owner = uploaded.getResponse().getCookie("transfer_owner");

    mvc.perform(get(TRANSFERS + "/" + key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value(key));
    mvc.perform(get(TRANSFERS).cookie(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value(key));

    String ticket = ticket(key, null);
    mvc.perform(get(TRANSFERS + "/" + key + "/download").param("ticket", ticket))
        .andExpect(status().isOk())
        .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));

    mvc.perform(delete(TRANSFERS + "/" + key).cookie(owner))
        .andExpect(status().isNoContent());

    mvc.perform(get(TRANSFERS + "/capacity"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").exists());
  }

  @Test
  void revokingUploadDeniesAnonymousButNotAdminOrGrantedUser() throws Exception {
    String adminToken = adminLogin();
    replaceSystemPermissions("transfer.download", "transfer.list", "transfer.query", "transfer.delete");

    mvc.perform(multipart(TRANSFERS).file(file("blocked.txt")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));

    mvc.perform(multipart(TRANSFERS).file(file("admin.txt"))
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk());

    String username = "granted" + System.nanoTime();
    long userId = createAccount(adminToken, username);
    String userToken = userLogin(username);

    mvc.perform(multipart(TRANSFERS).file(file("no-grant.txt"))
            .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isForbidden());

    grantToAccount(adminToken, userId, "transfer.upload");
    mvc.perform(multipart(TRANSFERS).file(file("granted.txt"))
            .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isOk());

    grantToAccount(adminToken, userId);
    mvc.perform(multipart(TRANSFERS).file(file("revoked.txt"))
            .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isForbidden());
  }

  @Test
  void revokingListQueryDeleteAndDownloadDeniesAnonymousImmediately() throws Exception {
    MvcResult uploaded = uploadAnonymously("keep.txt", "data".getBytes(StandardCharsets.UTF_8));
    String key = objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("key").asText();
    Cookie owner = uploaded.getResponse().getCookie("transfer_owner");
    String adminToken = adminLogin();

    replaceSystemPermissions();

    mvc.perform(get(TRANSFERS).cookie(owner)).andExpect(status().isForbidden());
    mvc.perform(get(TRANSFERS + "/" + key)).andExpect(status().isForbidden());
    mvc.perform(delete(TRANSFERS + "/" + key).cookie(owner)).andExpect(status().isForbidden());
    mvc.perform(post(TRANSFERS + "/" + key + "/download-ticket")).andExpect(status().isForbidden());
    mvc.perform(get(TRANSFERS + "/" + key + "/download")).andExpect(status().isForbidden());

    // 容量保持公开。
    mvc.perform(get(TRANSFERS + "/capacity")).andExpect(status().isOk());

    // 管理员仍然绕过全部权限检查（删除仍受浏览器 owner Cookie 约束）。
    mvc.perform(get(TRANSFERS + "/" + key).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk());
    mvc.perform(delete(TRANSFERS + "/" + key).cookie(owner)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isNoContent());
  }

  @Test
  void downloadTicketsAreShortLivedKeyBoundCapabilities() throws Exception {
    MvcResult first = uploadAnonymously("first.txt", "first".getBytes(StandardCharsets.UTF_8));
    String firstKey = objectMapper.readTree(first.getResponse().getContentAsString()).get("key").asText();
    MvcResult second = uploadAnonymously("second.txt", "second".getBytes(StandardCharsets.UTF_8));
    String secondKey = objectMapper.readTree(second.getResponse().getContentAsString()).get("key").asText();

    String ticket = ticket(firstKey, null);
    mvc.perform(get(TRANSFERS + "/" + firstKey + "/download").param("ticket", ticket))
        .andExpect(status().isOk())
        .andExpect(content().bytes("first".getBytes(StandardCharsets.UTF_8)));

    // 吊销匿名下载权限：已签发的票据仍然有效到过期为止，但不能用于其他文件。
    replaceSystemPermissions("transfer.upload", "transfer.list", "transfer.query", "transfer.delete");
    mvc.perform(get(TRANSFERS + "/" + firstKey + "/download").param("ticket", ticket))
        .andExpect(status().isOk());
    mvc.perform(get(TRANSFERS + "/" + secondKey + "/download").param("ticket", ticket))
        .andExpect(status().isForbidden());
    mvc.perform(get(TRANSFERS + "/" + firstKey + "/download")).andExpect(status().isForbidden());
    mvc.perform(post(TRANSFERS + "/" + firstKey + "/download-ticket")).andExpect(status().isForbidden());

    // 管理员始终可以换取票据；不存在的验证码不会签发票据。
    String adminToken = adminLogin();
    ticket(firstKey, adminToken);
    mvc.perform(post(TRANSFERS + "/ZZZZZZ/download-ticket")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isNotFound());

    // 直接带 Bearer 令牌的 API 客户端不需要票据。
    String username = "downloader" + System.nanoTime();
    long userId = createAccount(adminToken, username);
    grantToAccount(adminToken, userId, "transfer.download");
    String userToken = userLogin(username);
    mvc.perform(get(TRANSFERS + "/" + firstKey + "/download")
            .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
        .andExpect(status().isOk());
  }

  @Test
  void systemAccountCannotLogInBeModifiedOrBeRecreated() throws Exception {
    String adminToken = adminLogin();
    long systemId = systemAccountId();

    mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"anonymous\",\"password\":\"password123\"}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(post(ACCOUNTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"anonymous\",\"password\":\"password123\"}"))
        .andExpect(status().isConflict());

    mvc.perform(patch(ACCOUNTS + "/" + systemId)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isBadRequest());

    mvc.perform(patch(ACCOUNTS + "/" + systemId)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"new-password-456\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(delete(ACCOUNTS + "/" + systemId)
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void restartSeedingIsIdempotentAndDoesNotRegrantRevokedPermissions() throws Exception {
    String adminToken = adminLogin();
    long systemId = systemAccountId();

    replaceSystemPermissions();
    initializer.run(new DefaultApplicationArguments());
    initializer.run(new DefaultApplicationArguments());

    Integer systemRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM accounts WHERE system_account = TRUE", Integer.class);
    org.junit.jupiter.api.Assertions.assertEquals(1, systemRows);
    Integer permissionRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM permissions WHERE code LIKE 'transfer.%'", Integer.class);
    org.junit.jupiter.api.Assertions.assertEquals(5, permissionRows);
    org.junit.jupiter.api.Assertions.assertEquals(List.of(), permissionsOf(systemId));

    // 初始化器只在首次创建账号时授权；清空后不会被重启恢复。
    JsonNode system = systemAccount(adminToken);
    org.junit.jupiter.api.Assertions.assertEquals(0, system.get("permissions").size());
  }

  private MvcResult uploadAnonymously(String name, byte[] content) throws Exception {
    return mvc.perform(multipart(TRANSFERS).file(new MockMultipartFile("file", name, "text/plain", content)))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String ticket(String key, String token) throws Exception {
    var request = post(TRANSFERS + "/" + key + "/download-ticket");
    if (token != null) request.header(HttpHeaders.AUTHORIZATION, bearer(token));
    String body = mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").exists())
        .andReturn().getResponse().getContentAsString();
    String url = objectMapper.readTree(body).get("url").asText();
    return url.substring(url.indexOf("ticket=") + "ticket=".length());
  }

  private JsonNode systemAccount(String adminToken) throws Exception {
    String body = mvc.perform(get(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    for (JsonNode account : objectMapper.readTree(body).get("accounts")) {
      if (account.get("systemAccount").asBoolean()) return account;
    }
    throw new AssertionError("system account is missing from the account list");
  }

  private long systemAccountId() {
    Long id = jdbc.queryForObject("SELECT id FROM accounts WHERE system_account = TRUE", Long.class);
    if (id == null) throw new AssertionError("system account was not seeded");
    return id;
  }

  private List<String> permissionsOf(long accountId) {
    return jdbc.query("SELECT p.code FROM account_permissions ap JOIN permissions p ON p.id = ap.permission_id "
        + "WHERE ap.account_id = ? ORDER BY p.code", (rs, row) -> rs.getString("code"), accountId);
  }

  private void replaceSystemPermissions(String... codes) throws Exception {
    String adminToken = adminLogin();
    StringBuilder json = new StringBuilder("{\"permissions\":[");
    for (int i = 0; i < codes.length; i++) {
      if (i > 0) json.append(',');
      json.append('"').append(codes[i]).append('"');
    }
    json.append("]}");
    mvc.perform(put(ACCOUNTS + "/" + systemAccountId() + "/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.toString()))
        .andExpect(status().isOk());
  }

  private void grantToAccount(String adminToken, long accountId, String... codes) throws Exception {
    StringBuilder json = new StringBuilder("{\"permissions\":[");
    for (int i = 0; i < codes.length; i++) {
      if (i > 0) json.append(',');
      json.append('"').append(codes[i]).append('"');
    }
    json.append("]}");
    mvc.perform(put(ACCOUNTS + "/" + accountId + "/permissions")
            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.toString()))
        .andExpect(status().isOk());
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

  private String adminLogin() throws Exception {
    return login("admin", "admin-password-123", "ADMIN");
  }

  private String userLogin(String username) throws Exception {
    return login(username, "password123", "USER");
  }

  private String login(String username, String password, String role) throws Exception {
    String body = mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", nextIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value(role))
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).get("token").asText();
  }

  private static MockMultipartFile file(String name) {
    return new MockMultipartFile("file", name, "text/plain", name.getBytes(StandardCharsets.UTF_8));
  }

  private static String nextIp() {
    return "203.0.113." + CLIENT_IPS.incrementAndGet();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
