package com.God_Symbol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class PermissionRepository {
  private final JdbcTemplate jdbc;

  public PermissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<Permission> findAll() {
    return jdbc.query(
        "SELECT id, code, name, description, created_at FROM permissions ORDER BY code",
        (rs, row) -> map(rs));
  }

  public Optional<Permission> findByCode(String code) {
    return jdbc.query(
        "SELECT id, code, name, description, created_at FROM permissions WHERE code = ?",
        (rs, row) -> map(rs), code).stream().findFirst();
  }

  public Permission insert(String code, String name, String description, Instant createdAt) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO permissions (code, name, description, created_at) VALUES (?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, code);
      statement.setString(2, name);
      statement.setString(3, description);
      statement.setTimestamp(4, Timestamp.from(createdAt));
      return statement;
    }, keys);
    Number id = keys.getKey();
    if (id == null) throw new IllegalStateException("database did not return a permission id");
    return new Permission(id.longValue(), code, name, description, createdAt);
  }

  public boolean deleteByCode(String code) {
    return jdbc.update("DELETE FROM permissions WHERE code = ?", code) == 1;
  }

  public List<String> findCodesByAccountId(long accountId) {
    return jdbc.query(
        "SELECT p.code FROM account_permissions ap JOIN permissions p ON p.id = ap.permission_id "
            + "WHERE ap.account_id = ? ORDER BY p.code",
        (rs, row) -> rs.getString("code"), accountId);
  }

  /** 账号列表用一次查询取回所有权限，避免 N+1。 */
  public Map<Long, List<String>> findCodesByAccountIds(Collection<Long> accountIds) {
    Map<Long, List<String>> result = new LinkedHashMap<>();
    if (accountIds == null || accountIds.isEmpty()) return result;
    List<Long> ids = new ArrayList<>(new LinkedHashSet<>(accountIds));
    String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    jdbc.query(
        "SELECT ap.account_id, p.code FROM account_permissions ap "
            + "JOIN permissions p ON p.id = ap.permission_id "
            + "WHERE ap.account_id IN (" + placeholders + ") ORDER BY p.code",
        rs -> {
          result.computeIfAbsent(rs.getLong("account_id"), key -> new ArrayList<>()).add(rs.getString("code"));
        }, ids.toArray());
    return result;
  }

  public boolean existsGrant(long accountId, String code) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM account_permissions ap JOIN permissions p ON p.id = ap.permission_id "
            + "WHERE ap.account_id = ? AND p.code = ?",
        Integer.class, accountId, code);
    return count != null && count > 0;
  }

  /** 匿名请求使用“未登录用户”系统账号的授权。 */
  public boolean existsGrantForSystemAccount(String code) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM account_permissions ap "
            + "JOIN permissions p ON p.id = ap.permission_id "
            + "JOIN accounts a ON a.id = ap.account_id "
            + "WHERE a.system_account = TRUE AND a.enabled = TRUE AND p.code = ?",
        Integer.class, code);
    return count != null && count > 0;
  }

  public Set<Long> findGrantedPermissionIds(long accountId) {
    return new LinkedHashSet<>(jdbc.query(
        "SELECT permission_id FROM account_permissions WHERE account_id = ?",
        (rs, row) -> rs.getLong("permission_id"), accountId));
  }

  public int insertGrant(long accountId, long permissionId, Instant grantedAt, String grantedBy) {
    return jdbc.update(
        "INSERT INTO account_permissions (account_id, permission_id, granted_at, granted_by) VALUES (?, ?, ?, ?)",
        accountId, permissionId, Timestamp.from(grantedAt), grantedBy);
  }

  public int deleteGrant(long accountId, long permissionId) {
    return jdbc.update(
        "DELETE FROM account_permissions WHERE account_id = ? AND permission_id = ?",
        accountId, permissionId);
  }

  public int deleteGrants(long accountId, Collection<Long> permissionIds) {
    if (permissionIds == null || permissionIds.isEmpty()) return 0;
    List<Long> ids = new ArrayList<>(new LinkedHashSet<>(permissionIds));
    String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    List<Object> arguments = new ArrayList<>();
    arguments.add(accountId);
    arguments.addAll(ids);
    return jdbc.update(
        "DELETE FROM account_permissions WHERE account_id = ? AND permission_id IN (" + placeholders + ")",
        arguments.toArray());
  }

  private static Permission map(ResultSet rs) throws SQLException {
    return new Permission(
        rs.getLong("id"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getTimestamp("created_at").toInstant());
  }

  public record Permission(long id, String code, String name, String description, Instant createdAt) {}
}
