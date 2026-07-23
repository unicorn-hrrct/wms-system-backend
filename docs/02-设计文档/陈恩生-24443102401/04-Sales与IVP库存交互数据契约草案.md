# Sales 与 IVP 库存交互数据契约草案

> **提供方**：Sales（模块四）
> **接收方**：IVP Inventory & Procurement（模块三）
> **版本**：v0.1（待模块三、模块四及组长共同确认）
> **日期**：2026-07-23
> **适用范围**：下单预占、下单失败补偿、支付实扣、取消释放、退款回补

---

## 一、已确认的调用方向

| 场景 | 通信方式 | IVP 动作 |
|---|---|---|
| 创建订单 | Sales 同步调用 IVP | 预占库存 |
| 订单落库失败 | Sales 同步补偿调用 IVP | 释放本次预占 |
| 待支付订单取消/超时 | RabbitMQ | 释放预占 |
| 支付成功 | RabbitMQ | 预占转正式扣减 |
| 退款完成且商品可重新销售 | RabbitMQ | 回补实际库存 |

约束：

1. Sales 不直接更新 `sto_stock`、`sto_stock_log`，不直接操作 IVP 的 Redis Key。
2. IVP 是库存事实和预占记录的唯一维护方。
3. Sales 在调用 IVP 前生成 `orderNo`，不能依赖订单插入数据库后才生成的自增 `orderId`。
4. 商品价格、商品名和规格快照来自 Catalog；库存契约只传库存处理必需字段。

### 同步调用的落地方式

同步预占既可以在单体项目内通过 Spring 注入 Java 接口完成，也可以通过内部 HTTP 完成；无论使用哪种方式，请求字段和幂等语义必须一致。

若采用与 Catalog 相同的 Java API 模块方式，建议由模块三提供：

```java
package com.wms.ivp.api;

public interface InventoryClient {
    StockReservationResult reserve(StockReserveCommand command);
    void compensateRelease(StockReleaseCommand command);
}
```

HTTP/JSON 示例用于双方核对字段，也可直接映射为上述 Command/Result DTO。

---

## 二、Catalog 数据在 Sales 下单中的使用

Sales 根据模块二提供的 `CatalogClient`：

```java
SkuSnapshot getSkuById(Long skuId);
ProductSnapshot getProductBySkuId(Long skuId);
```

下单时执行：

1. `SkuSnapshot == null`：拒绝下单。
2. `SkuSnapshot.status != 0`：SKU 已禁用，拒绝下单。
3. `SkuSnapshot.productStatus != 0`：SPU 已下架，拒绝下单。
4. 成交单价只使用 `SkuSnapshot.price`，禁止使用 SPU 的 `salePrice`。
5. Sales 保存 `skuCode/price/specValues/productName/mainImage` 等订单快照。
6. 发给 IVP 的库存明细只需要 `skuId + quantity`；IVP 不信任也不使用客户端价格。

---

## 三、下单同步预占契约

### 3.1 建议接口

当前最新版接口文档的 `/stock/lock/decrease` 一次只支持一个 SKU，无法保证多 SKU 整单原子性。本草案建议调整为：

```http
POST /api/v1/stock/reservations
Authorization: <Sales 服务身份>
Idempotency-Key: <requestId>
Content-Type: application/json
```

### 3.2 请求体

```json
{
  "requestId": "4e2164d2-8f8c-4e8a-90cf-8347c09ce501",
  "orderNo": "ORD202607230001",
  "expireAt": "2026-07-23T16:30:00+08:00",
  "items": [
    {
      "skuId": 2001,
      "quantity": 1
    },
    {
      "skuId": 2002,
      "quantity": 2
    }
  ]
}
```

### 3.3 字段定义

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `requestId` | String(UUID) | 是 | 本次预占请求幂等键 |
| `orderNo` | String | 是 | Sales 预生成的唯一订单号 |
| `expireAt` | ISO-8601 OffsetDateTime | 是 | 与订单支付过期时间一致 |
| `items` | Array | 是 | 一次提交整单全部 SKU，不能为空 |
| `items[].skuId` | Long | 是 | SKU 主键 |
| `items[].quantity` | Integer | 是 | 正整数，必须大于 0 |

### 3.4 成功响应

```json
{
  "code": 200,
  "message": "库存预占成功",
  "data": {
    "reservationId": "RSV202607230001",
    "requestId": "4e2164d2-8f8c-4e8a-90cf-8347c09ce501",
    "orderNo": "ORD202607230001",
    "status": "RESERVED",
    "expireAt": "2026-07-23T16:30:00+08:00",
    "allocations": [
      {
        "skuId": 2001,
        "warehouseId": 1,
        "locationId": 101,
        "quantity": 1
      },
      {
        "skuId": 2002,
        "warehouseId": 1,
        "locationId": 102,
        "quantity": 2
      }
    ]
  }
}
```

### 3.5 失败响应

