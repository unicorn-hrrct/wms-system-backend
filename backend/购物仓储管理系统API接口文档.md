# 购物 / 仓储管理系统 — API 接口文档

> **版本：** v1.2  
> **基础URL：** `http://localhost:8080/api/v1`  
> **认证方式：** JWT Bearer Token（Header: `Authorization: Bearer <token>`）  
> **数据格式：** JSON（Content-Type: `application/json`）  
> **金额约定：** 一律使用 `BigDecimal`（数据库 `numeric(19,2)`），由服务端重算，禁止信任客户端价格  
> **时间约定：** ISO-8601 带时区（`2026-07-24T10:30:00+08:00`）  
> **幂等约定：** 写接口统一要求 `Idempotency-Key` 请求头（UUID）

---

## 目录

- [一、通用说明](#一通用说明)
- [二、用户与权限管理](#二用户与权限管理)
- [三、商品管理](#三商品管理)
- [四、供应商与客户管理](#四供应商与客户管理)
- [五、采购管理](#五采购管理)
- [六、库存管理（仓储核心）](#六库存管理仓储核心)
- [七、订单与销售管理](#七订单与销售管理)
- [八、数据统计](#八数据统计)
- [九、系统管理](#九系统管理)
- [十、进阶模块](#十进阶模块)

---

## 一、通用说明

### 1.1 统一响应格式

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... },
  "timestamp": 1724227200000
}
```

### 1.2 分页请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| pageNum | Integer | 否 | 页码，默认 1 |
| pageSize | Integer | 否 | 每页条数，默认 10 |

### 1.3 分页响应格式

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "total": 100,
    "pageNum": 1,
    "pageSize": 10,
    "pages": 10,
    "list": [...]
  }
}
```

### 1.4 全局错误码

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 401 | 未登录或Token过期 |
| 403 | 无权限访问 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |
| 50001 | 库存不足 |
| 50002 | 库存扣减失败（并发冲突） |
| 50003 | 订单状态异常 |
| 50004 | 支付超时/失败 |
| 50007 | 该库位尚有库存商品，禁止删除（v1.1） |
| 50008 | 订单状态不允许强制完结（v1.1） |
| 50009 | 退货单状态不允许该操作（v1.1） |
| 50012 | 批次日期非法（v1.1） |
| 50013 | 退货单重复提交（v1.1） |
| 20009 | 已超过新安全水位，请先盘点（v1.1 提示码） |
| 20010 | 强制删除成功，原库位库存已转移至废品库位（v1.1 提示码） |
| 50010 | 预占失败：可用库存不足（v1.2） |
| 50011 | 预占请求冲突：同一 `reservationRequestId` 正在处理（v1.2） |
| 50014 | 订单/支付/退款状态机非法转换（v1.2） |
| 50015 | 重复提交（Idempotency-Key 已存在但请求不一致）（v1.2） |
| 50016 | 退款金额/数量超过可退额度（v1.2） |
| 50017 | 退款已超过可处理时间窗口（v1.2） |
| 40301 | 越权访问：资源不属于当前用户（v1.2） |

---

## 二、用户与权限管理

### 2.1 用户登录

```
POST /auth/login
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 是 | 用户名 |
| password | String | 是 | 密码（MD5加密后传输） |

**请求示例：**

```json
{
  "username": "admin",
  "password": "e10adc3949ba59abbe56e057f20f883e"
}
```

**响应示例：**

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "userId": 1,
    "username": "admin",
    "nickname": "管理员",
    "avatar": "/avatar/default.png",
    "roles": ["admin"],
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expireTime": 1724832000000
  }
}
```

---

### 2.2 用户注册

```
POST /auth/register
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 是 | 用户名（唯一） |
| password | String | 是 | 密码 |
| nickname | String | 否 | 昵称 |
| phone | String | 否 | 手机号 |
| email | String | 否 | 邮箱 |

**响应示例：**

```json
{
  "code": 200,
  "message": "注册成功",
  "data": null
}
```

---

### 2.3 获取当前用户信息

```
GET /user/info
```

**请求头：** `Authorization: Bearer <token>`

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "userId": 1,
    "username": "admin",
    "nickname": "管理员",
    "avatar": "/avatar/admin.png",
    "phone": "13800138000",
    "email": "admin@example.com",
    "roles": ["admin"],
    "permissions": ["*:*:*"]
  }
}
```

---

### 2.4 修改密码

```
PUT /user/password
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| oldPassword | String | 是 | 原密码 |
| newPassword | String | 是 | 新密码（6-20位） |

---

### 2.5 获取角色列表

```
GET /role/list
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    { "roleId": 1, "roleName": "管理员", "roleKey": "admin", "status": 0 },
    { "roleId": 2, "roleName": "采购员", "roleKey": "buyer", "status": 0 },
    { "roleId": 3, "roleName": "仓管员", "roleKey": "keeper", "status": 0 },
    { "roleId": 4, "roleName": "销售员", "roleKey": "seller", "status": 0 },
    { "roleId": 5, "roleName": "普通用户", "roleKey": "user", "status": 0 }
  ]
}
```

---

### 2.6 获取菜单树（根据角色过滤）

```
GET /menu/tree
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "menuId": 1,
      "menuName": "系统管理",
      "icon": "system",
      "children": [
        { "menuId": 11, "menuName": "用户管理", "path": "/system/user" },
        { "menuId": 12, "menuName": "角色管理", "path": "/system/role" }
      ]
    }
  ]
}
```

---

## 三、商品管理

### 3.1 商品分类列表（树形）

```
GET /categories/tree
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "categoryId": 1,
      "categoryName": "数码产品",
      "parentId": 0,
      "sortOrder": 1,
      "children": [
        {
          "categoryId": 11,
          "categoryName": "手机",
          "parentId": 1,
          "sortOrder": 1,
          "children": []
        },
        {
          "categoryId": 12,
          "categoryName": "电脑",
          "parentId": 1,
          "sortOrder": 2,
          "children": []
        }
      ]
    }
  ]
}
```

---

### 3.2 新增商品分类

```
POST /categories
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| categoryName | String | 是 | 分类名称 |
| parentId | Long | 否 | 父级ID，默认0（顶级） |
| icon | String | 否 | 图标 |
| sortOrder | Integer | 否 | 排序号 |

---

### 3.3 商品列表（分页+搜索）

```
GET /products
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| keyword | String | 否 | 关键字搜索（名称/编号） |
| categoryId | Long | 否 | 分类ID |
| status | Integer | 否 | 状态：0=上架，1=下架 |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页条数 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "total": 56,
    "list": [
      {
        "productId": 1001,
        "productCode": "SP001",
        "productName": "iPhone 15 Pro",
        "categoryId": 11,
        "categoryName": "手机",
        "mainImage": "/images/iphone15pro.jpg",
        "unit": "台",
        "weight": 0.187,
        "purchasePrice": 7999.00,
        "salePrice": 8999.00,
        "stockQuantity": 120,
        "status": 0,
        "createTime": "2025-06-01 10:00:00"
      }
    ]
  }
}
```

---

### 3.4 新增商品（SPU）

```
POST /products
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| productCode | String | 是 | 商品编码（唯一） |
| productName | String | 是 | 商品名称 |
| categoryId | Long | 是 | 分类ID |
| mainImage | String | 否 | 主图URL |
| unit | String | 是 | 单位（个/件/箱等） |
| weight | Double | 否 | 重量(kg) |
| purchasePrice | BigDecimal | 是 | 进价 |
| salePrice | BigDecimal | 是 | 售价 |
| description | String | 否 | 商品描述（富文本） |
| skuList | Array | 是 | SKU规格列表（见下方） |

**skuList 结构：**

```json
[
  {
    "skuId": null,
    "skuCode": "SP001-BLACK-256",
    "specValues": "{\"颜色\":\"黑色\",\"存储\":\"256GB\"}",
    "price": 8999.00,
    "barcode": "6901234567890"
  }
]
```

---

### 3.5 编辑商品

```
PUT /products/{productId}
```

**路径参数：** `productId` - 商品ID

**请求参数：** 同新增商品

---

### 3.6 商品上下架

```
PUT /products/{productId}/status
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| status | Integer | 是 | 0=上架，1=下架 |

---

### 3.7 获取商品详情（含SKU列表）

```
GET /products/{productId}
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "productId": 1001,
    "productCode": "SP001",
    "productName": "iPhone 15 Pro",
    "categoryId": 11,
    "mainImage": "/images/iphone15pro.jpg",
    "unit": "台",
    "purchasePrice": 7999.00,
    "salePrice": 8999.00,
    "description": "<p>最新款...</p>",
    "skuList": [
      {
        "skuId": 2001,
        "skuCode": "SP001-BLACK-256",
        "specValues": {"颜色": "黑色", "存储": "256GB"},
        "price": 8999.00,
        "barcode": "6901234567890",
        "stockQuantity": 50
      },
      {
        "skuId": 2002,
        "skuCode": "SP001-WHITE-512",
        "specValues": {"颜色": "白色", "存储": "512GB"},
        "price": 9999.00,
        "barcode": "6901234567891",
        "stockQuantity": 70
      }
    ]
  }
}
```

---

### 3.8 删除商品（逻辑删除）

```
DELETE /products/{productId}
```

---

### 3.9 批量导入商品

```
POST /products/import
```

**Content-Type:** `multipart/form-data`

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | Excel文件（.xlsx/.xls） |

---

## 四、供应商与客户管理

> v1.2 起，客户档案与收货地址拆为两个子节；客户与 `sys_user` 解耦，独立建档。

### 4.0 客户档案（v1.2 新增）

> 当前项目将"客户"建模为独立实体 `crm_customer`，与登录账号 `sys_user` 通过 `user_id` 关联；
> 一个 `sys_user` 可对应 0..1 个 `crm_customer`，下单时使用 `customer_id` 关联订单。

#### 4.0.1 获取当前客户档案

```
GET /customer/me
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "customerId": 5001,
    "userId": 5,
    "nickname": "李明",
    "phone": "13800138000",
    "email": "liming@example.com",
    "level": "NORMAL",
    "registeredAt": "2026-05-12T09:30:00+08:00"
  }
}
```

#### 4.0.2 更新当前客户档案

```
PUT /customer/me
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| nickname | String | 否 | 昵称 |
| phone | String | 否 | 手机号 |
| email | String | 否 | 邮箱 |

**说明：** `customerId`、`userId`、`level`、`registeredAt` 不允许通过此接口修改；`phone` 变更需走单独验证码流程（v1.3 规划）。

---

### 4.1 供应商列表

```
GET /supplier/list
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| name | String | 否 | 供应商名称（模糊搜索） |
| contactPerson | String | 否 | 联系人 |
| status | Integer | 否 | 状态：0=合作中，1=已停用 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "total": 25,
    "list": [
      {
        "supplierId": 101,
        "supplierName": "深圳华为科技有限公司",
        "contactPerson": "张经理",
        "phone": "0755-12345678",
        "address": "深圳市龙岗区坂田街道",
        "email": "purchase@huawei.com",
        "status": 0,
        "createTime": "2025-03-15 09:00:00"
      }
    ]
  }
}
```

---

### 4.2 新增供应商

```
POST /supplier
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| supplierName | String | 是 | 供应商名称 |
| contactPerson | String | 否 | 联系人 |
| phone | String | 否 | 联系电话 |
| address | String | 否 | 地址 |
| email | String | 否 | 邮箱 |
| remark | String | 否 | 备注 |

