package com.God_Symbol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountsRepository {
  private final JdbcTemplate jdbc;

  public AccountsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public long insert(String username, String passwordHash, boolean enabled, Instant now, String createdBy) {
    return insert(username, passwordHash, enabled, now, createdBy, false);
  }

  public long insert(String username, String passwordHash, boolean enabled, Instant now, String createdBy,
      boolean systemAccount) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO accounts (username, password_hash, enabled, created_at, created_by, password_changed_at, "
              + "system_account) VALUES (?, ?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, username);
      statement.setString(2, passwordHash);
      statement.setBoolean(3, enabled);
      statement.setTimestamp(4, Timestamp.from(now));
      statement.setString(5, createdBy);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setBoolean(7, systemAccount);
      return statement;
    }, keys);
    Number id = keys.getKey();
    if (id == null) throw new IllegalStateException("database did not return an account id");
    return id.longValue();
  }

  /** 账号列表不读取 password_hash，避免敏感值进入内存或响应。 */
  public List<AccountSummary> findAll() {
    return jdbc.query(
        "SELECT id, username, enabled, created_at, created_by, password_changed_at, system_account "
            + "FROM accounts ORDER BY created_at DESC, id DESC",
        (rs, row) -> map(rs));
  }

  public Optional<AccountSummary> findById(long id) {
    List<AccountSummary> rows = jdbc.query(
        "SELECT id, username, enabled, created_at, created_by, password_changed_at, system_account "
            + "FROM accounts WHERE id = ?",
        (rs, row) -> map(rs), id);
    return rows.stream().findFirst();
  }

  /** “未登录用户”系统账号；正常情况下最多一行。 */
  public Optional<AccountSummary> findSystemAccount() {
    List<AccountSummary> rows = jdbc.query(
        "SELECT id, username, enabled, created_at, created_by, password_changed_at, system_account "
            + "FROM accounts WHERE system_account = TRUE ORDER BY id LIMIT 1",
        (rs, row) -> map(rs));
    return rows.stream().findFirst();
  }

  public long insertSystemAccount(String username, String passwordHash, Instant now, String createdBy) {
    return insert(username, passwordHash, true, now, createdBy, true);
  }

  /**
   * 仅认证与令牌校验使用：会读取 password_hash，禁止把结果直接序列化给客户端。
   */
  public Optional<AccountCredentials> findCredentialsByUsername(String username) {
    List<AccountCredentials> rows = jdbc.query(
        "SELECT id, username, password_hash, enabled, password_changed_at, system_account "
            + "FROM accounts WHERE username = ?",
        (rs, row) -> mapCredentials(rs), username);
    return rows.stream().findFirst();
  }

  public Optional<AccountCredentials> findCredentialsById(long id) {
    List<AccountCredentials> rows = jdbc.query(
        "SELECT id, username, password_hash, enabled, password_changed_at, system_account "
            + "FROM accounts WHERE id = ?",
        (rs, row) -> mapCredentials(rs), id);
    return rows.stream().findFirst();
  }

  public boolean update(long id, Boolean enabled, String passwordHash, Instant passwordChangedAt) {
    if (enabled == null && passwordHash == null) return false;
    StringBuilder sql = new StringBuilder("UPDATE accounts SET ");
    List<Object> arguments = new ArrayList<>();
    if (enabled != null) {
      sql.append("enabled = ?");
      arguments.add(enabled);
    }
    if (passwordHash != null) {
      if (!arguments.isEmpty()) sql.append(", ");
      sql.append("password_hash = ?, password_changed_at = ?");
      arguments.add(passwordHash);
      arguments.add(Timestamp.from(passwordChangedAt));
    }
    sql.append(" WHERE id = ? AND system_account = FALSE");
    arguments.add(id);
    return jdbc.update(sql.toString(), arguments.toArray()) >= 1;
  }

  public boolean delete(long id) {
    return jdbc.update("DELETE FROM accounts WHERE id = ? AND system_account = FALSE", id) == 1;
  }

  private static AccountSummary map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AccountSummary(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"),
        rs.getTimestamp("password_changed_at").toInstant(),
        rs.getBoolean("system_account"));
  }

  private static AccountCredentials mapCredentials(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AccountCredentials(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getBoolean("enabled"),
        rs.getTimestamp("password_changed_at").toInstant(),
        rs.getBoolean("system_account"));
  }

  /** 对外展示的账号视图，刻意不包含密码哈希。 */
  public record AccountSummary(long id, String username, boolean enabled, Instant createdAt,
      String createdBy, Instant passwordChangedAt, boolean systemAccount) {}

  /** 仅供认证与令牌校验使用，包含密码哈希，禁止直接返回给客户端。 */
  public record AccountCredentials(long id, String username, String passwordHash, boolean enabled,
      Instant passwordChangedAt, boolean systemAccount) {}
}
