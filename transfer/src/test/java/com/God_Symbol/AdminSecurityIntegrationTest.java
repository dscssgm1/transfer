package com.God_Symbol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin-security;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql",
    "transfer.storage=${java.io.tmpdir}/dropkey-admin-security-test",
    "transfer.admin.username=admin",
    "transfer.admin.password=admin-password-123",
    "transfer.admin.jwt-secret=0123456789abcdef0123456789abcdef",
    "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
class AdminSecurityIntegrationTest {
  private static final String LOGIN = "/api/admin/login";
  private static final String ACCOUNTS = "/api/admin/accounts";
  private static final AtomicInteger CLIENT_IPS = new AtomicInteger(10);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired org.springframework.security.authentication.AuthenticationManager authenticationManager;

  @Test
  void adminCanLogInRepeatedly() {
    var first = authenticationManager.authenticate(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "admin", "admin-password-123"));
    var second = authenticationManager.authenticate(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "admin", "admin-password-123"));
    org.junit.jupiter.api.Assertions.assertTrue(first.isAuthenticated());
    org.junit.jupiter.api.Assertions.assertTrue(second.isAuthenticated());
  }

  @Test
  void anonymousAndForgedTokensAreRejectedWithJson() throws Exception {
    mvc.perform(get(ACCOUNTS))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(401));

    mvc.perform(get(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void adminCanLoginAndManageAccounts() throws Exception {
    String token = login();
    mvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
        .andExpect(jsonPath("$.expiresAt").exists());

    String username = "alice" + System.nanoTime();
    String created = mvc.perform(post(ACCOUNTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.permissions").isArray())
        .andExpect(jsonPath("$.permissions").isEmpty())
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    long id = objectMapper.readTree(created).get("id").asLong();

    String listed = mvc.perform(get(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accounts").isArray())
        .andReturn().getResponse().getContentAsString();
    JsonNode accounts = objectMapper.readTree(listed).get("accounts");
    boolean found = false;
    for (JsonNode account : accounts) {
      if (account.get("username").asText().equals(username)) {
        found = true;
        if (!account.has("permissions") || !account.get("permissions").isArray()) {
          throw new AssertionError("account view must include a permissions array");
        }
        if (account.has("passwordHash") || account.has("password_hash")) {
          throw new AssertionError("account view must not expose password hash");
        }
      }
    }
    if (!found) throw new AssertionError("created account is missing from the list");

    mvc.perform(patch(ACCOUNTS + "/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    mvc.perform(patch(ACCOUNTS + "/" + id)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"new-password-456\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    mvc.perform(delete(ACCOUNTS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNoContent());
    mvc.perform(delete(ACCOUNTS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNotFound());
  }

  @Test
  void duplicateAndInvalidAccountsAreRejected() throws Exception {
    String token = login();
    String username = "bob" + System.nanoTime();
    String body = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";

    mvc.perform(post(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mvc.perform(post(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());

    mvc.perform(post(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ab\",\"password\":\"password123\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username + "x\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void loginFailuresAreRateLimited() throws Exception {
    String ip = "198.51.100." + CLIENT_IPS.incrementAndGet();
    for (int attempt = 0; attempt < 5; attempt++) {
      mvc.perform(post(LOGIN)
              .header("X-Forwarded-For", ip)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void existingAnonymousTransferApiStaysPublic() throws Exception {
    mvc.perform(get("/api/transfers/capacity"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").exists());
  }

  private String login() throws Exception {
    String ip = "198.51.100." + CLIENT_IPS.incrementAndGet();
    var response = mvc.perform(post(LOGIN)
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"admin-password-123\"}"))
        .andReturn().getResponse();
    if (response.getStatus() != 200) {
      throw new AssertionError("login failed: " + response.getStatus() + " " + response.getContentAsString());
    }
    return objectMapper.readTree(response.getContentAsString()).get("token").asText();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
