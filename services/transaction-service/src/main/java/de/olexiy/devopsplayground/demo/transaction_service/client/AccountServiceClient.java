package de.olexiy.devopsplayground.demo.transaction_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "account-service",
        url = "${account-service.url}",
        fallback = AccountServiceClientFallback.class
)
public interface AccountServiceClient {

    @GetMapping("/api/v1/accounts/{id}")
    AccountSummary getAccountById(@PathVariable("id") Long id);

    @PatchMapping("/api/v1/accounts/{id}/balance")
    void updateBalance(@PathVariable("id") Long id, @RequestBody BalancePatch request);
}
