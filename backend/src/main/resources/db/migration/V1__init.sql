-- OrderMgt schema (Flyway V1). MySQL 8 only -- no H2, no compatibility mode
-- anywhere in the stack (ADR-0008): local dev, tests and delivery all run
-- against the real database.
--
-- Every TIMESTAMP(n) default spells out the same precision, i.e. CURRENT_TIMESTAMP(3)
-- and never a bare CURRENT_TIMESTAMP: MySQL rejects the mismatch with error 1067
-- ("Invalid default value") when the declared precision and the default disagree.
-- Pinning the same precision in both places is what keeps a fresh migration
-- reproducible (ADR-0008).
--
-- This file has shipped: editing it changes its Flyway checksum and databases
-- that already ran it will refuse to start. See the README quick-start note for
-- the rebuild procedure.

CREATE TABLE catalog_items (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    sku        VARCHAR(64)   NOT NULL,
    name       VARCHAR(128)  NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_sku UNIQUE (sku)
);

-- Prices live here and only here: the AI picks a sku, never an amount.
INSERT INTO catalog_items (sku, name, unit_price, active) VALUES
    ('WIDGET-01', 'Widget',      19.90, TRUE),
    ('GADGET-02', 'Gadget',      49.50, TRUE),
    ('GIZMO-03',  'Gizmo',        5.00, TRUE),
    ('DOODAD-04', 'Doodad',     120.00, TRUE),
    ('THING-05',  'Thingamajig',  9.99, TRUE);

CREATE TABLE orders (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(32)   NOT NULL,
    customer_id  BIGINT        NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_no UNIQUE (order_no)
);

CREATE INDEX idx_orders_customer ON orders (customer_id, created_at);

CREATE TABLE order_lines (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    order_id    BIGINT        NOT NULL,
    sku         VARCHAR(64)   NOT NULL,
    item_name   VARCHAR(128)  NOT NULL,
    quantity    INT           NOT NULL,
    unit_price  DECIMAL(12, 2) NOT NULL,
    line_amount DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_lines_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_lines_order ON order_lines (order_id);

CREATE TABLE conversations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id     BIGINT       NOT NULL,
    title           VARCHAR(128) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_message_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_conv_customer ON conversations (customer_id, last_message_at);

CREATE TABLE messages (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT       NOT NULL,
    role              VARCHAR(16)  NOT NULL,
    content           VARCHAR(8000) NULL,
    pending_action_id BIGINT       NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES conversations (id)
);

CREATE INDEX idx_msg_conv ON messages (conversation_id, id);

CREATE TABLE pending_actions (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    token             VARCHAR(64)   NOT NULL,
    customer_id       BIGINT        NOT NULL,
    conversation_id   BIGINT        NOT NULL,
    source_message_id BIGINT        NULL,
    tool_name         VARCHAR(64)   NOT NULL,
    arguments_json    VARCHAR(4000) NOT NULL,
    summary           VARCHAR(255)  NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    expires_at        TIMESTAMP(3)  NOT NULL,
    resolved_at       TIMESTAMP(3)  NULL,
    result_ref        VARCHAR(64)   NULL,
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_pending_token UNIQUE (token)
);

CREATE INDEX idx_pending_customer ON pending_actions (customer_id, status, expires_at);

CREATE TABLE idempotency_records (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id     BIGINT        NOT NULL,
    operation       VARCHAR(64)   NOT NULL,
    idempotency_key VARCHAR(128)  NOT NULL,
    request_hash    CHAR(64)      NULL,
    status          VARCHAR(16)   NOT NULL,
    result_ref      VARCHAR(128)  NULL,
    response_json   VARCHAR(8000) NULL,
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at    TIMESTAMP(3)  NULL,
    expires_at      TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idem UNIQUE (customer_id, operation, idempotency_key)
);

CREATE INDEX idx_idem_expires ON idempotency_records (expires_at);

CREATE TABLE ai_invocations (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT        NULL,
    customer_id       BIGINT        NOT NULL,
    model             VARCHAR(64)   NOT NULL,
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    latency_ms        INT           NULL,
    outcome           VARCHAR(16)   NOT NULL,
    error             VARCHAR(1000) NULL,
    tool_calls_json   VARCHAR(8000) NULL,
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
);

CREATE INDEX idx_ai_conv ON ai_invocations (conversation_id, created_at);
