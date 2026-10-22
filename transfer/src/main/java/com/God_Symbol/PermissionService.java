package com.God_Symbol;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限目录与账号授权关系。
 *
 * <p>权限定义只来自 {@code permissions} 表：新增功能时插入一行即可，管理页面会自动展示。
 * 授权变更后无需刷新令牌，{@link PermissionGuard} 每次请求都实时查库。
 */
@Service
public class PermissionService {
  private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9._-]{1,99}$");
  private static final int MAX_ACCOUNTS = 200;
  private static final int MAX_PERMISSIONS = 100;

  private final PermissionRepository permissions;
  private final AccountsRepository accounts;

  public PermissionService(PermissionRepository permissions, AccountsRepository accounts) {
    this.permissions = permissions;
    this.accounts = accounts;
  }

  public List<PermissionRepository.Permission> listPermissions() {
    return permissions.findAll();
  }

  public List<String> allCodes() {
    return permissions.findAll().stream().map(PermissionRepository.Permission::code).toList();
  }

  /**
   * 在管理页面新增一条权限定义。Key 必须与代码中
   * {@code @RequiresPermission("...")} 或 {@code @PreAuthorize} 里的值一致。
   */
  @Transactional
  public PermissionRepository.Permission createPermission(String code, String name, String description) {
    String normalizedCode = normalizeCode(code);
    String normalizedName = name == null ? "" : name.trim();
    if (normalizedName.isEmpty() || normalizedName.length() > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "permission name is required and must be at most 100 characters");
    }
    String normalizedDescription = description == null ? null : description.trim();
    if (normalizedDescription != null && normalizedDescription.isEmpty()) normalizedDescription = null;
    if (normalizedDescription != null && normalizedDescription.length() > 500) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "permission description must be at most 500 characters");
    }
    if (permissions.findByCode(normalizedCode).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "permission already exists: " + normalizedCode);
    }
    try {
      return permissions.insert(normalizedCode, normalizedName, normalizedDescription, Instant.now());
    } catch (DuplicateKeyException e) {
      // 并发创建同一个 Key 时由唯一索引兜底。
      throw new ResponseStatusException(HttpStatus.CONFLICT, "permission already exists: " + normalizedCode);
    }
  }

  /** 删除权限定义会级联移除所有账号上的该授权。 */
  @Transactional
  public void deletePermission(String code) {
    String normalizedCode = normalizeCode(code);
    if (!permissions.deleteByCode(normalizedCode)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "permission not found: " + normalizedCode);
    }
  }

  public List<String> codesForAccount(long accountId) {
    return permissions.findCodesByAccountId(accountId);
  }

  public Map<Long, List<String>> codesForAccounts(Collection<Long> accountIds) {
    return permissions.findCodesByAccountIds(accountIds);
  }

  public boolean has(long accountId, String code) {
    return code != null && permissions.existsGrant(accountId, code);
  }

  /** 匿名访客的权限来自“未登录用户”系统账号，每次请求实时查询。 */
  public boolean hasAnonymous(String code) {
    return code != null && permissions.existsGrantForSystemAccount(code);
  }

  /** 覆盖某个账号的权限集合；空列表表示清空。 */
  @Transactional
  public void replaceForAccount(long accountId, List<String> codes, String grantedBy) {
    if (codes == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissions is required");
    }
    requireAccount(accountId);
    List<PermissionRepository.Permission> desired = resolve(normalize(codes, false, false));
    Set<Long> desiredIds = new LinkedHashSet<>(desired.stream().map(PermissionRepository.Permission::id).toList());
    Set<Long> currentIds = permissions.findGrantedPermissionIds(accountId);

    Set<Long> remove = new LinkedHashSet<>(currentIds);
    remove.removeAll(desiredIds);
    permissions.deleteGrants(accountId, remove);

    Instant now = Instant.now();
    for (Long permissionId : desiredIds) {
      if (!currentIds.contains(permissionId)) {
        permissions.insertGrant(accountId, permissionId, now, grantedBy);
      }
    }
  }

  /** 给选中的账号批量授予选中的权限；重复授权是幂等的。 */
  @Transactional
  public BulkResult grant(Collection<Long> accountIds, List<String> codes, String grantedBy) {
    List<Long> ids = requireAccounts(accountIds);
    List<PermissionRepository.Permission> selected = resolve(normalize(codes, true, true));
    Instant now = Instant.now();
    int changed = 0;
    for (Long accountId : ids) {
      Set<Long> currentIds = permissions.findGrantedPermissionIds(accountId);
      for (PermissionRepository.Permission permission : selected) {
        if (currentIds.contains(permission.id())) continue;
        try {
          changed += permissions.insertGrant(accountId, permission.id(), now, grantedBy);
        } catch (DuplicateKeyException ignored) {
          // 并发下已被其他请求插入，视为已授权。
        }
      }
    }
    return new BulkResult(ids.size(), selected.size(), changed);
  }

  /** 批量撤销；未授权的组合会被忽略。 */
  @Transactional
  public BulkResult revoke(Collection<Long> accountIds, List<String> codes) {
    List<Long> ids = requireAccounts(accountIds);
    List<PermissionRepository.Permission> selected = resolve(normalize(codes, true, true));
    int changed = 0;
    for (Long accountId : ids) {
      for (PermissionRepository.Permission permission : selected) {
        changed += permissions.deleteGrant(accountId, permission.id());
      }
    }
    return new BulkResult(ids.size(), selected.size(), changed);
  }

  private static String normalizeCode(String code) {
    String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    if (!CODE.matcher(value).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid permission code: " + code);
    }
    return value;
  }

  private List<String> normalize(List<String> codes, boolean required, boolean rejectEmpty) {
    if (codes == null) {
      if (required) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissions is required");
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String code : codes) normalized.add(normalizeCode(code));
    if (rejectEmpty && normalized.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissions must not be empty");
    }
    if (normalized.size() > MAX_PERMISSIONS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "too many permissions, at most " + MAX_PERMISSIONS + " per request");
    }
    return new ArrayList<>(normalized);
  }

  private List<PermissionRepository.Permission> resolve(List<String> codes) {
    if (codes.isEmpty()) return List.of();
    Map<String, PermissionRepository.Permission> catalog = new LinkedHashMap<>();
    for (PermissionRepository.Permission permission : permissions.findAll()) {
      catalog.put(permission.code(), permission);
    }
    List<PermissionRepository.Permission> resolved = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String code : codes) {
      PermissionRepository.Permission permission = catalog.get(code);
      if (permission == null) unknown.add(code);
      else resolved.add(permission);
    }
    if (!unknown.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "unknown permission code(s): " + String.join(", ", unknown));
    }
    return resolved;
  }

  private List<Long> requireAccounts(Collection<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountIds must not be empty");
    }
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    for (Long id : accountIds) {
      if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account id is required");
      ids.add(id);
    }
    if (ids.size() > MAX_ACCOUNTS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "too many accounts, at most " + MAX_ACCOUNTS + " per request");
    }
    List<Long> missing = new ArrayList<>();
    for (Long id : ids) {
      if (accounts.findById(id).isEmpty()) missing.add(id);
    }
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found: " + join(missing));
    }
    return new ArrayList<>(ids);
  }

  private void requireAccount(long accountId) {
    if (accounts.findById(accountId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found");
    }
  }

  private static String join(List<Long> values) {
    List<String> parts = new ArrayList<>();
    for (Long value : values) parts.add(String.valueOf(value));
    return String.join(", ", parts);
  }

  public record BulkResult(int accountCount, int permissionCount, int changed) {}
}
