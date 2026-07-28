INSERT INTO t_user (id, username, password, nickname, avatar, phone, email, age, status) VALUES
    (1, 'admin', '$2b$10$qAgiYvWtXPTzev4N6nk1kucZfJRseRX1gWNeWbjvzY9DhttNA3UHi', '管理员', '/avatar/admin.png', '13800138000', 'admin@example.com', 30, 0),
    (2, 'alice', '$2b$10$eYGJBsKUpy7P5nkLSFp8Mu7pKC51YeEtZh9njZRwa2WWg1Kn6ZpRG', 'Alice', '/avatar/default.png', '13800138001', '[email protected]', 28, 0),
    (3, 'bob', '$2b$10$dBVv6p/PPsj2Z5XP0rHHyuqcO25ZPxTFiljy1DH9UNja5s8DTX4Ce', 'Bob', '/avatar/default.png', '13800138002', '[email protected]', 32, 0),
    (4, 'buyer', '$2b$10$qAgiYvWtXPTzev4N6nk1kucZfJRseRX1gWNeWbjvzY9DhttNA3UHi', '采购员', '/avatar/default.png', '13800138003', 'buyer@example.com', 29, 0),
    (5, 'keeper', '$2b$10$qAgiYvWtXPTzev4N6nk1kucZfJRseRX1gWNeWbjvzY9DhttNA3UHi', '仓管员', '/avatar/default.png', '13800138004', 'keeper@example.com', 31, 0),
    (6, 'seller', '$2b$10$qAgiYvWtXPTzev4N6nk1kucZfJRseRX1gWNeWbjvzY9DhttNA3UHi', '销售员', '/avatar/default.png', '13800138005', 'seller@example.com', 27, 0);

INSERT INTO sys_role (id, role_name, role_key, role_sort, status) VALUES
    (1, '管理员', 'admin', 1, 0),
    (2, '采购员', 'buyer', 2, 0),
    (3, '仓管员', 'keeper', 3, 0),
    (4, '销售员', 'seller', 4, 0),
    (5, '普通用户', 'user', 5, 0);

INSERT INTO sys_user_role (user_id, role_id) VALUES
    (1, 1),
    (2, 5),
    (3, 5),
    (4, 2),
    (5, 3),
    (6, 4);

INSERT INTO sys_menu (id, parent_id, menu_name, path, icon, permission, menu_type, menu_sort, status) VALUES
    (1, 0, '系统管理', '/system', 'settings', 'system:*:*', 'M', 1, 0),
    (2, 0, '商品管理', '/products', 'package', 'product:*:*', 'M', 2, 0),
    (3, 0, '库存管理', '/inventory', 'warehouse', 'inventory:*:*', 'M', 3, 0),
    (4, 0, '采购管理', '/purchase', 'shopping-cart', 'purchase:*:*', 'M', 4, 0),
    (5, 0, '订单管理', '/order', 'receipt', 'order:*:*', 'M', 5, 0),
    (11, 1, '用户管理', '/users', 'users', 'system:user:list', 'C', 1, 0),
    (12, 1, '角色管理', '/roles', 'shield', 'system:role:list', 'C', 2, 0),
    (21, 2, '商品列表', '/products', 'list', 'product:list', 'C', 1, 0),
    (31, 3, '实时库存', '/inventory', 'boxes', 'inventory:stock:list', 'C', 1, 0),
    (32, 3, '仓库库位', '/warehouses', 'warehouse', 'inventory:warehouse:list', 'C', 2, 0),
    (41, 4, '采购申请', '/purchase/request', 'file-plus', 'purchase:request:list', 'C', 1, 0),
    (51, 5, '我的订单', '/order/my', 'receipt-text', 'order:mine:list', 'C', 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 11), (1, 12), (1, 21), (1, 31), (1, 32), (1, 41), (1, 51),
    (5, 2), (5, 3), (5, 5), (5, 21), (5, 31), (5, 51);

