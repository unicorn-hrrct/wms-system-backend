# 【反馈建议】Sales 销售模块业务漏洞、接口缺口与跨模块契约优化提案

> **反馈人**：陈恩生（Sales 销售模块负责人）
> **负责范围**：customer / address / order / payment / refund
> **针对文档**：`购物仓储管理系统API接口文档.md`（2026-07-23 更新版）
> **相关约定**：下单同步预占库存；取消、支付成功、退款完成通过 RabbitMQ 通知 IVP
> **日期**：2026-07-23

---

## 一、概述

在按照最新版接口文档推演 Sales 全链路后，发现当前规格仍存在库存重复预占、订单与库存标识循环依赖、多 SKU 部分预占、支付与取消并发、退款闭环缺失、越权访问以及消息可靠性等问题。

本文只讨论能够从当前文档直接确认的业务漏洞和接口缺口。实现时应遵循以下模块边界：

1. Sales 不直接更新 `sto_stock`、`sto_stock_log`，也不直接操作 IVP 的 Redis 库存 Key。
2. 创建订单时，由 Sales **同步调用 IVP 预占接口**；预占失败则不创建有效订单。
3. 取消、支付成功、退款完成，由 Sales 在本地业务事务中写入 Outbox，事务提交后再投递 MQ。
4. IVP 消费 MQ 后完成释放预占、预占转实扣及退款回补，并保证消费幂等。

### 风险摘要

| # | 等级 | 问题摘要 |
|---|---|---|
| 1 | 高危 | 同步预占与 `order.created` 消费预占并存，可能重复锁库 |
| 2 | 高危 | 预占接口依赖尚未生成的 `orderId`，多 SKU 又无整单原子与补偿 |
| 3 | 高危 | Redis Lua 与 PostgreSQL 库存账分裂，没有持久预占记录 |
| 4 | 高危 | 15 分钟库存锁短于30分钟订单期限，过期还不会自动恢复 Redis 计数 |
| 5 | 高危 | HTTP 与 MQ 幂等、Outbox、重投和服务身份认证未定义 |
| 6 | 高危 | Mock 支付缺少回调验签、状态查询及支付/取消并发裁决 |
| 7 | 高危 | 退款只有申请入口，没有审核、完成、部分退款和回补条件 |
| 8 | 高危 | 订单、地址、发货和消息接口缺少角色/数据归属约束 |
| 9 | 中危 | 订单状态示例与枚举冲突，合法转换没有冻结 |
| 10 | 中危 | customer 缺失，address 仅有列表和新增 |
| 11 | 高危 | MQ 载荷、消费幂等表、DLQ 和消费状态定义不完整 |
| 12 | 高危 | 数据库附录缺少客户、地址、支付、退款等核心表 |
| 13 | 中危 | 金额、快照、时间、版本和技术栈约定不一致 |

---

## 二、核心业务漏洞与风险诊断

### 1.【高危】同步预占与 `order.created` 消费预占并存，可能重复锁定库存

#### 漏洞场景

- 10.1.1 定义下单时同步调用 `POST /stock/lock/decrease` 预占库存。
- 10.2.1 又定义 `order.created` 由库存服务消费并“预扣减”。
- 10.2.3 的 `CANCEL_PENDING` 方案同样建立在 `order.created` 尚未完成预扣的前提上。

若两套逻辑同时实现，同一订单会在同步调用时预占一次，又在消费 `order.created` 时再次预占，产生重复锁库或库存不足误判。

#### 建议解决方案

以组长最新确认的调用方式为准：

| 事件/操作 | IVP 库存动作 |
|---|---|
| 创建订单同步接口 | 执行唯一一次库存预占 |
| `order.created` | 仅用于通知、审计等非库存动作，不再预占 |
| `order.paid` | 预占转正式扣减 |
| `order.cancelled` | 释放预占 |
| `refund.completed` | 回补可售库存 |

同时删除或重写 10.2.3 中依赖 `ORDER_CREATED` 执行预占的 `CANCEL_PENDING` 流程。IVP 应直接按“订单预占记录状态”处理支付和取消事件。

---

### 2.【高危】预占接口要求 `orderId`，但订单尚未创建，存在循环依赖

#### 漏洞场景

7.3 中 `POST /order/create` 成功后才返回 `orderId`；但 10.1.1 的库存预占接口要求调用方必须传入 `orderId`。

- 若先预占：此时还没有数据库生成的 `orderId`。
- 若先写订单：库存不足时会留下无效订单，还违背“库存不足立即下单失败”的约定。

