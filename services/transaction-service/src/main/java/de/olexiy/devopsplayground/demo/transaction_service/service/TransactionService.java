package de.olexiy.devopsplayground.demo.transaction_service.service;

import com.bank.transaction.model.*;
import de.olexiy.devopsplayground.demo.transaction_service.client.AccountServiceClient;
import de.olexiy.devopsplayground.demo.transaction_service.client.AccountSummary;
import de.olexiy.devopsplayground.demo.transaction_service.client.BalancePatch;
import de.olexiy.devopsplayground.demo.transaction_service.entity.Transaction;
import de.olexiy.devopsplayground.demo.transaction_service.entity.TransactionType;
import de.olexiy.devopsplayground.demo.transaction_service.exception.AccountNotFoundException;
import de.olexiy.devopsplayground.demo.transaction_service.exception.InsufficientFundsException;
import de.olexiy.devopsplayground.demo.transaction_service.exception.TransactionNotFoundException;
import de.olexiy.devopsplayground.demo.transaction_service.mapper.TransactionMapper;
import de.olexiy.devopsplayground.demo.transaction_service.repository.TransactionRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class TransactionService {

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "transactionDate", "transactionDate",
            "amount", "amount",
            "createdAt", "createdAt"
    );

    private final TransactionRepository repository;
    private final TransactionMapper mapper;
    private final AccountServiceClient accountClient;

    public TransactionResponse create(TransactionRequest request) {
        AccountSummary account = fetchAccount(request.getAccountId());
        validateAccountOwner(account, request.getCustomerId());

        TransactionType type = TransactionType.valueOf(request.getTransactionType().getValue());
        BigDecimal amount = BigDecimal.valueOf(request.getAmount());
        boolean isDebit = isDebitOperation(type);

        BigDecimal currentBalance = BigDecimal.valueOf(account.balance());
        BigDecimal balanceAfter = isDebit
                ? currentBalance.subtract(amount)
                : currentBalance.add(amount);

        if (isDebit) {
            BigDecimal limit = account.creditLimit() != null
                    ? BigDecimal.valueOf(account.creditLimit()).negate()
                    : BigDecimal.ZERO;
            if (balanceAfter.compareTo(limit) < 0) {
                throw new InsufficientFundsException(request.getAccountId(), amount);
            }
        }

        var txn = Transaction.builder()
                .referenceNumber(UUID.randomUUID().toString())
                .accountId(request.getAccountId())
                .customerId(request.getCustomerId())
                .transactionType(type)
                .amount(amount)
                .currency(request.getCurrency())
                .balanceAfter(balanceAfter)
                .description(request.getDescription())
                .transactionDate(toLocalDateTime(request.getTransactionDate()))
                .build();

        txn = repository.save(txn);

        String operation = isDebit ? "DEBIT" : "CREDIT";
        accountClient.updateBalance(request.getAccountId(), new BalancePatch(request.getAmount(), operation));

        return mapper.toResponse(txn);
    }

    @Transactional(readOnly = true)
    public PageTransactionResponse list(Integer page, Integer size, String sort,
                                        String transactionType, LocalDate dateFrom, LocalDate dateTo) {
        var pageable = buildPageable(page, size, sort);
        TransactionType typeEnum = transactionType != null ? TransactionType.valueOf(transactionType) : null;
        var result = repository.findByFilters(typeEnum, toStartOfDay(dateFrom), toEndOfDay(dateTo), pageable);
        return toPage(result);
    }

    @Transactional(readOnly = true)
    public TransactionResponse findById(Long id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public TransactionResponse findByReference(String referenceNumber) {
        return repository.findByReferenceNumber(referenceNumber)
                .map(mapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(referenceNumber));
    }

    @Transactional(readOnly = true)
    public PageTransactionResponse findByAccount(Long accountId, Integer page, Integer size, String sort,
                                                 LocalDate dateFrom, LocalDate dateTo, String transactionType) {
        var pageable = buildPageable(page, size, sort);
        TransactionType typeEnum = transactionType != null ? TransactionType.valueOf(transactionType) : null;
        var result = repository.findByAccountIdAndFilters(accountId, typeEnum, toStartOfDay(dateFrom), toEndOfDay(dateTo), pageable);
        return toPage(result);
    }

    @Transactional(readOnly = true)
    public PageTransactionResponse findByCustomer(Long customerId, Integer page, Integer size, String sort,
                                                  LocalDate dateFrom, LocalDate dateTo) {
        var pageable = buildPageable(page, size, sort);
        var result = repository.findByCustomerIdAndFilters(customerId, toStartOfDay(dateFrom), toEndOfDay(dateTo), pageable);
        return toPage(result);
    }

    @Transactional(readOnly = true)
    public ExternalScoreResponse getExternalScore(Long customerId) {
        LocalDateTime since = LocalDateTime.now().minusMonths(12);
        List<Transaction> transactions = repository.findByCustomerIdSince(customerId, since);

        if (transactions.isEmpty()) {
            throw new TransactionNotFoundException("No transactions found for customer %d in the last 12 months".formatted(customerId));
        }

        double creditTotal = transactions.stream()
                .filter(t -> t.getTransactionType() == TransactionType.CREDIT
                          || t.getTransactionType() == TransactionType.TRANSFER_IN)
                .mapToDouble(t -> t.getAmount().doubleValue())
                .sum();

        double debitTotal = transactions.stream()
                .filter(t -> t.getTransactionType() == TransactionType.DEBIT
                          || t.getTransactionType() == TransactionType.TRANSFER_OUT
                          || t.getTransactionType() == TransactionType.FEE)
                .mapToDouble(t -> t.getAmount().doubleValue())
                .sum();

        int count = transactions.size();
        double total = creditTotal + debitTotal;
        double creditRatio = total > 0 ? creditTotal / total : 0.5;
        double frequencyBonus = Math.min(count, 200);
        double score = Math.min(1000, Math.max(0, creditRatio * 800 + frequencyBonus));

        return new ExternalScoreResponse()
                .customerId(customerId)
                .score(score)
                .scoreGrade(ExternalScoreResponse.ScoreGradeEnum.fromValue(toGrade(score)))
                .transactionCount(count)
                .windowMonths(12)
                .calculatedAt(OffsetDateTime.now());
    }

    private AccountSummary fetchAccount(Long accountId) {
        try {
            return accountClient.getAccountById(accountId);
        } catch (FeignException.NotFound e) {
            throw new AccountNotFoundException(accountId);
        }
        // AccountServiceUnavailableException thrown by fallback — propagates as-is
    }

    private void validateAccountOwner(AccountSummary account, Long requestedCustomerId) {
        if (!"ACTIVE".equals(account.status())) {
            throw new AccountNotFoundException(account.id());
        }
        if (!account.customerId().equals(requestedCustomerId)) {
            throw new AccountNotFoundException(account.id());
        }
    }

    private boolean isDebitOperation(TransactionType type) {
        return type == TransactionType.DEBIT
                || type == TransactionType.TRANSFER_OUT
                || type == TransactionType.FEE;
    }

    private String toGrade(double score) {
        if (score >= 850) return "A";
        if (score >= 700) return "B";
        if (score >= 550) return "C";
        if (score >= 400) return "D";
        if (score >= 200) return "E";
        return "F";
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime odt) {
        return odt == null ? null : odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private LocalDateTime toStartOfDay(LocalDate date) {
        return date != null ? date.atStartOfDay() : null;
    }

    private LocalDateTime toEndOfDay(LocalDate date) {
        return date != null ? date.atTime(23, 59, 59) : null;
    }

    private PageRequest buildPageable(Integer page, Integer size, String sort) {
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionDate"));
        }
        String[] parts = sort.split(",", 2);
        String field = SORT_FIELDS.getOrDefault(parts[0].trim(), "transactionDate");
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    private PageTransactionResponse toPage(org.springframework.data.domain.Page<Transaction> result) {
        var meta = new PageMetadata()
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast());
        return new PageTransactionResponse()
                .content(result.getContent().stream().map(mapper::toResponse).toList())
                .page(meta);
    }
}