INSERT INTO pro_category (id, category_name, parent_id, icon, sort_order, status) VALUES
    (1, '数码产品', 0, 'cpu', 1, 0),
    (2, '办公用品', 0, 'briefcase', 2, 0),
    (3, '食品饮料', 0, 'cup-soda', 3, 0),
    (11, '手机', 1, 'smartphone', 1, 0),
    (12, '电脑', 1, 'laptop', 2, 0),
    (21, '打印耗材', 2, 'printer', 1, 0),
    (22, '纸张', 2, 'file-text', 2, 0),
    (31, '饮用水', 3, 'droplets', 1, 0);

INSERT INTO pro_product (id, product_code, product_name, category_id, main_image, unit, weight, purchase_price, sale_price, description, status) VALUES
    (1001, 'SP001', 'iPhone 15 Pro', 11, '/images/products/iphone15pro.jpg', '台', 0.187, 7999.00, 8999.00, '高端智能手机，适合门店和线上销售。', 0),
    (1002, 'SP002', 'ThinkPad X1 Carbon', 12, '/images/products/thinkpad-x1.jpg', '台', 1.120, 9999.00, 11999.00, '轻薄商务笔记本。', 0),
    (1003, 'SP003', 'A4 复印纸 70g', 22, '/images/products/a4-paper.jpg', '箱', 12.500, 115.00, 138.00, '办公常用 A4 复印纸，每箱 5 包。', 0),
    (1004, 'SP004', '硒鼓 CC388A', 21, '/images/products/toner.jpg', '个', 0.820, 82.00, 119.00, '适配常见黑白激光打印机。', 0),
    (1005, 'SP005', '矿泉水 550ml*24', 31, '/images/products/water.jpg', '箱', 13.200, 28.00, 39.90, '日常消耗品。', 1);

INSERT INTO pro_sku (id, product_id, sku_code, spec_values, price, barcode, status) VALUES
    (2001, 1001, 'SP001-BLACK-256', '{"颜色":"黑色","存储":"256GB"}', 8999.00, '6901234567890', 0),
    (2002, 1001, 'SP001-WHITE-512', '{"颜色":"白色","存储":"512GB"}', 9999.00, '6901234567891', 0),
    (2003, 1002, 'SP002-I7-32G-1T', '{"CPU":"i7","内存":"32GB","硬盘":"1TB"}', 11999.00, '6901234567892', 0),
    (2004, 1003, 'SP003-A4-70G-5P', '{"规格":"A4","克重":"70g","包装":"5包/箱"}', 138.00, '6901234567893', 0),
    (2005, 1004, 'SP004-CC388A', '{"型号":"CC388A"}', 119.00, '6901234567894', 0),
    (2006, 1005, 'SP005-550ML-24', '{"容量":"550ml","包装":"24瓶/箱"}', 39.90, '6901234567895', 1);

INSERT INTO sto_warehouse (id, warehouse_code, warehouse_name, type, address, manager, capacity, used_capacity, status) VALUES
    (1, 'WH-A', 'A仓库', 1, '深圳市宝安区创新路 18 号', '王仓管', 10000, 6500, 0),
    (2, 'WH-B', 'B仓库', 2, '广州市黄埔区云埔一路 8 号', '李仓管', 8000, 4200, 0);

INSERT INTO sto_location (id, warehouse_id, parent_id, location_type, location_code, location_name, sort_order, status) VALUES
    (101, 1, 0, 1, 'A-A', 'A区', 1, 0),
    (102, 1, 0, 1, 'A-B', 'B区', 2, 0),
    (201, 1, 101, 2, 'A-A-01', '01排货架', 1, 0),
    (202, 1, 101, 2, 'A-A-02', '02排货架', 2, 0),
    (301, 1, 201, 3, 'A-A-01-01', '01层-01格', 1, 0),
    (302, 1, 201, 3, 'A-A-01-02', '01层-02格', 2, 0),
    (303, 1, 202, 3, 'A-A-02-01', '02层-01格', 1, 0),
    (401, 2, 0, 1, 'B-A', 'A区', 1, 0),
    (501, 2, 401, 2, 'B-A-01', '01排货架', 1, 0),
    (601, 2, 501, 3, 'B-A-01-01', '01层-01格', 1, 0);

