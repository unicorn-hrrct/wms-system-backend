# Customer / Address / Aftersale / Refund 分类交付清单

> 负责人：陈恩生（24443102401）
>
> 基线：`upstream/sales@5dc682e`
>
> 契约：`backend/购物仓储管理系统API接口文档.md` v1.2
>
> 状态：分类覆盖包已实现并验证，尚待组长合入统一后端

## 1. 路径映射

- `sales/src/main/java/**` → `backend/src/main/java/**`
- `sales/src/test/java/**` → `backend/src/test/java/**`
- `sales/sql/V001__customer_address_refund.sql` → 作为独立 PostgreSQL 增量迁移审查和
  执行，不替换、不拼接团队 `backend/src/main/resources/schema.sql`

复制源码前必须以本文件基线重建干净工作树，并逐文件审查差异；不得把根 `sales/`
整体复制为第二个 Spring Boot 模块。

## 2. 主源码

### Customer

- `controller/CustomerController.java`
- `dto/CustomerUpdateRequest.java`
- `security/CustomerIdProvider.java`
- `service/CustomerService.java`
- `service/SalesIdempotencyService.java`
- `vo/CustomerApiResponse.java`

### Address

- `controller/AddressController.java`
- `dto/AddressUpdateRequest.java`
- `service/AddressService.java`

### Aftersale

- `controller/AftersaleController.java`
- `dto/AftersaleApplyRequest.java`
- `dto/AftersaleAuditRequest.java`
- `service/AftersaleService.java`
- `vo/AftersaleResponse.java`

### Refund

- `controller/RefundController.java`
- `dto/RefundAuditRequest.java`
- `dto/RefundCompleteRequest.java`
- `json/RefundTimeJsonCodec.java`
- `service/RefundService.java`
- `vo/RefundApiResponse.java`
- `vo/RefundResponse.java`

上述相对路径均位于 `sales/src/main/java/com/example/demo/`。共享的
`CustomerIdProvider` 和 `SalesIdempotencyService` 必须与四组业务文件一起集成，
不能只复制 Controller 或 Service。

## 3. 测试

- `controller/CustomerAddressAftersaleRefundHttpIntegrationTest.java`
- `controller/SalesWriteIdempotencyHeaderTest.java`
- `dto/AftersaleRefundRequestValidationTest.java`
- `schema/SalesOverlayMigrationTest.java`
- `service/AftersaleServiceContractTest.java`
- `service/CustomerAddressServiceContractTest.java`
- `service/RefundServiceContractTest.java`
- `service/SalesIdempotencyPostgresIntegrationTest.java`

上述相对路径均位于 `sales/src/test/java/com/example/demo/`。HTTP 与迁移测试会重建
指定测试库，必须使用可丢弃数据库。

## 4. 数据库迁移

`sales/sql/V001__customer_address_refund.sql` 提供：

- 客户、地址、订单、订单项、售后和退款的复合归属唯一键与外键；
- 默认地址、类型、状态、金额、数量和回补类型约束；
- Aftersale/Refund 金额统一为 `numeric(19,2)`；
- 按 CUSTOMER/USER 主体隔离的写接口幂等记录；
- 退款客户时间索引及售后—退款一对一索引。

迁移会拒绝不满足 v1.2 归属和状态约束的历史数据。详细执行边界见
`sales/INTEGRATION.md`。

## 5. PR 门禁

创建代码 PR 前执行：

```text
git diff --name-only upstream/sales...HEAD
```

本交付要求输出全部以 `sales/` 开头。出现 `backend/`、`reports/` 或其他目录时停止
创建 PR；日报必须另走 `master`。
