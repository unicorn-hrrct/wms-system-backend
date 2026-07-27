package com.example.demo.common;

public enum ApiErrorCode {

    SUCCESS(200, "操作成功"),
    SAFETY_STOCK_EXCEEDED(20009, "已超过新安全水位，请先盘点"),
    FORCE_DELETE_LOCATION_OK(20010, "强制删除成功，原库位库存已转移至废品库位"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或 Token 过期"),
    FORBIDDEN(403, "无权限访问"),
    RESOURCE_FORBIDDEN(40301, "越权访问：资源不属于当前用户"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),
    STOCK_NOT_ENOUGH(50001, "库存不足"),
    STOCK_DEDUCT_CONFLICT(50002, "库存扣减失败"),
    ORDER_STATUS_INVALID(50003, "订单状态异常"),
    PAYMENT_TIMEOUT(50004, "支付超时或失败"),
    LOCATION_HAS_STOCK(50007, "该库位尚有库存商品，禁止删除"),
    FORCE_CLOSE_NOT_ALLOWED(50008, "订单状态不允许强制完结"),
    RETURN_STATUS_INVALID(50009, "退货单状态不允许该操作"),
    STOCK_RESERVE_FAILED(50010, "预占失败：可用库存不足"),
    RESERVATION_CONFLICT(50011, "预占请求冲突：同一 reservationRequestId 正在处理"),
    BATCH_DATE_INVALID(50012, "批次日期非法"),
    RETURN_DUPLICATE(50013, "退货单重复提交"),
    ORDER_STATE_INVALID(50014, "订单/支付/退款状态机非法转换"),
    IDEMPOTENCY_CONFLICT(50015, "重复提交(Idempotency-Key 已存在但请求不一致)"),
    REFUND_AMOUNT_EXCEEDED(50016, "退款金额/数量超过可退额度"),
    REFUND_TIME_WINDOW_EXCEEDED(50017, "退款已超过可处理时间窗口");

    private final int code;
    private final String message;

    ApiErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