INSERT INTO sto_stock (id, sku_id, warehouse_id, location_id, quantity, locked_quantity, batch_no) VALUES
    (1, 2001, 1, 301, 70, 3, 'BATCH20260701'),
    (2, 2001, 2, 601, 48, 0, 'BATCH20260702'),
    (3, 2002, 1, 302, 72, 2, 'BATCH20260703'),
    (4, 2003, 1, 303, 35, 1, 'BATCH20260704'),
    (5, 2004, 1, 302, 520, 20, 'BATCH20260705'),
    (6, 2005, 2, 601, 260, 8, 'BATCH20260706'),
    (7, 2006, 1, 303, 180, 0, 'BATCH20260707');

INSERT INTO sto_stock_log (id, sku_id, warehouse_id, location_id, type, quantity_change, before_qty, after_qty, source_no, operator, operate_time, remark) VALUES
    (7001, 2001, 1, 301, 1, 50, 20, 70, 'IB20260720001', '王仓管', '2026-07-20 14:30:00', '采购入库'),
    (7002, 2001, 2, 601, 1, 48, 0, 48, 'IB20260720002', '李仓管', '2026-07-20 15:10:00', '采购入库'),
    (7003, 2001, 1, 301, 2, -5, 75, 70, 'SO20260721001', '系统', '2026-07-21 16:00:00', '销售出库'),
    (7004, 2002, 1, 302, 1, 72, 0, 72, 'IB20260719001', '王仓管', '2026-07-19 11:00:00', '采购入库'),
    (7005, 2003, 1, 303, 1, 35, 0, 35, 'IB20260718001', '王仓管', '2026-07-18 10:15:00', '采购入库'),
    (7006, 2004, 1, 302, 1, 520, 0, 520, 'IB20260717001', '王仓管', '2026-07-17 09:20:00', '采购入库'),
    (7007, 2005, 2, 601, 1, 260, 0, 260, 'IB20260716001', '李仓管', '2026-07-16 13:45:00', '采购入库');

INSERT INTO pur_supplier (id, supplier_name, contact_person, phone, address, email, remark, status) VALUES
    (101, '深圳华为科技有限公司', '张经理', '0755-12345678', '深圳市龙岗区坂田街道', 'purchase@huawei.com', '核心供应商', 0),
    (102, '北京小米电子有限公司', '王经理', '010-87654321', '北京市海淀区', 'purchase@mi.com', NULL, 0);

INSERT INTO crm_customer (id, user_id, nickname, phone, email, level) VALUES
    (5001, 1, '管理员', '13800138000', 'admin@example.com', 'VIP'),
    (5002, 2, 'Alice', '13800138001', 'alice@example.com', 'NORMAL'),
    (5003, 3, 'Bob', '13800138002', 'bob@example.com', 'NORMAL'),
    (5004, 4, '采购员', '13800138003', 'buyer@example.com', 'NORMAL'),
    (5005, 5, '仓管员', '13800138004', 'keeper@example.com', 'NORMAL'),
    (5006, 6, '销售员', '13800138005', 'seller@example.com', 'NORMAL');

INSERT INTO crm_address (id, customer_id, receiver_name, receiver_phone, province, city, district, detail_address, is_default) VALUES
    (201, 5002, '李明', '13900139000', '广东省', '深圳市', '南山区', '科技园南路1号', TRUE);

INSERT INTO crm_product_favorite (id, customer_id, product_id, create_time) VALUES
    (1, 5002, 1002, '2026-07-22 10:00:00');

