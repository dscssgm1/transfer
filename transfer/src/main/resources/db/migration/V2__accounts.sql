-- 普通账号：只能由管理员创建，不支持公开注册。
-- 管理员本身来自环境变量，不在此表中。
CREATE TABLE accounts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  password_changed_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_accounts_username UNIQUE (username)
);
