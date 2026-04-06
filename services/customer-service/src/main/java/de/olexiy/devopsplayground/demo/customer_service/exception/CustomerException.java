package de.olexiy.devopsplayground.demo.customer_service.exception;

public abstract sealed class CustomerException extends RuntimeException
        permits CustomerNotFoundException, CustomerEmailConflictException {

    protected CustomerException(String message) {
        super(message);
    }
}