INSERT INTO crm_browse_history (id, customer_id, product_id, sku_id, product_name, main_image, view_count, last_view_time, create_time, update_time) VALUES
    (1, 5002, 1002, 2003, 'ThinkPad X1 Carbon', '/images/products/thinkpad-x1.jpg', 3, '2026-07-23 11:20:00', '2026-07-21 09:00:00', '2026-07-23 11:20:00');

INSERT INTO cs_ticket (id, ticket_no, customer_id, user_id, subject, category, status, priority, assigned_to, last_message, last_message_time, create_time, update_time) VALUES
    (1, 'CS202607230001', 5002, 2, 'Order delivery question', 'ORDER', 1, 1, 6, 'We are checking the logistics status.', '2026-07-23 14:20:00', '2026-07-23 14:00:00', '2026-07-23 14:20:00');

INSERT INTO cs_ticket_message (id, ticket_id, sender_user_id, sender_type, content, images, create_time) VALUES
    (1, 1, 2, 'CUSTOMER', 'When will my package arrive?', '[]', '2026-07-23 14:00:00'),
    (2, 1, 6, 'AGENT', 'We are checking the logistics status.', '[]', '2026-07-23 14:20:00');

INSERT INTO msg_notification (id, title, content, type, target_type, target_user_id, target_role_key, sender_id, biz_type, biz_id, status, publish_time, expire_time) VALUES
    (1, 'System maintenance', 'The service will be upgraded tonight.', 'SYSTEM', 'ALL', NULL, NULL, 1, 'SYSTEM', 'MAINT-20260723', 1, '2026-07-23 09:00:00', NULL),
    (2, 'Order reminder', 'Your order has a new status update.', 'ORDER', 'USER', 2, NULL, 1, 'ORDER', '10001', 1, '2026-07-23 10:00:00', NULL);

INSERT INTO sto_stock_alert_rule (id, sku_id, min_alert, max_alert, enabled) VALUES
    (1, 2001, 20, 500, TRUE),
    (2, 2002, 20, 500, TRUE),
    (3, 2003, 40, 300, TRUE),
    (4, 2004, 100, 500, TRUE),
    (5, 2005, 80, 500, TRUE),
    (6, 2006, 50, 400, TRUE);

INSERT INTO sys_dict_type (id, dict_type, dict_name, status) VALUES
    (1, 'sys_order_status', '订单状态', 0),
    (2, 'sys_pay_type', '支付方式', 0),
    (3, 'sys_warehouse_type', '仓库类型', 0),
    (4, 'sys_stock_alert_type', '库存预警类型', 0),
    (5, 'sys_aftersale_type', '售后类型', 0);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, css_class, sort_order, status) VALUES
    (1, 'sys_order_status', '待支付', '0', 'warning', 1, 0),
    (2, 'sys_order_status', '已支付', '1', 'primary', 2, 0),
    (3, 'sys_order_status', '已发货', '2', 'info', 3, 0),
    (4, 'sys_order_status', '已完成', '3', 'success', 4, 0),
    (5, 'sys_order_status', '已取消', '4', 'danger', 5, 0),
    (6, 'sys_order_status', '售后处理中', '5', 'warning', 6, 0),
    (7, 'sys_pay_type', '支付宝', '1', 'primary', 1, 0),
    (8, 'sys_pay_type', '微信', '2', 'success', 2, 0),
    (9, 'sys_pay_type', '余额', '3', 'info', 3, 0),
    (10, 'sys_warehouse_type', '主仓库', '1', 'primary', 1, 0),
    (11, 'sys_warehouse_type', '分仓库', '2', 'info', 2, 0),
    (12, 'sys_stock_alert_type', '低库存', 'LOW_STOCK', 'warning', 1, 0),
    (13, 'sys_stock_alert_type', '库存过高', 'OVER_STOCK', 'danger', 2, 0),
    (14, 'sys_aftersale_type', '退货退款', '1', 'warning', 1, 0),
    (15, 'sys_aftersale_type', '换货', '2', 'info', 2, 0);

