package com.God_Symbol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时准备权限体系的基础数据（幂等）：
 *
 * <ol>
 *   <li>确保文件传输的 5 个权限定义存在；</li>
 *   <li>确保“未登录用户”系统账号存在；</li>
 *   <li><b>仅在该系统账号首次创建时</b>授予全部 5 项权限，之后管理员的撤销不会被重启恢复。</li>
 * </ol>
 *
 * <p>用代码而不是 Flyway 种子数据，是为了在测试环境（{@code spring.flyway.enabled=false}）
 * 也能得到完全一致的行为。
 */
@Component
public class SystemAccountInitializer implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(SystemAccountInitializer.class);

  private final AccountsRepository accounts;
  private final PermissionRepository permissions;

  public SystemAccountInitializer(AccountsRepository accounts, PermissionRepository permissions) {
    this.accounts = accounts;
    this.permissions = permissions;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Instant now = Instant.now();
    ensurePermissionDefinitions(now);

    if (accounts.findSystemAccount().isPresent()) return;

    long accountId;
    try {
      accountId = accounts.insertSystemAccount(SystemAccount.USERNAME, SystemAccount.PASSWORD_HASH, now,
          SystemAccount.CREATED_BY);
    } catch (DuplicateKeyException e) {
      // 多实例同时启动，或数据库里已有同名的普通账号（Flyway 关闭的手工环境）。
      log.warn("could not create system account '{}': username is already in use", SystemAccount.USERNAME);
      return;
    }

    // 首次创建时给出与升级前完全一致的匿名体验；之后由管理员自由调整。
    Map<String, Long> permissionIds = new LinkedHashMap<>();
    for (PermissionRepository.Permission permission : permissions.findAll()) {
      permissionIds.put(permission.code(), permission.id());
    }
    for (TransferPermissions.Seed seed : TransferPermissions.defaults()) {
      Long permissionId = permissionIds.get(seed.code());
      if (permissionId != null) {
        permissions.insertGrant(accountId, permissionId, now, SystemAccount.CREATED_BY);
      }
    }
    log.info("created system account '{}' with default transfer permissions", SystemAccount.USERNAME);
  }

  private void ensurePermissionDefinitions(Instant now) {
    for (TransferPermissions.Seed seed : TransferPermissions.defaults()) {
      if (permissions.findByCode(seed.code()).isPresent()) continue;
      try {
        permissions.insert(seed.code(), seed.name(), seed.description(), now);
      } catch (DuplicateKeyException ignored) {
        // 另一个实例已经创建；唯一索引保证不会重复。
      }
    }
  }
}
