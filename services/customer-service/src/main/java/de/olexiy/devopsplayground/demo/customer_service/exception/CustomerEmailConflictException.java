package de.olexiy.devopsplayground.demo.customer_service.exception;

public final class CustomerEmailConflictException extends CustomerException {

    public CustomerEmailConflictException(String email) {
        super("Email '%s' is already in use".formatted(email));
    }
}
