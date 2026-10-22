-- 系统账号：代表所有未登录访客。
-- system_account = TRUE 的行禁止登录、删除、禁用和重置密码；
-- 它的权限就是匿名访客的权限，由管理员在账号 / 权限页面维护。
ALTER TABLE accounts ADD COLUMN system_account BOOLEAN NOT NULL DEFAULT FALSE;

-- 兼容升级：旧版本允许创建名为 anonymous 的普通账号。
-- 将其改名并保留密码、权限与登录能力，为系统账号腾出保留用户名。
-- id 拼接后最长 36 个字符，低于 username 的 64 字符上限。
UPDATE accounts
SET username = CONCAT('anonymous_legacy_', id)
WHERE username = 'anonymous' AND system_account = FALSE;
