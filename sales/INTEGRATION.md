# Sales 分类覆盖包集成说明

## 1. 集成原则

- 目标后端基线必须包含组长提交 `5dc682e` 的正式售后接口和团队 Schema。
- 只按 `MANIFEST.md` 的“分类路径 → 团队路径”映射审查文件，不整体复制目录。
- 先审查增量 SQL，再集成源码，最后运行清单中的定向测试和完整回归。
- 本目录不会修改、删除或重命名团队 `backend/` 中的任何文件。

## 2. 必须保留的业务边界

### 身份

认证 Token 提供用户名，系统重新加载登录主体后取得账号 `userId`；Customer、
Address、用户侧 Aftersale/Refund 必须再通过 `crm_customer.user_id` 映射真实
`customerId`。禁止把账号 ID 直接当作客户主键。

### 幂等

Customer、Address、Aftersale、Refund 的写入口使用 UUID `Idempotency-Key`。分类
实现按操作、主体类型、主体 ID 和 key 隔离，并保存请求摘要与首次响应；相同 key
绑定不同载荷返回 `50015`。

### 归属与额度

申请售后必须同时校验登录账号、真实客户、订单和订单项归属。订单项在计算累计申请
金额/数量时需加锁，待审核申请也必须占用额度，避免并发超额。

### 退款完成

Sales 不直接修改 IVP 库存。退款完成只写 `sales.refund.completed` Outbox；只有
`type=2` 退货退款允许 `restock=true`。RabbitMQ/IVP 实际消费必须单独联调，不能由
Outbox 落库测试替代。

## 3. 售后契约边界

正式 v1.2 已冻结：

```text
POST /api/v1/order/aftersale
GET  /api/v1/web/aftersale
GET  /api/v1/web/aftersale/{aftersaleId}
PUT  /api/v1/web/aftersale/{aftersaleId}/audit
```

申请只创建 `ord_aftersale(PENDING)`；商家审核通过后，售后单变为 `APPROVED` 并进入
退款管理。现有团队代码对 `type=3` 换货审核通过后不创建退款单，但正式文档尚未定义
后续换货完成接口。本分类交付保留该隔离，不自行创造换货状态或复用退款完成事件。

## 4. 集成验证

每个阶段至少执行以下门禁：

1. 所有 Git 差异只位于根目录 `sales/`。
2. 增量 SQL 可在团队 Schema 初始化后重复审查，且不复制完整数据库定义。
3. Customer、Address、Refund/Aftersale 定向测试无 failure/error。
4. 完整回归必须如实记录跨模块失败，不能把 Payment 或消息环境问题归为本负责范围通过。
5. 未运行 RabbitMQ 与 IVP 消费时，只能声明 Outbox 数据已验证。

## 5. 增量 SQL 执行方式

`sql/V001__customer_address_refund.sql` 的目标基线是 `upstream/sales@5dc682e`。脚本
包含 PostgreSQL `DO $$ ... $$` 块，应通过 PostgreSQL JDBC `Statement`、`psql` 或
能够原样执行 PostgreSQL 脚本的迁移工具运行。不要把它直接配置为 Spring
`spring.sql.init.schema-locations`；Spring 的基础脚本分割器不能可靠解析该语法。

执行顺序：

1. 备份数据库，并先在临时库或测试库演练。
2. 初始化或升级到团队 `schema.sql` 对应基线。
3. 清理或回填不满足客户、地址、订单、订单项、售后和退款复合归属的历史数据。
4. 执行 V001；脚本任一检查或约束失败时会整体回滚，不得绕过约束继续上线。
5. 对同一已迁移测试库再次执行 V001，确认重复执行成功。

该迁移会验证历史行并取得 DDL 锁。生产大表需要单独评估锁等待时间；现有同名幂等表
若来自其他版本，也必须先比较列和约束，不能仅凭 `IF NOT EXISTS` 认定结构一致。

## 6. 2026-07-29 可复现验证

验证环境：

- Java 21.0.11；
- PostgreSQL 18.4；
- Redis 兼容实例；
- 团队基线 `5dc682e`，把本目录 `src/main/java`、`src/test/java` 按相对路径临时覆盖
  到一份独立工作树的 `backend/src`，主仓库 `backend/` 未修改。

结果：

- 分类契约测试：28/28，通过；其中 PostgreSQL 幂等事务边界 5/5、迁移重复执行
  1/1、真实 HTTP 回归 2/2，DTO/Controller/Service 契约测试 20/20。
- 项目级非破坏性回归（不重复执行上面的迁移和 HTTP 两类破坏性测试）：45/45，通过；
  运行前已加载团队 Schema、数据和 V001，并关闭 Spring 重复初始化，
  `mvn clean test` 重新编译 171 个主源码文件。
- 团队原 `BusinessApiControllerTests` 在临时验证副本中仅做契约适配：Customer 更新
  不再携带 `phone`，Customer/Aftersale 写操作使用标准 UUID `Idempotency-Key`。
  这些适配没有提交到团队 `backend/`。
- RabbitMQ `localhost:5672` 未运行，日志存在连接拒绝；Outbox 事务落库与事件载荷已
  验证，真实消息发布和 IVP 消费未验证。

分类测试命令需要显式启用真实 PostgreSQL 测试，把
`sales.postgres.target-url` 指向可清空的测试库，并明确确认允许修改该目标：

```text
mvn -Dtest=SalesWriteIdempotencyHeaderTest,AftersaleRefundRequestValidationTest,SalesOverlayMigrationTest,AftersaleServiceContractTest,CustomerAddressServiceContractTest,RefundServiceContractTest,CustomerAddressAftersaleRefundHttpIntegrationTest,SalesIdempotencyPostgresIntegrationTest -Dsales.postgres.integration=true -Dsales.postgres.allow-destructive-target=true -Dsales.postgres.target-url=<disposable-jdbc-url> test
```

`SalesOverlayMigrationTest` 会清空目标库中的团队表，幂等事务测试也会执行 V001
并创建、删除专用探针表；禁止把生产或共享数据库作为 `sales.postgres.target-url`。

## 7. 尚未冻结或尚未联调的边界

- v1.2 没有定义 `type=3` 换货审核后的完成/发货接口，本交付不会虚构流程。
- v1.2 定义了 `50017`，但没有冻结可处理时间窗口的参数来源和起算点，本交付未自行
  增加时间窗口。
- v1.2 要求按订单项“实付金额”控制可退额度；当前团队 Schema 没有订单项级折扣或
  实付字段。本实现以 `ord_order_item.subtotal` 为上限，整单优惠如何分摊必须由组长
  冻结后再单独修改。
- RabbitMQ 发布、失败重试、死信和 IVP `restock` 消费需要跨模块联调，不能由本交付
  的 Outbox 测试替代。