---

### 4.3 客户收货地址管理

> v1.2 起地址归属字段为 `customer_id`（从 JWT 派生），不允许请求体传入；
> 下单时必须把地址完整快照写入 `ord_order`，后续用户修改地址不影响历史订单。

#### 4.3.1 地址列表

```
GET /address/list
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "addressId": 201,
      "customerId": 5001,
      "receiverName": "李明",
      "receiverPhone": "13900139000",
      "province": "广东省",
      "city": "深圳市",
      "district": "南山区",
      "detailAddress": "科技园南路1号",
      "isDefault": true
    }
  ]
}
```

#### 4.3.2 新增收货地址

```
POST /address
Idempotency-Key: <uuid>
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| receiverName | String | 是 | 收货人姓名 |
| receiverPhone | String | 是 | 收货人手机号 |
| province | String | 是 | 省 |
| city | String | 是 | 市 |
| district | String | 是 | 区 |
| detailAddress | String | 是 | 详细地址 |
| isDefault | Boolean | 否 | 是否默认地址 |

**业务约束：**

- `isDefault=true` 时必须在事务内把同客户其他地址置为非默认；
- 同一 `customer_id` 至多保留一个默认地址（数据库层用部分唯一索引 `(customer_id) WHERE is_default=true AND deleted=false` 约束）；
- `receiverPhone` 须通过正则校验中国大陆 11 位手机号。

#### 4.3.3 地址详情（v1.2 新增）

```
GET /address/{addressId}
```

**权限：** 仅本人地址可访问，越权返回 `40301`。

#### 4.3.4 修改地址（v1.2 新增）

```
PUT /address/{addressId}
Idempotency-Key: <uuid>
```

**请求参数：** 同 4.3.2。**业务约束：** 地址已被订单引用后，仅允许修改 `receiverName` / `receiverPhone`，地理字段视为快照。

#### 4.3.5 删除地址（v1.2 新增）

```
DELETE /address/{addressId}
```

**业务约束：**

- 默认地址删除时，若客户还有其他地址，则自动把最早创建的一条置为默认；
- 客户最后一条地址不允许删除，需先新增。

#### 4.3.6 设置默认地址（v1.2 新增）

```
PUT /address/{addressId}/default
```

**业务约束：** 事务内 `customer_id` 维度下其他地址全部置 `is_default=false`，目标地址置 `true`。

---

## 五、采购管理

### 5.1 采购申请列表

```
GET /purchase/request/list
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| requestNo | String | 否 | 申请单号 |
| status | Integer | 否 | 状态：0=待审核，1=已通过，2=已驳回 |
| startDate | String | 否 | 开始日期（yyyy-MM-dd） |
| endDate | String | 否 | 结束日期 |

---

### 5.2 提交采购申请

```
POST /purchase/request
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| supplierId | Long | 是 | 供应商ID |
| items | Array | 是 | 采购明细（见下方） |
| remark | String | 否 | 备注 |

**items 结构：**

```json
[
  {
    "skuId": 2001,
    "skuCode": "SP001-BLACK-256",
    "quantity": 50,
    "expectedPrice": 7900.00,
    "remark": "第一批"
  }
]
```

**响应示例：**

```json
{
  "code": 200,
  "message": "申请提交成功",
  "data": {
    "requestId": 3001,
    "requestNo": "PO20250721001",
    "status": 0
  }
}
```

---

### 5.3 审核采购申请

```
PUT /purchase/request/{requestId}/audit
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| auditStatus | Integer | 是 | 1=通过，2=驳回 |
| auditRemark | String | 否 | 审核意见 |

---

### 5.4 生成采购订单（审核通过后）

```
POST /purchase/order
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| requestId | Long | 是 | 采购申请ID |
| deliveryDate | String | 否 | 预计到货日期 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "orderId": 4001,
    "orderNo": "PO20250721001",
    "supplierId": 101,
    "totalAmount": 395000.00,
    "status": 0
  }
}
```

---

### 5.4.1 采购订单强制完结（部分入库收尾）

```
PUT /purchase/order/{orderId}/force-close
```

**适用场景：** 采购订单状态为 `PARTIAL_INBOUND`(1) 时，因供应商断货/终止合作等原因，将剩余未到货数量作废并直接收尾为 `CLOSED`(3)，避免订单永远卡在部分入库。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| orderId | Long | 是 | 采购订单ID（路径参数） |
| closeReason | String | 是 | 强制完结原因（必填，写入结案日志） |
| closeType | Integer | 否 | 完结类型：1=供应商断货，2=协商终止，3=其他，默认 3 |
| discardedItems | Array | 否 | 作废明细（不传则按剩余未到货数量自动作废） |

**discardedItems 结构：**

```json
[
  { "orderItemId": 5001, "discardedQuantity": 20 }
]
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "orderId": 4001,
    "orderNo": "PO20250721001",
    "status": 3,
    "closedAt": "2025-07-22 10:30:00",
    "closeLogId": 7001
  }
}
```

**业务约束：**

- 仅允许状态 `PARTIAL_INBOUND`(1) 的订单强制完结，其他状态返回 `50008 订单状态不允许强制完结`。
- 必须填写 `closeReason`，写入 `pur_order_close_log` 留痕。
- 已退货单关联的订单不允许强制完结，需先关闭退货流程。
- 作废数量会从 `pur_order_item.remaining_quantity` 中扣减，但不触发库存变更。

**状态流转更新：**

```
待执行(0) ──inbound_partial──▶ 部分入库(1) ──force_close──▶ 已关闭(3)
                                    │
                                    └──inbound_full──▶ 已完成(2)
```

---

### 5.5 采购入库

```
POST /purchase/inbound
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| orderId | Long | 是 | 采购订单ID |
| warehouseId | Long | 是 | 入库仓库ID |
| items | Array | 是 | 入库明细 |

**items 结构：**

```json
[
  {
    "orderItemId": 5001,
    "skuId": 2001,
    "actualQuantity": 48,
    "locationId": 10203,   // 库位ID
    "batchNo": "B20250721",
    "productionDate": "2025-07-15",
    "expireDate": "2026-07-15",
    "remark": "破损2件"
  }
]
```

**业务约束：**

- `batchNo` 必填，且在同一 `(sku_id, warehouse_id)` 下唯一。
- `productionDate` 与 `expireDate` 为可选字段，但若传入则必须 `expireDate > productionDate`，否则返回 `50012 批次日期非法`。
- 食品/美妆/医药类 SKU（`pro_category.requires_batch_date=1`）强制必填 `productionDate` 与 `expireDate`。
- 入库成功后，`sto_stock` 同步新增两列：`production_date DATE` / `expire_date DATE`，作为后续 FIFO 拣货与 `inventory.expiring` MQ 事件的数据源。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "inboundId": 6001,
    "inboundNo": "IB20250721001",
    "status": 1
  }
}
```

---

### 5.6 采购退货

```
POST /purchase/return
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| orderId | Long | 是 | 原采购订单ID |
| reason | String | 是 | 退货原因 |
| items | Array | 是 | 退货明细（skuId + quantity） |

**items 结构：**

```json
[
  { "skuId": 2001, "quantity": 50 }
]
```

**业务约束（防负库存）：**

- 提交退货时，系统按 `(sku_id, warehouse_id, location_id)` 校验 **可用库存 = `quantity − locked_quantity`**。
- 若任一明细的可用库存 < 退货数量，整单拒绝，返回 `50001 库存不足，退货数量超过可用库存`。
- 通过校验后，**退货单创建即预占**：
  - `sto_stock.locked_quantity += items[i].quantity`
  - 写入 `sto_stock_log`，`type=2 退货预占`，`quantity_change = -退货数`，`locked_change = +退货数`。
- 供应商/财务确认收货后，调用 `PUT /purchase/return/{returnId}/confirm`：
  - `sto_stock.quantity -= items[i].quantity`
  - `sto_stock.locked_quantity -= items[i].quantity`
  - 写入 `sto_stock_log`，`type=2 退货出库`（二次流水，标记锁定释放）。
- 退货驳回则调用 `PUT /purchase/return/{returnId}/reject`，仅释放锁定：
  - `sto_stock.locked_quantity -= items[i].quantity`
  - 写入 `sto_stock_log`，`type=2 退货驳回`，`locked_change = -退货数`。

**状态变化：**

| 退货单状态 | 库存动作 |
|-----------|---------|
| `PENDING`(0) 创建成功 | `locked_quantity` 增加 |
| `CONFIRMED`(1) 确认收货 | `quantity` 与 `locked_quantity` 同时扣减 |
| `REJECTED`(2) 驳回 | 仅 `locked_quantity` 回退 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "returnId": 8001,
    "returnNo": "RT20250721001",
    "status": 0,
    "lockedAt": "2025-07-22 11:00:00"
  }
}
```

#### 5.6.1 确认退货到货

```
PUT /purchase/return/{returnId}/confirm
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| returnId | Long | 是 | 退货单ID（路径参数） |
| confirmRemark | String | 否 | 确认备注 |

#### 5.6.2 驳回退货申请

```
PUT /purchase/return/{returnId}/reject
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| returnId | Long | 是 | 退货单ID（路径参数） |
| rejectReason | String | 是 | 驳回原因 |

---

## 六、库存管理（仓储核心）

### 6.1 仓库列表

```
GET /warehouse/list
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "warehouseId": 1,
      "warehouseCode": "WH-A",
      "warehouseName": "A仓库",
      "type": 1,
      "typeName": "主仓库",
      "address": "深圳市宝安区XX路XX号",
      "manager": "王仓管",
      "capacity": 10000,
      "usedCapacity": 6500,
      "status": 0
    }
  ]
}
```

---

### 6.2 库位结构（仓库 > 库区 > 货架 > 库位）

```
GET /location/tree?warehouseId={warehouseId}
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "areaId": 101,
      "areaName": "A区",
      "warehouseId": 1,
      "shelves": [
        {
          "shelfId": 201,
          "shelfName": "01排货架",
          "positions": [
            { "positionId": 301, "positionName": "01层-01格", "skuId": 2001, "quantity": 30 },
            { "positionId": 302, "positionName": "01层-02格", "skuId": 2002, "quantity": 20 }
          ]
        }
      ]
    }
  ]
}
```

#### 6.2.1 删除库位（有货强拦截）

