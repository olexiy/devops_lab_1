package de.olexiy.devopsplayground.demo.account_service.mapper;

import com.bank.account.model.AccountResponse;
import com.bank.account.model.AccountResponse.AccountTypeEnum;
import com.bank.account.model.AccountResponse.StatusEnum;
import de.olexiy.devopsplayground.demo.account_service.entity.Account;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountStatus;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountType;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

@Component
public class AccountMapper {

    public AccountResponse toResponse(Account a) {
        return new AccountResponse()
                .id(a.getId())
                .accountNumber(a.getAccountNumber())
                .customerId(a.getCustomerId())
                .accountType(toTypeEnum(a.getAccountType()))
                .status(toStatusEnum(a.getStatus()))
                .currency(a.getCurrency())
                .balance(a.getBalance() != null ? a.getBalance().doubleValue() : 0.0)
                .creditLimit(a.getCreditLimit() != null ? a.getCreditLimit().doubleValue() : null)
                .openDate(a.getOpenDate())
                .closeDate(a.getCloseDate())
                .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().atOffset(ZoneOffset.UTC) : null)
                .updatedAt(a.getUpdatedAt() != null ? a.getUpdatedAt().atOffset(ZoneOffset.UTC) : null);
    }

    private AccountTypeEnum toTypeEnum(AccountType type) {
        return switch (type) {
            case CHECKING -> AccountTypeEnum.CHECKING;
            case SAVINGS  -> AccountTypeEnum.SAVINGS;
            case CREDIT   -> AccountTypeEnum.CREDIT;
            case DEPOSIT  -> AccountTypeEnum.DEPOSIT;
        };
    }

    private StatusEnum toStatusEnum(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> StatusEnum.ACTIVE;
            case FROZEN -> StatusEnum.FROZEN;
            case CLOSED -> StatusEnum.CLOSED;
        };
    }
}
