package de.olexiy.devopsplayground.demo.account_service.exception;

public final class AccountNotFoundException extends AccountException {
    public AccountNotFoundException(long id) {
        super("Account with id %d not found".formatted(id));
    }
}