```
DELETE /location/{locationId}
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| locationId | Long | 是 | 库位ID（路径参数） |
| force | Boolean | 否 | 是否强制删除（默认 false，开启后会跳过库存拦截，但不会级联清库存） |

**业务约束：**

- **默认行为（有货强拦截）：** 删除前执行以下校验，任一命中即拒绝删除：

  ```sql
  SELECT COUNT(*) FROM sto_stock
  WHERE location_id = #{locationId}
    AND (quantity > 0 OR locked_quantity > 0);
  ```

  返回 `50007 该库位尚有库存商品，禁止删除`，并附带明细：`{ skuId, quantity, lockedQuantity }`。

- **强制删除（`force=true`）：** 跳过库存数量校验，但不允许级联删除 `sto_stock` 记录，仅将 `location_id` 置为 `NULL` 或转移到"虚拟废品库位"。返回 `20010 强制删除成功，原库位库存已转移至废品库位`。

- **父子节点约束：** 若库位存在子节点（货架下的库位、库区下的货架），必须先删除子节点，或传 `cascade=true` 级联删除（`cascade` 与 `force` 同时传时仅 `cascade` 生效）。

- **软删除优先：** `sto_location` 表保留 `deleted` 字段，默认采用软删除（`deleted=1`），仅 `force=true` 才做物理删除。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "locationId": 301,
    "deletedAt": "2025-07-22 12:00:00",
    "softDeleted": true,
    "stockTransferred": 0
  }
}
```

---

### 6.3 实时库存查询

```
GET /stock/query
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| skuId | Long | 否 | SKU ID |
| productId | Long | 否 | 商品ID |
| warehouseId | Long | 否 | 仓库ID |
| locationId | Long | 否 | 库位ID |
| keyword | String | 否 | 关键字（商品名/SKU编码/条码） |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "total": 1,
    "list": [
      {
        "skuId": 2001,
        "skuCode": "SP001-BLACK-256",
        "productName": "iPhone 15 Pro",
        "specValues": {"颜色": "黑色", "存储": "256GB"},
        "barcode": "6901234567890",
        "unit": "台",
        "totalStock": 118,
        "availableStock": 115,
        "lockedStock": 3,
        "warehouses": [
          { "warehouseId": 1, "warehouseName": "A仓库", "quantity": 70 },
          { "warehouseId": 2, "warehouseName": "B仓库", "quantity": 48 }
        ],
        "lastInboundTime": "2025-07-20 14:30:00",
        "lastOutboundTime": "2025-07-19 16:00:00"
      }
    ]
  }
}
```

---

### 6.4 库存流水记录

```
GET /stock/log/list
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| skuId | Long | 否 | SKU ID |
| type | Integer | 否 | 流水类型：1=入库，2=出库，3=盘点调整，4=调拨 |
| startDate | String | 否 | 开始日期 |
| endDate | String | 否 | 结束日期 |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页条数 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "total": 320,
    "list": [
      {
        "logId": 7001,
        "skuId": 2001,
        "skuCode": "SP001-BLACK-256",
        "type": 1,
        "typeName": "采购入库",
        "quantityChange": 50,
        "beforeQty": 68,
        "afterQty": 118,
        "sourceNo": "IB20250721001",
        "operator": "王仓管",
        "operateTime": "2025-07-20 14:30:00",
        "remark": "采购入库"
      }
    ]
  }
}
```

---

### 6.5 库存预警列表

```
GET /stock/alert/list
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| warehouseId | Long | 否 | 仓库ID（不传则全仓聚合） |
| alertType | String | 否 | 预警类型：LOW_STOCK / OVER_STOCK / EXPIRING，可多选用逗号分隔 |

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "skuId": 2001,
      "skuCode": "SP001-BLACK-256",
      "productName": "iPhone 15 Pro",
      "currentStock": 5,
      "availableStock": 3,
      "minStock": 20,
      "maxStock": 500,
      "alertType": "LOW_STOCK",
      "alertMessage": "可用库存 3 件，低于安全水位 20 件"
    }
  ]
}
```

**安全库存水位来源（优先级从高到低）：**

1. `sto_stock.min_stock`（每条 `(sku_id, warehouse_id, location_id)` 记录独立配置，覆盖粒度最细）。
2. `pro_sku.safety_stock`（SKU 级默认水位，覆盖未配置 `min_stock` 的库位）。
3. `sys_config` 中 `sys.stock.alert.low.threshold`（全局默认阈值，仅在前两者均缺失时 fallback）。

**MQ 联动：**

触发预警时同步发送 MQ 事件（详见 10.2 节）：

- `inventory.low_stock` —— 库存低于安全水位（`available_stock < min_stock`）
- `inventory.over_stock` —— 库存高于超储水位（`quantity > max_stock`）
- `inventory.expiring` —— 批次到期日距今 ≤ `pro_sku.expiry_warn_days`

---

### 6.5.1 配置 SKU 安全水位

```
PUT /stock/safety-stock
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| skuId | Long | 是 | SKU ID |
| warehouseId | Long | 是 | 仓库ID |
| locationId | Long | 否 | 库位ID（不传则覆盖该仓所有库位） |
| minStock | Integer | 是 | 安全库存下限（≥ 0） |
| maxStock | Integer | 否 | 超储上限 |

**业务约束：**

- 同一 `(sku_id, warehouse_id, location_id)` 仅保留一条 `min_stock` 配置，重复 PUT 为覆盖。
- 若库位上有 `quantity > 0` 的库存且 `min_stock` 高于当前可用库存，提交时返回 `20009 已超过新安全水位，请先盘点` 提示，但允许强制覆盖（需传 `force=true`）。

---

### 6.6 库存盘点

#### 6.6.1 创建盘点单

```
POST /stock/check
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| warehouseId | Long | 是 | 盘点仓库ID |
| type | Integer | 是 | 盘点类型：1=全盘，2=抽盘 |
| checkItems | Array | 是 | 盘点明细（抽盘时传） |

**checkItems 结构：**

```json
[
  { "skuId": 2001, "systemQty": 118 },
  { "skuId": 2002, "systemQty": 72 }
]
```

#### 6.6.2 提交盘点结果

```
PUT /stock/check/{checkId}/submit
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| results | Array | 是 | 盘点结果 |

**results 结构：**

```json
[
  { "skuId": 2001, "actualQty": 116, "diffQty": -2, "reason": "丢失2件" },
  { "skuId": 2002, "actualQty": 75, "diffQty": 3, "reason": "多出3件" }
]
```

---

### 6.7 库存调拨

```
POST /stock/transfer
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| fromWarehouseId | Long | 是 | 调出仓库ID |
| toWarehouseId | Long | 是 | 调入仓库ID |
| items | Array | 是 | 调拨明细 |
| remark | String | 否 | 调拨原因 |

**items 结构：**

```json
[
  { "skuId": 2001, "quantity": 20, "fromLocationId": 301, "toLocationId": 401 }
]
```

---

## 七、订单与销售管理（购物端）

### 7.1 商品搜索（前台）

```
GET /shop/product/search
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| keyword | String | 否 | 搜索关键字 |
| categoryId | Long | 否 | 分类ID |
| minPrice | Double | 否 | 最低价格 |
| maxPrice | Double | 否 | 最高价格 |
| sortBy | String | 否 | 排序字段：price/sales/newest |
| sortOrder | String | 否 | asc/desc |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页条数 |

---

### 7.2 购物车

#### 7.2.1 加入购物车

```
POST /cart/add
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| skuId | Long | 是 | SKU ID |
| quantity | Integer | 是 | 数量（≥1） |

#### 7.2.2 购物车列表

```
GET /cart/list
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "cartItemId": 8001,
      "skuId": 2001,
      "skuCode": "SP001-BLACK-256",
      "productName": "iPhone 15 Pro",
      "specValues": {"颜色": "黑色", "存储": "256GB"},
      "mainImage": "/images/iphone15pro.jpg",
      "price": 8999.00,
      "quantity": 1,
      "stockQuantity": 115,
      "selected": true
    }
  ]
}
```

#### 7.2.3 修改购物车数量

```
PUT /cart/item/{cartItemId}
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| quantity | Integer | 是 | 新数量 |

#### 7.2.4 删除购物车项

```
DELETE /cart/item/{cartItemId}
```

---

### 7.3 下单（创建订单）

```
POST /order/create
Idempotency-Key: <uuid>
```

**请求头：**

| Header | 必填 | 说明 |
|--------|------|------|
| `Idempotency-Key` | 是 | UUID；同一 key 在 24 小时内重复请求会返回首次结果 |
| `Authorization` | 是 | 用户 JWT |

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| reservationRequestId | String | 是 | 客户端生成的预占请求号（建议 UUID），用于调用库存批量预占接口 |
| addressId | Long | 是 | 收货地址ID（必须属于当前 customer） |
| cartItemIds | Array | 是 | 购物车项ID数组 |
| items | Array | 否 | 直购明细（不经购物车下单时使用），与 `cartItemIds` 互斥 |
| remark | String | 否 | 订单备注 |
| couponId | Long | 否 | 优惠券ID（可选） |

**`items` 结构：**

```json
[
  { "skuId": 2001, "quantity": 1 }
]
```

**业务约束（v1.2 强化）：**

1. **价格服务端重算**：`totalAmount` / `payAmount` / `freight` / `discountAmount` 全部由服务端基于 `pro_sku.price` 和 `coupon` 规则重算，禁止信任客户端传值。
2. **预占原子性**：服务端在事务内调用 `POST /stock/reservations`（见 10.1.1）批量预占，**整单全部成功才创建有效订单**；部分成功则整单回滚，已成功的预占按 `reservationRequestId` 幂等补偿释放。
3. **库存锁与订单过期时间对齐**：订单有效期与库存预占 TTL 必须取同一配置 `sys.order.stock.lock.seconds`（默认 1800s = 30 分钟）；订单过期任务与库存锁过期任务使用同一调度源，避免窗口错位。
4. **Idempotency-Key 唯一约束**：`ord_order.idempotency_key` 上加唯一约束；重复请求时校验请求载荷一致，否则返回 `50015`。
5. **地址归属校验**：`addressId` 必须属于当前 `customer_id`，否则返回 `40301`。
6. **快照写入**：下单事务内把 `address` 全字段（收货人/电话/省市区/详细地址）、`skuCode/specValues/productName/price` 全部写入 `ord_order` 与 `ord_order_item`，后续地址或商品变更不影响历史订单。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "orderId": 9001,
    "orderNo": "ORD20260724001",
    "reservationId": "RSV-20260724-0001",
    "totalAmount": 8999.00,
    "payAmount": 8699.01,
    "discountAmount": 299.99,
    "freight": 0.00,
    "status": 0,
    "statusText": "待支付",
    "expireTime": "2026-07-24T11:00:00+08:00"
  }
}
```

**错误码：**

