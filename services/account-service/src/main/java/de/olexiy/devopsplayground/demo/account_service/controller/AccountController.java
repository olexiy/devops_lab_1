package de.olexiy.devopsplayground.demo.account_service.controller;

import com.bank.account.api.ApiApi;
import com.bank.account.model.*;
import de.olexiy.devopsplayground.demo.account_service.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class AccountController implements ApiApi {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<AccountResponse> createAccount(AccountRequest request) {
        var created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + created.getId()))
                .body(created);
    }

    @Override
    public ResponseEntity<PageAccountResponse> listAccounts(
            Integer page, Integer size, String sort, Long customerId, String status, String accountType) {
        return ResponseEntity.ok(service.list(page, size, sort, customerId, status, accountType));
    }

    @Override
    public ResponseEntity<AccountResponse> getAccountById(Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @Override
    public ResponseEntity<AccountResponse> getAccountByNumber(String accountNumber) {
        return ResponseEntity.ok(service.findByAccountNumber(accountNumber));
    }

    @Override
    public ResponseEntity<PageAccountResponse> getAccountsByCustomer(
            Long customerId, Integer page, Integer size, String sort) {
        return ResponseEntity.ok(service.findByCustomerId(customerId, page, size, sort));
    }

    @Override
    public ResponseEntity<AccountResponse> updateAccount(Long id, AccountUpdateRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @Override
    public ResponseEntity<AccountResponse> updateAccountStatus(Long id, AccountStatusRequest request) {
        return ResponseEntity.ok(service.updateStatus(id, request));
    }

    @Override
    public ResponseEntity<AccountResponse> updateAccountBalance(Long id, BalanceUpdateRequest request) {
        return ResponseEntity.ok(service.updateBalance(id, request));
    }

    @Override
    public ResponseEntity<Void> deleteAccount(Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AverageBalanceResponse> getAverageBalance(Long customerId) {
        return ResponseEntity.ok(service.getAverageBalance(customerId));
    }
}
