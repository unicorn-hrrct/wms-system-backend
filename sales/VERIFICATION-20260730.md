# 2026-07-30 Customer / Address / Aftersale / Refund 基线复验

> 负责人：陈恩生（24443102401）
> 验证完成时间：2026-07-30 15:49（Asia/Shanghai）
> 团队分类基线：`upstream/sales@c9ccce2`
> 正式契约：`backend/购物仓储管理系统API接口文档.md` v1.2
> 记录性质：当前 v1.2 分类覆盖包的可复现验证证据

## 1. 验证对象

2026-07-30 08:46 重新拉取远端后，`upstream/sales` 已由组长通过 PR #15 合并至
`c9ccce2`，包含 2026-07-29 新增的 PostgreSQL 幂等事务测试及对应集成说明。

根目录 `sales/` 不是独立 Spring Boot 工程。本次仍使用隔离验证工作树：

- 团队后端基底为 `5dc682e`；
- 将当前分类包的 29 个 `src/main`、`src/test` 文件按相对路径覆盖到隔离副本；
- 隔离副本中的 29 个文件与当前分支 `0166966` 的分类源码经换行标准化后逐文件一致；
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
- PostgreSQL 18.4，本地专用可丢弃数据库
  `jdbc:postgresql://127.0.0.1:55432/sales_verify_20260730`；
- Redis 兼容实例 `127.0.0.1:56379`；
- `127.0.0.1:5672` 没有 RabbitMQ 监听进程。

分类验证通过 `sales/scripts/Invoke-SalesClassificationVerification.ps1` 执行。脚本
先核对独立工作树、固定 Git HEAD、21 个主源码、8 个测试源码、V001 和已审查的编译
兼容文件，再检查 Java/Maven、回环地址、非默认端口、专用库名和逐字破坏性确认。先
运行 `-IncludeProjectRegression -DryRun`；干跑不会连接端口或执行 Maven。删除
`-DryRun` 后，本次正式运行依次：

1. 执行 `mvn clean test -DskipTests`；
2. 执行固定 8 类，并安全解析且精确核对 8 份新生成的 Surefire XML；
3. `clean` 后执行固定迁移测试，恢复专用库并精确核对 1/1；
4. 再次 `clean`，执行固定 10 类项目回归并精确核对 48/48。

密码以 `SecureString` 传入，只通过子进程环境使用，Maven 输出会脱敏并在结束后恢复
原环境。本次干净编译重新编译 171 个主源码文件和 13 个测试源码文件，结果为
`BUILD SUCCESS`；既有 deprecated/unchecked 提示不属于编译错误。

项目级非重建型回归在重新执行 `SalesOverlayMigrationTest` 恢复
`schema.sql + data.sql + V001` 后运行。为避免测试类无序重建同一数据库，明确排除
`CustomerAddressAftersaleRefundHttpIntegrationTest`、
`SalesOverlayMigrationTest` 和 `SalesIdempotencyPostgresIntegrationTest`，并设置
`spring.sql.init.mode=never`；这三类重建/清理数据库的测试已包含在前一阶段的分类
验证中。其余接口测试仍会在专用库写入测试数据，“非重建型”不表示只读。

专用数据库会被迁移测试清空和重建；不得把生产库、共享库或保存有项目数据的数据库
放入上述流程。

## 3. 结果

### 3.1 分类验证

修复 Windows PowerShell XML 类型适配导致的根标签误判，并补齐
`pom.xml/schema.sql/data.sql` 重解析链检查后，基础门禁于 10:37 从头运行成功。
下午补充 3 项地址边界测试后，最终门禁于 15:47 从干净编译开始完整运行；分类阶段
重新生成并由脚本逐份校验以下 8 份 XML：

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `CustomerAddressAftersaleRefundHttpIntegrationTest` | 2 | 0 | 0 | 0 |
| `SalesWriteIdempotencyHeaderTest` | 1 | 0 | 0 | 0 |
| `AftersaleRefundRequestValidationTest` | 3 | 0 | 0 | 0 |
| `SalesOverlayMigrationTest` | 1 | 0 | 0 | 0 |
| `AftersaleServiceContractTest` | 5 | 0 | 0 | 0 |
| `CustomerAddressServiceContractTest` | 7 | 0 | 0 | 0 |
| `RefundServiceContractTest` | 7 | 0 | 0 | 0 |
| `SalesIdempotencyPostgresIntegrationTest` | 5 | 0 | 0 | 0 |
| **合计** | **31** | **0** | **0** | **0** |

