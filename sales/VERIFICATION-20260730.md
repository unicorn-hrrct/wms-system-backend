# 2026-07-30 Customer / Address / Aftersale / Refund 基线复验

> 负责人：陈恩生（24443102401）
> 验证完成时间：2026-07-30 09:06（Asia/Shanghai）
> 团队分类基线：`upstream/sales@c9ccce2`
> 正式契约：`backend/购物仓储管理系统API接口文档.md` v1.2
> 记录性质：当前 v1.2 分类覆盖包的可复现验证证据

## 1. 验证对象

2026-07-30 08:46 重新拉取远端后，`upstream/sales` 已由组长通过 PR #15 合并至
`c9ccce2`，包含 2026-07-29 新增的 PostgreSQL 幂等事务测试及对应集成说明。

根目录 `sales/` 不是独立 Spring Boot 工程。本次仍使用隔离验证工作树：

- 团队后端基底为 `5dc682e`；
- 将当前分类包的 29 个 `src/main`、`src/test` 文件按相对路径覆盖到隔离副本；
- 隔离副本中的 29 个文件与 `upstream/sales@c9ccce2` 经换行标准化后逐文件一致；
- 测试读取的 `sales/sql/V001__customer_address_refund.sql` 也与当前分类包一致；
- 隔离副本只用于验证，没有把其 `backend/` 差异提交或推送。

本次验证对象仍是正式 v1.2 已冻结的 Customer、Address、Aftersale/Refund 分类实现。
组长 2026-07-29 确认但尚待正式文档同步的换货完成、两周窗口、优惠订单退款上限及
禁止库存回补四项规则没有落入运行时代码，也不在本次通过范围内。

## 2. 环境与命令

验证环境：

- Java 21.0.11；Surefire 报告记录
  `java.home=D:\Java\jdk-21.0.11+10`；
- Apache Maven 3.9.16；
- PostgreSQL 18.4，一次性新建的本地测试集群
  `jdbc:postgresql://127.0.0.1:55432/postgres`；
- Redis 兼容实例 `127.0.0.1:56379`；
- `127.0.0.1:5672` 没有 RabbitMQ 监听进程。

其中 Java 版本和连接参数来自本次 Surefire XML；Maven 与 PostgreSQL 版本由
`mvn -version`、`postgres --version` 独立复核，5672 监听状态于 09:00 用本机 TCP
监听查询复核。

先执行 JDK 21 干净编译：

```text
mvn clean test -DskipTests
```

Maven 重新编译 171 个主源码文件和 14 个测试源码文件，结果为 `BUILD SUCCESS`。编译
日志包含既有的 deprecated/unchecked 提示，没有编译错误；该步骤跳过了测试执行。

分类测试使用以下 8 个测试类。命令中的 PostgreSQL 必须是允许清空的临时目标，密码
只通过本机参数提供，不写入仓库：

```text
mvn \
  -Dtest=SalesWriteIdempotencyHeaderTest,AftersaleRefundRequestValidationTest,SalesOverlayMigrationTest,AftersaleServiceContractTest,CustomerAddressServiceContractTest,RefundServiceContractTest,CustomerAddressAftersaleRefundHttpIntegrationTest,SalesIdempotencyPostgresIntegrationTest \
  -Dsales.postgres.integration=true \
  -Dsales.postgres.allow-destructive-target=true \
  -Dsales.postgres.target-url=jdbc:postgresql://127.0.0.1:55432/postgres \
  -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55432/postgres \
  -Dspring.datasource.username=postgres \
  -Dspring.datasource.password= \
  -Dspring.data.redis.host=127.0.0.1 \
  -Dspring.data.redis.port=56379 \
  -Dspring.data.redis.password=<本地测试密码> \
  test
```

`SalesOverlayMigrationTest` 会重建目标库中的团队表，
`SalesIdempotencyPostgresIntegrationTest` 也会执行 V001 并创建、删除探针表。不得
把生产库、共享库或保存有项目数据的数据库放到上述目标参数。

## 3. 结果

干净编译后，Surefire 于 2026-07-30 09:06 重新生成 8 份 XML 报告：

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `CustomerAddressAftersaleRefundHttpIntegrationTest` | 2 | 0 | 0 | 0 |
| `SalesWriteIdempotencyHeaderTest` | 1 | 0 | 0 | 0 |
| `AftersaleRefundRequestValidationTest` | 3 | 0 | 0 | 0 |
| `SalesOverlayMigrationTest` | 1 | 0 | 0 | 0 |
| `AftersaleServiceContractTest` | 5 | 0 | 0 | 0 |
| `CustomerAddressServiceContractTest` | 4 | 0 | 0 | 0 |
| `RefundServiceContractTest` | 7 | 0 | 0 | 0 |
| `SalesIdempotencyPostgresIntegrationTest` | 5 | 0 | 0 | 0 |
| **合计** | **28** | **0** | **0** | **0** |

本次复验证明：

- Customer/Address 的真实客户映射、资源归属、默认地址与写请求重放仍通过；
- Aftersale/Refund 的申请、审核、完成、取消、额度和 Outbox 边界仍通过；
- V001 可在新建 PostgreSQL 目标上重复执行；
- 并发首次请求、失败回滚重试、过期 key、新旧载荷冲突及 CUSTOMER/USER 主体隔离
  5 项真实 PostgreSQL 幂等事务测试均通过。

Spring 测试上下文启动时尝试连接 `localhost:5672`，日志明确记录
`Connection refused`。该连接失败没有使上述 28 项测试失败，但也说明本次结果不能
用于证明 RabbitMQ 可用。

## 4. 结论边界

- `28/28` 只覆盖上表 8 个分类测试类，不能写成全项目所有测试通过。
- 当前报告证明 Outbox 事务落库边界，不证明 RabbitMQ 实际发布、重试、死信或 IVP
  消费与库存结果。
- 本次没有验证换货完成接口、确认收货起两周、优惠订单 50% 退款上限及
  `restock` 只能为 `false`；这些仍按 `AFTERSALE-RULES-READINESS.md` 的开工门禁
  等待正式接口和 Schema 依赖同步。
- 2026-07-29 的项目级非破坏性 `45/45` 是既有独立证据；本地没有保存足够信息还原
  当时精确的 45 项类清单，因此本次没有凭猜测重复宣称该结果。
