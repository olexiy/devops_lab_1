package de.olexiy.devopsplayground.demo.transaction_service.exception;

public final class AccountNotFoundException extends TransactionException {
    public AccountNotFoundException(Long accountId) {
        super("Account with id %d not found".formatted(accountId));
    }
}
