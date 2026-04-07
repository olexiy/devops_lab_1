package de.olexiy.devopsplayground.demo.transaction_service.client;

import de.olexiy.devopsplayground.demo.transaction_service.exception.AccountServiceUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class AccountServiceClientFallback implements AccountServiceClient {

    @Override
    public AccountSummary getAccountById(Long id) {
        throw new AccountServiceUnavailableException(id);
    }

    @Override
    public void updateBalance(Long id, BalancePatch request) {
        throw new AccountServiceUnavailableException(id);
    }
}
