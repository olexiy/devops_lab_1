package de.olexiy.devopsplayground.demo.customer_service.exception;

public final class CustomerNotFoundException extends CustomerException {

    public CustomerNotFoundException(long id) {
        super("Customer with id %d not found".formatted(id));
    }

    public CustomerNotFoundException(String email) {
        super("Customer with email '%s' not found".formatted(email));
    }
}
