package de.olexiy.devopsplayground.demo.transaction_service.exception;

import java.math.BigDecimal;

public final class InsufficientFundsException extends TransactionException {
    public InsufficientFundsException(Long accountId, BigDecimal amount) {
        super("Insufficient funds on account %d for amount %s".formatted(accountId, amount.toPlainString()));
    }
}
