package com.God_Symbol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {
  private JdbcTemplate jdbc;
  private BCryptPasswordEncoder passwordEncoder;
  private AccountsRepository accounts;
  private AccountService service;

  @BeforeEach
  void setUp() {
    DataSource dataSource = new EmbeddedDatabaseBuilder().generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2).addScript("classpath:schema.sql").build();
    jdbc = new JdbcTemplate(dataSource);
    passwordEncoder = new BCryptPasswordEncoder(4);

    AdminProperties properties = new AdminProperties();
    properties.setUsername("admin");
    properties.setPassword("admin-password-123");
    accounts = new AccountsRepository(jdbc);
    PermissionService permissions = new PermissionService(new PermissionRepository(jdbc), accounts);
    service = new AccountService(accounts, passwordEncoder, new AdminIdentity(properties), permissions);
  }

  @Test
  void createdAccountIsNormalizedHashedAndListed() {
    var created = service.create(" Alice ", "password123", null, "admin");

    assertEquals("alice", created.username());
    assertTrue(created.enabled());
    assertEquals("admin", created.createdBy());
    assertTrue(created.permissions().isEmpty());

    String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, created.id());
    assertNotEquals("password123", hash);
    assertTrue(hash.startsWith("$2"));
    assertTrue(passwordEncoder.matches("password123", hash));

    var accounts = service.list();
    assertEquals(1, accounts.size());
    assertEquals("alice", accounts.get(0).username());
    assertTrue(accounts.get(0).permissions().isEmpty());
  }

  @Test
  void duplicateUsernameIsRejected() {
    service.create("alice", "password123", true, "admin");
    assertStatus(HttpStatus.CONFLICT, () -> service.create(" Alice ", "password123", true, "admin"));
  }

  @Test
  void administratorUsernameIsReserved() {
    assertStatus(HttpStatus.CONFLICT, () -> service.create("Admin", "password123", true, "admin"));
  }

  @Test
  void invalidUsernameAndPasswordAreRejected() {
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.create("ab", "password123", true, "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.create("bad name", "password123", true, "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.create("alice", "short", true, "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.create("alice", "x".repeat(73), true, "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.create("alice", "   ", true, "admin"));
  }

  @Test
  void disablingKeepsPasswordAndResettingKeepsEnabled() {
    var created = service.create("alice", "password123", true, "admin");
    String originalHash = hashOf(created.id());

    var disabled = service.update(created.id(), false, null);
    assertFalse(disabled.enabled());
    assertEquals(originalHash, hashOf(created.id()));

    var reset = service.update(created.id(), null, "new-password-456");
    assertFalse(reset.enabled());
    String newHash = hashOf(created.id());
    assertNotEquals(originalHash, newHash);
    assertTrue(passwordEncoder.matches("new-password-456", newHash));
  }

  @Test
  void updateRequiresAFieldAndAnExistingAccount() {
    var created = service.create("alice", "password123", true, "admin");
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(created.id(), null, null));
    assertStatus(HttpStatus.NOT_FOUND, () -> service.update(9999, true, null));
  }

  @Test
  void deletionIsIdempotentFromTheApiPerspective() {
    var created = service.create("alice", "password123", true, "admin");
    service.delete(created.id());
    assertTrue(service.list().isEmpty());
    assertStatus(HttpStatus.NOT_FOUND, () -> service.delete(created.id()));
  }

  @Test
  void systemAccountUsernameIsReserved() {
    assertStatus(HttpStatus.CONFLICT, () -> service.create("anonymous", "password123", true, "admin"));
    assertStatus(HttpStatus.CONFLICT, () -> service.create(" Anonymous ", "password123", true, "admin"));
  }

  @Test
  void systemAccountIsListedButCannotBeModifiedOrDeleted() {
    long systemId = accounts.insertSystemAccount(SystemAccount.USERNAME, SystemAccount.PASSWORD_HASH,
        java.time.Instant.now(), SystemAccount.CREATED_BY);
    var created = service.create("alice", "password123", true, "admin");

    var listed = service.list().stream().filter(a -> a.id() == systemId).findFirst().orElseThrow();
    assertTrue(listed.systemAccount());
    assertTrue(listed.enabled());

    assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(systemId, false, null));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.update(systemId, null, "new-password-456"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.delete(systemId));

    assertEquals(2, service.list().size());
    assertEquals(created.id(), service.get(created.id()).id());
  }

  private String hashOf(long id) {
    return jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, id);
  }

  private static void assertStatus(HttpStatus expected, Executable executable) {
    ResponseStatusException exception = assertThrows(ResponseStatusException.class, executable);
    assertEquals(expected.value(), exception.getStatusCode().value());
  }
}