| 错误码 | 场景 |
|--------|------|
| 40301 | `addressId` 不属于当前 customer |
| 50001 | 任一 SKU 可用库存不足 |
| 50010 | 任一 SKU 预占失败 |
| 50015 | `Idempotency-Key` 已存在但请求不一致 |

---

### 7.4 订单支付

> v1.2 起支付链路拆为三步：预支付 → 用户支付 → 异步回调。`payPassword` 字段保留但**仅用于余额支付的兼容性占位**，Mock 支付场景下不要求真实密码。

#### 7.4.1 创建预支付单

```
POST /payment/prepay
Idempotency-Key: <uuid>
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| orderId | Long | 是 | 订单ID |
| payType | Integer | 是 | 支付方式：1=支付宝，2=微信，3=余额，4=Mock 通道 |

**业务约束：**

- 仅允许 `status=0`（待支付）且 `expire_time > NOW()` 的本人订单；
- 返回 `payNo`，用于后续回调幂等；
- `pay_payment.pay_no` 加唯一约束，`providerTransactionNo`（支付渠道流水号）唯一。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "payNo": "PAY20260724001",
    "orderId": 9001,
    "payType": 4,
    "payAmount": 8699.01,
    "expireTime": "2026-07-24T11:05:00+08:00",
    "mockPayUrl": "/mock-pay?payNo=PAY20260724001"
  }
}
```

#### 7.4.2 发起支付（兼容旧入口）

```
POST /order/{orderId}/pay
Idempotency-Key: <uuid>
```

**说明：** 兼容 v1.0 调用方式，内部等价于 `POST /payment/prepay`。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| payType | Integer | 是 | 支付方式：1=支付宝，2=微信，3=余额，4=Mock 通道 |
| payPassword | String | 否 | 仅当 `payType=3` 时使用 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "orderId": 9001,
    "payNo": "PAY20260724001",
    "payStatus": 0,
    "expireTime": "2026-07-24T11:05:00+08:00"
  }
}
```

#### 7.4.3 Mock 支付异步回调（v1.2 新增）

```
POST /payment/mock-callback
Content-Type: application/json
```

> Mock 回调由"前端沙箱"或测试脚本调用，不开放给真实客户端；
> 服务端必须按 `payNo` 做幂等，并使用条件更新避免支付/取消并发。

**请求头：**

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Mock-Timestamp` | 是 | 毫秒时间戳，校验时间窗口 ±5 分钟 |
| `X-Mock-Nonce` | 是 | UUID，单次有效，由 `pay_nonce` 表去重 |
| `X-Mock-Signature` | 是 | HMAC-SHA256(secret, `timestamp + nonce + body`)，校验失败返回 401 |

**请求参数：**

```json
{
  "payNo": "PAY20260724001",
  "status": "SUCCESS",
  "providerTransactionNo": "MOCK-TX-20260724-0001",
  "paidAt": "2026-07-24T10:35:00+08:00",
  "paidAmount": 8699.01
}
```

**业务约束：**

1. 签名校验失败或时间窗口外 → 返回 401；
2. `nonce` 已存在 → 返回 200 但不再处理（防重放）；
3. `providerTransactionNo` 已绑定到其他 `payNo` → 返回 `50014 状态机非法`；
4. 订单支付成功更新使用条件 SQL：

   ```sql
   UPDATE ord_order
   SET status = 1, pay_time = NOW()
   WHERE id = :orderId
     AND status = 0
     AND expire_time > NOW();
   ```

   影响行数为 0 时，说明已被取消/已支付，进入"已扣款但订单已取消"分支：自动生成退款单并走 §7.11 完成流程。

#### 7.4.4 查询支付状态（v1.2 新增）

```
GET /payment/{payNo}/status
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "payNo": "PAY20260724001",
    "orderId": 9001,
    "payStatus": 1,
    "payStatusText": "支付成功",
    "providerTransactionNo": "MOCK-TX-20260724-0001",
    "paidAt": "2026-07-24T10:35:00+08:00",
    "paidAmount": 8699.01
  }
}
```

---

### 7.5 订单详情

```
GET /order/{orderId}
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "orderId": 9001,
    "orderNo": "ORD20260724001",
    "status": 1,
    "statusText": "已支付/待发货",
    "totalAmount": 8999.00,
    "payAmount": 8699.01,
    "freight": 0.00,
    "createTime": "2026-07-24T10:20:00+08:00",
    "payTime": "2026-07-24T10:35:00+08:00",
    "address": {
      "receiverName": "李明",
      "receiverPhone": "13900139000",
      "fullAddress": "广东省深圳市南山区科技园南路1号"
    },
    "items": [
      {
        "orderItemId": 10001,
        "skuId": 2001,
        "skuCode": "SP001-BLACK-256",
        "productName": "iPhone 15 Pro",
        "specValues": {"颜色": "黑色", "存储": "256GB"},
        "mainImage": "/images/iphone15pro.jpg",
        "price": 8999.00,
        "quantity": 1,
        "subtotal": 8999.00
      }
    ],
    "timeline": [
      { "time": "2026-07-24T10:20:00+08:00", "event": "提交订单" },
      { "time": "2026-07-24T10:35:00+08:00", "event": "支付成功" }
    ]
  }
}
```

---

### 7.6 我的订单列表

```
GET /order/my
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| status | Integer | 否 | 订单状态筛选 |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页条数 |

**订单主状态枚举：**

| 值 | 含义 |
|----|------|
| 0 | 待支付 |
| 1 | 已支付/待发货 |
| 2 | 已发货 |
| 3 | 已完成 |
| 4 | 已取消 |
| 5 | 售后处理中 |

**合法状态转换矩阵（v1.2 冻结）：**

```text
0  待支付   → 1 待发货   （支付成功）
0  待支付   → 4 已取消   （用户主动取消 / 超时未付）
1  待发货   → 2 已发货   （商家发货）
1  待发货   → 4 已取消   （商家取消，需走退款）
2  已发货   → 3 已完成   （用户确认收货）
2  已发货   → 5 售后处理中（仅当存在未完成售后单）
3  已完成   → 5 售后处理中（仅当存在未完成售后单）
```

非法迁移返回 `50014 订单/支付/退款状态机非法转换`。

**部分售后规则（v1.2 明确）：** 订单存在部分退款/部分售后时，**不得**把整张订单置为 `5=售后处理中`——保留原主状态，仅在订单详情与列表中以 `hasPartialAftersale=true` 标记。

**售后/退款状态枚举：**

| 值 | 含义 |
|----|------|
| 0 | PENDING  待审核 |
| 1 | APPROVED 已审核通过 |
| 2 | REJECTED 已驳回 |
| 3 | REFUNDING 退款中 |
| 4 | COMPLETED 退款完成 |
| 5 | CANCELLED 售后取消 |

---

### 7.7 取消订单

```
PUT /order/{orderId}/cancel
Idempotency-Key: <uuid>
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| cancelReason | String | 是 | 取消原因 |

**业务约束（v1.2 强化）：**

- 仅 `status=0`（待支付）的订单允许用户主动取消；`status=1`（待发货）取消需走商家侧或工单流程；
- 取消时事务内写 `local_message`（Outbox），事务提交后投递 `order.cancelled` MQ；
- 重复请求按 `Idempotency-Key` 幂等。

---

### 7.8 确认收货

```
PUT /order/{orderId}/receive
```

---

### 7.9 申请售后（退货/换货）

> v1.2 起售后申请仅产生 `ord_aftersale` 记录，**不直接退款**；
> 商家审核通过后再走 §7.11 退款管理；申请时校验累计可退金额/数量。

```
POST /order/aftersale
Idempotency-Key: <uuid>
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| orderId | Long | 是 | 订单ID（必须属于当前 customer） |
| orderItemId | Long | 是 | 订单项ID |
| type | Integer | 是 | 1=仅退款，2=退货退款，3=换货 |
| reason | String | 是 | 原因 |
| images | Array | 否 | 凭证图片URL数组 |
| applyRefundAmount | BigDecimal | 否 | 申请退款金额（`type=1/2` 时必填） |
| applyRefundQuantity | Integer | 否 | 申请退货数量（`type=2` 时必填） |
| remark | String | 否 | 详细说明 |

**业务约束（v1.2 新增）：**

- 累计 `applyRefundAmount + 已退款金额` 不得超过该订单项实付金额；
- 累计 `applyRefundQuantity + 已退数量` 不得超过该订单项 `quantity`；
- 校验失败返回 `50016 退款金额/数量超过可退额度`；
- 仅 `status ∈ {2,3}` 的订单允许申请售后；
- 提交后状态 `PENDING(0)`，需等商家审核。

#### 7.9.1 商家侧售后列表

```
GET /web/aftersale
```

**权限：** `seller/admin`。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| status | Integer | 否 | 状态筛选：0=待审核，1=已通过，2=已驳回 |
| aftersaleNo | String | 否 | 售后单号 |
| orderNo | String | 否 | 关联订单号 |
| startDate | String | 否 | 申请开始时间 |
| endDate | String | 否 | 申请结束时间 |
| pageNum/pageSize | Integer | 否 | 分页 |

#### 7.9.2 售后详情

```
GET /web/aftersale/{aftersaleId}
```

#### 7.9.3 商家审核售后

```
PUT /web/aftersale/{aftersaleId}/audit
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| auditStatus | Integer | 是 | 1=通过，2=驳回 |
| approvedAmount | BigDecimal | 否 | 审核通过金额，默认取申请金额，不得超过申请金额 |
| approvedQuantity | Integer | 否 | 审核通过数量，退货退款时应大于 0 |
| auditRemark | String | 条件必填 | 驳回时必填 |

**业务约束：**

- 仅 `PENDING(0)` 状态允许审核；
- 通过后售后单状态变为 `APPROVED(1)`，并生成 `APPROVED(1)` 状态退款单，后续由 §7.11 执行退款；
- 驳回后售后单状态变为 `REJECTED(2)`，不生成退款单。

---

### 7.10 后台发货

