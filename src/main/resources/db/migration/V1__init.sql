-- Baseline schema. Add domain tables in later versioned migrations (V2__, V3__, ...).

CREATE TABLE schema_metadata (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    initialized_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