| 业务码 | 场景 | Sales 处理 |
|---|---|---|
| `400` | 参数非法、数量小于 1、明细为空 | 直接提示，不重试 |
| `50001` | 任一 SKU 可用库存不足 | 整单失败，不创建订单 |
| `50002` | 库存并发冲突 | 可按约定有限重试 |
| 待分配 | 同一 `requestId/orderNo` 携带了不同明细 | 拒绝并告警，不覆盖首次请求 |

### 3.6 幂等和原子性

1. 同一 `requestId` 且请求内容相同：IVP 返回首次 `reservationId` 和结果，不重复预占。
2. 同一 `requestId/orderNo` 但明细不同：拒绝请求。
3. 多 SKU 必须全部预占成功或全部失败，不能留下部分成功。
4. IVP 按固定 `skuId/stockId` 顺序锁行，降低多 SKU 死锁概率。
5. IVP 返回的 `reservationId` 必须持久化，后续支付、取消、退款均以它定位库存记录。

---

## 四、订单落库失败的同步补偿契约

该接口只用于“预占已经成功，但 Sales 订单本地事务失败”的异常补偿。正常的用户取消/超时取消仍通过 MQ。

```http
POST /api/v1/stock/reservations/{reservationId}/release
Authorization: <Sales 服务身份>
Idempotency-Key: <operationId>
```

```json
{
  "operationId": "OP-1f06eb1b-7e5b-46d8-b792-915ab02a9bd2",
  "orderNo": "ORD202607230001",
  "reason": "ORDER_PERSIST_FAILED"
}
```

幂等要求：

- `RESERVED → RELEASED`：执行释放并返回成功。
- 已是 `RELEASED/EXPIRED`：重复调用仍返回成功。
- 已是 `CONFIRMED`：拒绝释放并告警，不能把已实扣库存当预占释放。
- 同步补偿失败时，Sales 必须写入可重试的 Outbox/补偿记录，不能只记录日志后丢弃。

---

## 五、MQ 统一消息信封

支付、取消和退款均采用同一外层结构：

```json
{
  "messageId": "8c71a5aa-65b9-4ecf-826d-a3d66a490361",
  "eventType": "ORDER_PAID",
  "schemaVersion": 1,
  "source": "sales",
  "occurredAt": "2026-07-23T16:00:00+08:00",
  "traceId": "trace-20260723-0001",
  "aggregateVersion": 2,
  "payload": {}
}
```

| 字段 | 用途 |
|---|---|
| `messageId` | RabbitMQ 重复投递幂等键 |
| `eventType` | 业务事件类型 |
| `schemaVersion` | 事件结构版本，便于兼容升级 |
| `source` | 固定为 `sales` |
| `occurredAt` | 事件实际发生时间，带时区 |
| `traceId` | 跨模块日志追踪 |
| `aggregateVersion` | 订单/退款业务版本，用于识别旧消息 |
| `payload` | 具体业务载荷 |

MQ 投递要求：

1. Sales 业务状态更新与 Outbox 消息写入必须在同一 PostgreSQL 事务。
2. 事务提交后投递 RabbitMQ；Publisher Confirm 失败时定时重投。
3. IVP 的消费日志、预占状态更新、库存更新和库存流水必须在同一事务。
4. IVP 提交数据库事务后才 ACK。

---

## 六、支付成功扣库存契约

### 6.1 Routing Key

```text
order.paid
```

> 若组内最终统一使用模块命名空间，可整体调整为 `sales.order.paid`，但生产者和消费者必须使用同一常量。

### 6.2 事件载荷

```json
{
  "messageId": "8c71a5aa-65b9-4ecf-826d-a3d66a490361",
  "eventType": "ORDER_PAID",
  "schemaVersion": 1,
  "source": "sales",
  "occurredAt": "2026-07-23T16:00:00+08:00",
  "traceId": "trace-20260723-0001",
  "aggregateVersion": 2,
  "payload": {
    "orderNo": "ORD202607230001",
    "reservationId": "RSV202607230001",
    "payNo": "PAY202607230001",
    "paidAt": "2026-07-23T16:00:00+08:00",
    "items": [
      { "skuId": 2001, "quantity": 1 },
      { "skuId": 2002, "quantity": 2 }
    ]
  }
}
```

### 6.3 IVP 处理语义

```text
RESERVED → CONFIRMED
quantity -= n
locked_quantity -= n
```

- 重复 `ORDER_PAID`：幂等返回成功，不重复扣减。
- 预占已 `RELEASED/EXPIRED`：不得继续扣减，进入异常/DLQ 并通知 Sales 补偿。
- `items` 与原预占记录不一致：拒绝并告警，以 IVP 持久化预占明细为准。

---

## 七、取消订单解锁契约

### 7.1 Routing Key

```text
order.cancelled
```

### 7.2 事件载荷

