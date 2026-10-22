-- 功能权限目录与账号授权关系。
-- permissions 初始为空：以后新增功能时插入一行（Flyway 迁移或手工 SQL），
-- 管理页面刷新后会自动出现该权限，无需修改前后端代码。
CREATE TABLE permissions (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(100) NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE account_permissions (
  account_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  granted_at DATETIME(6) NOT NULL,
  granted_by VARCHAR(64) NOT NULL,
  PRIMARY KEY (account_id, permission_id),
  CONSTRAINT fk_ap_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_ap_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

CREATE INDEX idx_ap_permission ON account_permissions (permission_id);
