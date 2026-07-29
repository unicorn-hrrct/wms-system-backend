-- Customer / Address / Aftersale / Refund classification overlay
-- Target baseline: upstream/sales 5dc682e (PostgreSQL 18).
--
-- This script intentionally does not replace backend/src/main/resources/schema.sql.
-- It fails loudly when legacy rows violate the v1.2 ownership invariants.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.crm_customer') IS NULL
       OR to_regclass('public.crm_address') IS NULL
       OR to_regclass('public.ord_order') IS NULL
       OR to_regclass('public.ord_order_item') IS NULL
       OR to_regclass('public.ord_aftersale') IS NULL
       OR to_regclass('public.ref_refund') IS NULL THEN
        RAISE EXCEPTION
            'Sales V001 requires the upstream customer/order/aftersale/refund schema';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM crm_customer
        WHERE deleted IS NULL OR deleted NOT IN (0, 1)
    ) THEN
        RAISE EXCEPTION 'crm_customer contains invalid deleted values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM crm_address
        WHERE is_default IS NULL
           OR deleted IS NULL
           OR deleted NOT IN (0, 1)
    ) THEN
        RAISE EXCEPTION 'crm_address contains invalid default/deleted values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ord_order
        WHERE customer_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'ord_order.customer_id must be backfilled before Sales V001';
    END IF;
END
$$;

ALTER TABLE crm_customer
    ALTER COLUMN deleted SET DEFAULT 0,
    ALTER COLUMN deleted SET NOT NULL;

ALTER TABLE crm_address
    ALTER COLUMN is_default SET DEFAULT FALSE,
    ALTER COLUMN is_default SET NOT NULL,
    ALTER COLUMN deleted SET DEFAULT 0,
    ALTER COLUMN deleted SET NOT NULL;

ALTER TABLE ord_order
    ALTER COLUMN customer_id SET NOT NULL;

ALTER TABLE ord_aftersale
    ALTER COLUMN apply_refund_amount TYPE NUMERIC(19, 2)
        USING apply_refund_amount::NUMERIC(19, 2),
    ALTER COLUMN approved_amount TYPE NUMERIC(19, 2)
        USING approved_amount::NUMERIC(19, 2),
    ALTER COLUMN apply_refund_amount SET DEFAULT 0,
    ALTER COLUMN apply_refund_amount SET NOT NULL,
    ALTER COLUMN apply_refund_quantity SET DEFAULT 0,
    ALTER COLUMN apply_refund_quantity SET NOT NULL;

ALTER TABLE ref_refund
    ALTER COLUMN apply_refund_amount TYPE NUMERIC(19, 2)
        USING apply_refund_amount::NUMERIC(19, 2),
    ALTER COLUMN approved_amount TYPE NUMERIC(19, 2)
        USING approved_amount::NUMERIC(19, 2),
    ALTER COLUMN actual_refund_amount TYPE NUMERIC(19, 2)
        USING actual_refund_amount::NUMERIC(19, 2),
    ALTER COLUMN apply_refund_amount SET DEFAULT 0,
    ALTER COLUMN apply_refund_amount SET NOT NULL,
    ALTER COLUMN apply_refund_quantity SET DEFAULT 0,
    ALTER COLUMN apply_refund_quantity SET NOT NULL,
    ALTER COLUMN restock SET DEFAULT FALSE,
    ALTER COLUMN restock SET NOT NULL,
    ALTER COLUMN applied_at
        SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai');

CREATE TABLE IF NOT EXISTS sales_idempotency_subject (
    subject_type VARCHAR(16) NOT NULL,
    subject_id   BIGINT      NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sales_idempotency_subject
        PRIMARY KEY (subject_type, subject_id),
    CONSTRAINT ck_sales_idempotency_subject_type
        CHECK (subject_type IN ('CUSTOMER', 'USER')),
    CONSTRAINT ck_sales_idempotency_subject_id
        CHECK (subject_id > 0)
);

