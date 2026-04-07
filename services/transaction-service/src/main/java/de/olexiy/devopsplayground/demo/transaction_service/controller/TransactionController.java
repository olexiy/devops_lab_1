package de.olexiy.devopsplayground.demo.transaction_service.controller;

import com.bank.transaction.api.ApiApi;
import com.bank.transaction.model.*;
import de.olexiy.devopsplayground.demo.transaction_service.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class TransactionController implements ApiApi {

    private final TransactionService service;

    @Override
    public ResponseEntity<TransactionResponse> createTransaction(TransactionRequest request) {
        var created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/transactions/" + created.getId()))
                .body(created);
    }

    @Override
    public ResponseEntity<PageTransactionResponse> listTransactions(
            Integer page, Integer size, String sort, String transactionType,
            LocalDate dateFrom, LocalDate dateTo) {
        return ResponseEntity.ok(service.list(page, size, sort, transactionType, dateFrom, dateTo));
    }

    @Override
    public ResponseEntity<TransactionResponse> getTransactionById(Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @Override
    public ResponseEntity<TransactionResponse> getTransactionByReference(String referenceNumber) {
        return ResponseEntity.ok(service.findByReference(referenceNumber));
    }

    @Override
    public ResponseEntity<PageTransactionResponse> getTransactionsByAccount(
            Long accountId, Integer page, Integer size, String sort,
            LocalDate dateFrom, LocalDate dateTo, String transactionType) {
        return ResponseEntity.ok(service.findByAccount(accountId, page, size, sort, dateFrom, dateTo, transactionType));
    }

    @Override
    public ResponseEntity<PageTransactionResponse> getTransactionsByCustomer(
            Long customerId, Integer page, Integer size, String sort,
            LocalDate dateFrom, LocalDate dateTo) {
        return ResponseEntity.ok(service.findByCustomer(customerId, page, size, sort, dateFrom, dateTo));
    }

    @Override
    public ResponseEntity<ExternalScoreResponse> getExternalScore(Long customerId) {
        return ResponseEntity.ok(service.getExternalScore(customerId));
    }
}
