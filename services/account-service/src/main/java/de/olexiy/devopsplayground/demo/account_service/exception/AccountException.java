package de.olexiy.devopsplayground.demo.account_service.exception;

public abstract sealed class AccountException extends RuntimeException
        permits AccountNotFoundException, AccountNumberNotFoundException,
                InsufficientFundsException, CustomerServiceUnavailableException,
                CustomerNotFoundException {

    protected AccountException(String message) {
        super(message);
    }
}
