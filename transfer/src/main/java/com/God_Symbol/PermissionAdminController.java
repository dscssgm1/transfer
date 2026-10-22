package com.God_Symbol;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 权限目录与账号授权管理，受 {@code /api/admin/** hasRole("ADMIN")} 保护。
 */
@RestController
@RequestMapping("/api/admin")
public class PermissionAdminController {
  private final PermissionService permissionService;
  private final AccountService accountService;

  public PermissionAdminController(PermissionService permissionService, AccountService accountService) {
    this.permissionService = permissionService;
    this.accountService = accountService;
  }

  /** 权限目录：页面新增或直接写入 permissions 表后都会出现在这里。 */
  @GetMapping("/permissions")
  public Map<String, Object> list() {
    return Map.of("permissions", permissionService.listPermissions());
  }

  /** 在管理页面新增权限定义；code 即代码注解中使用的唯一 Key。 */
  @PostMapping("/permissions")
  @ResponseStatus(HttpStatus.CREATED)
  public PermissionRepository.Permission create(@RequestBody CreatePermissionRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    return permissionService.createPermission(request.code(), request.name(), request.description());
  }

  /** 删除权限定义，同时级联移除所有账号上的该授权。 */
  @DeleteMapping("/permissions/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String code) {
    permissionService.deletePermission(code);
  }

  /** 覆盖单个账号的权限集合。 */
  @PutMapping("/accounts/{id}/permissions")
  public AccountService.AccountView replace(@PathVariable long id,
      @RequestBody ReplacePermissionsRequest request, @AuthenticationPrincipal Jwt jwt) {
    if (request == null || request.permissions() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissions is required");
    }
    permissionService.replaceForAccount(id, request.permissions(), jwt.getSubject());
    return accountService.get(id);
  }

  /** 批量授予：accountIds × permissions 的笛卡尔积。 */
  @PostMapping("/account-permissions/grant")
  public PermissionService.BulkResult grant(@RequestBody BulkPermissionRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    return permissionService.grant(request.accountIds(), request.permissions(), jwt.getSubject());
  }

  /** 批量撤销；未授权的组合会被忽略。 */
  @PostMapping("/account-permissions/revoke")
  public PermissionService.BulkResult revoke(@RequestBody BulkPermissionRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    return permissionService.revoke(request.accountIds(), request.permissions());
  }

  record CreatePermissionRequest(String code, String name, String description) {}
  record ReplacePermissionsRequest(List<String> permissions) {}
  record BulkPermissionRequest(List<Long> accountIds, List<String> permissions) {}
}
