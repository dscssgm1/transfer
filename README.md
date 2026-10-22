# DropKey — 匿名文件传输服务（单体部署）

一个无需注册的临时文件传输服务：A 上传文件后得到 6 位验证码，B 输入验证码即可下载；文件严格小于 2 GiB，上传 20 分钟后自动清除；上传者可在“我的文件”页管理并立即删除自己的文件。上传、下载、删除、列表和验证码查询都纳入功能权限体系：未登录访客由「未登录用户」系统账号统一代表（默认拥有全部文件操作权限），管理员可通过环境变量账号登录，创建普通账号并分配功能权限；普通账号通过同一入口登录，受保护操作会实时校验权限。

## 部署形态

| 组件 | 运行位置 | 职责 |
|---|---|---|
| nginx | Docker 容器 | 托管 Vue 静态页面，反代 `/api` 与健康检查到宿主机 |
| MySQL | Docker 容器 | 保存文件元数据 |
| Spring Boot | 宿主机 JAR | 提供 `/api/transfers` 接口，文件写入本机存储目录 |

nginx 是唯一对外入口（默认 8080），浏览器只与它通信，因此 `/api` 同源、Cookie 无需 CORS 配置。

## 目录结构

```
transfer/
├── nginx/
│   ├── Dockerfile          # nginx 镜像：仅配置，不含前端产物
│   └── default.conf        # 静态托管 + /api 反代
├── docker-compose.yml      # nginx + MySQL
├── transfer/               # Spring Boot 后端（Java 21 / Maven）
└── transfer-ui/            # Vue 3 + Vite 前端项目
    └── dist/               # 构建产物，通过 bind mount 提供给 nginx
```

### 前端使用 bind mount

nginx 容器把宿主机的 `${FRONTEND_DIR}`（默认 `/home/transfer/transfer-ui`）挂载到 `/usr/share/nginx/html`，因此：

- 前端产物**不打进镜像**，更新前端无需 `docker compose build`
- 替换目录内的文件后**立即生效**，无需重启容器
- `index.html` 响应带 `Cache-Control: no-cache`，浏览器刷新即可拿到新版本
- `assets/` 下的文件名含内容哈希，可安全长期缓存

服务器上该目录应直接存放 `dist` 的内容：

```
/home/transfer/transfer-ui/
├── index.html
└── assets/
    ├── index-xxxx.js
    └── index-xxxx.css
```

本地路径不同时用环境变量覆盖：

```bash
FRONTEND_DIR=/path/to/transfer-ui/dist docker compose up -d nginx
```

## 数据库

- MySQL 8.0+（推荐 8.4 LTS），驱动 `com.mysql:mysql-connector-j`，迁移使用 Flyway `flyway-mysql`。
- 时间列使用 `DATETIME(6)` 保存 **UTC** 时间；连接串带 `serverTimezone=UTC`，容器通过 `--default-time-zone=+00:00` 固定为 UTC。
- 表结构由 Flyway 在启动时自动创建：`V1__init.sql`（传输记录）、`V2__accounts.sql`（普通账号）、`V3__permissions.sql`（权限目录与授权关系）、`V4__system_account.sql`（“未登录用户”系统账号标记）。

## 生产部署

### 1. 启动 MySQL 与 nginx

```bash
cd /home/transfer

# 1. 构建前端并放到挂载目录
cd transfer-ui && npm install && npm run build && cd ..
# transfer-ui/dist/ 的内容需要位于 ${FRONTEND_DIR}（默认 /home/transfer/transfer-ui）

# 2. 启动中间件
MYSQL_ROOT_PASSWORD=<强随机密码> \
MYSQL_PASSWORD=<强随机密码> \
docker compose up -d --build
```

端口可用环境变量覆盖：`NGINX_PORT`（默认 8080）、`MYSQL_PORT`（默认 3306）、`FRONTEND_DIR`（默认 `/home/transfer/transfer-ui`）。

### 更新前端

```bash
# 本地构建
cd transfer-ui && npm run build

# 覆盖到服务器挂载目录（无需重启容器）
scp -r dist/* root@<server>:/home/transfer/transfer-ui/
```

