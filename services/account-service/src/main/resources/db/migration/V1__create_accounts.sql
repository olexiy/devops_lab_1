CREATE TABLE accounts
(
    id             BIGINT                                              NOT NULL AUTO_INCREMENT,
    account_number VARCHAR(20)                                         NOT NULL,
    customer_id    BIGINT                                              NOT NULL,
    account_type   ENUM ('CHECKING', 'SAVINGS', 'CREDIT', 'DEPOSIT')  NOT NULL,
    status         ENUM ('ACTIVE', 'FROZEN', 'CLOSED')                NOT NULL DEFAULT 'ACTIVE',
    currency       CHAR(3)                                             NOT NULL DEFAULT 'EUR',
    balance        DECIMAL(19, 4)                                      NOT NULL DEFAULT 0.0000,
    credit_limit   DECIMAL(19, 4)                                      NULL,
    open_date      DATE                                                NOT NULL,
    close_date     DATE                                                NULL,
    created_at     DATETIME(3)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)                                         NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_accounts_account_number (account_number),
    INDEX idx_accounts_customer_id (customer_id),
    INDEX idx_accounts_customer_type (customer_id, account_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