当前预占接口一次只接收一个 `skuId`。一个订单有多个 SKU 时，前几个 SKU 可能预占成功，后一个失败，但文档没有定义整单原子性和已成功项的补偿释放。

#### 建议解决方案

1. Sales 在下单开始时先生成全局唯一 `orderNo` 或 `reservationRequestId`，不要依赖入库后才产生的自增 ID。
2. 将单 SKU 接口补充为整单批量预占接口，例如：

```http
POST /stock/reservations
Idempotency-Key: <uuid>
```

```json
{
  "reservationRequestId": "RSV-20260723-0001",
  "orderNo": "ORD-20260723-0001",
  "expireAt": "2026-07-23T16:30:00+08:00",
  "items": [
    { "skuId": 2001, "quantity": 1 },
    { "skuId": 2002, "quantity": 2 }
  ]
}
```

3. IVP 必须保证同一请求内所有明细“全部成功或全部失败”，并返回 `reservationId` 及实际分仓/库位分配明细。
4. 若预占成功但 Sales 订单事务失败，Sales 必须按同一 `reservationRequestId` 调用幂等补偿释放接口；双方还需有超时对账任务兜底。

---

### 3.【高危】Redis 预占与 PostgreSQL 库存账没有形成一致事务

#### 漏洞场景

10.1.2 的 Lua 示例只对 Redis 执行 `DECRBY/HSET/EXPIRE`，没有同步更新 PostgreSQL 中的 `sto_stock.locked_quantity`，也没有持久化订单预占记录。

但文档其他章节的实时库存查询、可用库存计算以及支付/取消后的库存动作均以 PostgreSQL 中的：

```text
available = quantity - locked_quantity
```

为依据。因此可能出现：

- Redis 已经预占，但数据库仍显示可售；
- Redis 重启或 Key 丢失后无法恢复订单预占；
- MQ 确认/释放时数据库中找不到对应锁定数量；
- 响应返回了 `lockId`，但 Lua 没有保存可由该 `lockId` 反查 SKU、数量和分配行的完整映射；
- 接口超时重试再次执行 `DECRBY`，造成重复预占。

#### 建议解决方案

1. PostgreSQL 作为库存事实来源；Redis 只用于快速失败、限流或可重建缓存。
2. IVP 增加持久化预占记录，例如 `sto_stock_reservation`，保存：

```text
reservation_id / request_id / order_no / status / expires_at
sku_id / warehouse_id / location_id / quantity / payload_hash
```

3. 在同一数据库事务内完成：
   - 条件更新 `locked_quantity += n`；
   - 插入预占记录；
   - 写入库存流水。
4. Redis 成功而数据库事务失败时，必须补偿 Redis；数据库成功而 Redis 失败时，数据库记录仍能重建缓存。
5. `quantity` 必须大于 0 且有合理上限；未知 SKU、非法 TTL 和未知 action 必须拒绝。Controller 校验和 Lua/数据库条件校验都要保留，防止负数 `DECRBY` 反向增加库存。

---

### 4.【高危】库存锁 15 分钟失效，但订单 30 分钟后才过期

#### 漏洞场景

- 10.1.1 中 `expireSeconds` 默认 `900` 秒，即 15 分钟。
- 7.3 返回的订单过期时间及系统参数均采用 30 分钟。

用户在第 16～30 分钟支付时，订单仍然有效，但库存锁可能已消失，导致支付成功后 `confirm` 找不到预占；也可能使商品在订单仍可支付期间重新变成可售，造成超卖。

此外，当前 Lua 的 `EXPIRE` 只会删除锁元数据，并不会自动 `INCRBY` 恢复已经从 Redis 扣掉的库存计数，可能造成 Redis 库存永久减少。

#### 建议解决方案

1. 库存预占到期时间必须由订单的绝对 `expireAt` 决定，不能由客户端任意传入短 TTL。
2. Redis TTL 应不少于“订单有效期 + 消息与网络缓冲时间”。
3. 数据库预占记录作为最终依据，Redis 仅作为并发控制/缓存；Redis Key 提前丢失时可根据预占记录恢复。
4. 超时取消任务与库存过期释放任务必须使用同一个订单过期时间配置。

---

### 5.【高危】下单、支付、取消、退款缺少完整幂等与可靠消息事务

#### 漏洞场景

- 7.3 下单接口未规定 `Idempotency-Key`，用户重复点击可能生成多张订单并多次预占。
- 支付请求或支付回调重复到达时，可能重复更新状态或重复发送 `order.paid`。
- 取消和退款完成重复提交时，可能重复释放或重复回补库存。
- 10.2.1 将 `POST /order/message/send` 暴露为普通 REST 接口，既可能被客户端伪造事件，也无法保证业务数据库提交与 MQ 投递的一致性。

