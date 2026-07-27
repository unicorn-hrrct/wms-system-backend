DROP TABLE IF EXISTS res_restock_rule CASCADE;
DROP TABLE IF EXISTS res_restock_suggestion CASCADE;
DROP TABLE IF EXISTS sto_owner_warehouse CASCADE;
DROP TABLE IF EXISTS own_owner CASCADE;
DROP TABLE IF EXISTS ord_message_log CASCADE;
DROP TABLE IF EXISTS mq_consume_log CASCADE;
DROP TABLE IF EXISTS sys_consumed_event CASCADE;
DROP TABLE IF EXISTS sys_outbox_event CASCADE;
DROP TABLE IF EXISTS sys_op_log CASCADE;
DROP TABLE IF EXISTS sys_dict_data CASCADE;
DROP TABLE IF EXISTS sys_dict_type CASCADE;
DROP TABLE IF EXISTS sys_config CASCADE;
DROP TABLE IF EXISTS ord_order_timeline CASCADE;
DROP TABLE IF EXISTS ord_shipping CASCADE;
DROP TABLE IF EXISTS ord_aftersale CASCADE;
DROP TABLE IF EXISTS ref_refund_item CASCADE;
DROP TABLE IF EXISTS ref_refund CASCADE;
DROP TABLE IF EXISTS pay_nonce CASCADE;
DROP TABLE IF EXISTS pay_payment CASCADE;
DROP TABLE IF EXISTS ord_order_item CASCADE;
DROP TABLE IF EXISTS ord_order CASCADE;
DROP TABLE IF EXISTS crm_cart CASCADE;
DROP TABLE IF EXISTS crm_address CASCADE;
DROP TABLE IF EXISTS crm_customer CASCADE;
DROP TABLE IF EXISTS sto_stock_reservation CASCADE;
DROP TABLE IF EXISTS sto_stock_transfer_item CASCADE;
DROP TABLE IF EXISTS sto_stock_transfer CASCADE;
DROP TABLE IF EXISTS sto_stock_check_item CASCADE;
DROP TABLE IF EXISTS sto_stock_check CASCADE;
DROP TABLE IF EXISTS sto_stock_alert_rule CASCADE;
DROP TABLE IF EXISTS pur_return_item CASCADE;
DROP TABLE IF EXISTS pur_return CASCADE;
DROP TABLE IF EXISTS pur_order_close_log CASCADE;
DROP TABLE IF EXISTS pur_inbound_item CASCADE;
DROP TABLE IF EXISTS pur_inbound CASCADE;
DROP TABLE IF EXISTS pur_order_item CASCADE;
DROP TABLE IF EXISTS pur_order CASCADE;
DROP TABLE IF EXISTS pur_request_item CASCADE;
DROP TABLE IF EXISTS pur_request CASCADE;
DROP TABLE IF EXISTS pur_supplier CASCADE;
DROP TABLE IF EXISTS sto_stock_log CASCADE;
DROP TABLE IF EXISTS sto_stock CASCADE;
DROP TABLE IF EXISTS sto_location CASCADE;
DROP TABLE IF EXISTS sto_warehouse CASCADE;
DROP TABLE IF EXISTS pro_sku CASCADE;
DROP TABLE IF EXISTS pro_product CASCADE;
DROP TABLE IF EXISTS pro_category CASCADE;
DROP TABLE IF EXISTS sys_role_menu CASCADE;
DROP TABLE IF EXISTS sys_user_role CASCADE;
DROP TABLE IF EXISTS sys_menu CASCADE;
DROP TABLE IF EXISTS sys_role CASCADE;
DROP TABLE IF EXISTS t_user CASCADE;

CREATE TABLE t_user (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    avatar      VARCHAR(255) DEFAULT '/avatar/default.png',
    phone       VARCHAR(20),
    email       VARCHAR(100),
    age         INT,
    status      INT          DEFAULT 0,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          DEFAULT 0
);

