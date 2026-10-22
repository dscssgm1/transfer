package com.God_Symbol;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AccountService {
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");
  private static final int MIN_PASSWORD_BYTES = 8;
  private static final int MAX_PASSWORD_BYTES = 72;

  private final AccountsRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final AdminIdentity adminIdentity;
  private final PermissionService permissionService;

  public AccountService(AccountsRepository repository, PasswordEncoder passwordEncoder, AdminIdentity adminIdentity,
      PermissionService permissionService) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.adminIdentity = adminIdentity;
    this.permissionService = permissionService;
  }

  public List<AccountView> list() {
    List<AccountsRepository.AccountSummary> accounts = repository.findAll();
    Map<Long, List<String>> permissions = permissionService.codesForAccounts(
        accounts.stream().map(AccountsRepository.AccountSummary::id).toList());
    return accounts.stream()
        .map(account -> view(account, permissions.getOrDefault(account.id(), List.of())))
        .toList();
  }

  public AccountView get(long id) {
    AccountsRepository.AccountSummary account = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    return view(account, permissionService.codesForAccount(id));
  }

  public AccountView create(String username, String password, Boolean enabled, String createdBy) {
    String normalized = normalizeUsername(username);
    validatePassword(password);
    if (adminIdentity.configured() && adminIdentity.username().equalsIgnoreCase(normalized)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "username is reserved for the administrator");
    }
    if (SystemAccount.USERNAME.equals(normalized)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "username is reserved for the system account");
    }
    Instant now = Instant.now();
    long id;
    try {
      id = repository.insert(normalized, passwordEncoder.encode(password), enabled == null || enabled, now, createdBy);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
    }
    AccountsRepository.AccountSummary created = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "account was not persisted"));
    return view(created, List.of());
  }

  public AccountView update(long id, Boolean enabled, String password) {
    if (enabled == null && password == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one of enabled or password is required");
    }
    AccountsRepository.AccountSummary account = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    requireNotSystemAccount(account, "modified");
    String passwordHash = password == null ? null : encodeNewPassword(password);
    repository.update(id, enabled, passwordHash, Instant.now());
    AccountsRepository.AccountSummary updated = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    return view(updated, permissionService.codesForAccount(id));
  }

  public void delete(long id) {
    AccountsRepository.AccountSummary account = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    requireNotSystemAccount(account, "deleted");
    if (!repository.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found");
  }

  private static void requireNotSystemAccount(AccountsRepository.AccountSummary account, String action) {
    if (account.systemAccount()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "system account cannot be " + action);
    }
  }

  private static AccountView view(AccountsRepository.AccountSummary account, List<String> permissions) {
    return new AccountView(account.id(), account.username(), account.enabled(), account.createdAt(),
        account.createdBy(), account.passwordChangedAt(), permissions, account.systemAccount());
  }

  private String normalizeUsername(String username) {
    String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(normalized).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "username must be 3-32 characters: lowercase letters, digits, dot, underscore or dash");
    }
    return normalized;
  }

  private String encodeNewPassword(String password) {
    validatePassword(password);
    return passwordEncoder.encode(password);
  }

  private void validatePassword(String password) {
    if (password == null || password.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password is required");
    }
    int bytes = password.getBytes(StandardCharsets.UTF_8).length;
    if (bytes < MIN_PASSWORD_BYTES || bytes > MAX_PASSWORD_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "password must be between " + MIN_PASSWORD_BYTES + " and " + MAX_PASSWORD_BYTES + " bytes");
    }
  }

  /** 账号视图，包含权限码，但绝不包含密码哈希。 */
  public record AccountView(long id, String username, boolean enabled, Instant createdAt, String createdBy,
      Instant passwordChangedAt, List<String> permissions, boolean systemAccount) {}
}