```json
{
  "messageId": "4de01191-0734-4f91-882e-a96dcfbc694e",
  "eventType": "ORDER_CANCELLED",
  "schemaVersion": 1,
  "source": "sales",
  "occurredAt": "2026-07-23T16:05:00+08:00",
  "traceId": "trace-20260723-0002",
  "aggregateVersion": 2,
  "payload": {
    "orderNo": "ORD202607230001",
    "reservationId": "RSV202607230001",
    "cancelType": "USER_CANCEL",
    "cancelReason": "不想购买了",
    "cancelledAt": "2026-07-23T16:05:00+08:00",
    "items": [
      { "skuId": 2001, "quantity": 1 },
      { "skuId": 2002, "quantity": 2 }
    ]
  }
}
```

`cancelType` 建议枚举：

```text
USER_CANCEL
PAYMENT_TIMEOUT
SYSTEM_CANCEL
```

### 7.3 IVP 处理语义

```text
RESERVED → RELEASED
quantity 不变
locked_quantity -= n
```

- 重复 `ORDER_CANCELLED`：幂等返回成功。
- 订单已支付且预占已经 `CONFIRMED`：不得再执行预占释放；已支付取消应进入退款流程。
- `ORDER_PAID` 与 `ORDER_CANCELLED` 并发时，IVP 对预占状态做 CAS，只允许一个终态转换成功。

---

## 八、退款完成回补契约

### 8.1 Routing Key

```text
refund.completed
```

### 8.2 事件载荷

```json
{
  "messageId": "f66ad7dc-44e5-4983-bf89-f03037b0ffcb",
  "eventType": "REFUND_COMPLETED",
  "schemaVersion": 1,
  "source": "sales",
  "occurredAt": "2026-07-24T10:00:00+08:00",
  "traceId": "trace-20260724-0001",
  "aggregateVersion": 3,
  "payload": {
    "refundNo": "REF202607240001",
    "orderNo": "ORD202607230001",
    "reservationId": "RSV202607230001",
    "completedAt": "2026-07-24T10:00:00+08:00",
    "items": [
      {
        "orderItemId": 10001,
        "skuId": 2001,
        "quantity": 1,
        "restock": true
      }
    ]
  }
}
```

### 8.3 IVP 处理语义

- `restock=true`：商品已实际退回且可再次销售，执行 `quantity += n`。
- `restock=false`：仅退款不退货、商品报废或不可二次销售，不增加可售库存。
- 同一 `refundNo + orderItemId` 重复消息不得重复回补。
- 单个订单项累计回补数量不得超过该订单项此前实际扣减数量。

---

## 九、库存状态语义

双方冻结以下含义：

```text
available = quantity - locked_quantity
```

| 动作 | `quantity` | `locked_quantity` | 预占状态 |
|---|---:|---:|---|
| 下单预占 | 不变 | `+n` | `RESERVED` |
| 取消/补偿释放 | 不变 | `-n` | `RELEASED` |
| 预占过期 | 不变 | `-n` | `EXPIRED` |
| 支付实扣 | `-n` | `-n` | `CONFIRMED` |
| 有效退货回补 | `+n` | 不变 | `PARTIALLY_REFUNDED/REFUNDED` |

数据库更新必须始终满足：

```text
quantity >= 0
locked_quantity >= 0
quantity >= locked_quantity
```

库存流水建议：

| 动作 | `quantity_change` | `locked_change` |
|---|---:|---:|
| 预占 | `0` | `+n` |
| 释放 | `0` | `-n` |
| 实扣 | `-n` | `-n` |
| 回补 | `+n` | `0` |

---

## 十、双方幂等键

| 操作 | 业务幂等键 |
|---|---|
| 同步预占 | `requestId`，并对 `orderNo` 建唯一约束 |
| 同步补偿释放 | `operationId` |
| 支付实扣 | `(orderNo, ORDER_PAID, inventory)` |
| 取消释放 | `(orderNo, ORDER_CANCELLED, inventory)` |
| 退款回补 | `(refundNo, REFUND_COMPLETED, inventory)` |
| MQ 重复投递 | `(messageId, consumer)` |

相同业务幂等键但 payload 不同时，不得覆盖首次数据，应记录 `payloadHash` 冲突并进入告警/隔离流程。

---

## 十一、需要共同确认的事项

1. 批量预占接口最终路径和 Java DTO 包名。
2. IVP 自动选择仓库/库位，还是 Sales 指定仓库。
3. 库存预占超时是否完全采用 Sales 提供的绝对 `expireAt`。
4. Routing Key 最终使用 `order.*`，还是 `sales.order.*`。
5. `order.created` 是否仅供通知使用，明确禁止再次预占库存。
6. `refund.completed` 在何种售后验收状态下设置 `restock=true`。
7. 幂等冲突使用哪个统一业务码。
8. 双方共享 DTO 放入公共 `api-contract` 模块，还是各自定义并通过 JSON Schema/OpenAPI 对齐。

---

*本文件为联调草案。双方确认后，应将最终内容回写到正式 API 接口文档和数据库设计文档，再开始对应实现。*