#### 建议解决方案

1. `POST /order/create` 必须要求 `Idempotency-Key`，并在 `ord_order.idempotency_key` 建唯一约束；重复请求返回首次订单结果。
2. 支付使用 `payNo/providerTransactionNo` 唯一约束，退款使用 `refundNo/providerRefundNo` 唯一约束。
3. 订单、支付、取消、退款状态更新必须使用“旧状态条件更新”或版本号，影响行数为 0 时返回状态冲突。
4. 删除面向普通客户端的 `/order/message/send`，改为 Sales 内部服务：
   - 在订单/支付/取消/退款本地事务内写 `local_message`（Outbox）；
   - 事务提交后投递；
   - Broker 未确认时定时重投；
   - 超过重试次数进入 DLQ 并告警。
5. `/order/message/status/{messageId}` 仅允许内部运维或管理员访问。
6. 同步库存预占接口只允许 Sales 服务身份调用，不能仅凭普通商城用户 JWT；`lockId/reservationId` 也不能被当作唯一授权凭据。

---

### 6.【高危】支付流程只有“直接支付”，缺少回调验签、状态查询和并发裁决

#### 漏洞场景

7.4 只定义 `POST /order/{orderId}/pay`，但没有定义：

- 预支付单创建；
- Mock 支付异步回调；
- 回调签名和防重放；
- 支付状态查询；
- 重复/乱序回调；
- “订单取消瞬间支付成功”的并发裁决；
- 已扣款但订单状态未更新时的对账与补偿。

接口还允许提交 `payPassword`，但当前项目约定为 Mock 支付，未说明支付密码的存储、校验和安全边界。

#### 建议解决方案

至少补充：

```http
POST /payment/prepay
POST /payment/mock-callback
GET  /payment/{payNo}/status
```

并明确：

1. 预支付只允许待支付且未过期的本人订单。
2. 回调使用 `timestamp + nonce + payload` 的 HMAC 签名，并限制时间窗口、防止 nonce 重放。
3. 回调按 `providerTransactionNo` 幂等。
4. 订单支付使用条件更新：

```sql
UPDATE ord_order
SET status = 1, pay_time = NOW()
WHERE id = :orderId
  AND status = 0
  AND expire_time > NOW();
```

5. 支付和取消并发时只允许一个状态迁移成功；若渠道已扣款但订单已取消，应进入自动退款/人工补偿流程，不能再次确认库存。
6. Mock 支付不应要求用户提交真实支付密码。

---

### 7.【高危】退款模块只有申请入口，没有审核、完成和库存回补闭环

#### 漏洞场景

7.9 只定义 `POST /order/aftersale`，且请求仅包含单个 `orderItemId`、类型、原因和图片。当前没有：

- 退款列表与详情；
- 商家审核通过/驳回；
- 退款执行或渠道回调；
- 全额/部分退款金额与数量；
- 累计可退金额、可退数量校验；
- 退款完成 MQ 事件；
- 重复退款防护。

因此已确认由 Sales 负责的 `refund` 无法形成业务闭环，IVP 也没有可靠时机执行库存回补。

#### 建议解决方案

补充最小接口集：

```http
POST /refund
GET  /refund/my
GET  /web/refund
GET  /web/refund/{refundId}
PUT  /web/refund/{refundId}/audit
PUT  /web/refund/{refundId}/complete
```

退款状态至少明确：

```text
PENDING → APPROVED → COMPLETED
PENDING → REJECTED
```

退款明细必须记录 `orderItemId`、`skuId`、退款数量、申请金额、核准金额。系统按原支付金额、历史已退款金额和历史已退款数量在服务端校验。

只有退款真正完成并且退款状态事务提交后，才发送 `refund.completed`。事件必须带 `restock` 或等价的验收入库结论：

- 商品已实际退回并可再次销售：IVP 回补库存；
- 仅退款不退货、商品报废或不可二次销售：不得增加可售库存。

重复事件必须按 `refundNo + eventType` 幂等，累计回补数量不得超过该订单项此前实际扣减数量。

---

### 8.【高危】缺少接口权限和数据归属规则，存在越权访问（IDOR）风险

#### 漏洞场景

文档只在全局说明使用 JWT，但未对订单、地址、售后、后台发货、消息发送接口规定角色和资源归属校验。

若实现只按路径 ID 查询，普通用户可能：

