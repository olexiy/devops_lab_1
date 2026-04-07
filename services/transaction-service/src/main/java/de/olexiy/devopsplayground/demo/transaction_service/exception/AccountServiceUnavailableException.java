package de.olexiy.devopsplayground.demo.transaction_service.exception;

public final class AccountServiceUnavailableException extends TransactionException {
    public AccountServiceUnavailableException(Long accountId) {
        super("account-service is unavailable, cannot process account %d".formatted(accountId));
    }
}
