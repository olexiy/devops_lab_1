package de.olexiy.devopsplayground.demo.account_service.exception;

public final class CustomerNotFoundException extends AccountException {
    public CustomerNotFoundException(long customerId) {
        super("Customer with id %d not found".formatted(customerId));
    }
}
