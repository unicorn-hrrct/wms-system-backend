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
