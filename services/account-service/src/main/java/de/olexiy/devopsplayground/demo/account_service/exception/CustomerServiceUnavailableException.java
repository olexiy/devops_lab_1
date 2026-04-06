package de.olexiy.devopsplayground.demo.account_service.exception;

public final class CustomerServiceUnavailableException extends AccountException {
    public CustomerServiceUnavailableException(long customerId) {
        super("customer-service is unavailable, cannot validate customer %d".formatted(customerId));
    }
}
