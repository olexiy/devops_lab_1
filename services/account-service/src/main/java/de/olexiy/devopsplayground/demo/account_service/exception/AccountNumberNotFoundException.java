package de.olexiy.devopsplayground.demo.account_service.exception;

public final class AccountNumberNotFoundException extends AccountException {
    public AccountNumberNotFoundException(String accountNumber) {
        super("Account with number '%s' not found".formatted(accountNumber));
    }
}
