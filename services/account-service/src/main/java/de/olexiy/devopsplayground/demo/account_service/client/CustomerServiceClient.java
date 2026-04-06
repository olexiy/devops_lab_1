package de.olexiy.devopsplayground.demo.account_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "customer-service",
        url = "${customer-service.url}",
        fallback = CustomerServiceClientFallback.class
)
public interface CustomerServiceClient {

    @GetMapping("/api/v1/customers/{id}")
    CustomerSummary getCustomerById(@PathVariable("id") Long id);
}
