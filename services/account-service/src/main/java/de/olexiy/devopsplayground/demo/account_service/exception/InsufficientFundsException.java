package de.olexiy.devopsplayground.demo.account_service.exception;

import java.math.BigDecimal;

public final class InsufficientFundsException extends AccountException {
    public InsufficientFundsException(long accountId, BigDecimal amount) {
        super("Insufficient funds on account %d: requested debit %.2f exceeds available balance".formatted(accountId, amount));
    }
}