```
POST /order/{orderId}/ship
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| logisticsCompany | String | 是 | 物流公司 |
| logisticsNo | String | 是 | 物流单号 |

**业务约束（v1.2 强化）：**

- 仅允许 `seller/admin` 角色调用；
- 状态转换 `1 → 2` 必须使用条件 SQL；
- 发货事件写入 Outbox，事务提交后投递 `order.shipped`。

---

### 7.11 退款管理（v1.2 新增）

> 退款状态机：`PENDING(0) → APPROVED(1) → REFUNDING(3) → COMPLETED(4)`；
> `PENDING(0) → REJECTED(2)`；任意中间态可 `→ CANCELLED(5)`。

#### 7.11.1 商家侧退款列表

```
GET /web/refund
```

**权限：** `seller/admin`。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| status | Integer | 否 | 状态筛选 |
| refundNo | String | 否 | 退款单号 |
| orderNo | String | 否 | 关联订单号 |
| startDate | String | 否 | 申请开始日期 |
| endDate | String | 否 | 申请结束日期 |
| pageNum/pageSize | Integer | 否 | 分页 |

#### 7.11.2 退款详情

```
GET /web/refund/{refundId}
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "refundId": 11001,
    "refundNo": "RF20260724001",
    "orderId": 9001,
    "orderItemId": 10001,
    "type": 2,
    "typeText": "退货退款",
    "applyRefundAmount": 8999.00,
    "applyRefundQuantity": 1,
    "approvedAmount": 8999.00,
    "actualRefundAmount": null,
    "status": 0,
    "statusText": "待审核",
    "reason": "尺寸不对",
    "images": ["/upload/2026/07/24/a.jpg"],
    "appliedAt": "2026-07-24T15:00:00+08:00",
    "restock": false
  }
}
```

#### 7.11.3 我的退款列表（用户侧）

```
GET /refund/my
```

**请求参数：** 同 7.11.1，但 `customerId` 维度从 JWT 派生。

#### 7.11.4 商家审核退款

```
PUT /web/refund/{refundId}/audit
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| auditStatus | Integer | 是 | 1=通过，2=驳回 |
| approvedAmount | BigDecimal | 条件必填 | `auditStatus=1` 时必填，不得超过 `applyRefundAmount` |
| approvedQuantity | Integer | 条件必填 | `auditStatus=1` 且 type=退货退款 时必填 |
| auditRemark | String | 否 | 审核备注 |

**业务约束：**

- 仅 `PENDING(0)` 状态允许审核；
- `auditStatus=1`：写入 `approved_amount/approved_quantity`，状态 → `APPROVED(1)`；
- `auditStatus=2`：状态 → `REJECTED(2)`，必须填 `auditRemark`；
- 状态转换用条件 SQL，影响行数 0 → `50014`。

#### 7.11.5 执行退款（内部接口）

```
PUT /web/refund/{refundId}/complete
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| actualRefundAmount | BigDecimal | 是 | 实际退款金额 |
| providerRefundNo | String | 是 | 渠道退款流水号 |
| restock | Boolean | 是 | 商品是否已退回并可二次销售 |

**业务约束：**

- 仅 `APPROVED(1)` 状态允许执行；
- `restock=true` 时，事务提交后写 Outbox 投递 `refund.completed` 事件，**payload 必须带 `restock=true`**，由 IVP 消费后回补可售库存；
- `restock=false` 时（仅退款不退货 / 报废 / 不可二次销售）投递 `refund.completed`，但 `restock=false`，IVP **不得** 增加可售库存；
- 状态 → `REFUNDING(3)`；异步回调或同步响应到达后 → `COMPLETED(4)`；
- 累计回补数量不得超过该订单项实际扣减数量；超量返回 `50016`。

#### 7.11.6 取消退款申请（用户侧）

```
PUT /refund/{refundId}/cancel
```

**业务约束：** 仅 `PENDING(0)` 状态且本人申请允许取消。

---

## 八、数据统计

### 8.1 库存概览仪表盘

```
GET /dashboard/stock
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "totalSkuCount": 356,
    "totalStockValue": 2856000.00,
    "lowStockCount": 12,
    "outOfStockCount": 3,
    "todayInboundCount": 8,
    "todayOutboundCount": 23,
    "topStockProducts": [
      { "productName": "iPhone 15 Pro", "quantity": 118 },
      { "productName": "MacBook Air M3", "quantity": 85 }
    ],
    "recentTrend": [
      { "date": "2025-07-15", "inbound": 150, "outbound": 230 },
      { "date": "2025-07-16", "inbound": 180, "outbound": 195 }
    ]
  }
}
```

---

### 8.2 销售报表

```
GET /report/sales
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| startDate | String | 是 | 开始日期 |
| endDate | String | 是 | 结束日期 |
| granularity | String | 否 | 统计粒度：day/week/month |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "totalSalesAmount": 586300.00,
    "totalOrderCount": 342,
    "averageOrderAmount": 1714.33,
    "dailyTrend": [
      { "date": "2025-07-15", "amount": 42500, "count": 28 },
      { "date": "2025-07-16", "amount": 38900, "count": 24 }
    ],
    "topProducts": [
      { "productName": "iPhone 15 Pro", "salesAmount": 179980, "salesCount": 20 },
      { "productName": "AirPods Pro 2", "salesAmount": 89500, "salesCount": 50 }
    ],
    "categoryDistribution": [
      { "categoryName": "手机", "percentage": 45.2 },
      { "categoryName": "电脑", "percentage": 28.6 }
    ]
  }
}
```

---

### 8.3 采购报表

```
GET /report/purchase
```

**请求参数：** 同销售报表

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "totalPurchaseAmount": 420000.00,
    "totalOrderCount": 18,
    "supplierRanking": [
      { "supplierName": "深圳华为科技", "amount": 180000, "percentage": 42.9 },
      { "supplierName": "北京小米电子", "amount": 120000, "percentage": 28.6 }
    ]
  }
}
```

---

## 九、系统管理

### 9.1 操作日志列表

```
GET /log/operation
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 否 | 操作人 |
| module | String | 否 | 模块名 |
| operation | String | 否 | 操作类型 |
| startDate | String | 否 | 开始时间 |
| endDate | String | 否 | 结束时间 |
| pageNum | Integer | 否 | 页码 |
| pageSize | Integer | 否 | 每页条数 |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "total": 1520,
    "list": [
      {
        "logId": 11001,
        "username": "admin",
        "module": "商品管理",
        "operation": "新增商品",
        "method": "POST /products",
        "requestUrl": "/api/v1/products",
        "ip": "192.168.1.100",
        "operateTime": "2025-07-21 10:00:00",
        "status": 0,
        "errorMsg": null
      }
    ]
  }
}
```

---

### 9.2 字典数据列表

```
GET /dict/type/{dictType}
```

**常用字典类型：**

| dictType | 说明 |
|----------|------|
| sys_order_status | 订单状态 |
| sys_pay_type | 支付方式 |
| sys_warehouse_type | 仓库类型 |
| sys_stock_alert_type | 库存预警类型 |
| sys_aftersale_type | 售后类型 |

**响应示例：**

```json
{
  "code": 200,
  "data": [
    { "dictLabel": "待支付", "dictValue": "0", "cssClass": "warning" },
    { "dictLabel": "已支付", "dictValue": "1", "cssClass": "primary" },
    { "dictLabel": "已发货", "dictValue": "2", "cssClass": "info" },
    { "dictLabel": "已完成", "dictValue": "3", "cssClass": "success" },
    { "dictLabel": "已取消", "dictValue": "4", "cssClass": "danger" }
  ]
}
```

---

### 9.3 系统参数配置

```
GET /config/list
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    { "configKey": "sys.order.auto.cancel.minutes", "configName": "订单自动取消时间(分钟)", "configValue": "30" },
    { "configKey": "sys.order.stock.lock.seconds", "configName": "订单/库存锁共用过期秒数", "configValue": "1800" },
    { "configKey": "sys.stock.alert.low.threshold", "configName": "低库存预警阈值", "configValue": "20" },
    { "configKey": "sys.pay.alipay.sandbox", "configName": "支付宝沙箱模式", "configValue": "true" }
  ]
}
```

**v1.2 新增配置：**

| configKey | 说明 | 默认值 |
|-----------|------|--------|
| `sys.order.auto.cancel.minutes` | 订单自动取消分钟数 | `30` |
| `sys.order.stock.lock.seconds` | 订单/库存锁共用 TTL 秒数 | `1800` |

**业务约束（v1.2 强化）：** 上述两个参数**必须保持一致语义**：`sys.order.auto.cancel.minutes × 60 == sys.order.stock.lock.seconds`；订单过期定时任务与库存锁过期释放任务必须读取同一配置源，超时取消订单与释放库存同步进行。

---

## 十、进阶模块

### 10.1 库存超卖控制（Redis 分布式锁 + Lua 脚本）

> **v1.2 架构口径：** PostgreSQL `sto_stock` 为**库存事实来源**；Redis 仅用于快速失败、限流、可重建缓存。
> 任何库存变动必须**先在数据库事务内**完成 `quantity/locked_quantity` 与流水更新，再异步同步 Redis；
> Redis 成功而数据库事务失败 → 必须补偿 Redis；数据库成功而 Redis 失败 → DB 记录可重建缓存。
> 同一下单链路下，预占动作**只在 `POST /stock/reservations`（同步）执行一次**，`order.created` MQ 不再重复预占。

#### 10.1.0 整单批量预占（v1.2 新增，推荐）

```
POST /stock/reservations
Idempotency-Key: <uuid>
```

**请求头：**

| Header | 必填 | 说明 |
|--------|------|------|
| `Authorization` | 是 | **仅允许 Sales 服务身份（service token），不接受普通用户 JWT** |
| `Idempotency-Key` | 是 | UUID；同一 key 重复请求返回首次结果 |

**请求参数：**

```json
{
  "reservationRequestId": "RSV-20260724-0001",
  "orderNo": "ORD-20260724-0001",
  "expireAt": "2026-07-24T11:00:00+08:00",
  "items": [
    { "skuId": 2001, "quantity": 1 },
    { "skuId": 2002, "quantity": 2 }
  ]
}
```

**业务约束：**

1. **整单原子性**：所有明细"全部成功或全部失败"；任一失败整单回滚，已成功的预占按 `reservationRequestId` 幂等补偿释放。
2. **预占记录持久化**：在 `sto_stock_reservation` 表写入每条 `(reservation_id, request_id, order_no, status, expires_at, sku_id, warehouse_id, location_id, quantity, payload_hash)`，状态机 `RESERVED → CONFIRMED / RELEASED / EXPIRED`。
3. **多库位分仓分配**：服务端按可用库存自动选择 `warehouse_id / location_id`，返回分配明细。
4. **过期时间由订单决定**：服务端必须从 `orderNo` 反查 `ord_order.expire_time`，**不接受客户端 `expireAt` 作为唯一依据**，仅用于对账。
5. **数据不变式**：`quantity >= 0`、`locked_quantity >= 0`、`quantity >= locked_quantity`；任一条件不满足返回 `50010`。
6. **数量校验**：`quantity > 0` 且不大于单笔上限；未知 SKU / 非法动作返回 `50010`。

**响应示例（成功）：**

```json
{
  "code": 200,
  "data": {
    "reservationId": "RSV-20260724-0001",
    "expireAt": "2026-07-24T11:00:00+08:00",
    "items": [
      { "skuId": 2001, "warehouseId": 1, "locationId": 301, "quantity": 1 },
      { "skuId": 2002, "warehouseId": 1, "locationId": 302, "quantity": 2 }
    ]
  }
}
```

#### 10.1.1 单 SKU 预占（兼容旧入口）

```
POST /stock/lock/decrease
Idempotency-Key: <uuid>
```

