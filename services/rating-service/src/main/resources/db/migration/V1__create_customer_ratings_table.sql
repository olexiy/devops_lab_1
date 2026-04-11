CREATE TABLE customer_ratings (
    customer_id             BIGINT          NOT NULL,
    rating_score            DECIMAL(5, 2)   NOT NULL,
    rating_class            VARCHAR(5)      NOT NULL,
    risk_level              VARCHAR(20)     NOT NULL,
    calculated_at           TIMESTAMP       NOT NULL,
    calculation_version     VARCHAR(10)     NOT NULL,
    avg_balance_12m         DECIMAL(19, 4),
    product_count           INTEGER,
    transaction_volume_12m  DECIMAL(19, 4),
    external_score          DECIMAL(5, 2),
    processing_pod          VARCHAR(100),
    processing_duration_ms  INTEGER,

    CONSTRAINT pk_customer_ratings PRIMARY KEY (customer_id)
);
