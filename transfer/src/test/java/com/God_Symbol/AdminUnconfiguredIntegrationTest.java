package com.God_Symbol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin-unconfigured;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql",
    "transfer.storage=${java.io.tmpdir}/dropkey-admin-unconfigured-test",
    "transfer.admin.username=",
    "transfer.admin.password=",
    "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
class AdminUnconfiguredIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void adminEndpointsReportDisabledWhenCredentialsAreMissing() throws Exception {
    mvc.perform(get("/api/admin/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false));

    mvc.perform(post("/api/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"anything\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value(503));
  }

  @Test
  void ordinaryUsersCanLogInWithoutAnAdministrator() throws Exception {
    String username = "user" + System.nanoTime();
    Instant now = Instant.now();
    jdbc.update("INSERT INTO accounts (username, password_hash, enabled, created_at, created_by, password_changed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        username, passwordEncoder.encode("password123"), true, Timestamp.from(now), "test", Timestamp.from(now));

    mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value("USER"))
        .andExpect(jsonPath("$.permissions").isArray())
        .andExpect(jsonPath("$.token").isString());

    // 旧接口只接受管理员，普通账号即使密码正确也不能从这里拿到管理员令牌。
    mvc.perform(post("/api/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andExpect(status().isServiceUnavailable());
  }
}