**说明：** v1.2 起仅作为兼容接口保留，**新接入方应使用 §10.1.0 批量预占**。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| skuId | Long | 是 | SKU ID |
| quantity | Integer | 是 | 扣减数量 |
| reservationRequestId | String | 是 | **预占请求号（替代原 orderId）**，与 `POST /order/create` 透传同一值 |
| expireAt | String | 是 | ISO-8601 过期时间，**必须由订单 `expire_time` 决定**，不接受短 TTL |
| warehouseId | Long | 否 | 指定仓库；不传则按可用库存自动分配 |

**响应示例（成功）：**

```json
{
  "code": 200,
  "message": "库存锁定成功",
  "data": {
    "reservationId": "RSV-20260724-0001",
    "skuId": 2001,
    "lockedQuantity": 1,
    "remainingStock": 114,
    "expireAt": "2026-07-24T11:00:00+08:00"
  }
}
```

**响应示例（库存不足）：**

```json
{
  "code": 50010,
  "message": "预占失败：可用库存不足",
  "data": null
}
```

**响应示例（并发冲突）：**

```json
{
  "code": 50002,
  "message": "库存扣减失败，请重试",
  "data": null
}
```

---

#### 10.1.2 释放/确认库存预占

```
POST /stock/lock/release
Idempotency-Key: <uuid>
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| reservationId | String | 是 | 预占记录ID（替代原 lockId） |
| action | String | 是 | `confirm=确认扣减（支付成功）` / `cancel=释放回滚（取消订单）` / `restock=回补库存（退款完成且商品已退回）` |
| refundNo | String | 条件必填 | `action=restock` 时必填 |

**Lua 脚本核心逻辑（参考，v1.2 仅作 Redis 缓存层参考）：**

```lua
-- stock_decrease.lua
-- v1.2: 仅作 Redis 快速失败/限流层；事实扣减必须以 PG 事务为准
local stockKey = KEYS[1]
local lockKey  = KEYS[2]
local quantity = tonumber(ARGV[1])
local reservationRequestId = ARGV[2]
local expireAtTs = tonumber(ARGV[3])

local current = tonumber(redis.call('GET', stockKey) or '0')
if current < quantity then
    return {0, current}  -- 库存不足
end

redis.call('DECRBY', stockKey, quantity)
redis.call('HSET', lockKey, reservationRequestId, quantity)
redis.call('EXPIREAT', lockKey, expireAtTs)
return {1, current - quantity}  -- 预占成功
```

**v1.2 关键修正：**

- 用 `EXPIREAT`（绝对时间戳）代替 `EXPIRE`（相对 TTL），与订单 `expire_time` 对齐；
- `orderId` 替换为 `reservationRequestId`，避免依赖尚未入库的 `orderId`；
- Lua 中对 `quantity` 做 `> 0` 校验，防止负数 `DECRBY` 反向增加库存；
- 数据库事务失败时，调用 `POST /stock/lock/release action=cancel` 按 `reservationRequestId` 幂等补偿 Redis；
- 数据库预占记录 `sto_stock_reservation` 为最终依据，Redis 丢失后可重建缓存。

---

### 10.2 订单异步处理（RabbitMQ 4）

> **v1.2 架构口径：** 面向普通客户端的 MQ 投递入口取消；改为 Sales 内部服务在本地事务内写 `sales_outbox`（local_message 表），事务提交后由调度器投递；投递失败重试 N 次后进入 DLQ 表 `mq_dead_letter` 并告警。**`order.created` MQ 不再触发库存预占**——预占仅由 §10.1.0 同步完成。

#### 10.2.1 内部事件出口（v1.2 重构）

```
POST /order/message/send
```

**权限：** **仅允许 Sales 服务内部调用**，必须使用 service token，不接受普通用户 JWT。

**说明：** 实际生产路径为"业务事务 → 写 `sales_outbox` → 提交后由 `@Scheduled` 投递"，本接口仅作内部契约/调试入口保留。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| orderId | Long | 是 | 订单ID |
| eventType | String | 是 | 事件类型：`ORDER_CREATED` / `ORDER_PAID` / `ORDER_CANCELLED` / `ORDER_SHIPPED` / `REFUND_COMPLETED` |
| payload | Object | 是 | 事件载荷 |

**payload 示例（ORDER_PAID）：**

```json
{
  "orderId": 9001,
  "orderNo": "ORD-20260724-0001",
  "reservationId": "RSV-20260724-0001",
  "userId": 5,
  "totalAmount": 8699.01,
  "items": [
    { "skuId": 2001, "warehouseId": 1, "locationId": 301, "quantity": 1, "price": 8999.00 }
  ]
}
```

**payload 示例（REFUND_COMPLETED）：**

```json
{
  "refundNo": "RF-20260724-0001",
  "orderNo": "ORD-20260724-0001",
  "orderItemId": 10001,
  "skuId": 2001,
  "warehouseId": 1,
  "locationId": 301,
  "quantity": 1,
  "restock": true,
  "actualRefundAmount": 8999.00,
  "completedAt": "2026-07-24T16:00:00+08:00"
}
```

**`restock=false` 时 IVP 仅记录不回补可售库存**（适用：仅退款不退货 / 报废 / 不可二次销售）。

**统一事件信封（v1.2 冻结）：**

```json
{
  "messageId": "uuid",
  "eventType": "ORDER_PAID",
  "schemaVersion": 1,
  "source": "sales",
  "occurredAt": "2026-07-24T10:35:00+08:00",
  "traceId": "trace-id",
  "payload": { ... }
}
```

**幂等键**：`payload` 中的业务号（`orderNo` / `refundNo` / `reservationId`）+ `eventType`，由消费者组合 `(sourceNo, eventType, consumer)` 唯一约束去重（详见 §10.2.3）。

**MQ Topic/Queue 设计（v1.2 重命名）：**

| Exchange / Routing Key | 用途 | 消费者 |
|------------------------|------|--------|
| `sales.events` / `sales.order.created` | 新订单事件 | 通知服务（不再做库存预占） |
| `sales.events` / `sales.order.paid` | 支付完成事件 | 库存服务（confirm）、物流服务、积分服务 |
| `sales.events` / `sales.order.cancelled` | 取消事件 | 库存服务（release）、优惠券服务 |
| `sales.events` / `sales.order.shipped` | 发货事件 | 物流服务、通知服务 |
| `sales.events` / `sales.refund.completed` | 退款完成事件 | 库存服务（按 `restock` 决定是否回补）、财务服务 |
| `inventory.events` / `inventory.low_stock` | 库存低于安全水位 | 通知服务、补货建议服务 |
| `inventory.events` / `inventory.over_stock` | 库存高于超储水位 | 通知服务 |
| `inventory.events` / `inventory.expiring` | 批次即将到期 | 通知服务、促销服务 |

**DLQ：** 所有队列声明 `x-dead-letter-exchange` 指向 `sales.dlx` / `inventory.dlx`，落地表 `mq_dead_letter`。

---

#### 10.2.3 MQ 消费幂等与乱序防护

库存服务作为 `sales.order.*` 事件的关键消费者，必须满足以下契约，避免消息乱序或重复消费导致的库存死锁。

> **v1.2 重要修正：** `order.created` **不再触发库存预占**，仅作审计/通知；CANCEL_PENDING 语义由"创建未到先收取消"调整为"创建后取消在 MQ 抖动期间错位到达"——库存动作仅依赖 DB 中已有的 `sto_stock_reservation` 状态。

**幂等表设计（v1.2 增 `marker` 列）：**

```sql
CREATE TABLE mq_consume_log (
    id              BIGSERIAL PRIMARY KEY,
    message_id      VARCHAR(64) NOT NULL,
    source_no       VARCHAR(64) NOT NULL,   -- orderNo / refundNo / reservationId
    event_type      VARCHAR(32) NOT NULL,   -- ORDER_CREATED / ORDER_PAID / ORDER_CANCELLED / REFUND_COMPLETED
    consumer        VARCHAR(32) NOT NULL,   -- inventory / notification / shipping
    status          SMALLINT NOT NULL,      -- 0=待处理 1=成功 2=失败重试 3=死信
    marker          VARCHAR(32),            -- v1.2 新增：CANCEL_PENDING / REFUND_Restock_PENDING
    payload_hash    VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(message_id, consumer),
    UNIQUE(source_no, event_type, consumer)
);
```

**乱序处理（CANCEL_PENDING 防重，v1.2 调整）：**

- 消费 `sales.order.cancelled` 时，按 `source_no + ORDER_CANCELLED` 查幂等表：
  - 若已 `status=1`：直接 ack；
  - 否则执行库存释放（`sto_stock_reservation.status = RELEASED`、`locked_quantity -= quantity`、写 `sto_stock_log`），与订单 `idempotency_key` 比对确保幂等。
- 消费 `sales.order.created`（**不再做库存动作**）：仅做审计/通知；若幂等表存在 `marker=CANCEL_PENDING` 记录，说明该订单已取消，跳过通知中"已锁库"类文案。
- 消费 `sales.refund.completed`：
  - `payload.restock=true`：执行库存回补（`sto_stock.quantity += quantity`），回写 `sto_stock_log`；
  - `payload.restock=false`：仅记录退款流水，不动 `sto_stock.quantity`；
  - 累计回补数量不得超过该订单项实际扣减数量；超量返回 `50016`。
- 重投：幂等键 `(source_no, event_type, consumer)` 命中已成功记录 → 直接 ack；
- 兜底：5 分钟扫描 `mq_consume_log WHERE status=0 AND created_at < NOW()-30min` 仍未处理 → 人工介入。

**重试策略：**

- 业务异常（库存不足、状态机非法）：不重试，写 `status=2` 终态并投递补偿 MQ；
- 系统异常（DB 抖动、MQ 暂时不可达）：指数退避重试 3 次（1s / 5s / 30s），仍失败写 `status=3` 并落入 DLQ 表 `mq_dead_letter`；
- 超过重试次数必须告警（v1.3 接入通知服务）。

---

---

#### 10.2.2 查询消息发送状态

```
GET /order/message/status/{messageId}
```

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "messageId": "MSG-20250721-001",
    "eventType": "ORDER_PAID",
    "status": "DELIVERED",
    "sendTime": "2025-07-21 10:25:31",
    "consumeTime": "2025-07-21 10:25:32"
  }
}
```

---

### 10.3 数据大屏（ECharts 实时数据）

#### 10.3.1 大屏总览数据

```
GET /dashboard/screen
```

