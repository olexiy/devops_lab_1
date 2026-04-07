package de.olexiy.devopsplayground.demo.transaction_service.client;

public record AccountSummary(
        Long id,
        Long customerId,
        String status,
        Double balance,
        String currency,
        Double creditLimit
) {}
