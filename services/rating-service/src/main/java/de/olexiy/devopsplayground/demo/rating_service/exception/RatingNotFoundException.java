package de.olexiy.devopsplayground.demo.rating_service.exception;

public class RatingNotFoundException extends RuntimeException {

    public RatingNotFoundException(Long customerId) {
        super("Rating not found for customer: " + customerId);
    }
}