**说明：** 返回大屏所需的所有聚合数据，前端用 ECharts 渲染。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "kpiCards": [
      { "title": "今日销售额", "value": 128600, "unit": "元", "change": "+12.5%", "trend": "up" },
      { "title": "今日订单量", "value": 156, "unit": "单", "change": "+8.3%", "trend": "up" },
      { "title": "今日入库量", "value": 892, "unit": "件", "change": "-3.2%", "trend": "down" },
      { "title": "当前库存总值", "value": 2856000, "unit": "元", "change": "+2.1%", "trend": "up" }
    ],
    "salesTrend": {
      "dates": ["07-15","07-16","07-17","07-18","07-19","07-20","07-21"],
      "amounts": [42500,38900,51200,47800,55600,62300,128600],
      "counts": [28,24,35,30,38,42,156]
    },
    "categoryPie": [
      { "name": "手机", "value": 45.2 },
      { "name": "电脑", "value": 28.6 },
      { "name": "配件", "value": 15.3 },
      { "name": "其他", "value": 10.9 }
    ],
    "warehouseBar": [
      { "name": "A仓库", "inbound": 520, "outbound": 380, "stock": 6500 },
      { "name": "B仓库", "inbound": 372, "outbound": 512, "stock": 4800 }
    ],
    "hotProducts": [
      { "name": "iPhone 15 Pro", "sales": 20, "amount": 179980 },
      { "name": "AirPods Pro 2", "sales": 50, "amount": 89500 },
      { "name": "MacBook Air M3", "sales": 12, "amount": 107880 }
    ],
    "realtimeOrders": [
      { "time": "10:25:30", "orderNo": "ORD20250721001", "amount": 8699.01, "customer": "李*" },
      { "time": "10:24:15", "orderNo": "ORD20250720998", "amount": 1299.00, "customer": "张*" },
      { "time": "10:23:08", "orderNo": "ORD20250720997", "amount": 4599.00, "customer": "王*" }
    ],
    "alertList": [
      { "level": "high", "message": "iPhone 15 Pro 黑色256G 库存仅剩5件！" },
      { "level": "medium", "message": "A仓库容量使用率达85%" }
    ]
  }
}
```

---

#### 10.3.2 WebSocket 实时推送（大屏自动刷新）

```
WS /ws/dashboard/screen
```

**连接认证：** 连接时在 URL 中携带 token：`/ws/dashboard/screen?token=xxx`

**服务端推送消息格式：**

```json
{
  "type": "DATA_UPDATE",
  "timestamp": 1724227530000,
  "data": {
    "kpiCards": [ ... ],
    "realtimeOrders": [ ... ]
  }
}
```

---

### 10.4 扫码出入库

#### 10.4.1 条码解析

```
GET /barcode/parse?code={barcode}
```

**说明：** 根据条码查询对应的 SKU 信息。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "barcode": "6901234567890",
    "skuId": 2001,
    "skuCode": "SP001-BLACK-256",
    "productName": "iPhone 15 Pro",
    "specValues": {"颜色": "黑色", "存储": "256GB"},
    "unit": "台",
    "currentStock": 114,
    "price": 8999.00,
    "mainImage": "/images/iphone15pro.jpg"
  }
}
```

---

#### 10.4.2 扫码入库

```
POST /scan/inbound
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| barcodes | Array | 是 | 条码数组 |
| quantities | Array | 是 | 对应数量数组 |
| warehouseId | Long | 是 | 目标仓库ID |
| locationId | Long | 是 | 目标库位ID |
| sourceType | String | 是 | 来源类型：PURCHASE / RETURN / TRANSFER |
| sourceNo | String | 是 | 来源单号 |
| batchNo | String | 否 | 批次号 |
| productionDate | String | 否 | 生产日期（YYYY-MM-DD） |
| expireDate | String | 否 | 到期日期（YYYY-MM-DD） |

**请求示例：**

```json
{
  "barcodes": ["6901234567890", "6901234567891"],
  "quantities": [10, 5],
  "warehouseId": 1,
  "locationId": 301,
  "sourceType": "PURCHASE",
  "sourceNo": "PO20250721001",
  "batchNo": "B20250721",
  "productionDate": "2025-07-15",
  "expireDate": "2026-07-15"
}
```

**业务约束：** 与 5.5 一致——`productionDate` 与 `expireDate` 在食品/美妆/医药类 SKU 上强制必填；`expireDate` 必须晚于 `productionDate`。

---

#### 10.4.3 扫码出库

```
POST /scan/outbound
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| barcodes | Array | 是 | 条码数组 |
| quantities | Array | 是 | 对应数量数组 |
| warehouseId | Long | 是 | 出库仓库ID |
| locationId | Long | 是 | 出库库位ID |
| destType | String | 是 | 目的类型：SALE / RETURN / TRANSFER |
| destNo | String | 是 | 目的单号（如订单号） |

---

### 10.5 多仓库 / 多货主

#### 10.5.0 商家入驻申请与审核

> 普通用户提交入驻申请；后台管理员审核通过后，系统自动创建货主、绑定可经营仓库，并为申请账号授予 `seller` 角色。

##### 10.5.0.1 提交商家入驻申请

```
POST /merchant/apply
```

**请求头：**

| Header | 必填 | 说明 |
|--------|------|------|
| Idempotency-Key | 是 | 幂等键，重复提交返回同一申请 |

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| merchantName | String | 是 | 商家名称 |
| merchantCode | String | 否 | 商家编码；不填则审核通过时自动生成 |
| contactName | String | 是 | 联系人 |
| contactPhone | String | 是 | 联系电话 |
| contactEmail | String | 否 | 联系邮箱 |
| licenseNo | String | 是 | 营业执照号 |
| licenseImage | String | 否 | 营业执照图片地址 |
| businessScope | String | 否 | 经营范围 |
| address | String | 否 | 经营地址 |

**响应核心字段：**

| 字段名 | 类型 | 说明 |
|--------|------|------|
| applicationId | Long | 申请ID |
| applicationNo | String | 申请单号 |
| status | Integer | 0 待审核 / 1 已通过 / 2 已驳回 |
| ownerId | Long | 审核通过后生成的货主ID |
| warehouseIds | Long[] | 审核通过后绑定的仓库ID |

##### 10.5.0.2 我的商家入驻申请

```
GET /merchant/application/my
```

##### 10.5.0.3 后台商家入驻申请列表

```
GET /web/merchant/applications
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| status | Integer | 否 | 0 待审核 / 1 已通过 / 2 已驳回 |
| merchantName | String | 否 | 商家名称，模糊匹配 |
| applicationNo | String | 否 | 申请单号，模糊匹配 |
| startDate | DateTime | 否 | 申请开始时间 |
| endDate | DateTime | 否 | 申请结束时间 |
| pageNum | Integer | 否 | 页码，默认 1 |
| pageSize | Integer | 否 | 每页条数，默认 10 |

##### 10.5.0.4 商家入驻申请详情

```
GET /web/merchant/applications/{applicationId}
```

##### 10.5.0.5 审核商家入驻申请

```
PUT /web/merchant/applications/{applicationId}/audit
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| auditStatus | Integer | 是 | 1 通过 / 2 驳回 |
| merchantCode | String | 否 | 审核通过时指定商家编码；优先级高于申请时填写的编码 |
| warehouseIds | Long[] | 否 | 审核通过后绑定的仓库ID |
| auditRemark | String | 否 | 审核备注；驳回时必填 |

**规则：**

- 仅 `status=0` 的申请可审核；
- 通过后创建 `own_owner` 货主记录，并写入 `sto_owner_warehouse` 仓库绑定；
- 通过后为申请用户授予 `seller` 角色，用户重新登录后生效；
- 商家编码会统一转为大写，并校验不能与已有货主或待审核/已通过申请重复。

---

#### 10.5.1 货主列表

```
GET /owner/list
```

**响应示例：**

```json
{
  "code": 200,
  "data": [
    {
      "ownerId": 1,
      "ownerName": "自营",
      "ownerCode": "SELF",
      "contact": "总部",
      "warehouseIds": [1, 2]
    },
    {
      "ownerId": 2,
      "ownerName": "第三方商家A",
      "ownerCode": "TP_A",
      "contact": "赵经理",
      "warehouseIds": [3]
    }
  ]
}
```

---

#### 10.5.2 按货主查询库存

```
GET /stock/by-owner
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| ownerId | Long | 是 | 货主ID |
| warehouseId | Long | 否 | 仓库ID（不传则查所有仓库） |

---

#### 10.5.3 跨货主库存隔离校验

```
POST /stock/isolation-check
```

**说明：** 在出入库前校验货主是否有权操作该仓库的库存。

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| ownerId | Long | 是 | 货主ID |
| warehouseId | Long | 是 | 仓库ID |
| skuId | Long | 是 | SKU ID |
| action | String | 是 | INBOUND / OUTBOUND |

---

### 10.6 智能补货提醒

#### 10.6.1 补货建议列表

```
GET /restock/suggestion
```

**说明：** 基于历史销售速度和当前库存，计算智能补货建议。

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "total": 8,
    "list": [
      {
        "suggestionId": 1,
        "skuId": 2001,
        "skuCode": "SP001-BLACK-256",
        "productName": "iPhone 15 Pro",
        "specValues": {"颜色": "黑色", "存储": "256GB"},
        "currentStock": 5,
        "safetyStock": 20,
        "avgDailySales": 3.5,
        "daysOfStockRemaining": 1.4,
        "suggestedQuantity": 60,
        "suggestedSupplierId": 101,
        "suggestedSupplierName": "深圳华为科技有限公司",
        "urgency": "HIGH",
        "estimatedArrivalDays": 3,
        "reason": "日均销量3.5件，当前库存仅够维持1.4天，建议立即补货60件（满足约17天销量）"
      },
      {
        "suggestionId": 2,
        "skuId": 2050,
        "skuCode": "AP002-WHITE",
        "productName": "AirPods Pro 2",
        "specValues": {"颜色": "白色"},
        "currentStock": 25,
        "safetyStock": 50,
        "avgDailySales": 8.2,
        "daysOfStockRemaining": 3.0,
        "suggestedQuantity": 100,
        "urgency": "MEDIUM",
        "reason": "日均销量8.2件，当前库存仅够维持3天，建议补货100件"
      }
    ]
  }
}
```

---

#### 10.6.2 一键生成补货采购单

