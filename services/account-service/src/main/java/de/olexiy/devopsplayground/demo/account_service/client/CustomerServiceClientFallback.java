package de.olexiy.devopsplayground.demo.account_service.client;

import de.olexiy.devopsplayground.demo.account_service.exception.CustomerServiceUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class CustomerServiceClientFallback implements CustomerServiceClient {

    @Override
    public CustomerSummary getCustomerById(Long id) {
        throw new CustomerServiceUnavailableException(id);
    }
}