CREATE TABLE IF NOT EXISTS sales_idempotency_record (
    id                BIGSERIAL PRIMARY KEY,
    operation         VARCHAR(80)  NOT NULL,
    subject_type      VARCHAR(16)  NOT NULL,
    subject_id        BIGINT       NOT NULL,
    idempotency_key   UUID         NOT NULL,
    request_hash      CHAR(64)     NOT NULL,
    status            SMALLINT     NOT NULL DEFAULT 0,
    response_payload  JSONB,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP,
    expires_at        TIMESTAMP    NOT NULL
        DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    CONSTRAINT fk_sales_idempotency_subject
        FOREIGN KEY (subject_type, subject_id)
        REFERENCES sales_idempotency_subject(subject_type, subject_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_sales_idempotency_operation_subject_key
        UNIQUE (operation, subject_type, subject_id, idempotency_key),
    CONSTRAINT ck_sales_idempotency_status
        CHECK (status IN (0, 1)),
    CONSTRAINT ck_sales_idempotency_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_sales_idempotency_expires_at
    ON sales_idempotency_record(expires_at);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'crm_customer'::regclass
          AND conname = 'ck_crm_customer_deleted'
    ) THEN
        ALTER TABLE crm_customer
            ADD CONSTRAINT ck_crm_customer_deleted
            CHECK (deleted IN (0, 1));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'crm_customer'::regclass
          AND conname = 'uq_crm_customer_id_user'
    ) THEN
        ALTER TABLE crm_customer
            ADD CONSTRAINT uq_crm_customer_id_user
            UNIQUE (id, user_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'crm_address'::regclass
          AND conname = 'ck_crm_address_deleted'
    ) THEN
        ALTER TABLE crm_address
            ADD CONSTRAINT ck_crm_address_deleted
            CHECK (deleted IN (0, 1));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'crm_address'::regclass
          AND conname = 'uq_crm_address_id_customer'
    ) THEN
        ALTER TABLE crm_address
            ADD CONSTRAINT uq_crm_address_id_customer
            UNIQUE (id, customer_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_order'::regclass
          AND conname = 'uq_order_id_customer'
    ) THEN
        ALTER TABLE ord_order
            ADD CONSTRAINT uq_order_id_customer
            UNIQUE (id, customer_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_order_item'::regclass
          AND conname = 'uq_order_item_id_order'
    ) THEN
        ALTER TABLE ord_order_item
            ADD CONSTRAINT uq_order_item_id_order
            UNIQUE (id, order_id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_order'::regclass
          AND conname = 'fk_order_customer_user'
    ) THEN
        ALTER TABLE ord_order
            ADD CONSTRAINT fk_order_customer_user
            FOREIGN KEY (customer_id, user_id)
            REFERENCES crm_customer(id, user_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_order'::regclass
          AND conname = 'fk_order_address_customer'
    ) THEN
        ALTER TABLE ord_order
            ADD CONSTRAINT fk_order_address_customer
            FOREIGN KEY (address_id, customer_id)
            REFERENCES crm_address(id, customer_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'fk_aftersale_customer_user'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT fk_aftersale_customer_user
            FOREIGN KEY (customer_id, user_id)
            REFERENCES crm_customer(id, user_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'fk_aftersale_order_customer'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT fk_aftersale_order_customer
            FOREIGN KEY (order_id, customer_id)
            REFERENCES ord_order(id, customer_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'fk_aftersale_item_order'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT fk_aftersale_item_order
            FOREIGN KEY (order_item_id, order_id)
            REFERENCES ord_order_item(id, order_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'fk_refund_order_customer'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT fk_refund_order_customer
            FOREIGN KEY (order_id, customer_id)
            REFERENCES ord_order(id, customer_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'fk_refund_item_order'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT fk_refund_item_order
            FOREIGN KEY (order_item_id, order_id)
            REFERENCES ord_order_item(id, order_id)
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'fk_aftersale_refund'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT fk_aftersale_refund
            FOREIGN KEY (refund_id)
            REFERENCES ref_refund(id)
            NOT VALID;
    END IF;
END
$$;

ALTER TABLE ord_order
    VALIDATE CONSTRAINT fk_order_customer_user;
ALTER TABLE ord_order
    VALIDATE CONSTRAINT fk_order_address_customer;
ALTER TABLE ord_aftersale
    VALIDATE CONSTRAINT fk_aftersale_customer_user;
ALTER TABLE ord_aftersale
    VALIDATE CONSTRAINT fk_aftersale_order_customer;
ALTER TABLE ord_aftersale
    VALIDATE CONSTRAINT fk_aftersale_item_order;
ALTER TABLE ref_refund
    VALIDATE CONSTRAINT fk_refund_order_customer;
ALTER TABLE ref_refund
    VALIDATE CONSTRAINT fk_refund_item_order;
ALTER TABLE ord_aftersale
    VALIDATE CONSTRAINT fk_aftersale_refund;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'ck_aftersale_type'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT ck_aftersale_type
            CHECK (type IN (1, 2, 3));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'ck_aftersale_status'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT ck_aftersale_status
            CHECK (status BETWEEN 0 AND 2);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'ck_aftersale_apply_amount'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT ck_aftersale_apply_amount
            CHECK (
                apply_refund_amount >= 0
                AND (
                    type NOT IN (1, 2)
                    OR apply_refund_amount > 0
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'ck_aftersale_apply_quantity'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT ck_aftersale_apply_quantity
            CHECK (
                apply_refund_quantity >= 0
                AND (
                    type <> 2
                    OR apply_refund_quantity > 0
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'ck_aftersale_approved_amount'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT ck_aftersale_approved_amount
            CHECK (
                approved_amount IS NULL
                OR (
                    approved_amount > 0
                    AND approved_amount <= apply_refund_amount
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ord_aftersale'::regclass
          AND conname = 'ck_aftersale_approved_quantity'
    ) THEN
        ALTER TABLE ord_aftersale
            ADD CONSTRAINT ck_aftersale_approved_quantity
            CHECK (
                approved_quantity IS NULL
                OR (
                    approved_quantity > 0
                    AND approved_quantity <= apply_refund_quantity
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_type'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_type
            CHECK (type IN (1, 2, 3));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_status'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_status
            CHECK (status BETWEEN 0 AND 5);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_apply_amount'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_apply_amount
            CHECK (
                apply_refund_amount >= 0
                AND (
                    type NOT IN (1, 2)
                    OR apply_refund_amount > 0
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_apply_quantity'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_apply_quantity
            CHECK (
                apply_refund_quantity >= 0
                AND (
                    type <> 2
                    OR apply_refund_quantity > 0
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_approved_amount'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_approved_amount
            CHECK (
                approved_amount IS NULL
                OR (
                    approved_amount > 0
                    AND approved_amount <= apply_refund_amount
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_approved_quantity'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_approved_quantity
            CHECK (
                approved_quantity IS NULL
                OR (
                    approved_quantity > 0
                    AND approved_quantity <= apply_refund_quantity
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_actual_amount'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_actual_amount
            CHECK (
                actual_refund_amount IS NULL
                OR (
                    actual_refund_amount > 0
                    AND approved_amount IS NOT NULL
                    AND actual_refund_amount <= approved_amount
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'ref_refund'::regclass
          AND conname = 'ck_refund_restock_type'
    ) THEN
        ALTER TABLE ref_refund
            ADD CONSTRAINT ck_refund_restock_type
            CHECK (NOT restock OR type = 2);
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_aftersale_refund
    ON ord_aftersale(refund_id)
    WHERE refund_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ref_refund_customer_time
    ON ref_refund(customer_id, applied_at DESC, id DESC);

COMMIT;
