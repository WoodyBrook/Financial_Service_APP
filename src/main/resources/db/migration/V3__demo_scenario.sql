-- Live demo scenario orchestration state.
-- The table lives in the shared schema; only the demo profile registers the code that touches it.
-- It is the single source of truth for the scenario step machine — business rows are never
-- counted or inspected to guess where the demo is.

CREATE TABLE demo_scenario_state (
    scenario_key       VARCHAR(64) PRIMARY KEY,
    step               VARCHAR(32) NOT NULL,
    customer_id        BIGINT REFERENCES customer (id),
    anchor_payment_id  BIGINT REFERENCES txn (id),
    simulated_as_of    TIMESTAMPTZ,
    case_id            BIGINT REFERENCES case_record (id),
    sanctions_hit_id   BIGINT REFERENCES sanctions_hit (id),
    version            BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL
);

-- Payment references come from a sequence, not from count()+1, so concurrent payment
-- creation can never hand out the same reference twice.
CREATE SEQUENCE payment_ref_seq START WITH 1;
