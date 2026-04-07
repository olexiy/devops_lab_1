package de.olexiy.devopsplayground.demo.transaction_service.exception;

public final class TransactionNotFoundException extends TransactionException {
    public TransactionNotFoundException(Long id) {
        super("Transaction with id %d not found".formatted(id));
    }

    public TransactionNotFoundException(String referenceNumber) {
        super("Transaction with reference number %s not found".formatted(referenceNumber));
    }
}