旧版本的哈希资源不会自动删除，可定期清理 `assets/` 下未被 `index.html` 引用的文件。

### 2. 启动宿主机上的 Spring Boot

```bash
java -jar transfer/target/transfer-1.0.0.jar \
  --server.port=8080 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/transfer?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8&useUnicode=true' \
  --spring.datasource.username=transfer \
  --spring.datasource.password='<MYSQL_PASSWORD>' \
  --transfer.storage=/var/lib/transfer/data \
  --transfer.owner-secret='<长随机字符串>' \
  --transfer.cookie-secure=true
```

要点：

- 后端**不要**对外暴露 8080，只让 nginx 访问；公网只开 nginx 的端口。
- `transfer.storage` 必须是**绝对路径**。相对路径会相对于 JVM 工作目录解析，systemd 下容易落到 `/data`。
- 公网 HTTPS 时 `transfer.cookie-secure=true`，否则浏览器不会保存管理 Cookie。
- 容器内的 nginx 通过 `host.docker.internal` 访问宿主机；Compose 已配置 `host-gateway` 映射（Linux 必需，Docker Desktop 内置）。

### 3. HTTPS

nginx 目前监听 80。公网部署建议在前面再加一层 HTTPS 终止（宿主机 Caddy/Nginx，或云负载均衡），并把 `transfer.cookie-secure` 保持为 `true`。

## 健康检查

`/actuator/health/readiness` 已纳入 `db` 检查器：数据库不可用时返回 `503 DOWN`，页面页脚的指示灯也据此显示。nginx 已把该路径反代到后端。

```bash
curl -i http://127.0.0.1:8080/actuator/health/readiness
```

## 构建说明

前端由 nginx 托管，后端单独打包：

```bash
# 前端
cd transfer-ui && npm run build        # 产物 transfer-ui/dist

# 后端
cd ../transfer && mvn clean package -DskipTests   # 产物 transfer/target/transfer-1.0.0.jar
```

`nginx/Dockerfile` 只负责写入 `nginx/default.conf`，前端产物通过 bind mount 提供，不参与镜像构建。

## 本地开发

后端（`transfer/`），需要本机 MySQL 监听 3306：

```bash
cd transfer
mvn spring-boot:run   # 默认 8080，连接 mysql://localhost:3306/transfer
```

如果只想启动数据库：

```bash
cd ..           # transfer/ 根目录
docker compose up -d mysql
```

前端（`transfer-ui/`）：

```bash
cd transfer-ui
npm install
npm run dev           # 默认 5173，/api 代理到 http://localhost:8080
```

## 数据库连接排障

启动时报 `Communications link failure` 或 `Connection refused`，说明 MySQL 未运行或地址端口不对：

```bash
docker compose ps
docker compose logs --tail=100 mysql
mysqladmin ping -h 127.0.0.1 -P 3306 -u transfer -p
```

手工创建库和用户（非 Docker 部署时）：

```sql
CREATE DATABASE transfer CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'transfer'@'%' IDENTIFIED BY 'transfer';
GRANT ALL PRIVILEGES ON transfer.* TO 'transfer'@'%';
FLUSH PRIVILEGES;
```

## 备份

```bash
docker compose exec -T mysql mysqldump -utransfer -p transfer > transfer-db-$(date +%F).sql
```

文件存放在宿主机的 `transfer.storage` 目录（示例为 `/var/lib/transfer/data`），需与数据库备份**同时**保留，否则记录和文件会不一致。两者都可直接拷贝，不涉及 Docker 卷。

由于文件 20 分钟后自动删除，备份的价值主要在数据库表结构和运行配置，而不是历史文件。

## API

| 方法 | 路径 | 权限 Key | 说明 |
|------|------|----------|------|
| POST | `/api/transfers` | `transfer.upload` | 上传文件（multipart 字段 `file`），返回 6 位验证码 |
| GET | `/api/transfers` | `transfer.list` | 列出当前浏览器（owner Cookie）上传的文件 |
| GET | `/api/transfers/capacity` | 无（公开） | 全站共享存储的已用 / 总量 / 剩余字节数 |
| GET | `/api/transfers/{key}` | `transfer.query` | 按验证码查询文件信息 |
| POST | `/api/transfers/{key}/download-ticket` | `transfer.download` | 换取 60 秒有效的下载票据，返回 `{"url","expiresAt"}` |
| GET | `/api/transfers/{key}/download` | 有效票据或 `transfer.download` | 按验证码下载文件 |
| DELETE | `/api/transfers/{key}` | `transfer.delete` | 删除当前浏览器（owner Cookie）上传的文件 |

