# Sales 分类交付

> 负责人：陈恩生（24443102401）
> 负责范围：Customer、Address、Refund；Aftersale 仅承接 Refund 前置协作边界
> 团队基线：`upstream/sales` 的 `c9ccce2`
> 正式契约：`backend/购物仓储管理系统API接口文档.md` v1.2

## 1. 目录用途

本目录用于按组长 2026-07-28 确认的新规则分类交付 Sales 代码。后续提交只新增或修改
根目录 `sales/`，不直接改动团队 `backend/`。

本目录是面向集成审查的源码覆盖包，不是第二套可独立启动的 Spring Boot 工程。源码
保留团队现有的 `com.example.demo` 包名和相对路径，组长审查通过后再按清单集成到
统一后端。

## 2. 交付范围

- Customer：当前客户档案、真实客户身份映射、更新幂等和带时区响应边界。
- Address：客户归属、默认地址事务、订单快照限制和四个写入口幂等。
- Refund：真实客户归属、额度/状态/金额约束、列表分页、带时区响应、商家写入口幂等
  和 `sales.refund.completed` Outbox 边界。
- Aftersale：仅适配正式 v1.2 已冻结的申请、商家列表、详情和审核流程；不会自行扩展
  未冻结的换货完成流程。

## 3. 权威顺序

1. 团队仓库正式 API 文档 v1.2。
2. 团队 `upstream/sales` 当前代码、Schema 和可复现测试。
3. 本目录中的分类源码、增量 SQL 和测试。
4. 旧模块说明、调用时序及标为草案/建议的个人文档。

旧个人 `origin/sales` 仅用于提取已经验证的 Customer、Address、Refund 约束，不作为
本次 PR 的合并基线，也不会用它覆盖组长在 `5dc682e` 新增的售后接口。

## 4. 交付结构

```text
sales/
├── README.md
├── AFTERSALE-RULES-READINESS.md
├── VERIFICATION-20260730.md
├── INTEGRATION.md
├── MANIFEST.md
├── scripts/
│   ├── Invoke-SalesClassificationVerification.ps1
│   └── Test-SalesPrScope.ps1
├── sql/
│   └── V001__customer_address_refund.sql
└── src/
    ├── main/java/com/example/demo/...
    └── test/java/com/example/demo/...
```

实际文件及其对应团队路径以 `MANIFEST.md` 为准。`sql/` 只提供本负责范围的增量迁移，
不复制或替换团队完整 `schema.sql`。

## 5. PR 路径门禁

代码 PR 的基线固定为团队 `upstream/sales`。提交前必须满足：

```text
git diff --name-only upstream/sales...HEAD
```

输出中的每个路径都必须以 `sales/` 开头；出现 `backend/`、`reports/` 或其他根目录
路径即停止提交。日报继续单独提交到 `master`，不进入本分类代码 PR。

## 6. 当前状态

截至 2026-07-30，Customer、Address、Aftersale/Refund 分类源码、增量 SQL 和测试均
已放入本目录。验证结果和集成限制记录在 `INTEGRATION.md`，文件级交付状态记录在
`MANIFEST.md`；2026-07-30 的独立基线复验记录见 `VERIFICATION-20260730.md`。

“已交付”表示分类覆盖包已实现并在临时覆盖环境验证，不表示已经写入团队
`backend/`，也不表示 RabbitMQ/IVP 消费端已联调完成。是否合入统一后端由组长在
代码 PR 中审查决定。

组长 2026-07-29 确认但尚待正式文档同步的换货完成、两周窗口、优惠订单退款上限和
禁止库存回补规则，其当前证据、阻塞点、文件级改造范围和验收矩阵见
`AFTERSALE-RULES-READINESS.md`。该文件是实施准备，不表示四项规则已经落地。

## 7. PR 范围门禁

分别重新拉取 `origin` 与 `upstream`、提交并推送当前代码分支后，执行：

