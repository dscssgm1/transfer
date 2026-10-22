package com.God_Symbol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceTest {
  private JdbcTemplate jdbc;
  private PermissionService service;
  private AccountsRepository accounts;
  private long aliceId;
  private long bobId;

  @BeforeEach
  void setUp() {
    DataSource dataSource = new EmbeddedDatabaseBuilder().generateUniqueName(true)
        .setType(EmbeddedDatabaseType.H2).addScript("classpath:schema.sql").build();
    jdbc = new JdbcTemplate(dataSource);
    accounts = new AccountsRepository(jdbc);
    service = new PermissionService(new PermissionRepository(jdbc), accounts);
    aliceId = accounts.insert("alice", "hash", true, Instant.now(), "admin");
    bobId = accounts.insert("bob", "hash", true, Instant.now(), "admin");
  }

  @Test
  void catalogStartsEmptyAndIsOrderedByCode() {
    assertTrue(service.listPermissions().isEmpty());
    assertTrue(service.allCodes().isEmpty());

    insertPermission("transfer.manage", "管理传输");
    insertPermission("account.audit", "审计账号");

    assertEquals(List.of("account.audit", "transfer.manage"), service.allCodes());
    assertEquals("管理传输", service.listPermissions().get(1).name());
  }

  @Test
  void replaceAddsRemovesAndKeepsUnchangedGrants() {
    insertPermission("a.one", "One");
    insertPermission("b.two", "Two");
    insertPermission("c.three", "Three");

    service.replaceForAccount(aliceId, List.of("a.one", "b.two"), "admin");
    assertEquals(List.of("a.one", "b.two"), service.codesForAccount(aliceId));

    Timestamp grantedAt = jdbc.queryForObject(
        "SELECT ap.granted_at FROM account_permissions ap JOIN permissions p ON p.id = ap.permission_id "
            + "WHERE ap.account_id = ? AND p.code = 'a.one'", Timestamp.class, aliceId);

    service.replaceForAccount(aliceId, List.of("B.TWO ", "c.three"), "admin");

    assertEquals(List.of("b.two", "c.three"), service.codesForAccount(aliceId));
    Timestamp kept = jdbc.queryForObject(
        "SELECT ap.granted_at FROM account_permissions ap JOIN permissions p ON p.id = ap.permission_id "
            + "WHERE ap.account_id = ? AND p.code = 'b.two'", Timestamp.class, aliceId);
    assertNotNull(kept);
    assertNotNull(grantedAt);
  }

  @Test
  void replaceWithEmptyListClearsAllPermissions() {
    insertPermission("a.one", "One");
    service.replaceForAccount(aliceId, List.of("a.one"), "admin");
    service.replaceForAccount(aliceId, List.of(), "admin");
    assertTrue(service.codesForAccount(aliceId).isEmpty());
    assertFalse(service.has(aliceId, "a.one"));
  }

  @Test
  void bulkGrantAndRevokeAreIdempotentAndDeduplicated() {
    insertPermission("a.one", "One");
    insertPermission("b.two", "Two");

    PermissionService.BulkResult granted = service.grant(List.of(aliceId, bobId, aliceId), List.of("a.one"), "admin");
    assertEquals(2, granted.accountCount());
    assertEquals(1, granted.permissionCount());
    assertEquals(2, granted.changed());

    assertEquals(0, service.grant(List.of(aliceId), List.of("a.one"), "admin").changed());

    PermissionService.BulkResult revoked = service.revoke(List.of(aliceId, bobId), List.of("a.one", "b.two"));
    assertEquals(2, revoked.changed());
    assertEquals(0, service.revoke(List.of(aliceId), List.of("a.one")).changed());
    assertTrue(service.codesForAccount(aliceId).isEmpty());
    assertTrue(service.codesForAccount(bobId).isEmpty());
  }

  @Test
  void codesForAccountsReturnsOnlyAccountsWithGrants() {
    insertPermission("a.one", "One");
    service.replaceForAccount(aliceId, List.of("a.one"), "admin");

    var codes = service.codesForAccounts(List.of(aliceId, bobId));
    assertEquals(List.of("a.one"), codes.get(aliceId));
    assertNull(codes.get(bobId));
  }

  @Test
  void permissionsCanBeCreatedAndDeletedFromTheCatalog() {
    PermissionRepository.Permission created =
        service.createPermission(" UI.Feature ", " UI 功能 ", " 说明 ");

    assertEquals("ui.feature", created.code());
    assertEquals("UI 功能", created.name());
    assertEquals("说明", created.description());
    assertEquals(List.of("ui.feature"), service.allCodes());

    assertStatus(HttpStatus.CONFLICT, () -> service.createPermission("ui.feature", "重复", null));

    service.deletePermission("UI.FEATURE");
    assertTrue(service.allCodes().isEmpty());
    assertStatus(HttpStatus.NOT_FOUND, () -> service.deletePermission("ui.feature"));
  }

  @Test
  void invalidPermissionDefinitionsAreRejected() {
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createPermission("Bad Code", "名称", null));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createPermission("ok.code", "   ", null));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createPermission("ok.code", "x".repeat(101), null));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createPermission("ok.code", "名称", "x".repeat(501)));
    assertTrue(service.allCodes().isEmpty());
  }

  @Test
  void deletingAPermissionRemovesItsGrants() {
    service.createPermission("a.one", "One", null);
    service.replaceForAccount(aliceId, List.of("a.one"), "admin");

    service.deletePermission("a.one");

    assertTrue(service.codesForAccount(aliceId).isEmpty());
    Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM account_permissions", Integer.class);
    assertEquals(0, remaining);
  }

  @Test
  void unknownCodesAndAccountsAreRejectedBeforeWriting() {
    insertPermission("a.one", "One");

    assertStatus(HttpStatus.BAD_REQUEST, () -> service.replaceForAccount(aliceId, List.of("missing.code"), "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.replaceForAccount(aliceId, List.of("Bad Code"), "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.grant(List.of(aliceId), List.of(), "admin"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.grant(List.of(), List.of("a.one"), "admin"));
    assertStatus(HttpStatus.NOT_FOUND, () -> service.replaceForAccount(9999, List.of("a.one"), "admin"));
    assertStatus(HttpStatus.NOT_FOUND, () -> service.grant(List.of(aliceId, 9999L), List.of("a.one"), "admin"));

    assertTrue(service.codesForAccount(aliceId).isEmpty(), "failed validation must not write grants");
  }

  @Test
  void deletingAnAccountRemovesItsGrants() {
    insertPermission("a.one", "One");
    service.replaceForAccount(aliceId, List.of("a.one"), "admin");

    accounts.delete(aliceId);

    Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM account_permissions", Integer.class);
    assertEquals(0, remaining);
  }

  @Test
  void anonymousPermissionsComeOnlyFromTheEnabledSystemAccount() {
    insertPermission("transfer.upload", "上传文件");
    long systemId = accounts.insertSystemAccount(SystemAccount.USERNAME, SystemAccount.PASSWORD_HASH,
        Instant.now(), SystemAccount.CREATED_BY);

    assertFalse(service.hasAnonymous("transfer.upload"));

    service.replaceForAccount(aliceId, List.of("transfer.upload"), "admin");
    assertFalse(service.hasAnonymous("transfer.upload"), "regular accounts must not grant anonymous access");

    service.replaceForAccount(systemId, List.of("transfer.upload"), "admin");
    assertTrue(service.hasAnonymous("transfer.upload"));
    assertFalse(service.hasAnonymous("transfer.missing"));

    jdbc.update("UPDATE accounts SET enabled = FALSE WHERE id = ?", systemId);
    assertFalse(service.hasAnonymous("transfer.upload"), "disabled system account grants no anonymous access");
  }

  private void insertPermission(String code, String name) {
    jdbc.update("INSERT INTO permissions (code, name, description, created_at) VALUES (?, ?, ?, ?)",
        code, name, null, Timestamp.from(Instant.now()));
  }

  private static void assertStatus(HttpStatus expected, Executable executable) {
    ResponseStatusException exception = assertThrows(ResponseStatusException.class, executable);
    assertEquals(expected.value(), exception.getStatusCode().value());
  }
}
