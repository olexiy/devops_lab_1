CREATE TABLE transactions
(
    id               BIGINT                                                              NOT NULL AUTO_INCREMENT,
    reference_number CHAR(36)                                                            NOT NULL,
    account_id       BIGINT                                                              NOT NULL,
    customer_id      BIGINT                                                              NOT NULL,
    transaction_type ENUM ('CREDIT', 'DEBIT', 'TRANSFER_IN', 'TRANSFER_OUT', 'FEE')    NOT NULL,
    amount           DECIMAL(19, 4)                                                      NOT NULL,
    currency         CHAR(3)                                                             NOT NULL DEFAULT 'EUR',
    balance_after    DECIMAL(19, 4)                                                      NOT NULL,
    description      VARCHAR(500)                                                        NULL,
    transaction_date DATETIME(3)                                                         NOT NULL,
    created_at       DATETIME(3)                                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_transactions_reference_number (reference_number),
    INDEX idx_transactions_account_id (account_id),
    INDEX idx_transactions_customer_id (customer_id),
    INDEX idx_transactions_customer_date (customer_id, transaction_date),
    INDEX idx_transactions_account_date (account_id, transaction_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
