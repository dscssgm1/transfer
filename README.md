# DropKey — 匿名文件传输服务（单体部署）

一个无登录的临时文件传输服务：A 上传文件后得到 6 位验证码，B 输入验证码即可下载；文件严格小于 2 GiB，上传 20 分钟后自动清除；上传者可在“我的文件”页管理并立即删除自己的文件。

当前阶段为**单体部署**：单个 Spring Boot 进程同时提供前端页面和 `/api` 接口，元数据保存在 MySQL。后续如需微服务或多实例扩展，保持 `/api/transfers` 契约不变即可拆分。

## 目录结构

```
transfer/
├── Dockerfile              # 多阶段构建：前端 → 后端(打包进 Vue) → JRE
├── docker-compose.yml      # 单 app + MySQL
├── transfer/               # Spring Boot 后端（Java 21 / Maven）
└── transfer-ui/            # Vue 3 + Vite 前端源码
```

## 数据库

- MySQL 8.0+（推荐 8.4 LTS），驱动 `com.mysql:mysql-connector-j`，迁移使用 Flyway `flyway-mysql`。
- 时间列使用 `DATETIME(6)` 保存 **UTC** 时间；连接串带 `serverTimezone=UTC`，容器通过 `--default-time-zone=+00:00` 固定为 UTC。
- 表结构由 `transfer/src/main/resources/db/migration/V1__init.sql` 在启动时自动创建。

## 生产部署（Docker Compose）

```bash
# 在 transfer/ 目录下
TRANSFER_OWNER_SECRET=<一段长随机字符串> \
MYSQL_ROOT_PASSWORD=<MySQL root 密码> \
TRANSFER_COOKIE_SECURE=true \
docker compose up --build -d
```

- 服务监听 `http://<host>:8080`，根路径为 Vue 页面，`/api` 同源。
- 上传文件存放在 Docker 卷 `transfer-data`；元数据存放在 MySQL 卷 `mysql-data`。
- 公网 HTTPS 部署时必须设置 `TRANSFER_COOKIE_SECURE=true` 并使用强随机 `TRANSFER_OWNER_SECRET`。
- 宿主端口可用 `APP_PORT` 覆盖（如 `APP_PORT=9000 docker compose up -d`）。Windows 上若 8080 落在 Hyper-V 保留端口段内导致绑定失败，改此变量即可。

## 健康检查

`/actuator/health/readiness` 已纳入 `db` 检查器：数据库不可用时返回 `503 DOWN`，因此容器健康状态和前端页脚的指示灯都能反映数据库真实状态。

```bash
curl -i http://127.0.0.1:8080/actuator/health/readiness
```

## 构建说明

`Dockerfile` 为多阶段构建：`node` 编译 Vue → `maven` 打包后端并注入前端产物 → JRE 运行。静态资源**只**来自构建时的 Vue 产物（`.dockerignore` 排除了本地 `transfer/src/main/resources/static`），因此不会把陈旧的前端打进镜像。

本机构建时请先同步前端产物，再打包：

```bash
cd transfer-ui && npm run build
rm -rf ../transfer/src/main/resources/static && mkdir -p ../transfer/src/main/resources/static
cp -R dist/* ../transfer/src/main/resources/static/
cd ../transfer && mvn clean package -DskipTests
```

## 本地开发

后端（`transfer/`），需要本机 MySQL 监听 3306：

```bash
cd transfer
mvn spring-boot:run   # 默认 8080，连接 mysql://localhost:3306/transfer
```

如果只想启动数据库：

```bash
cd ..           # transfer/ 根目录
docker compose up -d db
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
docker compose logs --tail=100 db
mysqladmin ping -h 127.0.0.1 -P 3306 -u transfer -ptransfer
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
docker compose exec -T db mysqldump -utransfer -ptransfer transfer > transfer-db-$(date +%F).sql
```

MySQL 备份与 `transfer-data` 文件卷必须**同时**保留，否则数据库记录和实际文件会不一致。

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