未登录访客的权限来自「未登录用户」系统账号，默认包含以上全部 5 个 Key，因此升级后匿名上传/查询/下载/列表/删除的行为不变。

### 下载票据

浏览器通过 `<a href>` 下载时无法附加 `Authorization` 头，所以网页会先调用 `POST /api/transfers/{key}/download-ticket`，再用返回的 URL 触发下载：

```bash
# 未登录用户默认拥有 transfer.download，可直接换取
curl -s -X POST http://127.0.0.1:8080/api/transfers/ABCD23/download-ticket
# {"url":"/api/transfers/ABCD23/download?ticket=...","expiresAt":"..."}

# 命令行也可以直接带 Bearer 令牌下载（不需要票据）
curl -fL -OJ -H "Authorization: Bearer $USER_TOKEN" \
  http://127.0.0.1:8080/api/transfers/ABCD23/download
```

票据是 HMAC-SHA256 签名的 `过期时间.签名`，只绑定单个验证码，不写入数据库；默认 60 秒过期，可用 `TRANSFER_DOWNLOAD_TICKET_TTL` 调整（上限 5 分钟）。票据会出现在 URL 查询参数中，可能进入访问日志，因此只用于这一个下载接口。

### 共享存储配额

默认 40 GiB（`TRANSFER_CAPACITY_BYTES=42949672960`）。已用容量按数据库中**未过期**文件的字节数汇总，页面顶部的容量条对所有人可见。

空间不足时上传返回 `507 Insufficient Storage`，且不会留下临时或半成品文件。校验在写入前按声明大小执行一次，写入后再按实际字节数复核一次。

```bash
curl -s http://127.0.0.1:8080/api/transfers/capacity
# {"used":0,"limit":42949672960,"available":42949672960}
```

> 当前实现用进程内锁保证单实例下并发上传不会超配额。若将来改为多实例，需要换成数据库层的容量预约。

## 登录、账号与权限

### 登录方式

管理员和普通账号共用 `POST /api/auth/login`。前端右上角的「登录」按钮打开同一个弹窗；登录成功后：

- 管理员顶部额外出现「系统管理」按钮，进入 `#/admin`，页面分为「账号管理」和「权限管理」两个标签页。
- 普通账号没有「系统管理」入口；即使直接请求 `/api/admin/**` 也会得到 `403`。

| 账号类型 | 来源 | JWT claims |
|---|---|---|
| 管理员 | `TRANSFER_ADMIN_USERNAME` / `TRANSFER_ADMIN_PASSWORD` 环境变量 | `sub`、`roles=["ADMIN"]`、`cred` |
| 普通账号 | `accounts` 表，由管理员创建 | `sub`、`roles=["USER"]`、`uid`、`pwdv` |

管理员账号**不写入数据库**，也没有公开注册入口。JWT 保存在 localStorage 的 `dropkey.auth.jwt`，后续请求使用 `Authorization: Bearer <token>`；旧版本的 `dropkey.admin.jwt` 会在页面启动时自动迁移。

### 环境变量

| 环境变量 | 说明 |
|---|---|
| `TRANSFER_ADMIN_USERNAME` | 管理员用户名，留空则管理员功能关闭（普通账号仍可登录） |
| `TRANSFER_ADMIN_PASSWORD` | 管理员密码，留空则管理员功能关闭 |
| `TRANSFER_ADMIN_JWT_SECRET` | JWT 签名密钥，建议至少 32 字节随机值；留空时由管理员用户名和密码派生 |
| `TRANSFER_ADMIN_SESSION_TTL` | 管理员登录有效期，默认 `8h`，限制在 5 分钟 ~ 7 天 |
| `TRANSFER_AUTH_SESSION_TTL` | 普通账号登录有效期，默认 `8h`，限制在 5 分钟 ~ 7 天 |
| `TRANSFER_DOWNLOAD_TICKET_TTL` | 浏览器下载票据有效期，默认 `60s`；非法值回退为 60 秒，最长 5 分钟 |
| `TRANSFER_ADMIN_LOGIN_MAX_ATTEMPTS` | 登录窗口内允许的失败次数，默认 5 |
| `TRANSFER_ADMIN_LOGIN_WINDOW` | 登录失败计数窗口，默认 `15m` |

