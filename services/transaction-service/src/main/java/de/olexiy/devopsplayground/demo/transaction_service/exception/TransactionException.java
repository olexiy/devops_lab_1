package de.olexiy.devopsplayground.demo.transaction_service.exception;

public abstract sealed class TransactionException extends RuntimeException
        permits TransactionNotFoundException, AccountNotFoundException,
                InsufficientFundsException, AccountServiceUnavailableException {

    protected TransactionException(String message) {
        super(message);
    }
}