CREATE TABLE sys_role (
    id          BIGSERIAL PRIMARY KEY,
    role_name   VARCHAR(50) NOT NULL,
    role_key    VARCHAR(50) NOT NULL UNIQUE,
    role_sort   INT         DEFAULT 0,
    status      INT         DEFAULT 0,
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_menu (
    id          BIGSERIAL PRIMARY KEY,
    parent_id   BIGINT       DEFAULT 0,
    menu_name   VARCHAR(50)  NOT NULL,
    path        VARCHAR(255),
    icon        VARCHAR(50),
    permission  VARCHAR(100),
    menu_type   VARCHAR(10)  DEFAULT 'M',
    menu_sort   INT          DEFAULT 0,
    status      INT          DEFAULT 0,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    menu_id BIGINT NOT NULL REFERENCES sys_menu(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE pro_category (
    id                  BIGSERIAL PRIMARY KEY,
    category_name       VARCHAR(100) NOT NULL,
    parent_id           BIGINT       DEFAULT 0,
    icon                VARCHAR(50),
    sort_order          INT          DEFAULT 0,
    status              INT          DEFAULT 0,
    requires_batch_date INT          DEFAULT 0,
    create_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          DEFAULT 0
);

CREATE TABLE pro_product (
    id             BIGSERIAL PRIMARY KEY,
    product_code   VARCHAR(50)   NOT NULL UNIQUE,
    product_name   VARCHAR(150)  NOT NULL,
    category_id    BIGINT        NOT NULL REFERENCES pro_category(id),
    main_image     VARCHAR(255),
    unit           VARCHAR(20)   NOT NULL,
    weight         NUMERIC(10, 3),
    purchase_price NUMERIC(12, 2) NOT NULL,
    sale_price     NUMERIC(12, 2) NOT NULL,
    description    TEXT,
    status         INT           DEFAULT 0,
    create_time    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    deleted        INT           DEFAULT 0
);

CREATE TABLE pro_sku (
    id               BIGSERIAL PRIMARY KEY,
    product_id       BIGINT         NOT NULL REFERENCES pro_product(id) ON DELETE CASCADE,
    sku_code         VARCHAR(80)    NOT NULL UNIQUE,
    spec_values      TEXT,
    price            NUMERIC(12, 2) NOT NULL,
    barcode          VARCHAR(80),
    safety_stock     INT            DEFAULT 0,
    expiry_warn_days INT            DEFAULT 30,
    status           INT            DEFAULT 0,
    create_time      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    deleted     INT            DEFAULT 0
);

CREATE TABLE sto_warehouse (
    id             BIGSERIAL PRIMARY KEY,
    warehouse_code VARCHAR(50)  NOT NULL UNIQUE,
    warehouse_name VARCHAR(100) NOT NULL,
    type           INT          DEFAULT 1,
    address        VARCHAR(255),
    manager        VARCHAR(50),
    capacity       INT          DEFAULT 0,
    used_capacity  INT          DEFAULT 0,
    status         INT          DEFAULT 0,
    create_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sto_location (
    id            BIGSERIAL PRIMARY KEY,
    warehouse_id  BIGINT       NOT NULL REFERENCES sto_warehouse(id) ON DELETE CASCADE,
    parent_id     BIGINT       DEFAULT 0,
    location_type INT          NOT NULL,
    location_code VARCHAR(80)  NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    sort_order    INT          DEFAULT 0,
    status        INT          DEFAULT 0,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          DEFAULT 0
);

CREATE TABLE sto_stock (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT    NOT NULL REFERENCES pro_sku(id) ON DELETE CASCADE,
    warehouse_id    BIGINT    NOT NULL REFERENCES sto_warehouse(id),
    location_id     BIGINT    REFERENCES sto_location(id),
    quantity        INT       DEFAULT 0 CHECK (quantity >= 0),
    locked_quantity INT       DEFAULT 0 CHECK (locked_quantity >= 0 AND locked_quantity <= quantity),
    min_stock       INT       DEFAULT NULL,
    max_stock       INT       DEFAULT NULL,
    batch_no        VARCHAR(80),
    production_date DATE,
    expire_date     DATE,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         INT       DEFAULT 0,
    CONSTRAINT uq_stock UNIQUE (sku_id, warehouse_id, location_id)
);

CREATE TABLE sto_stock_log (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT       NOT NULL REFERENCES pro_sku(id),
    warehouse_id    BIGINT       NOT NULL REFERENCES sto_warehouse(id),
    location_id     BIGINT       REFERENCES sto_location(id),
    type            INT          NOT NULL,
    quantity_change INT          NOT NULL,
    before_qty      INT          NOT NULL,
    after_qty       INT          NOT NULL,
    source_no       VARCHAR(80),
    request_id      VARCHAR(120) UNIQUE,
    operator        VARCHAR(50),
    operate_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255)
);

CREATE INDEX idx_stock_sku ON sto_stock(sku_id);
CREATE INDEX idx_stock_log_sku_time ON sto_stock_log(sku_id, operate_time DESC);

CREATE TABLE pur_supplier (
    id             BIGSERIAL PRIMARY KEY,
    supplier_name  VARCHAR(150) NOT NULL UNIQUE,
    contact_person VARCHAR(50),
    phone           VARCHAR(30),
    address         VARCHAR(255),
    email           VARCHAR(100),
    remark          VARCHAR(500),
    status          INT DEFAULT 0,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE crm_customer (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL UNIQUE REFERENCES t_user(id) ON DELETE CASCADE,
    nickname     VARCHAR(50),
    phone        VARCHAR(20),
    email        VARCHAR(100),
    level        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    registered_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted      INT          DEFAULT 0
);

CREATE TABLE crm_address (
    id             BIGSERIAL PRIMARY KEY,
    customer_id    BIGINT NOT NULL REFERENCES crm_customer(id) ON DELETE CASCADE,
    receiver_name  VARCHAR(50) NOT NULL,
    receiver_phone VARCHAR(30) NOT NULL,
    province       VARCHAR(50) NOT NULL,
    city           VARCHAR(50) NOT NULL,
    district       VARCHAR(50) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    is_default     BOOLEAN DEFAULT FALSE,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        INT DEFAULT 0
);
CREATE UNIQUE INDEX uq_address_customer_default ON crm_address(customer_id) WHERE is_default = TRUE AND deleted = 0;

CREATE TABLE pur_request (
    id            BIGSERIAL PRIMARY KEY,
    request_no    VARCHAR(40) NOT NULL UNIQUE,
    supplier_id   BIGINT NOT NULL REFERENCES pur_supplier(id),
    applicant_id  BIGINT NOT NULL REFERENCES t_user(id),
    total_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    status        INT NOT NULL DEFAULT 0,
    remark        VARCHAR(500),
    auditor_id    BIGINT REFERENCES t_user(id),
    audit_remark  VARCHAR(500),
    audit_time    TIMESTAMP,
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pur_request_item (
    id             BIGSERIAL PRIMARY KEY,
    request_id     BIGINT NOT NULL REFERENCES pur_request(id) ON DELETE CASCADE,
    sku_id         BIGINT NOT NULL REFERENCES pro_sku(id),
    sku_code       VARCHAR(80) NOT NULL,
    quantity       INT NOT NULL CHECK (quantity > 0),
    expected_price NUMERIC(12,2) NOT NULL CHECK (expected_price >= 0),
    remark         VARCHAR(255)
);

CREATE TABLE pur_order (
    id             BIGSERIAL PRIMARY KEY,
    order_no       VARCHAR(40) NOT NULL UNIQUE,
    request_id     BIGINT NOT NULL UNIQUE REFERENCES pur_request(id),
    supplier_id    BIGINT NOT NULL REFERENCES pur_supplier(id),
    delivery_date  DATE,
    total_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    status         INT NOT NULL DEFAULT 0,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pur_order_item (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT NOT NULL REFERENCES pur_order(id) ON DELETE CASCADE,
    sku_id            BIGINT NOT NULL REFERENCES pro_sku(id),
    sku_code          VARCHAR(80) NOT NULL,
    quantity          INT NOT NULL CHECK (quantity > 0),
    inbound_quantity  INT NOT NULL DEFAULT 0 CHECK (inbound_quantity >= 0),
    returned_quantity INT NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
    discarded_quantity INT NOT NULL DEFAULT 0 CHECK (discarded_quantity >= 0),
    price             NUMERIC(12,2) NOT NULL
);

CREATE TABLE pur_inbound (
    id            BIGSERIAL PRIMARY KEY,
    inbound_no    VARCHAR(40) NOT NULL UNIQUE,
    order_id      BIGINT NOT NULL REFERENCES pur_order(id),
    warehouse_id  BIGINT NOT NULL REFERENCES sto_warehouse(id),
    status        INT NOT NULL DEFAULT 1,
    operator_id   BIGINT NOT NULL REFERENCES t_user(id),
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pur_inbound_item (
    id              BIGSERIAL PRIMARY KEY,
    inbound_id      BIGINT NOT NULL REFERENCES pur_inbound(id) ON DELETE CASCADE,
    order_item_id   BIGINT NOT NULL REFERENCES pur_order_item(id),
    sku_id          BIGINT NOT NULL REFERENCES pro_sku(id),
    actual_quantity INT NOT NULL CHECK (actual_quantity > 0),
    location_id     BIGINT NOT NULL REFERENCES sto_location(id),
    batch_no        VARCHAR(80) NOT NULL,
    production_date DATE,
    expire_date     DATE,
    remark          VARCHAR(255)
);

CREATE TABLE pur_order_close_log (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES pur_order(id),
    close_reason  VARCHAR(500) NOT NULL,
    close_type    INT NOT NULL DEFAULT 3,
    operator_id   BIGINT NOT NULL REFERENCES t_user(id),
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pur_return (
    id              BIGSERIAL PRIMARY KEY,
    return_no       VARCHAR(40) NOT NULL UNIQUE,
    order_id        BIGINT NOT NULL REFERENCES pur_order(id),
    reason          VARCHAR(500) NOT NULL,
    status          INT NOT NULL DEFAULT 0,
    applicant_id    BIGINT NOT NULL REFERENCES t_user(id),
    confirm_remark  VARCHAR(500),
    reject_reason   VARCHAR(500),
    locked_at       TIMESTAMP,
    confirm_time    TIMESTAMP,
    reject_time     TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pur_return_item (
    id         BIGSERIAL PRIMARY KEY,
    return_id  BIGINT NOT NULL REFERENCES pur_return(id) ON DELETE CASCADE,
    order_item_id BIGINT NOT NULL REFERENCES pur_order_item(id),
    sku_id     BIGINT NOT NULL REFERENCES pro_sku(id),
    quantity   INT NOT NULL CHECK (quantity > 0),
    warehouse_id BIGINT NOT NULL REFERENCES sto_warehouse(id),
    location_id BIGINT NOT NULL REFERENCES sto_location(id)
);

CREATE TABLE sto_stock_alert_rule (
    id        BIGSERIAL PRIMARY KEY,
    sku_id    BIGINT NOT NULL UNIQUE REFERENCES pro_sku(id) ON DELETE CASCADE,
    min_alert INT NOT NULL DEFAULT 20,
    max_alert INT NOT NULL DEFAULT 500,
    enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- v1.2 §10.1.0 整单批量预占：持久化预占记录，PG 为库存事实来源
CREATE TABLE sto_stock_reservation (
    id            BIGSERIAL PRIMARY KEY,
    reservation_id VARCHAR(64) NOT NULL UNIQUE,
    request_id    VARCHAR(120),
    order_no      VARCHAR(40),
    sku_id        BIGINT NOT NULL REFERENCES pro_sku(id),
    warehouse_id  BIGINT NOT NULL REFERENCES sto_warehouse(id),
    location_id   BIGINT NOT NULL REFERENCES sto_location(id),
    quantity      INT NOT NULL CHECK (quantity > 0),
    status        SMALLINT NOT NULL DEFAULT 0,  -- 0=RESERVED 1=CONFIRMED 2=RELEASED 3=EXPIRED 4=RESTOCK
    expires_at    TIMESTAMP,
    payload_hash  VARCHAR(64),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_reservation_status_expires ON sto_stock_reservation(status, expires_at);
CREATE INDEX idx_reservation_order_no ON sto_stock_reservation(order_no);

CREATE TABLE sto_stock_check (
    id           BIGSERIAL PRIMARY KEY,
    check_no     VARCHAR(40) NOT NULL UNIQUE,
    warehouse_id BIGINT NOT NULL REFERENCES sto_warehouse(id),
    type         INT NOT NULL,
    status       INT NOT NULL DEFAULT 0,
    operator_id  BIGINT NOT NULL REFERENCES t_user(id),
    submit_time  TIMESTAMP,
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sto_stock_check_item (
    id         BIGSERIAL PRIMARY KEY,
    check_id   BIGINT NOT NULL REFERENCES sto_stock_check(id) ON DELETE CASCADE,
    sku_id     BIGINT NOT NULL REFERENCES pro_sku(id),
    location_id BIGINT REFERENCES sto_location(id),
    system_qty INT NOT NULL,
    actual_qty INT,
    diff_qty   INT,
    reason     VARCHAR(255),
    UNIQUE(check_id, sku_id, location_id)
);

CREATE TABLE sto_stock_transfer (
    id                BIGSERIAL PRIMARY KEY,
    transfer_no       VARCHAR(40) NOT NULL UNIQUE,
    from_warehouse_id BIGINT NOT NULL REFERENCES sto_warehouse(id),
    to_warehouse_id   BIGINT NOT NULL REFERENCES sto_warehouse(id),
    status            INT NOT NULL DEFAULT 1,
    remark            VARCHAR(500),
    operator_id       BIGINT NOT NULL REFERENCES t_user(id),
    create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sto_stock_transfer_item (
    id               BIGSERIAL PRIMARY KEY,
    transfer_id      BIGINT NOT NULL REFERENCES sto_stock_transfer(id) ON DELETE CASCADE,
    sku_id           BIGINT NOT NULL REFERENCES pro_sku(id),
    quantity         INT NOT NULL CHECK (quantity > 0),
    from_location_id BIGINT NOT NULL REFERENCES sto_location(id),
    to_location_id   BIGINT NOT NULL REFERENCES sto_location(id)
);

CREATE TABLE crm_cart (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    sku_id      BIGINT NOT NULL REFERENCES pro_sku(id) ON DELETE CASCADE,
    quantity    INT NOT NULL CHECK (quantity > 0),
    selected    BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, sku_id)
);

CREATE TABLE ord_order (
    id               BIGSERIAL PRIMARY KEY,
    order_no         VARCHAR(40) NOT NULL UNIQUE,
    user_id          BIGINT NOT NULL REFERENCES t_user(id),
    customer_id      BIGINT REFERENCES crm_customer(id),
    reservation_id   VARCHAR(64),
    address_id       BIGINT NOT NULL REFERENCES crm_address(id),
    address_snapshot JSONB NOT NULL,
    total_amount     NUMERIC(14,2) NOT NULL,
    discount_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    freight          NUMERIC(12,2) NOT NULL DEFAULT 0,
    pay_amount       NUMERIC(14,2) NOT NULL,
    status           INT NOT NULL DEFAULT 0,
    has_partial_aftersale BOOLEAN NOT NULL DEFAULT FALSE,
    remark           VARCHAR(500),
    cancel_reason    VARCHAR(500),
    idempotency_key  VARCHAR(120) NOT NULL UNIQUE,
    expire_time      TIMESTAMP NOT NULL,
    pay_time         TIMESTAMP,
    ship_time        TIMESTAMP,
    receive_time     TIMESTAMP,
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_order_user_status_time ON ord_order(user_id, status, create_time DESC);

CREATE TABLE ord_order_item (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES ord_order(id) ON DELETE CASCADE,
    sku_id        BIGINT NOT NULL REFERENCES pro_sku(id),
    sku_code      VARCHAR(80) NOT NULL,
    product_name  VARCHAR(150) NOT NULL,
    spec_values   TEXT,
    main_image    VARCHAR(255),
    price         NUMERIC(12,2) NOT NULL,
    quantity      INT NOT NULL CHECK (quantity > 0),
    subtotal      NUMERIC(14,2) NOT NULL,
    warehouse_id  BIGINT NOT NULL REFERENCES sto_warehouse(id),
    location_id   BIGINT NOT NULL REFERENCES sto_location(id)
);

CREATE TABLE pay_payment (
    id                     BIGSERIAL PRIMARY KEY,
    pay_no                 VARCHAR(40)  NOT NULL UNIQUE,
    order_id               BIGINT       NOT NULL UNIQUE REFERENCES ord_order(id),
    pay_type               INT          NOT NULL,
    pay_status             INT          NOT NULL DEFAULT 0,
    pay_amount             NUMERIC(14,2) NOT NULL,
    provider_transaction_no VARCHAR(80) UNIQUE,
    expire_time            TIMESTAMP,
    mock_pay_url           VARCHAR(255),
    callback_nonce         VARCHAR(80),
    callback_received_at   TIMESTAMP,
    pay_time               TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_time            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pay_nonce (
    nonce   VARCHAR(80) PRIMARY KEY,
    used_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ord_aftersale (
    id            BIGSERIAL PRIMARY KEY,
    aftersale_no  VARCHAR(40) NOT NULL UNIQUE,
    order_id      BIGINT NOT NULL REFERENCES ord_order(id),
    order_item_id BIGINT NOT NULL REFERENCES ord_order_item(id),
    user_id       BIGINT NOT NULL REFERENCES t_user(id),
    type          INT NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    images        TEXT,
    remark        VARCHAR(500),
    status        INT NOT NULL DEFAULT 0,
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- v1.2 §7.11 退款状态机：PENDING(0)/APPROVED(1)/REJECTED(2)/REFUNDING(3)/COMPLETED(4)/CANCELLED(5)
CREATE TABLE ref_refund (
    id                    BIGSERIAL PRIMARY KEY,
    refund_no             VARCHAR(40)  NOT NULL UNIQUE,
    order_id              BIGINT       NOT NULL REFERENCES ord_order(id),
    order_item_id         BIGINT       NOT NULL REFERENCES ord_order_item(id),
    customer_id           BIGINT       NOT NULL REFERENCES crm_customer(id),
    type                  INT          NOT NULL,        -- 1=仅退款 2=退货退款 3=换货
    status                INT          NOT NULL DEFAULT 0,
    apply_refund_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    apply_refund_quantity INT          NOT NULL DEFAULT 0,
    approved_amount       NUMERIC(14,2),
    approved_quantity     INT,
    actual_refund_amount  NUMERIC(14,2),
    provider_refund_no    VARCHAR(80) UNIQUE,
    restock               BOOLEAN      NOT NULL DEFAULT FALSE,
    reason                VARCHAR(500) NOT NULL,
    images                TEXT,
    audit_remark          VARCHAR(500),
    applied_at            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    audited_at            TIMESTAMP,
    completed_at          TIMESTAMP,
    idempotency_key       VARCHAR(120) UNIQUE,
    create_time           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ref_refund_status ON ref_refund(status, applied_at DESC);

CREATE TABLE ref_refund_item (
    id          BIGSERIAL PRIMARY KEY,
    refund_id   BIGINT NOT NULL REFERENCES ref_refund(id) ON DELETE CASCADE,
    sku_id      BIGINT NOT NULL REFERENCES pro_sku(id),
    warehouse_id BIGINT REFERENCES sto_warehouse(id),
    location_id  BIGINT REFERENCES sto_location(id),
    quantity    INT    NOT NULL CHECK (quantity > 0)
);

CREATE TABLE ord_shipping (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT NOT NULL UNIQUE REFERENCES ord_order(id),
    logistics_company VARCHAR(100) NOT NULL,
    logistics_no      VARCHAR(100) NOT NULL,
    shipped_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ord_order_timeline (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES ord_order(id) ON DELETE CASCADE,
    event       VARCHAR(100) NOT NULL,
    event_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_dict_type (
    id          BIGSERIAL PRIMARY KEY,
    dict_type   VARCHAR(100) NOT NULL UNIQUE,
    dict_name   VARCHAR(100) NOT NULL,
    status      INT NOT NULL DEFAULT 0
);

CREATE TABLE sys_dict_data (
    id          BIGSERIAL PRIMARY KEY,
    dict_type   VARCHAR(100) NOT NULL REFERENCES sys_dict_type(dict_type) ON DELETE CASCADE,
    dict_label  VARCHAR(100) NOT NULL,
    dict_value  VARCHAR(100) NOT NULL,
    css_class   VARCHAR(50),
    sort_order  INT NOT NULL DEFAULT 0,
    status      INT NOT NULL DEFAULT 0,
    UNIQUE(dict_type, dict_value)
);

CREATE TABLE sys_config (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(150) NOT NULL UNIQUE,
    config_name  VARCHAR(150) NOT NULL,
    config_value VARCHAR(500) NOT NULL,
    status       INT NOT NULL DEFAULT 0,
    update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_op_log (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES t_user(id),
    username     VARCHAR(50),
    module       VARCHAR(100) NOT NULL,
    operation    VARCHAR(100) NOT NULL,
    method       VARCHAR(120),
    request_url  VARCHAR(500),
    ip           VARCHAR(64),
    operate_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cost_ms      BIGINT,
    status       INT NOT NULL DEFAULT 0,
    error_msg    VARCHAR(1000)
);
CREATE INDEX idx_op_log_user_time ON sys_op_log(username, operate_time DESC);

CREATE TABLE sys_outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(80) NOT NULL UNIQUE,
    routing_key   VARCHAR(100) NOT NULL,
    event_type    VARCHAR(40),
    source        VARCHAR(32) DEFAULT 'sales',
    schema_version SMALLINT DEFAULT 1,
    trace_id      VARCHAR(64),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id  VARCHAR(80) NOT NULL,
    payload       JSONB NOT NULL,
    status        INT NOT NULL DEFAULT 0,
    retry_count   INT NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    published_time TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON sys_outbox_event(status, next_retry_time);

CREATE TABLE sys_consumed_event (
    id           BIGSERIAL PRIMARY KEY,
    event_id     VARCHAR(80) NOT NULL UNIQUE,
    consumer     VARCHAR(100) NOT NULL,
    consume_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- MQ 消费幂等日志（10.2.3）：以 (message_id, consumer) / (source_no, event_type, consumer) 去重
CREATE TABLE mq_consume_log (
    id           BIGSERIAL PRIMARY KEY,
    message_id   VARCHAR(80)  NOT NULL,
    source_no    VARCHAR(80)  NOT NULL,
    event_type   VARCHAR(40)  NOT NULL,
    consumer     VARCHAR(40)  NOT NULL,
    status       SMALLINT     NOT NULL DEFAULT 0,
    marker       VARCHAR(40),
    payload_hash VARCHAR(80),
    error_msg    VARCHAR(500),
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (message_id, consumer),
    UNIQUE (source_no, event_type, consumer)
);

-- 订单消息发送日志（10.2.2）：记录事件投递状态
CREATE TABLE ord_message_log (
    id           BIGSERIAL PRIMARY KEY,
    message_id   VARCHAR(80) NOT NULL UNIQUE,
    event_type   VARCHAR(40) NOT NULL,
    order_id     BIGINT,
    payload      JSONB       NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    send_time    TIMESTAMP,
    consume_time TIMESTAMP,
    create_time  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_message_log_order ON ord_message_log(order_id, event_type);

-- 货主（10.5.1）
CREATE TABLE own_owner (
    id          BIGSERIAL PRIMARY KEY,
    owner_code  VARCHAR(50)  NOT NULL UNIQUE,
    owner_name  VARCHAR(100) NOT NULL,
    contact     VARCHAR(50),
    phone       VARCHAR(30),
    status      INT          NOT NULL DEFAULT 0,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 货主与仓库的归属关系（10.5.2/3）
CREATE TABLE sto_owner_warehouse (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    BIGINT NOT NULL REFERENCES own_owner(id) ON DELETE CASCADE,
    warehouse_id BIGINT NOT NULL REFERENCES sto_warehouse(id) ON DELETE CASCADE,
    UNIQUE (owner_id, warehouse_id)
);

-- 补货建议（10.6.1）
CREATE TABLE res_restock_suggestion (
    id                 BIGSERIAL PRIMARY KEY,
    sku_id             BIGINT       NOT NULL REFERENCES pro_sku(id),
    sku_code           VARCHAR(80),
    product_name       VARCHAR(150),
    spec_values        TEXT,
    current_stock      INT          NOT NULL DEFAULT 0,
    safety_stock       INT          NOT NULL DEFAULT 0,
    avg_daily_sales    NUMERIC(10, 2) NOT NULL DEFAULT 0,
    days_remaining     NUMERIC(10, 2) NOT NULL DEFAULT 0,
    suggested_quantity INT          NOT NULL DEFAULT 0,
    suggested_supplier_id BIGINT    REFERENCES pur_supplier(id),
    suggested_supplier_name VARCHAR(150),
    urgency            VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    estimated_arrival_days INT    NOT NULL DEFAULT 3,
    reason             VARCHAR(500),
    status             INT          NOT NULL DEFAULT 0,
    create_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_restock_sku ON res_restock_suggestion(sku_id);

-- 补货规则（10.6.3）：单行配置，id=1 视为全局
CREATE TABLE res_restock_rule (
    id                       BIGSERIAL PRIMARY KEY,
    default_safety_stock_days INT NOT NULL DEFAULT 7,
    default_lead_time_days   INT NOT NULL DEFAULT 3,
    sales_history_days       INT NOT NULL DEFAULT 30,
    enable_auto_notify       BOOLEAN NOT NULL DEFAULT TRUE,
    notify_channels          VARCHAR(200) DEFAULT 'email,sms,system',
    update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
