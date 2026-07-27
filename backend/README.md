# Spring Boot Demo · 购物仓储管理系统（WMS）

> 基于 Spring Boot 4.1.0 + Spring Security 7 + MyBatis-Plus 3.5 的仓储管理后台（WMS）服务端。
> 配套前端 API 文档：[购物仓储管理系统API接口文档.md](购物仓储管理系统API接口文档.md)（**v1.2**，2026-07-24）。
> 业务流程与时序图见 [wms-api-sequence/](wms-api-sequence/) 目录（按章节切分的 Markdown）。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-336791?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-8-DC382D?logo=redis)](https://redis.io/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600?logo=rabbitmq)](https://www.rabbitmq.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

> 🔗 **Gitee 仓库**：`<待填写 Gitee 仓库地址>`（首推推送后回填）

---

## 目录

- [一、技术栈与版本](#一技术栈与版本)
- [二、项目架构](#二项目架构)
- [三、基础设施启动（首次运行）](#三基础设施启动首次运行)
- [四、启动方式](#四启动方式)
- [五、环境变量与默认值](#五环境变量与默认值)
- [六、API 一览](#六api-一览)
- [七、关键实现细节](#七关键实现细节)

---

## 0. 模块边界与责任划分

> 当前版本（Maven 单模块 + 包内划分）按以下边界组织代码；
> 重构目标为按业务域拆分为 **多模块 Maven 工程**（父 POM + `platform / catalog / procurement / ivp / sales / app` 六子模块）。

| 模块 | Service 接口（当前包） | 主要责任 | 对应 API 文档章节 |
|------|------------------------|----------|--------------------|
| **Platform** | `UserService`、`RoleService`、`MenuService`、`AuthService` | 用户/角色/菜单/认证、JWT、Service Token | §二 |
| **Catalog** | `ProductCatalogService`、`OwnerService`、`BarcodeService` | 商品 / SPU / SKU / 分类 / 货主 / 条码 | §三、§10.5 |
| **Procurement** | `ProcurementService`、`SupplierAddressService` | 供应商 / 采购申请 / 采购订单 / 入库 / 退货 | §四、§五 |
| **IVP** | `InventoryQueryService`、`InventoryCommandService`、`StockMutationService`、`StockLockService`、`RestockService` | 仓库 / 库位 / 库存 / 流水 / 盘点 / 调拨 / 预占 | §六、§10.1、§10.6 |
| **Sales** | `SalesService`、`OrderMessageService`、`OutboxEventService` | 客户 / 地址 / 购物车 / 订单 / 支付 / 退款 / 售后 | §四.0、§四.3、§七、§10.2 |
| **共享基础设施** | `SecurityConfig`、`RabbitMqConfig`、`MybatisPlusConfig`、`JsonConfig`、`OperationLogAspect` | Spring Security / RabbitMQ / MyBatis-Plus / Jackson / 操作日志 | §一.1、§九 |

**数据归属**（与 API 文档附录 A 一致）：`crm_* / ord_* / pay_* / ref_*` 归 Sales；`sto_*` 归 IVP；`pro_* / own_*` 归 Catalog；`sup_* / pur_*` 归 Procurement；`sys_*` 归 Platform；`sales_outbox / mq_consume_log / mq_dead_letter` 跨模块。

---

## 一、技术栈与版本

| 组件 | 版本 | 备注 |
|---|---|---|
| **JDK** | 21 (LTS) | 项目目标版本（`pom.xml` 中 `java.version=21`）。本机默认 `java -version` 可能输出 JDK 25，需要时设置 `JAVA_HOME` 指向 JDK 21 |
| **Maven** | 3.9.16 | 已装在 `C:\Users\unicorn\tools\apache-maven-3.9.16` |
| **Spring Boot** | 4.1.0 | 注意：Initializr 上的版本号带 `.RELEASE` 后缀，但 Maven Central 上的坐标是裸版本号 `<version>4.1.0</version>` |
| **Spring Framework** | 7.0.8 | 由 Spring Boot 4.1.0 管理 |
| **Spring Security** | 7.1.0 | 由 Spring Boot 4.1.0 管理 |
| **Spring Web MVC** | starter-webmvc 4.1.0 | Spring Boot 4 中 servlet web starter 名改为 `spring-boot-starter-webmvc` |
| **Tomcat (内嵌)** | 11.0.22 | |
| **MyBatis-Plus** | 3.5.15 | starter: `mybatis-plus-spring-boot4-starter`；分页插件在 `mybatis-plus-jsqlparser` |
| **MyBatis-Spring** | 3.0.5 | |
| **PostgreSQL JDBC Driver** | 42.7.11 | `org.postgresql:postgresql` |
| **Redis** | 8 | 缓存 + 分布式锁 + Lua 脚本实现库存扣减 |
| **Redisson** | 4.6.1 | `redisson-spring-boot-starter` |
| **RabbitMQ** | 4 | 订单 / 库存事件、死信队列（DLX） |
| **Jackson** | 2.21.4 | 显式引入 Jackson 2，与 Spring Boot 4 自带的 Jackson 3 自动配置共存 |
| **Lombok** | 1.18.46 | `<optional>true</optional>` |
| **HikariCP** | 7.0.2 | 由 `spring-boot-starter-jdbc` 管理 |
| **Testcontainers** | 2.0.5 | 集成测试用，starter 名带 `testcontainers-` 前缀 |
| **数据库** | PostgreSQL 18 | 本地服务名 `postgresql-x64-18`，默认 `localhost:5432/demo_db`，库账号 `demo` / `demo_pwd_2026` |

> 备注：本机还装有 JDK 25.0.3（默认 `java`），以及 JetBrains Runtime 21（`JAVA_HOME=D:\Program Files\Android\Android Studio\jbr`）。项目能在 Java 21 上正常运行；如果用默认 `java -version` 看到的是 25，请把 `JAVA_HOME` 切到 JDK 21，或在 Maven 调用前显式 export。

---

## 二、项目架构

```
┌─────────────────────────────────────────────────────────────┐
│                  HTTP Client (Browser / curl / App)         │
└───────────────────────────────┬─────────────────────────────┘
                                │ JWT Bearer Token
                                ▼
┌─────────────────────────────────────────────────────────────┐
│         Spring Security Filter Chain (port 8080)            │
│   • /api/v1/auth/login, /register               → permitAll  │
│   • 管理员接口（users/role/商品/分类/SKU）       → ADMIN     │
│   • 其余业务接口                                → authenticated │
└───────────────────────────────┬─────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────┐
│              Spring MVC (Tomcat 11 内嵌)                     │
│   controller: auth / user / role / menu / userAccount /     │
│   category / product / sku / warehouse / inventory / stock /│
│   supplier / address / purchase / order / cart / shop /     │
│   analytics / system / app                                  │
└───────────────────────────────┬─────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────┐
│              Service 层                                     │
│   auth / user / product-catalog / inventory-query /         │
│   inventory-command / stock-mutation / procurement / sales / │
│   supplier-address / system-data / analytics /              │
│   outbox-event / operation-log                              │
└──────────┬─────────────────────┬───────────────────┬─────────┘
           ▼                     ▼                   ▼
┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐
│ MyBatis-Plus + Page │ │   Redis / Redisson  │ │  PostgreSQL 18      │
│   (PostgreSQL 18)   │ │  缓存 + 分布式锁 +  │ │   业务主存储         │
│                     │ │  Lua 脚本（库存扣减）│ │                     │
└─────────────────────┘ └─────────────────────┘ └─────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────┐
│              RabbitMQ (Topic Exchange + DLX)                 │
│   wms.order.events / wms.stock.alerts / wms.dead-letter     │
└─────────────────────────────────────────────────────────────┘
```

### 包结构

```
src/main/java/com/example/demo
├── DemoApplication.java            # @SpringBootApplication + @EnableScheduling + @MapperScan
├── aspect
│   └── OperationLogAspect.java     # 基于 AOP 的非 GET 操作日志（写 sys_oper_log）
├── common
│   ├── ApiErrorCode.java           # 业务错误码枚举
│   ├── PageResult.java             # 分页统一返回
│   └── Result.java                 # 统一返回体 {code, message, data}
├── config
│   ├── JsonConfig.java             # Jackson ObjectMapper
│   ├── MybatisPlusConfig.java      # 注册 PostgreSQL 方言分页插件
│   ├── RabbitMqConfig.java         # Exchange / Queue / Binding / 死信队列
│   └── SecurityConfig.java         # JWT + RBAC 安全配置
├── controller                      # 见下表「业务模块」
├── dto                             # 请求参数 DTO（@Valid 校验）
├── entity                          # 数据库实体（MyBatis-Plus @TableName）
├── exception
│   ├── BusinessException.java      # 业务异常，携带错误码
│   └── GlobalExceptionHandler.java # @RestControllerAdvice 全局异常
├── mapper                          # MyBatis-Plus Mapper
├── security                        # JWT 过滤器、UserDetails、工具类
├── service / service.impl          # 业务服务（含 Service/ServiceImpl 标准结构）
├── vo                              # 响应视图对象
└── src/main/resources
    ├── application.properties      # 数据源、Redis、RabbitMQ、JWT 等
    ├── schema.sql                  # 启动时自动建表（共 47 张）
    ├── data.sql                    # 启动时种子数据
    ├── scripts/
    │   ├── stock_decrease.lua      # Lua 脚本：Redis 原子扣减库存
    │   └── stock_release.lua       # Lua 脚本：Redis 释放库存锁
    ├── static/css/                 # 预留静态资源目录
    └── templates/                  # 预留模板目录
```

### 业务模块

| 模块 | 主要 Controller | 关键 Service | 说明 |
|---|---|---|---|
| 认证 | `AuthController`、`UserAccountController` | `AuthService`、`UserService` | 登录 / 注册 / 当前用户 / 修改密码 |
| 用户权限 | `UserController`、`RoleController`、`MenuController` | `UserService` | 用户、角色、菜单（RBAC），写操作需 ADMIN |
| 商品目录 | `CategoryController`、`ProductController`、`SkuController` | `ProductCatalogService` | 分类树、商品、SKU，含 CSV 导入 |
| 库存 | `InventoryController` | `InventoryQueryService`、`InventoryCommandService`、`StockMutationService` | 仓库 / 库位 / 库存查询与变动、安全库存调整 |
| 采购 | `PurchaseController`、`SupplierController`、`AddressController` | `ProcurementService`、`SupplierAddressService` | 采购申请 / 订单 / 入库 / 退货 + 供应商 / 地址 |
| 销售 / 订单 | `OrderController`、`CartController`、`ShopController` | `SalesService` | 购物车、订单、门店 |
| 系统 | `SystemDataController`、`AppDataController` | `SystemDataService` | 字典 / 应用初始化数据 |
| 统计 | `AnalyticsController` | `AnalyticsService` | 库存 / 销售 / 采购统计 |
| 横切 | — | `OutboxEventService`、`OperationLogWriter` + AOP | Outbox 事件发布、操作日志 |

---

## 三、基础设施启动（首次运行）

应用依赖 PostgreSQL、Redis、RabbitMQ 三个外部服务。**当前仓库不包含 `compose.yaml`**（README 历史版本里曾引用过），所以下面给的是「本机原生服务」的启动方式。

> 如果你希望走 Docker Compose 启动，请自行放一个 `compose.yaml` 到项目根目录，并按 §「环境变量与默认值」配置密码后 `docker compose up -d`。

### 3.1 启动 PostgreSQL 18

服务名：`postgresql-x64-18`（Windows 服务的默认名称）。需要 **管理员** 权限启动：

```powershell
# 以管理员打开 PowerShell
Start-Service postgresql-x64-18
Get-Service  postgresql-x64-18   # 看到 Running 即可
```

端口监听：`netstat -ano | findstr 5432` 能看到 `0.0.0.0:5432 ... LISTENING`。

### 3.2 启动 Redis

本项目使用本地 Redis（默认端口 6379，密码 `wms2026`）。如果已经常驻运行（任务管理器里能看到进程）则不用管；如未启动，到 Redis 安装目录执行：

```powershell
"C:\Program Files\Redis\redis-server.exe" redis.windows.conf
```

### 3.3 启动 RabbitMQ

服务名：`RabbitMQ`（默认 Windows 服务名）。同样需要 **管理员** 权限：

```powershell
Start-Service RabbitMQ
```

管理后台：http://localhost:15672，默认账号 `wms` / `wms2026`。

### 3.4 数据库初始化

应用启动时根据 `spring.sql.init.mode=always` 自动执行 `schema.sql` + `data.sql` 完成 47 张表的创建与种子数据写入。

如果 `demo` 用户 / `demo_db` 不存在，可手动跑一次 [sql/setup.sql](sql/setup.sql)（默认会建用户 `demo` / 密码 `demo_pwd@2026`，与 `application.properties` 默认值 `demo_pwd_2026` 不同 — 见 §「环境变量与默认值」一节）：

```powershell
$env:PGPASSWORD="<postgres 超级用户密码>"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -h localhost -p 5432 -f sql/setup.sql
```

> ⚠️ `setup.sql` 里的密码是 `demo_pwd@2026`（带 `@`），而 `application.properties` 里的默认值是 `demo_pwd_2026`（带 `_`）。**这两者对不上**。建议二选一：
>
> 1. 通过环境变量覆盖应用连接的密码：`$env:DB_PASSWORD='demo_pwd@2026'`
> 2. 或者编辑 `sql/setup.sql`，把密码改成 `demo_pwd_2026`
>
> 不要两边都留着，会反复踩坑。

---

## 四、启动方式

### 4.1 前置条件

| 软件 | 检查命令 | 期望输出 |
|---|---|---|
| JDK | `java -version` | `java version "21"`（若是 25，请设置 `JAVA_HOME` 指向 21） |
| Maven | `mvn -v` | `Apache Maven 3.9.16 ... Java version: 21.x` |
| PostgreSQL | `psql -U demo -d demo_db -c "select 1"` | 返回 `1` |
| Redis | `redis-cli -a wms2026 ping` | 返回 `PONG` |
| RabbitMQ | http://localhost:15672 | 管理后台可登录 |

### 4.2 启动命令

```bash
cd C:\Users\unicorn\Desktop\springboot-demo
mvn spring-boot:run
```

看到 `Started DemoApplication in 2.xxx seconds` 即成功。

### 4.3 打包运行（生产模式）

```bash
mvn -DskipTests package
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

### 4.4 Maven Wrapper

项目根目录已带 `mvnw.cmd`（Windows）和 `mvnw`（*nix），无需全局装 Maven：

```cmd
mvnw.cmd spring-boot:run
```

---

## 五、环境变量与默认值

所有默认值列在 `application.properties` 里，常用变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/demo_db` | 数据库 JDBC URL |
| `DB_USERNAME` | `demo` | 数据库用户 |
| `DB_PASSWORD` | `demo_pwd_2026` | 数据库密码（与 `setup.sql` 里的 `demo_pwd@2026` 不一致，使用时二选一） |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | `wms2026` | Redis 密码 |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ 地址 |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `wms` / `wms2026` | RabbitMQ 凭据 |
| `STOCK_LOCK_WAIT_MILLIS` | `3000` | Redisson 库存锁等待 |
| `STOCK_LOCK_LEASE_MILLIS` | `10000` | Redisson 库存锁租约 |
| `ORDER_IDEMPOTENCY_SECONDS` | `86400` | 订单幂等键 TTL |
| `OUTBOX_PUBLISH_DELAY_MILLIS` | `1000` | Outbox 定时发布间隔 |
| `OUTBOX_ENABLED` | `true` | 是否启用 Outbox 发布 |

---

## 六、API 一览

接口统一返回 `Result<T>`（`{code, message, data}`）。**所有非认证接口需在 Header 携带** `Authorization: Bearer <jwt>`。

| 模块 | 主要端点前缀 | 说明 |
|---|---|---|
| 认证 | `POST /api/v1/auth/login`、`POST /api/v1/auth/register` | 登录、注册（公开） |
| 当前用户 | `GET /api/v1/user/info`、`PUT /api/v1/user/password` | 当前登录用户 |
| 用户管理 | `GET/POST/PUT/DELETE /api/v1/users/**` | 用户 CRUD（`ADMIN`） |
| 角色 | `GET /api/v1/role/**` | 角色与菜单绑定 |
| 菜单 | `GET /api/v1/menu/tree` | 菜单树（前端动态路由用） |
| 商品分类 | `GET /api/v1/categories/tree`、`POST/PUT/DELETE /api/v1/categories/**` | 分类树与维护（写操作需 `ADMIN`） |
| 商品 | `GET/POST/PUT/DELETE /api/v1/products/**`、`POST /products/import` | 商品 CRUD + CSV 导入 |
| SKU | `GET/POST/PUT/DELETE /api/v1/skus/**` | SKU CRUD |
| 库存 | `GET /api/v1/warehouse/list`、`/location/tree`、`DELETE /location/{id}`、`/stock/**`、`PUT /stock/safety-stock` | 仓库 / 库位 / 库存查询与变动 |
| 采购 | `GET/POST/PUT /api/v1/purchase/**`（含 `force-close`、退货 confirm/reject） | 采购申请 / 订单 / 入库 / 退货 |
| 供应商 | `GET/POST /api/v1/supplier/**` | 供应商 |
| 地址 | `GET/POST/PUT /api/v1/address/**` | 供应商 / 客户地址 |
| 订单 | `GET/POST/PUT /api/v1/order/**` | 订单 |
| 购物车 | `GET/POST/PUT/DELETE /api/v1/cart/**` | 购物车 |
| 统计 | `GET /api/v1/dashboard/stock`、`/report/sales`、`/report/purchase` | 库存 / 销售 / 采购统计 |
| 应用数据 | `GET /api/v1/app/**` | 应用初始化数据（需登录） |
| 系统 | `GET /api/v1/log/operation`、`/dict/type/{type}`、`/config/list` | 日志 / 字典 / 配置 |

> 详细请求 / 响应字段、状态码、鉴权说明见 [购物仓储管理系统API接口文档.md](购物仓储管理系统API接口文档.md)。

### 6.1 默认账号

| 字段 | 值 |
|---|---|
| 用户名 | `admin` |
| 密码 | `admin123` |
| 角色 | `admin` |

### 6.2 示例

```bash
# 登录获取 JWT
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 使用 Token 访问用户列表
TOKEN=eyJhbG...
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/users
```

---

## 七、关键实现细节

### 7.1 JWT 认证

`JwtAuthenticationFilter` 拦截请求，从 `Authorization: Bearer <token>` 中解析用户信息并写入 `SecurityContext`。`SecurityConfig` 配置 RBAC 规则：

- `/api/v1/auth/login`、`/api/v1/auth/register` 公开
- `/api/v1/users/**`、`/api/v1/role/**`、分类 / 商品 / SKU 的**写操作**需 `ADMIN`
- 其余接口登录即可访问

### 7.2 全局统一返回与异常处理

`GlobalExceptionHandler` 处理：

- `BusinessException` → 业务错误码
- `MethodArgumentNotValidException` / `ConstraintViolationException` / `MethodArgumentTypeMismatchException` → 400
- `HttpRequestMethodNotSupportedException` → 405
- `AccessDeniedException` → 403
- `NoResourceFoundException` / `NoHandlerFoundException` → 404
- 其他异常 → 500（并记录日志）

### 7.3 AOP 操作日志

`OperationLogAspect` 拦截所有非 GET 控制层方法，把执行结果落到 `sys_oper_log` 表，含操作用户、模块、耗时、状态、错误信息。

### 7.4 MyBatis-Plus 分页

`MybatisPlusConfig` 注册 PostgreSQL 方言分页插件：

```java
interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
```

### 7.5 Redis / Redisson + Lua

- Redis 用做缓存
- Redisson 用于库存锁、订单幂等、并发控制
- **Lua 脚本（`resources/scripts/`）实现 Redis 原子扣减 / 释放**，避免 "GET → 校验 → DECR" 之间被并发穿透：
  - `stock_decrease.lua`：`DECRBY` + `HSET lock` 一步完成；返回 `{status, remaining, lockedTotal}`
  - `stock_release.lua`：事务回滚或订单取消时调用

### 7.6 RabbitMQ

`RabbitMqConfig` 声明：

- `wms.topic` 业务交换器
- `wms.dlx` 死信交换器
- `wms.order.events`、`wms.stock.alerts`、`wms.dead-letter` 队列

### 7.7 Outbox 模式

`OutboxEventService` 把要发到 MQ 的事件先落到 `outbox_event` 表，再由 `@Scheduled` 定时任务异步发布，保证业务数据与消息的一致性（at-least-once + 消费者幂等）。

### 7.8 Spring Boot 4 适配

- Web starter：`spring-boot-starter-webmvc`
- AOP starter：`spring-boot-starter-aspectj`
- MyBatis-Plus：`mybatis-plus-spring-boot4-starter`
- Testcontainers 2.x：`testcontainers-*` 前缀的 artifact