INSERT INTO sys_config (id, config_key, config_name, config_value, status) VALUES
    (1, 'sys.order.auto.cancel.minutes', '订单自动取消时间(分钟)', '30', 0),
    (2, 'sys.stock.alert.low.threshold', '低库存预警阈值', '20', 0),
    (3, 'sys.pay.alipay.sandbox', '支付宝沙箱模式', 'true', 0),
    (4, 'sys.order.stock.lock.seconds', '订单/库存锁共用过期秒数', '1800', 0);

SELECT setval('t_user_id_seq', (SELECT MAX(id) FROM t_user));
SELECT setval('sys_role_id_seq', (SELECT MAX(id) FROM sys_role));
SELECT setval('sys_menu_id_seq', (SELECT MAX(id) FROM sys_menu));
SELECT setval('pro_category_id_seq', (SELECT MAX(id) FROM pro_category));
SELECT setval('pro_product_id_seq', (SELECT MAX(id) FROM pro_product));
SELECT setval('pro_sku_id_seq', (SELECT MAX(id) FROM pro_sku));
SELECT setval('sto_warehouse_id_seq', (SELECT MAX(id) FROM sto_warehouse));
SELECT setval('sto_location_id_seq', (SELECT MAX(id) FROM sto_location));
SELECT setval('sto_stock_id_seq', (SELECT MAX(id) FROM sto_stock));
SELECT setval('sto_stock_log_id_seq', (SELECT MAX(id) FROM sto_stock_log));
SELECT setval('pur_supplier_id_seq', (SELECT MAX(id) FROM pur_supplier));
SELECT setval('crm_customer_id_seq', (SELECT MAX(id) FROM crm_customer));
SELECT setval('crm_address_id_seq', (SELECT MAX(id) FROM crm_address));
SELECT setval('crm_product_favorite_id_seq', (SELECT MAX(id) FROM crm_product_favorite));
SELECT setval('crm_browse_history_id_seq', (SELECT MAX(id) FROM crm_browse_history));
SELECT setval('cs_ticket_id_seq', (SELECT MAX(id) FROM cs_ticket));
SELECT setval('cs_ticket_message_id_seq', (SELECT MAX(id) FROM cs_ticket_message));
SELECT setval('msg_notification_id_seq', (SELECT MAX(id) FROM msg_notification));
SELECT setval('msg_notification_read_id_seq', 1, FALSE);
SELECT setval('sto_stock_alert_rule_id_seq', (SELECT MAX(id) FROM sto_stock_alert_rule));
SELECT setval('sys_dict_type_id_seq', (SELECT MAX(id) FROM sys_dict_type));
SELECT setval('sys_dict_data_id_seq', (SELECT MAX(id) FROM sys_dict_data));
SELECT setval('sys_config_id_seq', (SELECT MAX(id) FROM sys_config));

INSERT INTO own_owner (id, owner_code, owner_name, contact, phone, status) VALUES
    (1, 'SELF', '自营', '总部', '0755-00000001', 0),
    (2, 'TP_A', '第三方商家A', '赵经理', '0755-00000002', 0);

INSERT INTO sto_owner_warehouse (id, owner_id, warehouse_id) VALUES
    (1, 1, 1),
    (2, 1, 2),
    (3, 2, 2);

INSERT INTO res_restock_rule (id, default_safety_stock_days, default_lead_time_days, sales_history_days, enable_auto_notify, notify_channels) VALUES
    (1, 7, 3, 30, TRUE, 'email,sms,system');

SELECT setval('own_owner_id_seq', (SELECT MAX(id) FROM own_owner));
SELECT setval('sto_owner_warehouse_id_seq', (SELECT MAX(id) FROM sto_owner_warehouse));
SELECT setval('res_restock_rule_id_seq', (SELECT MAX(id) FROM res_restock_rule));
