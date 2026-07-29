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