- 查看或取消他人的订单；
- 修改他人的购物车或地址；
- 对他人的订单项发起售后；
- 调用后台发货接口；
- 伪造 `ORDER_PAID/ORDER_CANCELLED` 消息。

#### 建议解决方案

1. 用户侧接口的 `userId/customerId` 只能从 JWT 获取，禁止信任请求体中的归属 ID。
2. 使用 `addressId/cartItemId/orderId/orderItemId/refundId` 时必须逐级校验属于当前用户。
3. 后台发货、退款审核、退款完成仅允许 `seller/admin` 等明确角色。
4. MQ 投递接口不得对商城用户开放。
5. 在接口文档中为每个接口新增“鉴权角色”和“数据范围”列。

---

### 9.【中危】订单状态示例自相矛盾，状态转换条件未定义

#### 漏洞场景

- 7.5 订单详情示例返回 `status=2`，但 `statusText` 写“待发货”。
- 7.6 枚举又规定 `1=已支付/待发货`、`2=已发货`。
- 取消订单没有说明允许从哪些状态取消，也没有说明已支付取消是否自动生成退款。
- 申请售后后若直接把整张订单置为 `5=售后处理中`，一张多商品订单中的部分退款会覆盖订单原本的发货/完成状态。
- 早期 Sales 规格中还存在商家审核/驳回环节，最新版接口没有说明该环节是否已经取消。

#### 建议解决方案

1. 修正 7.5 示例：待发货应为 `status=1`，或把文案改为“已发货”。
2. 在接口文档补充完整状态转换矩阵和非法转换错误码。
3. 所有迁移使用 `WHERE id=? AND status=?` 条件更新。
4. 订单主状态、支付状态、发货状态、退款状态建议分别存储；部分售后不应覆盖订单主状态。
5. 由组长确认商家审核环节是删除还是保留，并同步更新状态机与接口。

---

### 10.【中危】customer 与 address 契约不完整

#### 漏洞场景

第四章标题包含“客户管理”，但实际没有客户档案接口；地址仅有列表和新增，缺少详情、修改、删除和设置默认地址。

同时没有明确：

- `customer` 是否独立于 `sys_user`；
- 地址归属字段；
- 默认地址并发唯一性；
- 删除默认地址后的处理；
- 新增地址的响应结构；
- 历史订单是否保存地址快照。

#### 建议解决方案

补充：

```http
GET    /customer/me
PUT    /customer/me
GET    /address/{addressId}
PUT    /address/{addressId}
DELETE /address/{addressId}
PUT    /address/{addressId}/default
```

默认地址更新必须在事务内完成；PostgreSQL 建议增加“每位客户至多一个未删除默认地址”的部分唯一索引。下单时必须把收货人、电话和完整地址保存为订单快照，用户之后修改地址不能影响历史订单。

---

### 11.【高危】MQ 事件结构不足且幂等表定义与处理文字不一致

#### 漏洞场景

1. 统一消息示例只有订单号，没有 `reservationId/lockId`、仓库分配和 SKU 明细，IVP 无法稳定定位需要确认或释放的预占记录。
2. 文档说 `sourceNo` 是幂等键，但同一订单会依次产生 `ORDER_CREATED/ORDER_PAID/ORDER_CANCELLED`，不能只按订单号去重。
3. `mq_consume_log` 表结构没有 `marker` 字段，10.2.3 却要求写入 `marker=CANCEL_PENDING`。
4. 没有定义退款完成事件。
5. 没有定义 Exchange、Routing Key、Queue、DLX/DLQ 的准确名称和绑定关系。
6. 生产端查询示例直接返回 `consumeTime`，但 RabbitMQ Publisher Confirm 只能证明 Broker 收到消息，不能证明下游消费成功；若没有消费回执，该字段无法可靠获得。

#### 建议解决方案

统一事件信封：

```json
{
  "messageId": "uuid",
  "eventType": "ORDER_PAID",
  "schemaVersion": 1,
  "source": "sales",
  "occurredAt": "2026-07-23T16:00:00+08:00",
  "traceId": "trace-id",
  "payload": {
    "orderId": 9001,
    "orderNo": "ORD-20260723-0001",
    "reservationId": "RSV-20260723-0001",
    "items": [
      { "skuId": 2001, "warehouseId": 1, "quantity": 1 }
    ]
  }
}
```

幂等唯一键应为：

```text
(sourceNo, eventType, consumer)
```

`messageId` 用于识别同一消息的重复投递，`payloadHash` 用于发现同一业务键但内容不一致。消费幂等记录与库存业务更新必须处于同一数据库事务。

---

### 12.【高危】数据库附录缺少 Sales 核心表，无法支撑已确认范围

