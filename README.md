# DropKey — 匿名文件传输服务（单体部署）

一个无登录的临时文件传输服务：A 上传文件后得到 6 位验证码，B 输入验证码即可下载；文件严格小于 2 GiB，上传 20 分钟后自动清除；上传者可在“我的文件”页管理并立即删除自己的文件。

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
- 表结构由 `transfer/src/main/resources/db/migration/V1__init.sql` 在启动时自动创建。

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

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/transfers` | 上传文件（multipart 字段 `file`），返回 6 位验证码 |
| GET | `/api/transfers` | 列出当前浏览器（owner Cookie）上传的文件 |
| GET | `/api/transfers/{key}` | 按验证码查询文件信息 |
| GET | `/api/transfers/{key}/download` | 按验证码下载文件 |
| DELETE | `/api/transfers/{key}` | 删除当前浏览器（owner Cookie）上传的文件 |

## 从 PostgreSQL 迁移已有数据

如果此前的 PostgreSQL 环境已保存过文件记录，**不能**直接换镜像了事：

1. 停止写入并备份 PostgreSQL 与 `transfer-data` 卷。
2. 单独导出 `transfers` 数据（不要用 `pg_dump` 的 DDL 直接导入 MySQL）。
3. 将 `TIMESTAMPTZ` 值转换为 UTC 后写入 MySQL 的 `DATETIME(6)` 列。
4. 核对记录数、验证码、文件名、大小、过期时间与实际文件是否一致。
5. 切换 `SPRING_DATASOURCE_URL` 后启动应用，确认 Flyway 校验通过。

如果是全新部署，直接使用本仓库的 MySQL 配置即可。
