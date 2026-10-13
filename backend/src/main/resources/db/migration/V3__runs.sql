-- One row per execution instance: a customer message triggering a prepare -> model call ->
-- persist, the unit that stopping, resubscribing and queueing all attach to (ADR-0011). It is a
-- subordinate record of a conversation, like messages -- no content is stored, so ADR-0009's ban
-- on message-level state stays untouched.
--
-- No CHECK on status: the values (RUNNING plus terminal COMPLETED/FAILED/CANCELLED/INTERRUPTED)
-- live in RunStatus, and the column is VARCHAR without a constraint like the others (ADR-0008
-- pins only the timestamp precision, spelled out here and in the default).
--
-- The "at most one RUNNING run per conversation" invariant is NOT enforced here: MySQL has no
-- partial index, so it lives in the in-process registry (ADR-0012). The (conversation_id, status)
-- index is what the sidebar's derived state reads.
CREATE TABLE runs (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT        NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    error           VARCHAR(1000) NULL,
    started_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at        TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_run_conv FOREIGN KEY (conversation_id) REFERENCES conversations (id)
);

CREATE INDEX idx_run_conv_status ON runs (conversation_id, status);