```
POST /restock/generate-order
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| suggestionIds | Array | 是 | 补货建议ID数组 |
| autoMerge | Boolean | 否 | 是否按供应商合并为一张采购单，默认true |

**响应示例：**

```json
{
  "code": 200,
  "data": {
    "generatedOrders": [
      {
        "orderNo": "AUTO_PO20250721001",
        "supplierId": 101,
        "supplierName": "深圳华为科技有限公司",
        "items": [
          { "skuId": 2001, "quantity": 60, "estimatedAmount": 474000.00 }
        ],
        "totalAmount": 474000.00
      }
    ]
  }
}
```

---

#### 10.6.3 补货规则配置

```
PUT /restock/rule
```

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| defaultSafetyStockDays | Integer | 否 | 默认安全库存天数，默认7 |
| defaultLeadTimeDays | Integer | 否 | 默认补货提前期（天），默认3 |
| salesHistoryDays | Integer | 否 | 销售历史统计周期（天），默认30 |
| enableAutoNotify | Boolean | 否 | 是否启用自动通知，默认true |
| notifyChannels | Array | 否 | 通知渠道：["email","sms","system"] |

---

## 附录

### A. 数据库表设计（核心表）

> v1.2 起明确 Sales 业务最小表集（crm_* / ord_* / pay_* / ref_*）与跨模块支撑表（sales_outbox / mq_consume_log / mq_dead_letter）；`sto_stock_reservation` 由 IVP 维护。

| 表名 | 说明 | 归属模块 |
|------|------|----------|
| sys_user | 用户表 | Platform |
| sys_role | 角色表 | Platform |
| sys_menu | 菜单权限表 | Platform |
| sys_config | 系统参数表 | Platform |
| pro_product | 商品(SPU)表 | Catalog |
| pro_sku | 商品SKU表 | Catalog |
| pro_category | 商品分类表 | Catalog |
| sup_supplier | 供应商表 | Procurement |
| pur_purchase_request | 采购申请表 | Procurement |
| pur_purchase_order | 采购订单表 | Procurement |
| pur_purchase_item | 采购明细表 | Procurement |
| pur_purchase_inbound | 采购入库表 | Procurement |
| pur_purchase_return | 采购退货表 | Procurement |
| sto_warehouse | 仓库表 | IVP |
| sto_location | 库位表 | IVP |
| sto_stock | 库存表（事实来源） | IVP |
| sto_stock_log | 库存流水表 | IVP |
| sto_stock_check | 盘点单表 | IVP |
| sto_stock_reservation | 库存预占记录（v1.2 新增） | IVP |
| **crm_customer** | **客户档案表（v1.2 新增）** | **Sales** |
| **crm_address** | **收货地址表（v1.2 新增）** | **Sales** |
| ord_order | 订单表 | Sales |
| ord_order_item | 订单明细表 | Sales |
| ord_cart | 购物车表 | Sales |
| ord_aftersale | 售后申请表 | Sales |
| **pay_payment** | **支付单表（v1.2 新增）** | **Sales** |
| **ref_refund** | **退款单表（v1.2 新增）** | **Sales** |
| **ref_refund_item** | **退款明细表（v1.2 新增）** | **Sales** |
| **sales_outbox** | **本地消息表（v1.2 新增，Outbox 模式）** | **Sales** |
| **mq_consume_log** | **MQ 消费幂等表（v1.2 新增）** | **跨模块** |
| **mq_dead_letter** | **死信表（v1.2 新增）** | **跨模块** |
| **mer_merchant_application** | **商家入驻申请表（v1.2 新增）** | **Catalog** |
| own_owner | 货主表 | Catalog |
| res_restock_suggestion | 补货建议表 | IVP |

**Sales 表关键唯一约束（v1.2 冻结）：**

| 表 | 唯一键 | 用途 |
|----|--------|------|
| `ord_order` | `UNIQUE(order_no)` | 订单号全局唯一 |
| `ord_order` | `UNIQUE(idempotency_key) WHERE idempotency_key IS NOT NULL` | 写接口幂等 |
| `crm_customer` | `UNIQUE(user_id)` | 一个登录账号对应一个客户 |
| `crm_address` | `UNIQUE(customer_id) WHERE is_default=true AND deleted=false` | 每客户至多一个默认地址 |
| `pay_payment` | `UNIQUE(pay_no)` | 支付单号全局唯一 |
| `pay_payment` | `UNIQUE(provider_transaction_no)` | 渠道流水号全局唯一 |
| `ref_refund` | `UNIQUE(refund_no)` | 退款单号全局唯一 |
| `ref_refund` | `UNIQUE(provider_refund_no)` | 渠道退款流水号全局唯一 |
| `sales_outbox` | `UNIQUE(message_id)` | Outbox 消息防重投 |

### B. 技术栈推荐

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.1 + Spring Security + MyBatis-Plus |
| 数据库 | PostgreSQL 18 + Redis 8 |
| 消息队列 | RabbitMQ 4（含 DLX / DLQ） |
| 缓存一致性 | PostgreSQL 为事实来源，Redis 仅做缓存；写后失效由 Outbox 异步触发 |
| 搜索引擎 | Elasticsearch（商品搜索） |
| 向量数据库 | Milvus / pgvector（RAG记忆） |
| 前端 | Vue 3 + Element Plus + ECharts + Pinia |
| 文档 | Swagger / Knife4j（自动生成API文档） |
| 部署 | Docker + Docker Compose + Nginx |
| 金额 | Java `BigDecimal` + PostgreSQL `numeric(19,2)`，前端 Long ID 序列化为字符串 |
| 时间 | ISO-8601 带时区；前后端均按 UTC 存储展示 |

---

> **文档版本：** v1.2  
> **更新日期：** 2026-07-24  
> **维护者：** 软件技术毕业实训项目组

---

## 修订记录

### v1.2 — 2026-07-24（Sales 模块反馈修复）

> 反馈人：陈恩生（Sales 模块负责人）  
> 反馈文档：`04-Sales模块业务漏洞与接口规格优化建议.md`  
> 本轮针对重复预占、orderId 循环依赖、TTL 错位、状态枚举冲突、退款闭环缺失、MQ 事件结构不全等 13 项问题进行接口契约冻结；技术栈由 Spring Boot 3.x / MySQL 8 / Redis 7 升级为 Spring Boot 4.1 / PostgreSQL 18 / Redis 8 / RabbitMQ 4。

| # | 问题等级 | 章节 | 变更摘要 | 来源 |
|---|---------|------|---------|------|
| 1 | 高危 | §10.1.0（新增） / §10.1.1 / §10.2.1 | 新增 `POST /stock/reservations` 整单批量预占接口；`order.created` MQ 不再触发预占，消除同步预占与 MQ 预占的重复锁库 | Sales-2026-001 |
| 2 | 高危 | §7.3 / §10.1.0 / §10.1.1 | 预占接口 `orderId` 改为 `reservationRequestId`；新增 `Idempotency-Key`；整单原子预占 + 补偿释放 | Sales-2026-002 |
| 3 | 高危 | §10.1（架构口径） / §10.2.3 | 明确 PostgreSQL 为库存事实来源，Redis 仅做缓存；新增 `sto_stock_reservation` 持久化预占记录（IVP 维护） | Sales-2026-003 |
| 4 | 高危 | §9.3 / §10.1.0 / §10.1.1 | 新增 `sys.order.stock.lock.seconds` 与 `sys.order.auto.cancel.minutes` 语义对齐；预占 TTL 由订单绝对过期时间决定，Lua 用 `EXPIREAT` 替代 `EXPIRE` | Sales-2026-004 |
| 5 | 高危 | §7.3 / §7.4 / §7.7 / §7.9 / §7.11 | 全部写接口加 `Idempotency-Key`；`/order/message/send` 改为 Sales 内部 service token；新增 `sales_outbox` + `mq_dead_letter` | Sales-2026-005 |
| 6 | 高危 | §7.4.1 / §7.4.3 / §7.4.4（新增） | 支付拆为预支付 / Mock 回调 / 状态查询三步；回调加 HMAC + nonce + 时间窗口；状态更新使用条件 SQL 解决并发 | Sales-2026-006 |
| 7 | 高危 | §7.9 / §7.11（新增） | 售后申请与退款流程拆开；新增商家审核 / 执行退款 / 我的退款 / 商家退款列表；`refund.completed` 事件带 `restock` 标志 | Sales-2026-007 |
| 8 | 高危 | §4.0 / §4.3 / §7.4 / §7.10 / §7.11 | 客户 ID 从 JWT 派生，禁止请求体传入；资源归属逐级校验；后台发货 / 退款审核 / 退款完成仅 `seller/admin` 角色 | Sales-2026-008 |
| 9 | 中危 | §7.5 / §7.6 | 修正 §7.5 状态文案不一致（`status=2→1`）；冻结合法状态转换矩阵；部分售后不覆盖主订单状态；新增退款状态枚举 | Sales-2026-009 |
| 10 | 中危 | §4.0（新增） / §4.3.3–4.3.6（新增） | 新增 `GET/PUT /customer/me`；地址补详情 / 修改 / 删除 / 默认；地址作为下单快照写入订单 | Sales-2026-010 |
| 11 | 高危 | §10.2.1 / §10.2.3 | MQ 事件信封统一加 `schemaVersion/traceId/payload.items[].warehouseId`；幂等键改为 `(source_no, event_type, consumer)`；`mq_consume_log` 增 `marker` 列；新增 `ORDER_SHIPPED` / `REFUND_COMPLETED` 事件；命名空间改为 `sales.*` / `inventory.*` | Sales-2026-011 |
| 12 | 高危 | 附录 A / 附录 B | 数据库附录新增 `crm_customer / crm_address / pay_payment / ref_refund / ref_refund_item / sales_outbox / mq_consume_log / mq_dead_letter / sto_stock_reservation` 9 张表；技术栈升级 SB 4.1 + PG 18 + Redis 8 + RabbitMQ 4 | Sales-2026-012 |
| 13 | 中危 | 文档头 / §1.4 / 附录 B | 文档版本 v1.0→v1.2；金额统一 `BigDecimal/numeric(19,2)`；时间统一 ISO-8601 带时区；Long ID 序列化为字符串；新增错误码 50010/50011/50014/50015/50016/50017/40301 | Sales-2026-013 |

### v1.1 — 2026-07-23（IVP 模块反馈修复）

| # | 问题等级 | 章节 | 变更摘要 | 来源 |
|---|---------|------|---------|------|
| 1 | 高危 | 5.4.1（新增） | 新增 `PUT /purchase/order/{orderId}/force-close` 强制完结接口，关闭 `PARTIAL_INBOUND` 卡死闭环 | IVP-2026-001 |
| 2 | 高危 | 5.6 / 5.6.1 / 5.6.2 | 采购退货改为"创建即预占 `locked_quantity`"，新增 `confirm` / `reject` 子接口，杜绝负库存 | IVP-2026-002 |
| 3 | 中危 | 6.5 / 6.5.1（新增） | `sto_stock` 增 `min_stock` 字段，三级水位优先级落库；新增 `PUT /stock/safety-stock` 配置接口 | IVP-2026-003 |
| 4 | 中危 | 10.2 / 10.2.3（新增） | 新增 `inventory.low_stock` / `over_stock` / `expiring` MQ 事件；补 `CANCEL_PENDING` 乱序防护与 `mq_consume_log` 幂等表 | IVP-2026-004 |
| 5 | 低危 | 6.2.1（新增） | 补齐 `DELETE /location/{locationId}`，默认有货强拦截 + `force=true` 强制转移废品库位 | IVP-2026-005 |
| 6 | 扩展 | 5.5 / 10.4.2 | 入库与扫码入库增 `productionDate` / `expireDate` 字段；`sto_stock` 同步落两列；附 `inventory.expiring` 触发链路 | IVP-2026-006 |

> 说明：本轮修订仅涉及接口文档层面的契约补充；Service 实现、Mapper / DO 字段、Controller 代码及 SQL 迁移脚本不在本轮范围内。