```powershell
powershell -ExecutionPolicy Bypass -File sales/scripts/Test-SalesPrScope.ps1
```

脚本只读检查以下条件，任一不满足即返回失败：

- 工作树无已跟踪或未跟踪差异；
- 当前分支跟踪 `origin/*`，且本地 HEAD 已完整推送；
- 当前 HEAD 包含最新 `upstream/sales`，不存在落后提交；
- 两点和三点 Git 差异路径集合一致，且全部严格位于根目录 `sales/`；
- `git diff --check` 无空白错误。

脚本不会执行 `fetch`、提交、推送或创建 PR；远端拉取仍须在运行门禁前显式完成。

## 8. 分类验证门禁

`Invoke-SalesClassificationVerification.ps1` 把当前 8 类 28 项定向验证固化为可复现
门禁，并可通过 `-IncludeProjectRegression` 追加项目级回归。它只接受独立的
`.tmp-sales-integration-*` / `.tmp-sales-verification-*` 工作树，拒绝团队真实
`backend/`，并逐文件核对 21 个主源码、8 个测试、V001 迁移及已审查的
`BusinessApiControllerTests` 编译兼容改动。

脚本仅允许本机非默认端口和专用 `sales_verify_*` / `sales_validation_*` PostgreSQL
库；该库会被测试清空，调用者必须逐字传入 `DESTROY:<JDBC URL>`。先使用 `-DryRun`
完成路径、Git、工具链和危险目标检查；干跑不会连接端口或执行 Maven。默认正式运行
会先做 Java 21 干净编译，再运行固定 8 类，并精确核对 8 份 Surefire XML 是否为
28 tests / 0 failures / 0 errors / 0 skipped。

启用 `-IncludeProjectRegression` 后，脚本还会先用固定迁移测试重置专用库并核对
1/1，再关闭 Spring SQL 自动初始化，运行固定 10 类项目回归并精确核对 45/45。
每个阶段都从 `clean` 后的独立 Surefire 报告集验收，避免 Maven 选择器拼写错误被
静默漏跑，也避免读取上一轮报告。项目回归仍会写入专用测试库，不是只读检查。

分类验证与项目回归有 5 类、20 项重复覆盖；去重后合计为 13 类、53 项不同测试，
不能把阶段执行次数相加宣称为 73 项不同测试。单独的 1/1 重置阶段用于准备数据库，
也不增加功能覆盖数。

密码参数使用 `SecureString`，脚本只通过子进程环境传递并对 Maven 输出脱敏。示例：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
$pgUrl = 'jdbc:postgresql://127.0.0.1:55432/sales_verify_20260730'
$pgPassword = Read-Host 'PostgreSQL password' -AsSecureString
$redisPassword = Read-Host 'Redis password' -AsSecureString
& .\sales\scripts\Invoke-SalesClassificationVerification.ps1 `
  -BackendDirectory '..\.tmp-sales-integration-20260729\backend' `
  -PostgresUrl $pgUrl `
  -PostgresUser postgres `
  -PostgresPassword $pgPassword `
  -RedisHost 127.0.0.1 `
  -RedisPort 56379 `
  -RedisPassword $redisPassword `
  -JdkHome 'D:\Java\jdk-21.0.11+10' `
  -MavenCommand '<absolute-path-to-mvn.cmd>' `
  -DestructiveTargetConfirmation "DESTROY:$pgUrl" `
  -IncludeProjectRegression `
  -DryRun
```

删除 `-DryRun` 才会执行验证；如只需默认 8 类 28 项分类门禁，同时删除
`-IncludeProjectRegression`。脚本只证明分类源码、真实 PostgreSQL 事务边界和
Outbox 落库；测试过程即使出现 RabbitMQ 连接拒绝日志，也不构成消息链路验证。
脚本不会验证 RabbitMQ 发布、重试、死信及 IVP 消费。