#### 漏洞场景

附录只列出 `ord_order`、`ord_order_item`、`ord_cart`、`ord_aftersale`，但已确认范围还需要客户、地址、支付和退款；文档正文使用了地址和消息幂等概念，附录也没有对应表。

缺失至少包括：

- 客户档案；
- 收货地址；
- 支付单；
- 退款单与退款明细；
- Sales Outbox；
- MQ 消费幂等表；
- 库存预占记录（应归 IVP）；
- 物流/发货记录（若不直接存在订单表）。

#### 建议解决方案

在数据库设计文档中补充表归属和唯一约束。Sales 最小表集建议与最终模块规格统一：

```text
crm_customer
crm_address
ord_order
ord_order_item
pay_payment
ref_refund
ref_refund_item
```

Outbox 可作为公共表或 Sales 支撑表另行增加。必须明确 `user_id`、`order_no`、`pay_no`、`refund_no`、第三方交易号和幂等键的唯一约束。

---

### 13.【中危】金额、快照、时间和文档版本/技术栈约定不一致

#### 漏洞场景

1. 接口未明确金额精度，若后端使用 `Double` 会出现精度误差。
2. 订单详情展示地址和商品信息，但未明确这些字段来自下单快照还是实时表。
3. 时间示例没有时区，不利于 MQ 重试、订单过期和支付回调比较。
4. 文档页首、页尾仍标记 v1.0/2025-07-21，但修订记录已经是 v1.1/2026-07-23。
5. 技术栈附录仍写 Spring Boot 3.x、MySQL 8、Redis 7，与组内已定的 Spring Boot 4.1、PostgreSQL 18、Redis 8、RabbitMQ 4 不一致。

#### 建议解决方案

- Java 金额统一使用 `BigDecimal`，PostgreSQL 使用 `numeric(19,2)`；所有金额由服务端重新计算，禁止信任客户端价格。
- 订单保存地址、SKU、商品名、规格、单价等不可变快照。
- 接口与 MQ 时间统一使用带时区的 ISO-8601。
- Long ID 对前端可序列化为字符串，避免 JavaScript 精度丢失。
- 统一文档版本、日期和技术栈后再据此编写 DDL、配置与代码。

---

## 三、建议冻结的 Sales ↔ IVP 库存状态语义

若 `quantity` 表示实际在库数量，且：

```text
available = quantity - locked_quantity
```

则双方接口应统一为：

| 业务动作 | `quantity` | `locked_quantity` |
|---|---:|---:|
| 下单预占 | 不变 | `+n` |
| 待支付取消 | 不变 | `-n` |
| 支付成功实扣 | `-n` | `-n` |
| 退款完成回补 | `+n` | 不变 |

每次更新都必须校验不变量：

```text
quantity >= 0
locked_quantity >= 0
quantity >= locked_quantity
```

并以 `reservationId/orderNo/refundNo` 作为业务幂等依据，写入可审计的库存流水。

---

## 四、建议优先级

### P0：编码前必须确认

1. 删除同步预占与 `order.created` 重复预占。
2. 冻结批量预占、补偿释放接口及库存字段语义。
3. 统一库存锁与订单过期时间。
4. 冻结订单、支付、退款状态机。
5. 明确 MQ Routing Key、事件结构、Outbox、重试和 DLQ。
6. 补齐权限/归属规则和 Sales 核心表。

### P1：核心流程开发时完成

1. 下单、支付、取消、退款幂等。
2. Mock 支付回调验签和并发状态裁决。
3. 客户/地址完整 CRUD。
4. 退款审核、完成及库存回补闭环。
5. 正常、异常、重复、乱序和超时测试。

### P2：联调与答辩前完成

1. Outbox 重投、DLQ 告警及人工补偿入口。
2. 订单—支付—库存对账。
3. 并发下单、重复回调、取消/支付竞态和部分退款测试报告。

---

## 五、需要组长/架构组最终确认的事项

1. 购物车是否归 Sales 负责人，还是由其他模块提供。
2. 客户档案复用 `sys_user` 还是单独建立 `crm_customer`。
3. 商家订单审核/驳回环节是否保留。
4. MQ Routing Key 最终使用 `order.*` 还是带模块命名空间的 `sales.order.*`。
5. Outbox 表由公共基建统一提供，还是各业务模块分别维护。
6. IVP 批量预占接口、补偿释放接口和预占记录状态机的最终契约。

---

*以上建议供组长、架构负责人及 IVP 模块负责人评估。确认后的接口、状态机和数据表应回写到正式接口/数据库设计文档，再开始对应实现。*