```bash
export TRANSFER_ADMIN_USERNAME=admin
export TRANSFER_ADMIN_PASSWORD='<强随机密码>'
export TRANSFER_ADMIN_JWT_SECRET='<至少 32 字节随机串>'

# 这些变量会被 application.properties 自动读取，无需再写成命令行参数，
# 避免密码出现在 ps 输出中。生产环境建议使用 systemd EnvironmentFile（权限 600）。
java -jar transfer/target/transfer-1.0.0.jar
```

### 令牌失效

- 管理员：修改用户名、密码或 JWT 密钥后，已签发的令牌立即失效。
- 普通账号：账号被禁用、删除或重置密码后，旧令牌在下一次请求时立即失效（每次请求都会回查账号）。
- 权限变更**不需要重新登录**：普通账号的权限每次请求都实时从 `account_permissions` 读取，撤销立即生效。
- 登录失败按客户端 IP 限流（默认 15 分钟 5 次），计数保存在内存中，仅适用于当前单实例部署。
- 退出登录只清除浏览器中的令牌；服务端不维护撤销列表。生产环境必须使用 HTTPS，并通过 CSP 等措施避免 XSS 读取 localStorage。

### 权限模型

权限定义保存在 `permissions` 表。服务首次启动时会自动写入文件传输所需的 5 个 Key（`transfer.upload`、`transfer.download`、`transfer.delete`、`transfer.list`、`transfer.query`），已存在则跳过，不会覆盖管理员后续的修改。管理员也可以用两种方式添加其他功能的权限：

1. 在管理页 `#/admin` →「权限管理」的权限目录表单中直接填写 Key、名称和说明；
2. 直接写数据库（适合部署时初始化）：

```sql
INSERT INTO permissions (code, name, description, created_at)
VALUES ('transfer.manage', '管理传输', '允许管理全站传输记录', UTC_TIMESTAMP(6));
```

`code` 规则：小写字母开头，只含小写字母、数字、`.`、`_`、`-`，长度 2–100，且全局唯一。页面上的「删除权限」会同时移除所有账号上的该授权。

- 授权关系保存在 `account_permissions`，一行表示一个账号与一个权限 Key 的对应关系；一个账号可以有多条记录。
- 账号行点「管理权限」可覆盖该账号的权限集合；「权限管理」页可以勾选多个账号和多个权限，批量授予或撤销。
- **管理员绕过所有权限检查**，因此不需要给管理员账号授权。
- 账号或权限被删除时，对应的授权记录会级联删除。

### 「未登录用户」系统账号

未登录访客不再游离于权限体系之外，而是统一由数据库中的系统账号代表：

- 用户名固定为 `anonymous`，在管理页显示为「未登录用户」并带「系统账号」徽标；该用户名不能再被创建为普通账号（`409`）。
- 启动时由 `SystemAccountInitializer` 幂等创建，**仅在首次创建时**授予 5 个文件传输权限。之后管理员撤销的权限不会因重启而恢复。
- 匿名请求的每个文件操作都会实时查询该账号的授权，因此修改权限后下一个请求立即生效。
- 系统账号**不能登录**（`401`）、**不能禁用、删除或重置密码**（`400`），但可以在「账号管理」或「权限管理」中随时调整权限。
- 所有未登录访客共享同一个账号的权限，无法按访客区分；这是“用统一账号管理匿名用户”的预期行为。

后端功能上线时，用 `@RequiresPermission` 标注该功能唯一的 Key：

```java
@GetMapping("/api/user/reports")
@RequiresPermission("transfer.manage")
public List<Report> reports() { ... }
```