本次复验证明：

- Customer/Address 的真实客户映射、资源归属、默认地址与写请求重放仍通过；
- 地址写操作越权、最后一条地址删除保护，以及订单已引用地址仅修改收货人字段通过；
- Aftersale/Refund 的申请、审核、完成、取消、额度和 Outbox 边界仍通过；
- V001 可在新建 PostgreSQL 目标上重复执行；
- 并发首次请求、失败回滚重试、过期 key、新旧载荷冲突及 CUSTOMER/USER 主体隔离
  5 项真实 PostgreSQL 幂等事务测试均通过。

脚本定稿前出现过两类未采信结果：Redis 认证参数不匹配导致 Spring 上下文错误；以及
原始测试已为 28/28、但 XML 根标签读取错误使脚本非零退出。两者修复且新增地址边界
测试后均从头复跑；上表只记录最终脚本退出码为 0 的结果。独立扫描 XML 未发现实际
数据库或 Redis 密码。

### 3.2 项目级非重建型回归

增强脚本在分类阶段通过后自动 `clean`，于 15:48 用迁移测试恢复专用库并核对
1/1；随后再次 `clean` 并运行以下固定 10 类，15:49 生成 10 份 XML：

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `AppDataControllerTests` | 3 | 0 | 0 | 0 |
| `AuthControllerTests` | 7 | 0 | 0 | 0 |
| `BusinessApiControllerTests` | 8 | 0 | 0 | 0 |
| `SalesWriteIdempotencyHeaderTest` | 1 | 0 | 0 | 0 |
| `UserControllerTests` | 6 | 0 | 0 | 0 |
| `DemoApplicationTests` | 1 | 0 | 0 | 0 |
| `AftersaleRefundRequestValidationTest` | 3 | 0 | 0 | 0 |
| `AftersaleServiceContractTest` | 5 | 0 | 0 | 0 |
| `CustomerAddressServiceContractTest` | 7 | 0 | 0 | 0 |
| `RefundServiceContractTest` | 7 | 0 | 0 | 0 |
| **合计** | **48** | **0** | **0** | **0** |

首次整理选择器时把 `AppDataControllerTests` 误写成不存在的 `AppDataTests`，Maven
只运行了 42 项；补正后若直接复用已被前一轮修改的数据库，会出现库存基数和商家申请
状态两项污染失败。该结果未被计为代码回归。执行迁移测试恢复干净数据后，最终一次
正确选择器为上表 48/48。

脚本对 10 份报告的精确文件集、suite 名、测试数、失败、错误、跳过、testcase 节点和
报告文件生成时间均验收通过。脚本退出后又使用独立只读命令解析同一批 XML，结果仍为
10 份、48 tests / 0 failures / 0 errors / 0 skipped，且类名集合与固定清单一致。

Spring 测试上下文启动时尝试连接 `localhost:5672`，日志明确记录
`Connection refused`。该连接失败没有使最终两阶段测试失败，但也说明本次结果不能
用于证明 RabbitMQ 可用。

## 4. 结论边界

- 分类 31 项与非重建型 48 项有 23 项重叠；两阶段合计覆盖当前 13 个测试类中的
  56 个不同测试方法，但不是一次无序执行的“56 项全量命令”。
- 当前报告证明 Outbox 事务落库边界，不证明 RabbitMQ 实际发布、重试、死信或 IVP
  消费与库存结果。
- 本次没有验证换货完成接口、确认收货起两周、优惠订单 50% 退款上限及
  `restock` 只能为 `false`；这些仍按 `AFTERSALE-RULES-READINESS.md` 的开工门禁
  等待正式接口和 Schema 依赖同步。
