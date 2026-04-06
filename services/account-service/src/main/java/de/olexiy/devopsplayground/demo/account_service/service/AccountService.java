package de.olexiy.devopsplayground.demo.account_service.service;

import com.bank.account.model.*;
import com.bank.account.model.BalanceUpdateRequest.OperationEnum;
import de.olexiy.devopsplayground.demo.account_service.client.CustomerServiceClient;
import de.olexiy.devopsplayground.demo.account_service.entity.Account;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountStatus;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountType;
import de.olexiy.devopsplayground.demo.account_service.exception.*;
import de.olexiy.devopsplayground.demo.account_service.mapper.AccountMapper;
import de.olexiy.devopsplayground.demo.account_service.repository.AccountRepository;
import feign.FeignException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
@Transactional
public class AccountService {

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "openDate", "openDate",
            "balance", "balance",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "status", "status"
    );

    private final AccountRepository repository;
    private final AccountMapper mapper;
    private final AccountNumberGenerator numberGenerator;
    private final CustomerServiceClient customerClient;

    public AccountService(AccountRepository repository, AccountMapper mapper,
                          AccountNumberGenerator numberGenerator,
                          CustomerServiceClient customerClient) {
        this.repository = repository;
        this.mapper = mapper;
        this.numberGenerator = numberGenerator;
        this.customerClient = customerClient;
    }

    public AccountResponse create(AccountRequest request) {
        validateCustomer(request.getCustomerId());

        var account = new Account();
        account.setCustomerId(request.getCustomerId());
        account.setAccountType(AccountType.valueOf(request.getAccountType().getValue()));
        account.setCurrency(request.getCurrency());
        account.setOpenDate(request.getOpenDate());

        if (request.getCreditLimit() != null) {
            account.setCreditLimit(BigDecimal.valueOf(request.getCreditLimit()));
        }

        // Two-step save: get ID first, then set account number
        account.setAccountNumber("TEMP");
        account = repository.save(account);
        account.setAccountNumber(numberGenerator.generate(account.getId(), account.getOpenDate()));
        return mapper.toResponse(repository.save(account));
    }

    @Transactional(readOnly = true)
    public PageAccountResponse list(Integer page, Integer size, String sort,
                                    Long customerId, String status, String accountType) {
        var pageable = buildPageable(page, size, sort);
        var statusEnum = status != null ? AccountStatus.valueOf(status) : null;
        var typeEnum = accountType != null ? AccountType.valueOf(accountType) : null;

        var result = repository.findByFilters(customerId, statusEnum, typeEnum, pageable);

        var meta = new PageMetadata()
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast());

        return new PageAccountResponse()
                .content(result.getContent().stream().map(mapper::toResponse).toList())
                .page(meta);
    }

    @Transactional(readOnly = true)
    public AccountResponse findById(Long id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public AccountResponse findByAccountNumber(String accountNumber) {
        return repository.findByAccountNumber(accountNumber)
                .map(mapper::toResponse)
                .orElseThrow(() -> new AccountNumberNotFoundException(accountNumber));
    }

    @Transactional(readOnly = true)
    public PageAccountResponse findByCustomerId(Long customerId, Integer page, Integer size, String sort) {
        var pageable = buildPageable(page, size, sort);
        var result = repository.findByCustomerId(customerId, pageable);

        var meta = new PageMetadata()
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast());

        return new PageAccountResponse()
                .content(result.getContent().stream().map(mapper::toResponse).toList())
                .page(meta);
    }

    public AccountResponse update(Long id, AccountUpdateRequest request) {
        var account = repository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        if (request.getCurrency() != null) {
            account.setCurrency(request.getCurrency());
        }
        if (request.getCreditLimit() != null) {
            account.setCreditLimit(BigDecimal.valueOf(request.getCreditLimit()));
        }
        return mapper.toResponse(repository.save(account));
    }

    public AccountResponse updateStatus(Long id, AccountStatusRequest request) {
        var account = repository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        var newStatus = AccountStatus.valueOf(request.getStatus().getValue());
        account.setStatus(newStatus);
        if (newStatus == AccountStatus.CLOSED) {
            account.setCloseDate(LocalDate.now());
        }
        return mapper.toResponse(repository.save(account));
    }

    public AccountResponse updateBalance(Long id, BalanceUpdateRequest request) {
        var account = repository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        var amount = BigDecimal.valueOf(request.getAmount());
        var newBalance = request.getOperation() == OperationEnum.CREDIT
                ? account.getBalance().add(amount)
                : account.getBalance().subtract(amount);

        // Check if debit is allowed
        if (request.getOperation() == OperationEnum.DEBIT) {
            var limit = account.getCreditLimit() != null
                    ? account.getCreditLimit().negate()
                    : BigDecimal.ZERO;
            if (newBalance.compareTo(limit) < 0) {
                throw new InsufficientFundsException(id, amount);
            }
        }

        account.setBalance(newBalance);
        return mapper.toResponse(repository.save(account));
    }

    public void delete(Long id) {
        var account = repository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        account.setStatus(AccountStatus.CLOSED);
        account.setCloseDate(LocalDate.now());
        repository.save(account);
    }

    @Transactional(readOnly = true)
    public AverageBalanceResponse getAverageBalance(Long customerId) {
        long count = repository.countActiveByCustomerId(customerId);
        if (count == 0) {
            throw new AccountNotFoundException(customerId); // reuse as "no active accounts"
        }
        var avg = repository.findAverageBalanceByCustomerId(customerId)
                .orElse(BigDecimal.ZERO);

        // Use currency from first active account (default EUR if none)
        var accounts = repository.findByCustomerIdAndStatus(customerId, AccountStatus.ACTIVE);
        String currency = accounts.isEmpty() ? "EUR" : accounts.get(0).getCurrency();

        return new AverageBalanceResponse(
                customerId,
                avg.doubleValue(),
                currency,
                (int) count,
                OffsetDateTime.now()
        );
    }

    private void validateCustomer(Long customerId) {
        try {
            var customer = customerClient.getCustomerById(customerId);
            if (!"ACTIVE".equals(customer.status())) {
                throw new CustomerNotFoundException(customerId);
            }
        } catch (FeignException.NotFound e) {
            throw new CustomerNotFoundException(customerId);
        }
        // CustomerServiceUnavailableException is thrown by fallback — propagates as-is
    }

    private PageRequest buildPageable(Integer page, Integer size, String sort) {
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        String[] parts = sort.split(",", 2);
        String field = SORT_FIELDS.getOrDefault(parts[0].trim(), "createdAt");
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, field));
    }
}