然后在管理页添加同名 Key，即可给账号授权。等价的 Spring Security 写法是：

```java
@PreAuthorize("@permissionGuard.has(authentication, 'transfer.manage')")
```

也可以使用 `@permissionGuard.hasAny(authentication, 'a.read', 'a.write')`。两种方式都会在方法执行前实时查 `account_permissions`，因此授予/撤销在下一个请求立即生效。需要功能权限的用户接口建议统一放在 `/api/user/**`（已要求登录）。

### 认证与管理员 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 管理员与普通账号统一登录，返回 token、roles、permissions、expiresAt |
| GET | `/api/auth/me` | 登录 | 校验令牌，返回用户名、角色、实时权限和有效期 |
| GET | `/api/admin/config` | 公开 | 管理员功能是否已配置 |
| POST | `/api/admin/login` | 公开 | 兼容旧版，**仅管理员**可登录 |
| GET | `/api/admin/me` | ADMIN | 校验令牌并返回当前管理员 |
| GET | `/api/admin/accounts` | ADMIN | 账号列表（含权限码，不含密码哈希） |
| POST | `/api/admin/accounts` | ADMIN | 添加账号 |
| PATCH | `/api/admin/accounts/{id}` | ADMIN | 修改启用状态和/或重置密码 |
| DELETE | `/api/admin/accounts/{id}` | ADMIN | 删除账号 |
| GET | `/api/admin/permissions` | ADMIN | 权限目录，按 code 排序；首次启动含 5 个 `transfer.*` Key |
| POST | `/api/admin/permissions` | ADMIN | 新增权限，body `{"code":"transfer.manage","name":"管理传输","description":"可选"}` |
| DELETE | `/api/admin/permissions/{code}` | ADMIN | 删除权限定义，并级联移除所有账号上的授权 |
| PUT | `/api/admin/accounts/{id}/permissions` | ADMIN | 覆盖单个账号的权限，body `{"permissions":["a","b"]}` |
| POST | `/api/admin/account-permissions/grant` | ADMIN | 批量授予，body `{"accountIds":[1,2],"permissions":["a"]}` |
| POST | `/api/admin/account-permissions/revoke` | ADMIN | 批量撤销，body 同上 |

```bash
# 管理员登录
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<管理员密码>"}' | jq -r .token)

# 创建普通账号
curl -s -X POST http://127.0.0.1:8080/api/admin/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"<初始密码>"}'

# 普通账号登录，返回 USER 令牌
USER_TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"<初始密码>"}' | jq -r .token)

# 授予权限后，同一个 USER_TOKEN 立即获得新权限
curl -s -X PUT http://127.0.0.1:8080/api/admin/accounts/1/permissions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"permissions":["transfer.manage"]}'

# 查看当前账号的实时权限
curl -s http://127.0.0.1:8080/api/auth/me \
  -H "Authorization: Bearer $USER_TOKEN"
```

状态码：未登录或凭据错误 `401`，已登录但权限不足 `403`；匿名访客的权限来自「未登录用户」系统账号，若该账号缺少对应权限同样返回 `403`。参数/权限码错误 `400`，账号或权限不存在 `404`，用户名或权限 Key 重复 `409`，登录限流 `429`，管理员未配置 `503`。普通账号用户名 3–32 位，仅允许小写字母、数字、点、下划线和短横线；密码 8–72 个 UTF-8 字节，使用 BCrypt 保存。

## 从 PostgreSQL 迁移已有数据

如果此前的 PostgreSQL 环境已保存过文件记录，**不能**直接换镜像了事：

1. 停止写入并备份 PostgreSQL 与 `transfer-data` 卷。
2. 单独导出 `transfers` 数据（不要用 `pg_dump` 的 DDL 直接导入 MySQL）。
3. 将 `TIMESTAMPTZ` 值转换为 UTC 后写入 MySQL 的 `DATETIME(6)` 列。
4. 核对记录数、验证码、文件名、大小、过期时间与实际文件是否一致。
5. 切换 `SPRING_DATASOURCE_URL` 后启动应用，确认 Flyway 校验通过。

如果是全新部署，直接使用本仓库的 MySQL 配置即可。
