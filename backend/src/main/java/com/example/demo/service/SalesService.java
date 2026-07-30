package com.example.demo.service;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.dto.AftersaleApplyRequest;
import com.example.demo.dto.PaymentPrepayRequest;
import com.example.demo.dto.StockReservationItem;
import com.example.demo.dto.StockReservationRequest;
import com.example.demo.vo.StockReservationResponse;
import com.example.demo.exception.BusinessException;
import com.example.demo.security.CurrentUserProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SalesService {

    private final JdbcTemplate jdbcTemplate;
    private final StockMutationService stockMutationService;
    private final StockReservationService stockReservationService;
    private final PaymentService paymentService;
    private final AftersaleService aftersaleService;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public SalesService(JdbcTemplate jdbcTemplate,
                        StockMutationService stockMutationService,
                        StockReservationService stockReservationService,
                        PaymentService paymentService,
                        AftersaleService aftersaleService,
                        CurrentUserProvider currentUser,
                        ObjectMapper objectMapper,
                        StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockMutationService = stockMutationService;
        this.stockReservationService = stockReservationService;
        this.paymentService = paymentService;
        this.aftersaleService = aftersaleService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> search(String keyword, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                      String sortBy, String sortOrder, int pageNum, int pageSize) {
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
        String order = switch (sortBy == null ? "newest" : sortBy) {
            case "price" -> "k.price " + direction;
            case "sales" -> "sales_count " + direction;
            default -> "p.create_time " + direction;
        };
        StringBuilder where = new StringBuilder(" WHERE p.deleted=0 AND k.deleted=0 AND p.status=0 AND k.status=0");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (p.product_name ILIKE ? OR p.product_code ILIKE ? OR k.sku_code ILIKE ?)");
            String value = "%" + keyword.trim() + "%";
            args.add(value); args.add(value); args.add(value);
        }
        if (categoryId != null) { where.append(" AND p.category_id=?"); args.add(categoryId); }
        if (minPrice != null) { where.append(" AND k.price>=?"); args.add(minPrice); }
        if (maxPrice != null) { where.append(" AND k.price<=?"); args.add(maxPrice); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pro_product p JOIN pro_sku k ON k.product_id=p.id" + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        String sql = """
            SELECT p.id product_id, p.product_code, p.product_name, p.category_id, p.main_image,
                   p.unit, k.id sku_id, k.sku_code, k.spec_values, k.price, k.barcode,
                   COALESCE(SUM(s.quantity-s.locked_quantity),0)::int available_stock,
                   COALESCE((SELECT SUM(oi.quantity) FROM ord_order_item oi JOIN ord_order o ON o.id=oi.order_id
                             WHERE oi.sku_id=k.id AND o.status IN (1,2,3,5)),0)::int sales_count
            FROM pro_product p JOIN pro_sku k ON k.product_id=p.id
            LEFT JOIN sto_stock s ON s.sku_id=k.id AND s.deleted=0
            """ + where + " GROUP BY p.id,k.id ORDER BY " + order + ", k.id LIMIT ? OFFSET ?";
        List<Map<String, Object>> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", rs.getLong("product_id"));
            row.put("productCode", rs.getString("product_code"));
            row.put("productName", rs.getString("product_name"));
            row.put("categoryId", rs.getLong("category_id"));
            row.put("mainImage", rs.getString("main_image"));
            row.put("unit", rs.getString("unit"));
            row.put("skuId", rs.getLong("sku_id"));
            row.put("skuCode", rs.getString("sku_code"));
            row.put("specValues", parseJsonMap(rs.getString("spec_values")));
            row.put("price", rs.getBigDecimal("price"));
            row.put("barcode", rs.getString("barcode"));
            row.put("stockQuantity", rs.getInt("available_stock"));
            row.put("sales", rs.getInt("sales_count"));
            return row;
        }, pageArgs.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    @Transactional
    public Map<String, Object> addCart(Long skuId, int quantity) {
        requireActiveSku(skuId);
        int available = availableStock(skuId);
        if (available < quantity) {
            throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH);
        }
        Long userId = currentUser.requireUserId();
        jdbcTemplate.update("""
            INSERT INTO crm_cart(user_id, sku_id, quantity)
            VALUES (?, ?, ?)
            ON CONFLICT(user_id, sku_id) DO UPDATE SET quantity=crm_cart.quantity+EXCLUDED.quantity,
                selected=TRUE, update_time=CURRENT_TIMESTAMP
            """, userId, skuId, quantity);
        Integer cartQuantity = jdbcTemplate.queryForObject("SELECT quantity FROM crm_cart WHERE user_id=? AND sku_id=?",
            Integer.class, userId, skuId);
        if (cartQuantity != null && cartQuantity > available) {
            throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH);
        }
        return cartList().stream().filter(item -> skuId.equals(item.get("skuId"))).findFirst().orElseThrow();
    }

    public List<Map<String, Object>> cartList() {
        return jdbcTemplate.query("""
            SELECT c.id, c.sku_id, k.sku_code, p.product_name, k.spec_values, p.main_image,
                   k.price, c.quantity, c.selected,
                   COALESCE(SUM(s.quantity-s.locked_quantity),0)::int stock_quantity
            FROM crm_cart c JOIN pro_sku k ON k.id=c.sku_id JOIN pro_product p ON p.id=k.product_id
            LEFT JOIN sto_stock s ON s.sku_id=k.id AND s.deleted=0
            WHERE c.user_id=? GROUP BY c.id,k.id,p.id ORDER BY c.create_time DESC,c.id DESC
            """, this::cartRow, currentUser.requireUserId());
    }

    @Transactional
    public Map<String, Object> updateCart(Long cartItemId, int quantity) {
        int changed = jdbcTemplate.update("UPDATE crm_cart SET quantity=?, update_time=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
            quantity, cartItemId, currentUser.requireUserId());
        if (changed == 0) throw new BusinessException(ApiErrorCode.NOT_FOUND, "购物车项不存在");
        Long skuId = jdbcTemplate.queryForObject("SELECT sku_id FROM crm_cart WHERE id=?", Long.class, cartItemId);
        if (availableStock(skuId) < quantity) throw new BusinessException(ApiErrorCode.STOCK_NOT_ENOUGH);
        return cartList().stream().filter(item -> cartItemId.equals(item.get("cartItemId"))).findFirst().orElseThrow();
    }

    public void deleteCart(Long cartItemId) {
        if (jdbcTemplate.update("DELETE FROM crm_cart WHERE id=? AND user_id=?", cartItemId, currentUser.requireUserId()) == 0) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "购物车项不存在");
        }
    }

    @Transactional
    public Map<String, Object> createOrder(Long addressId, List<Long> cartItemIds, String remark, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        Long userId = currentUser.requireUserId();
        List<Map<String, Object>> existing = jdbcTemplate.query("SELECT * FROM ord_order WHERE user_id=? AND idempotency_key=?",
            this::orderSummaryRow, userId, idempotencyKey);
        if (!existing.isEmpty()) return existing.getFirst();
        if (cartItemIds == null || cartItemIds.isEmpty()) throw new BusinessException(ApiErrorCode.BAD_REQUEST, "购物车项不能为空");

        Map<String, Object> address = requireAddress(addressId, userId);
        Long customerId = ((Number) address.get("customerId")).longValue();
        String placeholders = String.join(",", cartItemIds.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(userId); args.addAll(cartItemIds);
        List<CartOrderItem> items = jdbcTemplate.query("""
            SELECT c.id cart_id,c.sku_id,c.quantity,k.sku_code,k.spec_values,k.price,
                   p.product_name,p.main_image,p.status product_status,k.status sku_status
            FROM crm_cart c JOIN pro_sku k ON k.id=c.sku_id JOIN pro_product p ON p.id=k.product_id
            WHERE c.user_id=? AND c.id IN (
            """ + placeholders + ") ORDER BY c.id", (rs, rowNum) -> new CartOrderItem(rs.getLong("cart_id"), rs.getLong("sku_id"),
            rs.getInt("quantity"), rs.getString("sku_code"), rs.getString("spec_values"), rs.getBigDecimal("price"),
            rs.getString("product_name"), rs.getString("main_image"), rs.getInt("product_status"), rs.getInt("sku_status")), args.toArray());
        if (items.size() != cartItemIds.size()) throw new BusinessException(ApiErrorCode.BAD_REQUEST, "购物车项不存在或不属于当前用户");
        if (items.stream().anyMatch(item -> item.productStatus()!=0 || item.skuStatus()!=0)) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "购物车中包含已下架商品");
        }
        BigDecimal total = items.stream().map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String orderNo = businessNo("ORD");
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(configInt("sys.order.auto.cancel.minutes", 30));
        Long orderId;
        try {
            orderId = jdbcTemplate.queryForObject("""
                INSERT INTO ord_order(order_no,user_id,customer_id,address_id,address_snapshot,total_amount,pay_amount,
                                      status,remark,idempotency_key,expire_time)
                VALUES (?,?,?,?,CAST(? AS jsonb),?,?,0,?,?,?) RETURNING id
                """, Long.class, orderNo, userId, customerId, addressId, json(address), total, total, remark, idempotencyKey, expireTime);
        } catch (DuplicateKeyException ex) {
            return jdbcTemplate.queryForObject("SELECT * FROM ord_order WHERE user_id=? AND idempotency_key=?", this::orderSummaryRow, userId, idempotencyKey);
        }
        // v1.2 §10.1.0 整单批量预占（DB 行级锁 + sto_stock_reservation 持久化记录）
        List<StockReservationItem> reservationItems = new ArrayList<>();
        for (CartOrderItem item : items) {
            reservationItems.add(new StockReservationItem(item.skuId(), item.quantity(), null));
        }
        String reservationRequestId = idempotencyKey; // 客户端 Idempotency-Key 同时作为预占请求号
        StockReservationResponse reservation;
        try {
            reservation = stockReservationService.reserveAll(
                new StockReservationRequest(reservationRequestId, orderNo, expireTime, reservationItems));
        } catch (BusinessException ex) {
            throw ex;
        }
        // 把预占分配写到 ord_order_item
        List<StockReservationResponse.Line> lines = reservation.items();
        int idx = 0;
        for (CartOrderItem item : items) {
            StockReservationResponse.Line line = lines.get(idx++);
            jdbcTemplate.update("""
                INSERT INTO ord_order_item(order_id,sku_id,sku_code,product_name,spec_values,main_image,price,quantity,subtotal,warehouse_id,location_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, orderId, item.skuId(), item.skuCode(), item.productName(), item.specValues(), item.mainImage(),
                item.price(), item.quantity(), item.price().multiply(BigDecimal.valueOf(item.quantity())),
                line.warehouseId(), line.locationId());
        }
        // 记录 reservation_id（v1.2 §7.3）
        jdbcTemplate.update("UPDATE ord_order SET reservation_id=? WHERE id=?", reservationRequestId, orderId);
        jdbcTemplate.update("DELETE FROM crm_cart WHERE user_id=? AND id IN (" + placeholders + ")", args.toArray());
        addTimeline(orderId, "提交订单");
        addOutbox("sales.order.created", "ORDER_CREATED", orderId, Map.of("orderId", orderId, "orderNo", orderNo,
            "reservationId", reservationRequestId));
        redisTemplate.opsForValue().set("order:idem:" + userId + ":" + idempotencyKey,
            String.valueOf(orderId), Duration.ofDays(1));
        return jdbcTemplate.queryForObject("SELECT * FROM ord_order WHERE id=?", this::orderSummaryRow, orderId);
    }

    @Transactional
    public Map<String, Object> pay(Long orderId, Integer payType) {
        if (payType == null) {
            throw new BusinessException(ApiErrorCode.BAD_REQUEST, "payType 不能为空");
        }
        // v1.2 §7.4.2 兼容旧入口：内部等价于 POST /payment/prepay
        com.example.demo.vo.PaymentResponse prepay = paymentService.prepay(orderId,
            new PaymentPrepayRequest(payType, null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("payNo", prepay.payNo());
        result.put("payStatus", 0);
        result.put("expireTime", prepay.expireTime());
        result.put("mockPayUrl", prepay.mockPayUrl());
        return result;
    }

    public Map<String, Object> detail(Long orderId) {
        Long userId = currentUser.requireUserId();
        List<Map<String, Object>> orders = jdbcTemplate.query("SELECT * FROM ord_order WHERE id=? AND user_id=?", this::orderDetailRow, orderId, userId);
        if (orders.isEmpty()) throw new BusinessException(ApiErrorCode.NOT_FOUND, "订单不存在");
        Map<String, Object> result = orders.getFirst();
        result.put("items", jdbcTemplate.query("SELECT * FROM ord_order_item WHERE order_id=? ORDER BY id", this::orderItemRow, orderId));
        result.put("timeline", jdbcTemplate.query("SELECT event_time,event FROM ord_order_timeline WHERE order_id=? ORDER BY event_time,id",
            (rs, rowNum) -> Map.of("time", rs.getTimestamp("event_time").toLocalDateTime(), "event", rs.getString("event")), orderId));
        return result;
    }

    public Map<String, Object> myOrders(Integer status, int pageNum, int pageSize) {
        Long userId = currentUser.requireUserId();
        String condition = status == null ? "" : " AND status=?";
        List<Object> args = new ArrayList<>(); args.add(userId); if (status != null) args.add(status);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_id=?" + condition, Long.class, args.toArray());
        args.add(pageSize); args.add((pageNum-1)*pageSize);
        List<Map<String,Object>> list = jdbcTemplate.query("SELECT * FROM ord_order WHERE user_id=?" + condition + " ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",
            this::orderSummaryRow, args.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    public Map<String, Object> merchantOrders(Integer status, String orderNo, String username,
                                              String customerKeyword, LocalDateTime startDate,
                                              LocalDateTime endDate, int pageNum, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND o.status=?");
            args.add(status);
        }
        if (StringUtils.hasText(orderNo)) {
            where.append(" AND o.order_no ILIKE ?");
            args.add("%" + orderNo.trim() + "%");
        }
        if (StringUtils.hasText(username)) {
            where.append(" AND u.username ILIKE ?");
            args.add("%" + username.trim() + "%");
        }
        if (StringUtils.hasText(customerKeyword)) {
            where.append("""
                 AND (u.username ILIKE ? OR u.nickname ILIKE ? OR u.phone ILIKE ?
                      OR c.nickname ILIKE ? OR c.phone ILIKE ?
                      OR o.address_snapshot->>'receiverName' ILIKE ?
                      OR o.address_snapshot->>'receiverPhone' ILIKE ?)
                """);
            String value = "%" + customerKeyword.trim() + "%";
            for (int i = 0; i < 7; i++) {
                args.add(value);
            }
        }
        if (startDate != null) {
            where.append(" AND o.create_time >= ?");
            args.add(startDate);
        }
        if (endDate != null) {
            where.append(" AND o.create_time < ?");
            args.add(endDate);
        }
        String from = """
            FROM ord_order o
            JOIN t_user u ON u.id=o.user_id
            LEFT JOIN crm_customer c ON c.id=o.customer_id
            """;
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + from + where,
            Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNum - 1) * pageSize);
        List<Map<String, Object>> list = jdbcTemplate.query("""
            SELECT o.id, o.order_no, o.user_id, u.username, o.customer_id,
                   COALESCE(c.nickname, u.nickname) customer_name,
                   COALESCE(c.phone, u.phone) customer_phone,
                   o.total_amount, o.discount_amount, o.freight, o.pay_amount,
                   o.status, o.has_partial_aftersale, o.remark, o.cancel_reason,
                   o.expire_time, o.pay_time, o.ship_time, o.receive_time,
                   o.create_time, o.update_time,
                   COALESCE(items.item_count, 0) item_count,
                   COALESCE(items.total_quantity, 0) total_quantity,
                   items.first_product_name, items.first_image
            """ + from + """
            LEFT JOIN LATERAL (
                SELECT COUNT(*)::int item_count,
                       COALESCE(SUM(oi.quantity), 0)::int total_quantity,
                       (ARRAY_AGG(oi.product_name ORDER BY oi.id))[1] first_product_name,
                       (ARRAY_AGG(oi.main_image ORDER BY oi.id))[1] first_image
                FROM ord_order_item oi
                WHERE oi.order_id=o.id
            ) items ON TRUE
            """ + where + " ORDER BY o.create_time DESC, o.id DESC LIMIT ? OFFSET ?",
            this::merchantOrderRow, pageArgs.toArray());
        return page(total == null ? 0 : total, pageNum, pageSize, list);
    }

    @Transactional
    public void cancel(Long orderId, String reason) {
        OrderHeader order = requireOwnedOrderForUpdate(orderId);
        if (order.status()!=0) throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "只有待支付订单可以取消");
        // v1.2 §10.1：取消时按 reservation_id 释放预占
        List<String> reservations = jdbcTemplate.queryForList(
            "SELECT reservation_id FROM ord_order WHERE id=? AND reservation_id IS NOT NULL",
            String.class, orderId);
        for (String reservationId : reservations) {
            try { stockReservationService.cancel(reservationId); }
            catch (Exception ignored) { /* 单条失败不影响主流程 */ }
        }
        if (reservations.isEmpty()) {
            releaseOrderLocks(orderId, order, "order-cancel:");
        }
        jdbcTemplate.update("UPDATE ord_order SET status=4,cancel_reason=?,update_time=CURRENT_TIMESTAMP WHERE id=?", reason, orderId);
        addTimeline(orderId, "订单取消");
        addOutbox("sales.order.cancelled", "ORDER_CANCELLED", orderId, Map.of("orderId", orderId, "orderNo", order.orderNo()));
    }

    @Transactional
    public void ship(Long orderId, String company, String logisticsNo) {
        OrderHeader order = requireOrderForUpdate(orderId);
        if (order.status()!=1) throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "只有已支付订单可以发货");
        jdbcTemplate.update("INSERT INTO ord_shipping(order_id,logistics_company,logistics_no) VALUES (?,?,?)", orderId, company, logisticsNo);
        jdbcTemplate.update("UPDATE ord_order SET status=2,ship_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", orderId);
        addTimeline(orderId, "订单发货");
        addOutbox("sales.order.shipped", "ORDER", orderId, Map.of("orderId", orderId, "orderNo", order.orderNo()));
    }

    @Transactional
    public void receive(Long orderId) {
        OrderHeader order = requireOwnedOrderForUpdate(orderId);
        if (order.status()!=2) throw new BusinessException(ApiErrorCode.ORDER_STATUS_INVALID, "只有已发货订单可以确认收货");
        jdbcTemplate.update("UPDATE ord_order SET status=3,receive_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", orderId);
        addTimeline(orderId, "确认收货");
        addOutbox("sales.order.received", "ORDER", orderId, Map.of("orderId", orderId, "orderNo", order.orderNo()));
    }

    @Transactional
    public Map<String,Object> aftersale(Long orderId, Long orderItemId, Integer type, String reason,
                                        List<String> images, String remark, String idempotencyKey) {
        // v1.2 §7.9：售后申请先落 ord_aftersale，商家审核后再生成退款单。
        AftersaleApplyRequest request = new AftersaleApplyRequest(
            orderId, orderItemId, type, reason, images, null, null, remark);
        com.example.demo.vo.AftersaleResponse aftersale = aftersaleService.apply(request, idempotencyKey);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("aftersaleId", aftersale.aftersaleId());
        response.put("aftersaleNo", aftersale.aftersaleNo());
        response.put("status", aftersale.status());
        return response;
    }

    @Scheduled(fixedDelayString = "${app.order.expire-scan-delay-millis:60000}")
    @Transactional
    public void cancelExpiredOrders() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM ord_order WHERE status=0 AND expire_time<CURRENT_TIMESTAMP ORDER BY id LIMIT 100", Long.class);
        for (Long id : ids) {
            OrderHeader order = requireOrderForUpdate(id);
            if (order.status()==0) {
                // 释放预占（按 reservation_id）
                List<String> reservations = jdbcTemplate.queryForList(
                    "SELECT reservation_id FROM ord_order WHERE id=? AND reservation_id IS NOT NULL",
                    String.class, id);
                for (String reservationId : reservations) {
                    try { stockReservationService.cancel(reservationId); }
                    catch (Exception ignored) { }
                }
                if (reservations.isEmpty()) {
                    releaseOrderLocks(id, order, "order-expire:");
                }
                jdbcTemplate.update("UPDATE ord_order SET status=4,cancel_reason='支付超时自动取消',update_time=CURRENT_TIMESTAMP WHERE id=?", id);
                addTimeline(id, "支付超时自动取消");
                addOutbox("sales.order.cancelled", "ORDER_CANCELLED", id, Map.of("orderId",id,"orderNo",order.orderNo()));
            }
        }
    }

    private void releaseOrderLocks(Long orderId, OrderHeader order, String prefix) {
        int index=0;
        for (OrderStockItem item: orderStockItems(orderId)) {
            stockMutationService.adjust(item.skuId(),item.warehouseId(),item.locationId(),0,-item.quantity(),2,
                order.orderNo(),prefix+orderId+":"+index++,"系统",null,"释放订单锁定库存");
        }
    }

    private List<OrderStockItem> orderStockItems(Long orderId) {
        return jdbcTemplate.query("SELECT sku_id,warehouse_id,location_id,quantity FROM ord_order_item WHERE order_id=?",
            (rs,rowNum)->new OrderStockItem(rs.getLong("sku_id"),rs.getLong("warehouse_id"),rs.getLong("location_id"),rs.getInt("quantity")),orderId);
    }

    private OrderHeader requireOwnedOrderForUpdate(Long orderId) {
        OrderHeader order=requireOrderForUpdate(orderId);
        if (!order.userId().equals(currentUser.requireUserId())) throw new BusinessException(ApiErrorCode.FORBIDDEN,"无权操作该订单");
        return order;
    }

    private OrderHeader requireOrderForUpdate(Long orderId) {
        List<OrderHeader> rows=jdbcTemplate.query("SELECT order_no,user_id,pay_amount,status,expire_time FROM ord_order WHERE id=? FOR UPDATE",
            (rs,rowNum)->new OrderHeader(rs.getString("order_no"),rs.getLong("user_id"),rs.getBigDecimal("pay_amount"),rs.getInt("status"),rs.getTimestamp("expire_time").toLocalDateTime()),orderId);
        if (rows.isEmpty()) throw new BusinessException(ApiErrorCode.NOT_FOUND,"订单不存在");
        return rows.getFirst();
    }

    private Map<String,Object> requireAddress(Long addressId,Long userId) {
        List<Map<String,Object>> rows=jdbcTemplate.query("""
            SELECT a.id, a.customer_id, a.receiver_name, a.receiver_phone, a.province, a.city, a.district, a.detail_address
            FROM crm_address a
            JOIN crm_customer c ON c.id = a.customer_id
            WHERE a.id=? AND c.user_id=? AND a.deleted=0 AND c.deleted=0
            """,(rs,rowNum)->{
            Map<String,Object> map=new LinkedHashMap<>(); map.put("customerId",rs.getLong("customer_id")); map.put("receiverName",rs.getString("receiver_name")); map.put("receiverPhone",rs.getString("receiver_phone"));
            map.put("province",rs.getString("province")); map.put("city",rs.getString("city")); map.put("district",rs.getString("district")); map.put("detailAddress",rs.getString("detail_address"));
            map.put("fullAddress",rs.getString("province")+rs.getString("city")+rs.getString("district")+rs.getString("detail_address")); return map;
        },addressId,userId);
        if(rows.isEmpty()) throw new BusinessException(ApiErrorCode.NOT_FOUND,"收货地址不存在"); return rows.getFirst();
    }

    private void requireActiveSku(Long skuId) {
        Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pro_sku k JOIN pro_product p ON p.id=k.product_id WHERE k.id=? AND k.status=0 AND k.deleted=0 AND p.status=0 AND p.deleted=0",Integer.class,skuId);
        if(count==null||count==0) throw new BusinessException(ApiErrorCode.NOT_FOUND,"SKU不存在或已下架");
    }
    private int availableStock(Long skuId){Integer value=jdbcTemplate.queryForObject("SELECT COALESCE(SUM(quantity-locked_quantity),0)::int FROM sto_stock WHERE sku_id=? AND deleted=0",Integer.class,skuId);return value==null?0:value;}
    private int configInt(String key,int fallback){List<String> values=jdbcTemplate.queryForList("SELECT config_value FROM sys_config WHERE config_key=? AND status=0",String.class,key);try{return values.isEmpty()?fallback:Integer.parseInt(values.getFirst());}catch(NumberFormatException ex){return fallback;}}
    private void addTimeline(Long orderId,String event){jdbcTemplate.update("INSERT INTO ord_order_timeline(order_id,event) VALUES (?,?)",orderId,event);}
    private void addOutbox(String routing,String type,Long id,Object payload){String eventId=UUID.randomUUID().toString();Map<String,Object> body=new LinkedHashMap<>();body.put("eventId",eventId);if(payload instanceof Map<?,?> map){map.forEach((key,value)->body.put(String.valueOf(key),value));}else{body.put("data",payload);}jdbcTemplate.update("INSERT INTO sys_outbox_event(event_id,routing_key,aggregate_type,aggregate_id,payload) VALUES (?,?,?,?,CAST(? AS jsonb))",eventId,routing,type,String.valueOf(id),json(body));}
    private String businessNo(String prefix){return prefix+ LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception ex){throw new BusinessException(ApiErrorCode.INTERNAL_SERVER_ERROR,"数据序列化失败");}}
    private Map<String,Object> parseJsonMap(String value){if(!StringUtils.hasText(value))return Map.of();try{return objectMapper.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ex){return Map.of("raw",value);}}

    private Map<String,Object> cartRow(ResultSet rs,int rowNum)throws SQLException{Map<String,Object> row=new LinkedHashMap<>();row.put("cartItemId",rs.getLong("id"));row.put("skuId",rs.getLong("sku_id"));row.put("skuCode",rs.getString("sku_code"));row.put("productName",rs.getString("product_name"));row.put("specValues",parseJsonMap(rs.getString("spec_values")));row.put("mainImage",rs.getString("main_image"));row.put("price",rs.getBigDecimal("price"));row.put("quantity",rs.getInt("quantity"));row.put("stockQuantity",rs.getInt("stock_quantity"));row.put("selected",rs.getBoolean("selected"));return row;}
    private Map<String,Object> orderSummaryRow(ResultSet rs,int rowNum)throws SQLException{Map<String,Object> row=new LinkedHashMap<>();row.put("orderId",rs.getLong("id"));row.put("orderNo",rs.getString("order_no"));row.put("totalAmount",rs.getBigDecimal("total_amount"));row.put("payAmount",rs.getBigDecimal("pay_amount"));row.put("discountAmount",rs.getBigDecimal("discount_amount"));row.put("freight",rs.getBigDecimal("freight"));row.put("status",rs.getInt("status"));row.put("statusText",statusText(rs.getInt("status")));row.put("expireTime",rs.getTimestamp("expire_time").toLocalDateTime());row.put("createTime",rs.getTimestamp("create_time").toLocalDateTime());return row;}
    private Map<String,Object> orderDetailRow(ResultSet rs,int rowNum)throws SQLException{Map<String,Object> row=orderSummaryRow(rs,rowNum);row.put("payTime",rs.getTimestamp("pay_time")==null?null:rs.getTimestamp("pay_time").toLocalDateTime());try{row.put("address",objectMapper.readValue(rs.getString("address_snapshot"),new TypeReference<LinkedHashMap<String,Object>>(){}));}catch(Exception ex){row.put("address",Map.of());}return row;}
    private Map<String,Object> orderItemRow(ResultSet rs,int rowNum)throws SQLException{Map<String,Object> row=new LinkedHashMap<>();row.put("orderItemId",rs.getLong("id"));row.put("skuId",rs.getLong("sku_id"));row.put("skuCode",rs.getString("sku_code"));row.put("productName",rs.getString("product_name"));row.put("specValues",parseJsonMap(rs.getString("spec_values")));row.put("mainImage",rs.getString("main_image"));row.put("price",rs.getBigDecimal("price"));row.put("quantity",rs.getInt("quantity"));row.put("subtotal",rs.getBigDecimal("subtotal"));return row;}
    private Map<String,Object> merchantOrderRow(ResultSet rs,int rowNum)throws SQLException{Map<String,Object> row=new LinkedHashMap<>();row.put("orderId",rs.getLong("id"));row.put("orderNo",rs.getString("order_no"));row.put("userId",rs.getLong("user_id"));row.put("username",rs.getString("username"));row.put("customerId",rs.getLong("customer_id"));row.put("customerName",rs.getString("customer_name"));row.put("customerPhone",rs.getString("customer_phone"));row.put("totalAmount",rs.getBigDecimal("total_amount"));row.put("payAmount",rs.getBigDecimal("pay_amount"));row.put("discountAmount",rs.getBigDecimal("discount_amount"));row.put("freight",rs.getBigDecimal("freight"));row.put("status",rs.getInt("status"));row.put("statusText",statusText(rs.getInt("status")));row.put("hasPartialAftersale",rs.getBoolean("has_partial_aftersale"));row.put("remark",rs.getString("remark"));row.put("cancelReason",rs.getString("cancel_reason"));row.put("itemCount",rs.getInt("item_count"));row.put("totalQuantity",rs.getInt("total_quantity"));row.put("firstProductName",rs.getString("first_product_name"));row.put("firstImage",rs.getString("first_image"));row.put("expireTime",rs.getTimestamp("expire_time").toLocalDateTime());row.put("payTime",rs.getTimestamp("pay_time")==null?null:rs.getTimestamp("pay_time").toLocalDateTime());row.put("shipTime",rs.getTimestamp("ship_time")==null?null:rs.getTimestamp("ship_time").toLocalDateTime());row.put("receiveTime",rs.getTimestamp("receive_time")==null?null:rs.getTimestamp("receive_time").toLocalDateTime());row.put("createTime",rs.getTimestamp("create_time").toLocalDateTime());row.put("updateTime",rs.getTimestamp("update_time").toLocalDateTime());return row;}
    private String statusText(int status){return switch(status){case 0->"待支付";case 1->"已支付/待发货";case 2->"已发货";case 3->"已完成";case 4->"已取消";case 5->"售后处理中";default->"未知";};}
    private Map<String,Object> page(long total,int pageNum,int pageSize,List<?> list){Map<String,Object> row=new LinkedHashMap<>();row.put("total",total);row.put("pageNum",pageNum);row.put("pageSize",pageSize);row.put("pages",(total+pageSize-1)/pageSize);row.put("list",list);return row;}

    private record CartOrderItem(Long cartId,Long skuId,int quantity,String skuCode,String specValues,BigDecimal price,String productName,String mainImage,int productStatus,int skuStatus){}
    private record OrderHeader(String orderNo,Long userId,BigDecimal payAmount,int status,LocalDateTime expireTime){}
    private record OrderStockItem(Long skuId,Long warehouseId,Long locationId,int quantity){}
}
